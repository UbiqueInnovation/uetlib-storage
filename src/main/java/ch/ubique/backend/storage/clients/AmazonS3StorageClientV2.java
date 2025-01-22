package ch.ubique.backend.storage.clients;

import static com.google.common.net.HttpHeaders.*;

import ch.ubique.backend.core.executor.VirtualThreadUtils;
import ch.ubique.backend.core.http.header.HttpHeader;
import ch.ubique.backend.storage.OutputMetaData;
import ch.ubique.backend.storage.OutputMetaDataProvider;
import ch.ubique.backend.storage.StorageClient;
import ch.ubique.backend.storage.exception.S3StorageClientException;
import ch.ubique.backend.storage.exception.StorageClientException;
import ch.ubique.backend.storage.output.Output;
import ch.ubique.backend.storage.output.OutputWithPath;
import ch.ubique.backend.storage.utils.CompressionUtils;

import io.minio.errors.*;

import jakarta.annotation.Nullable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.DirectoryNotEmptyException;
import java.text.MessageFormat;
import java.time.Duration;
import java.util.*;

public sealed class S3StorageClient implements StorageClient {

    private final String bucket;
    private final S3Client s3Client;
    @Nullable private final OutputMetaDataProvider outputMetaDataProvider;

    // TODO: add other constructors
    public S3StorageClient(
            final String bucket,
            final String endpoint,
            final String accessKey,
            final String secretKey,
            @Nullable final Duration timeout,
            @Nullable final OutputMetaDataProvider outputMetaDataProvider) {
        // TODO: fix
        this.bucket = bucket;
        this.outputMetaDataProvider = outputMetaDataProvider;
        this.s3Client =
                S3Client.builder()
                        .credentialsProvider(
                                StaticCredentialsProvider.create(
                                        AwsBasicCredentials.create(accessKey, secretKey)))
                        .endpointOverride(URI.create(endpoint))
                        .build();
    }

    @NotNull
    private static Map<String, String> createHeaders(
            @NotNull final Output output, @Nullable final OutputMetaData metaData) {
        final var headers = new HashMap<String, String>();

        // contentEncoding
        output.contentEncoding()
                .ifPresent(contentEncoding -> headers.put(CONTENT_ENCODING, contentEncoding));

        // cacheControl
        Optional.ofNullable(metaData)
                .map(OutputMetaData::createCacheControl)
                .ifPresent(cacheControl -> headers.put(CACHE_CONTROL, cacheControl));

        // Expires
        Optional.ofNullable(metaData)
                .flatMap(OutputMetaData::createExpiresString)
                .ifPresent(expires -> headers.put(EXPIRES, expires));

        // ACL (Access Control)
        Optional.ofNullable(metaData)
                .flatMap(OutputMetaData::createCannedAcl)
                .ifPresent(cannedAcl -> headers.put(HttpHeader.AMZ_ACL.value(), cannedAcl));

        return headers;
    }

    @Override
    public final void write(
            final @NotNull Output output,
            final @NotNull String path,
            @Nullable final OutputMetaData outputMetaData)
            throws StorageClientException {
        final var metaData =
                OutputMetaData.resolveOutputMetaData(
                        outputMetaData, outputMetaDataProvider, output);

        try (final var inputStream = output.read()) {

            final var contentType = Objects.requireNonNull(output.contentType());

            final var request =
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(path)
                            .contentType(contentType)
                            .contentEncoding(output.contentEncoding().orElse(null))
                            .cacheControl(
                                    Optional.ofNullable(outputMetaData)
                                            .map(OutputMetaData::createCacheControl)
                                            .orElse(null))
                            .expires(
                                    Optional.ofNullable(outputMetaData)
                                            .flatMap(OutputMetaData::expires)
                                            .orElse(null))
                            .acl(
                                    Optional.ofNullable(outputMetaData)
                                            .flatMap(OutputMetaData::createCannedAcl)
                                            .orElse(null))
                            .metadata(createUserMetadata(metaData))
                            .build();

            s3Client.putObject(
                    request, RequestBody.fromInputStream(inputStream, output.contentLength()));
        } catch (final Exception e) {
            throw storageClientException(
                    MessageFormat.format(
                            "failed to write for [path={0}, outputMetaData={1}, output={2}]",
                            path, outputMetaData, output),
                    e);
        }
    }

    private Map<String, String> createUserMetadata(final OutputMetaData outputMetaData) {
        return outputMetaData.createCustomHeadersWithKeyMapper(this::keyForCustomMetadata);
    }

    protected String keyForCustomMetadata(final HttpHeader httpHeader) {
        return httpHeader.withXPrefix();
    }

    @Override
    public final void write(
            final Collection<@NotNull @Valid OutputWithPath> outputsWithPaths,
            @Nullable final OutputMetaData outputMetaData)
            throws StorageClientException {

        if (outputsWithPaths.isEmpty()) {
            return;
        }

        VirtualThreadUtils.throwingForEach(
                item -> write(item.output(), item.path(), outputMetaData), outputsWithPaths);
    }

    @Override
    public final InputStream read(final @NotNull String path, final boolean doUncompressIfNeeded)
            throws StorageClientException {
        try {
            final var request = GetObjectRequest.builder().bucket(bucket).key(path).build();
            final var response = s3Client.getObject(request);

            if (doUncompressIfNeeded) {
                final var contentEncoding = response.response().metadata().get("Content-Encoding");
                return CompressionUtils.tryToUncompress(response, contentEncoding, path);
            }

            return response;
        } catch (final Exception e) {
            throw storageClientException(
                    MessageFormat.format(
                            "failed to read for [path={0}, doUncompressIfNeeded={1}]",
                            path, doUncompressIfNeeded),
                    e);
        }
    }

    @Override
    public boolean delete(final @NotNull String path, final boolean recursive)
            throws StorageClientException {

        final var existing = list(path, true);

        if (existing.stream().anyMatch(object -> !object.equals(path)) && !recursive) {
            throw storageClientException(
                    MessageFormat.format("can not delete not empty directory. [path={0}]", path),
                    new DirectoryNotEmptyException(path));
        }

        final var objectsToDelete =
                recursive
                        ? existing.stream()
                                .map(key -> ObjectIdentifier.builder().key(key).build())
                                .toList()
                        : List.of(ObjectIdentifier.builder().key(path).build());

        final var delete = Delete.builder().objects(objectsToDelete).build();

        // TODO: handle exceptions
        final var deleteObjectsResponse =
                s3Client.deleteObjects(builder -> builder.bucket(bucket).delete(delete));

        if (!deleteObjectsResponse.errors().isEmpty()) {
            throw storageClientException(
                    "Failed to delete object(s) from S3",
                    new S3StorageClientException(deleteObjectsResponse.errors()));
        }

        return true;
    }

    @Override
    public Collection<String> list(@NotNull final String path, final boolean recursive)
            throws StorageClientException {
        try {
            final var request = ListObjectsV2Request.builder().bucket(bucket).prefix(path).build();
            final var response = s3Client.listObjectsV2(request);

            final var keys = new HashSet<String>();
            response.contents().forEach(s3Object -> keys.add(s3Object.key()));
            return keys;
        } catch (final AwsServiceException | SdkClientException e) {
            throw storageClientException(
                    MessageFormat.format(
                            "failed to list for [mappingPath={0}, recursive={1}]", path, recursive),
                    e);
        }
    }

    @Override
    public boolean exists(final @NotNull String path) throws StorageClientException {
        try {
            final var request = HeadObjectRequest.builder().bucket(bucket).key(path).build();
            s3Client.headObject(request);
            return true;
        } catch (final NoSuchKeyException e) {
            return false;
        } catch (final Exception e) {
            throw storageClientException("Failed to check existence of object in S3", e);
        }
    }

    @Override
    public String getSignedUrl(final @NotNull String path, final @NotNull OffsetDateTime expiry)
            throws StorageClientException {
        try {
            final var request = GetObjectRequest.builder().bucket(bucket).key(path).build();
            final var presigner = S3Presigner.create();
            final var presignedRequest =
                    presigner.presignGetObject(
                            builder ->
                                    builder.getObjectRequest(request)
                                            .signatureDuration(
                                                    Duration.between(
                                                            OffsetDateTime.now(), expiry)));

            return presignedRequest.url().toString();
        } catch (final Exception e) {
            throw storageClientException("Failed to get signed URL for S3 object", e);
        }
    }

    public static StorageClientException storageClientException(
            final String message, final Throwable cause) {
        // TODO: Add support for retryable exceptions (see AWS SDK doc)
        // TODO: Add support for non-retryable exceptions (see AWS SDK doc)
        // TODO: In general, clean up exception handling
        return StorageClientException.of(message, cause);
    }
}
