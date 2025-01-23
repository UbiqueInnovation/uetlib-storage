package ch.ubique.backend.storage.clients;

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

import jakarta.annotation.Nullable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.apache.commons.lang3.NotImplementedException;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.WebIdentityTokenFileCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.io.InputStream;
import java.nio.file.DirectoryNotEmptyException;
import java.text.MessageFormat;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;

public class S3StorageClient implements StorageClient {

    private static final String DEFAULT_REGION = "eu-central-1";

    private final String bucket;
    private final S3Client s3Client;
    @Nullable private final OutputMetaDataProvider outputMetaDataProvider;

    /**
     * Uses web identity token credentials with STS to request temporary security credentials. See
     * <a
     * href=https://docs.aws.amazon.com/STS/latest/APIReference/API_AssumeRoleWithWebIdentity.html>AssumeRoleWithWebIdentity</a>
     * for details.
     *
     * <p>The credential provider will read relevant configuration details, in particular web
     * identity token file path and aws role arn, from system properties or environment variables
     * (AWS_WEB_IDENTITY_TOKEN_FILE, AWS_ROLE_ARN, AWS_STS_REGIONAL_ENDPOINTS). See <a
     * href="https://docs.aws.amazon.com/cli/latest/userguide/cli-configure-envvars.html">Configuring
     * environment variables for the AWS CLI</a>.
     *
     * @param bucket The bucket name
     * @param region The endpoint URL of the S3 service. If null, the default region eu-central-1 is
     *     used
     */
    public S3StorageClient(final String bucket, @Nullable final String region) {
        this.bucket = bucket;
        this.outputMetaDataProvider = null;
        this.s3Client =
                S3Client.builder()
                        .credentialsProvider(WebIdentityTokenFileCredentialsProvider.create())
                        .region(Region.of(Objects.requireNonNullElse(region, DEFAULT_REGION)))
                        .build();
    }

    /**
     * @see #S3StorageClient(String, String, String, String, Duration, OutputMetaDataProvider)
     */
    public S3StorageClient(final String bucket, final String accessKey, final String secretKey) {
        this(bucket, null, accessKey, secretKey, null, null);
    }

    /**
     * @see #S3StorageClient(String, String, String, String, Duration, OutputMetaDataProvider)
     */
    public S3StorageClient(
            final String bucket,
            @Nullable final String region,
            final String accessKey,
            final String secretKey) {
        this(bucket, region, accessKey, secretKey, null, null);
    }

    /**
     * @param bucket The bucket name
     * @param region The endpoint URL of the S3 service. If null, the default region eu-central-1 is
     *     used
     * @param accessKey The AWS auth access key
     * @param secretKey The AWS auth secret key
     * @param timeout Timeout for requests (ignored for AWS S3)
     * @param outputMetaDataProvider Provider for output meta data
     */
    public S3StorageClient(
            final String bucket,
            @Nullable final String region,
            final String accessKey,
            final String secretKey,
            @Nullable final Duration timeout,
            @Nullable final OutputMetaDataProvider outputMetaDataProvider) {
        this.bucket = bucket;
        this.outputMetaDataProvider = outputMetaDataProvider;
        this.s3Client =
                S3Client.builder()
                        .credentialsProvider(() -> AwsBasicCredentials.create(accessKey, secretKey))
                        .region(Region.of(Objects.requireNonNullElse(region, DEFAULT_REGION)))
                        .build();
    }

    public static StorageClientException storageClientException(
            final String message, final Throwable cause) {
        // TODO: Add support for retryable exceptions (see AWS SDK doc)
        // TODO: Add support for non-retryable exceptions (see AWS SDK doc)
        // TODO: In general, clean up exception handling
        return StorageClientException.of(message, cause);
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
            final var listObjectsV2Request =
                    ListObjectsV2Request.builder().bucket(bucket).prefix(path).build();
            final var listObjectsV2Response = s3Client.listObjectsV2(listObjectsV2Request);

            final var keys = new HashSet<String>();
            listObjectsV2Response.contents().forEach(s3Object -> keys.add(s3Object.key()));
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
            final var headObjectRequest =
                    HeadObjectRequest.builder().bucket(bucket).key(path).build();
            s3Client.headObject(headObjectRequest);
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
            final PresignedGetObjectRequest presignedRequest;
            try (final var presigner = S3Presigner.create()) {
                presignedRequest =
                        presigner.presignGetObject(
                                builder ->
                                        builder.getObjectRequest(
                                                        getObjectRequestBuilder ->
                                                                getObjectRequestBuilder
                                                                        .bucket(bucket)
                                                                        .key(path))
                                                .signatureDuration(
                                                        Duration.between(
                                                                OffsetDateTime.now(), expiry)));
            }

            return presignedRequest.url().toString();
        } catch (final Exception e) {
            throw storageClientException(
                    MessageFormat.format(
                            "failed to get signed url for [mappingPath={0}, expiry={1}]",
                            path, expiry),
                    e);
        }
    }

    @Override
    public final String getUrl(final @NotNull String path) {
        throw new NotImplementedException(
                "S3 does not support permanent share URLs? (Maximum is 7 days as per S3 spec)");
    }
}
