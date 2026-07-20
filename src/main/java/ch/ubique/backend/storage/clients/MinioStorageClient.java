package ch.ubique.backend.storage.clients;

import static com.google.common.net.HttpHeaders.CACHE_CONTROL;
import static com.google.common.net.HttpHeaders.CONTENT_ENCODING;
import static com.google.common.net.HttpHeaders.EXPIRES;

import ch.ubique.backend.core.executor.VirtualThreadUtils;
import ch.ubique.backend.core.http.header.HttpHeader;
import ch.ubique.backend.storage.OutputMetaData;
import ch.ubique.backend.storage.OutputMetaDataProvider;
import ch.ubique.backend.storage.StorageClient;
import ch.ubique.backend.storage.exception.NonRetryableStorageClientException;
import ch.ubique.backend.storage.exception.RetryableStorageClientException;
import ch.ubique.backend.storage.exception.StorageClientException;
import ch.ubique.backend.storage.model.PresignedUrlMethod;
import ch.ubique.backend.storage.output.FileOutput;
import ch.ubique.backend.storage.output.Output;
import ch.ubique.backend.storage.output.OutputWithPath;
import ch.ubique.backend.storage.utils.CompressionUtils;

import io.minio.*;
import io.minio.Http.Method;
import io.minio.credentials.Provider;
import io.minio.credentials.StaticProvider;
import io.minio.errors.*;
import io.minio.messages.DeleteRequest;

import jakarta.validation.Valid;

import okhttp3.OkHttpClient;

import org.apache.commons.lang3.NotImplementedException;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryNotEmptyException;
import java.text.MessageFormat;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * {@link StorageClient} to perform bucket and object operations to any Amazon S3 / MinIO compatible
 * object storage service.
 */
public class MinioStorageClient implements StorageClient {

    private static final long EIGHT_MB = 8 * 1024 * 1024L;

    private final String bucket;
    private final MinioClient minioClient;
    private final @Nullable OutputMetaDataProvider outputMetaDataProvider;

    public MinioStorageClient(
            final String bucket,
            final String endpoint,
            final Provider credentialsProvider,
            @Nullable final Duration timeout,
            @Nullable final OutputMetaDataProvider outputMetaDataProvider) {
        this.bucket = bucket;
        this.outputMetaDataProvider = outputMetaDataProvider;
        final var timeoutOrDefault = Objects.requireNonNullElse(timeout, Duration.ofSeconds(15));
        final var httpClient =
                new OkHttpClient()
                        .newBuilder()
                        .connectTimeout(timeoutOrDefault)
                        .readTimeout(timeoutOrDefault)
                        .writeTimeout(timeoutOrDefault)
                        .build();
        minioClient =
                MinioClient.builder()
                        .endpoint(endpoint)
                        .credentialsProvider(credentialsProvider)
                        .httpClient(httpClient)
                        .build();
    }

    public MinioStorageClient(
            final String bucket,
            final String endpoint,
            final String accessKey,
            final String secretKey,
            @Nullable final Duration timeout,
            @Nullable final OutputMetaDataProvider outputMetaDataProvider) {
        this(
                bucket,
                endpoint,
                new StaticProvider(accessKey, secretKey, null),
                timeout,
                outputMetaDataProvider);
    }

    private static Map<String, String> createHeaders(
            final Output output, @Nullable final OutputMetaData metaData) {
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
                .flatMap(OutputMetaData::expires)
                .ifPresent(expires -> headers.put(EXPIRES, expires));

        // ACL (Access Control)
        Optional.ofNullable(metaData)
                .flatMap(OutputMetaData::createCannedAcl)
                .ifPresent(cannedAcl -> headers.put(HttpHeader.AMZ_ACL.value(), cannedAcl));

        return headers;
    }

    public static StorageClientException storageClientException(
            final String message, final Throwable cause) {

        if (!(cause instanceof final MinioException minioException)) {
            return StorageClientException.of(message, cause);
        }

        final var causeMessage = cause.getMessage();

        return switch (minioException) {
            case final ErrorResponseException ere
                    when causeMessage != null
                            && causeMessage.contains("The specified key does not exist") ->
                    new NonRetryableStorageClientException(message, ere);
            case final ErrorResponseException ere ->
                    new RetryableStorageClientException(message, ere);
            case final InsufficientDataException ide ->
                    new RetryableStorageClientException(message, ide);
            case final InvalidResponseException ire ->
                    new RetryableStorageClientException(message, ire);
            case final InternalException ie -> new RetryableStorageClientException(message, ie);
            case final ServerException se -> new RetryableStorageClientException(message, se);
            default -> {
                final var childCause = minioException.getCause();
                if (childCause != null) {
                    yield storageClientException(message, childCause);
                } else {
                    yield StorageClientException.of(message, minioException);
                }
            }
        };
    }

    @Override
    public final void write(
            final Output output, final String path, @Nullable final OutputMetaData outputMetaData)
            throws StorageClientException {

        if (output instanceof final FileOutput file) {
            writeFile(file, path, outputMetaData);
            return;
        }

        final var metaData = resolveMetaData(output, outputMetaData);
        try (final var inputStream = output.read()) {

            final var contentType = Objects.requireNonNull(output.contentType());
            final var contentLength = output.contentLength();

            final var putObjectArgs =
                    PutObjectArgs.builder()
                            .bucket(bucket)
                            .object(path)
                            .contentType(contentType)
                            .headers(createHeaders(output, metaData))
                            .stream(inputStream, contentLength, partSize(contentLength))
                            .userMetadata(createUserMetadata(metaData))
                            .build();
            minioClient.putObject(putObjectArgs);
        } catch (final IOException | MinioException e) {
            throw storageClientException(
                    MessageFormat.format(
                            "failed to write for [path={0}, outputMetaData={1}, output={2}]",
                            path, outputMetaData, output),
                    e);
        }
    }

    private void writeFile(
            final FileOutput output,
            final String path,
            final @Nullable OutputMetaData outputMetaData)
            throws StorageClientException {

        final var metaData = resolveMetaData(output, outputMetaData);
        try {
            final var uploadObjectArgs =
                    UploadObjectArgs.builder()
                            .bucket(bucket)
                            .object(path)
                            .contentType(Objects.requireNonNull(output.contentType()))
                            .headers(createHeaders(output, metaData))
                            .filename(
                                    output.file().toPath().toString(),
                                    partSize(output.contentLength()))
                            .userMetadata(createUserMetadata(metaData))
                            .build();

            minioClient.uploadObject(uploadObjectArgs);
        } catch (final MinioException e) {
            throw storageClientException(
                    MessageFormat.format(
                            "failed to write for [path={0}, outputMetaData={1}, output={2}]",
                            path, outputMetaData, output),
                    e);
        }
    }

    /**
     * Calculates a good part size for multipart uploads based on the content length. If the content
     * length is unknown (<= 0), a default part size of 8MiB is returned.
     *
     * <p>This method determines an appropriate part size for splitting large objects into multiple
     * parts during upload. The algorithm aims for approximately 200 parts and rounds the result up
     * to the nearest power of two, resulting in something between 100 and 200 parts.
     *
     * @param contentLength the total size of the content to be uploaded in bytes. Can be -1 if the
     *     content length is unknown
     * @return the calculated part size in bytes, guaranteed to be between {@link
     *     ObjectWriteArgs#MIN_MULTIPART_SIZE} and {@link ObjectWriteArgs#MAX_PART_SIZE}
     */
    static long partSize(final long contentLength) {
        if (contentLength <= 0) {
            // 8MiB (default in aws s3 cli, must be between 5MiB to 5GiB)
            // CAUTION: The upload is restricted to 10000 * partSize = 80GiB at the moment. We might
            // have to increase this at some point for unknown contentLength
            return EIGHT_MB;
        }

        final var targetNumberOfParts = 200;

        final var rawPartSize = (long) Math.ceil((double) contentLength / targetNumberOfParts);
        // Round up to power of two
        final var alignedPartSize = Long.highestOneBit(rawPartSize - 1) << 1;

        return Math.clamp(
                alignedPartSize, ObjectWriteArgs.MIN_MULTIPART_SIZE, ObjectWriteArgs.MAX_PART_SIZE);
    }

    private OutputMetaData resolveMetaData(
            final Output output, final @Nullable OutputMetaData outputMetaData) {
        return OutputMetaData.resolveOutputMetaData(outputMetaData, outputMetaDataProvider, output);
    }

    private Map<String, String> createUserMetadata(final OutputMetaData outputMetaData) {
        return outputMetaData.createCustomHeadersWithKeyMapper(this::keyForCustomMetadata);
    }

    protected String keyForCustomMetadata(final HttpHeader httpHeader) {
        return httpHeader.withXPrefix();
    }

    @Override
    public final void write(
            final Collection<@Valid OutputWithPath> outputsWithPaths,
            @Nullable final OutputMetaData outputMetaData)
            throws StorageClientException {

        if (outputsWithPaths.isEmpty()) {
            return;
        }

        VirtualThreadUtils.throwingForEach(
                item -> write(item.output(), item.path(), outputMetaData), outputsWithPaths);
    }

    @Override
    public final InputStream read(final String path, final boolean doUncompressIfNeeded)
            throws StorageClientException {
        try {
            final var objectResponse =
                    minioClient.getObject(
                            GetObjectArgs.builder().bucket(bucket).object(path).build());

            if (doUncompressIfNeeded) {
                final var contentEncoding = objectResponse.headers().get(CONTENT_ENCODING);
                return CompressionUtils.tryToUncompress(objectResponse, contentEncoding, path);
            }

            return objectResponse;
        } catch (final IOException | MinioException e) {
            throw storageClientException(
                    MessageFormat.format(
                            "failed to read for [path={0}, doUncompressIfNeeded={1}]",
                            path, doUncompressIfNeeded),
                    e);
        }
    }

    @Override
    public final boolean delete(final String path, final boolean recursive)
            throws StorageClientException {

        final var existing = list(path, true);

        if (!recursive && existing.stream().anyMatch(object -> !object.equals(path))) {
            throw storageClientException(
                    MessageFormat.format(
                            "cannot delete non-empty directory. [path={0}, recursive=false]", path),
                    new DirectoryNotEmptyException(path));
        }

        final var objectsToDelete =
                recursive
                        ? existing.stream().map(DeleteRequest.Object::new).toList()
                        : List.of(new DeleteRequest.Object(path));

        final var results =
                minioClient.removeObjects(
                        RemoveObjectsArgs.builder()
                                .bucket(bucket)
                                .objects(objectsToDelete)
                                .build());

        try {
            for (final var result : results) {
                result.get();
            }
        } catch (final MinioException e) {
            throw storageClientException(
                    MessageFormat.format(
                            "failed to delete for [path={0}, recursive={1}]", path, recursive),
                    e);
        }

        return true;
    }

    @Override
    public Collection<String> list(final String path, final boolean recursive)
            throws StorageClientException {
        final var objects =
                minioClient.listObjects(
                        ListObjectsArgs.builder()
                                .bucket(bucket)
                                .prefix(path)
                                .recursive(recursive)
                                .build());

        final var paths = new HashSet<String>();
        try {
            for (final var object : objects) {
                paths.add(object.get().objectName());
            }
        } catch (final MinioException e) {
            throw storageClientException(
                    MessageFormat.format(
                            "failed to list for [mappingPath={0}, recursive={1}]", path, recursive),
                    e);
        }
        return Collections.unmodifiableSet(paths);
    }

    @Override
    public boolean exists(final String path) throws StorageClientException {
        return !list(path, false).isEmpty();
    }

    @Override
    public final String getSignedUrl(final String path, final OffsetDateTime expiry)
            throws StorageClientException {
        return getSignedUrl(path, expiry, PresignedUrlMethod.GET);
    }

    @Override
    public final String getSignedUploadUrl(final String path, final OffsetDateTime expiry)
            throws StorageClientException {
        return getSignedUrl(path, expiry, PresignedUrlMethod.PUT);
    }

    private String getSignedUrl(
            final String path, final OffsetDateTime expiry, final PresignedUrlMethod method)
            throws StorageClientException {
        final var minioMethod =
                switch (method) {
                    case GET -> Method.GET;
                    case PUT -> Method.PUT;
                };
        final var expirySeconds =
                (int) Duration.between(OffsetDateTime.now(expiry.getOffset()), expiry).toSeconds();
        try {
            return minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(minioMethod)
                            .expiry(expirySeconds)
                            .bucket(bucket)
                            .object(path)
                            .build());
        } catch (final MinioException e) {
            throw storageClientException(
                    MessageFormat.format(
                            "failed to get signed url for [mappingPath={0}, expiry={1}]",
                            path, expiry),
                    e);
        }
    }

    @Override
    public final String getUrl(final String path) {
        throw new NotImplementedException(
                "Minio (actually S3) does not support permanent share URLs? (Maximum is 7 days as"
                        + " per S3 spec)");
    }

    public void createBucket(final String bucketName) throws StorageClientException {
        try {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
        } catch (final MinioException e) {
            throw storageClientException(
                    MessageFormat.format(
                            "failed to create bucket for [bucketName={0}]", bucketName),
                    e);
        }
    }
}
