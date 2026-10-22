package ch.ubique.backend.storage.clients;

import ch.ubique.backend.core.executor.VirtualThreadUtils;
import ch.ubique.backend.core.http.header.HttpHeader;
import ch.ubique.backend.core.utils.StreamUtils;
import ch.ubique.backend.storage.CopyOptions;
import ch.ubique.backend.storage.OutputMetaData;
import ch.ubique.backend.storage.OutputMetaDataProvider;
import ch.ubique.backend.storage.StorageClient;
import ch.ubique.backend.storage.exception.RetryableStorageClientException;
import ch.ubique.backend.storage.exception.S3StorageClientException;
import ch.ubique.backend.storage.exception.StorageClientException;
import ch.ubique.backend.storage.model.PresignedUrlMethod;
import ch.ubique.backend.storage.output.ByteOutput;
import ch.ubique.backend.storage.output.FileOutput;
import ch.ubique.backend.storage.output.GZipOutput;
import ch.ubique.backend.storage.output.Output;
import ch.ubique.backend.storage.output.OutputWithPath;
import ch.ubique.backend.storage.utils.CompressionUtils;
import ch.ubique.backend.storage.utils.CopyUtils;

import jakarta.validation.Valid;

import org.apache.commons.lang3.NotImplementedException;
import org.jspecify.annotations.Nullable;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.auth.credentials.WebIdentityTokenFileCredentialsProvider;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.transfer.s3.S3TransferManager;
import software.amazon.awssdk.transfer.s3.model.DownloadRequest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryNotEmptyException;
import java.text.MessageFormat;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;

public class AmazonS3StorageClient implements StorageClient {

    private static final long MAX_COPY_SIZE = 5L * 1024 * 1024 * 1024;

    private static final String DEFAULT_REGION = "eu-central-1";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);

    private final String bucket;
    private final S3TransferManager tm;
    private final S3AsyncClient s3AsyncClient;
    @Nullable private final OutputMetaDataProvider outputMetaDataProvider;
    private final ExecutorService taskExecutor = Executors.newVirtualThreadPerTaskExecutor();

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
    public AmazonS3StorageClient(final String bucket, @Nullable final String region) {
        this.bucket = bucket;
        this.outputMetaDataProvider = null;
        this.s3AsyncClient =
                S3AsyncClient.builder()
                        .credentialsProvider(WebIdentityTokenFileCredentialsProvider.create())
                        .region(Region.of(Objects.requireNonNullElse(region, DEFAULT_REGION)))
                        .multipartEnabled(true)
                        // TODO: test in deployment if checksums work as expected for gzip uploads
                        .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                        .httpClientBuilder(
                                NettyNioAsyncHttpClient.builder()
                                        .connectionTimeout(DEFAULT_TIMEOUT)
                                        .readTimeout(DEFAULT_TIMEOUT)
                                        .writeTimeout(DEFAULT_TIMEOUT))
                        .build();
        this.tm = S3TransferManager.builder().s3Client(s3AsyncClient).build();
    }

    /**
     * @see #AmazonS3StorageClient(String, String, String, String, Duration, OutputMetaDataProvider)
     */
    public AmazonS3StorageClient(
            final String bucket, final String accessKey, final String secretKey) {
        this(bucket, null, accessKey, secretKey, null, null);
    }

    /**
     * @see #AmazonS3StorageClient(String, String, String, String, Duration, OutputMetaDataProvider)
     */
    public AmazonS3StorageClient(
            final String bucket,
            @Nullable final String region,
            final String accessKey,
            final String secretKey) {
        this(bucket, region, accessKey, secretKey, null, null);
    }

    /**
     * @param bucket The bucket name
     * @param region The region of the S3 service. If null, the default region eu-central-1 is used
     * @param accessKey The AWS auth access key
     * @param secretKey The AWS auth secret key
     * @param timeout Timeout for requests
     * @param outputMetaDataProvider Provider for output meta data
     */
    public AmazonS3StorageClient(
            final String bucket,
            @Nullable final String region,
            final String accessKey,
            final String secretKey,
            @Nullable final Duration timeout,
            @Nullable final OutputMetaDataProvider outputMetaDataProvider) {
        this.bucket = bucket;
        this.outputMetaDataProvider = outputMetaDataProvider;
        final var timeoutOrDefault = Objects.requireNonNullElse(timeout, DEFAULT_TIMEOUT);
        this.s3AsyncClient =
                S3AsyncClient.builder()
                        .credentialsProvider(
                                StaticCredentialsProvider.create(
                                        AwsBasicCredentials.create(accessKey, secretKey)))
                        .region(Region.of(Objects.requireNonNullElse(region, DEFAULT_REGION)))
                        .multipartEnabled(true)
                        .httpClientBuilder(
                                NettyNioAsyncHttpClient.builder()
                                        .connectionTimeout(timeoutOrDefault)
                                        .readTimeout(timeoutOrDefault)
                                        .writeTimeout(timeoutOrDefault))
                        // TODO: test in deployment if checksums work as expected for gzip uploads
                        .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                        .build();
        this.tm = S3TransferManager.builder().s3Client(s3AsyncClient).build();
    }

    public AmazonS3StorageClient(
            final String bucket,
            @Nullable final OutputMetaDataProvider outputMetaDataProvider,
            final S3AsyncClient s3AsyncClient) {
        this.bucket = bucket;
        this.outputMetaDataProvider = outputMetaDataProvider;
        this.s3AsyncClient = s3AsyncClient;
        this.tm = S3TransferManager.builder().s3Client(s3AsyncClient).build();
    }

    private static StorageClientException toStorageClientException(
            final String message, final Throwable throwable) {
        return toStorageClientException(message, throwable, false);
    }

    private static StorageClientException toStorageClientException(
            final String message, final Throwable throwable, final boolean canRetry) {

        // Task was cancelled -> can be retried
        if (throwable instanceof final CancellationException e) {
            return new RetryableStorageClientException(message, e);
        }

        // Unwrap the cause of the CompletionException
        if (throwable instanceof final CompletionException e) {
            final var cause = e.getCause();
            if (cause != null) {
                return toStorageClientException(message, cause, canRetry);
            }
        }

        // SdkClientException is retryable -> can be retried
        if (throwable instanceof final SdkClientException e && e.retryable()) {
            return new RetryableStorageClientException(message, throwable);
        }

        // Fallback: use the boolean flag
        if (canRetry) {
            return new RetryableStorageClientException(message, throwable);
        }
        return StorageClientException.of(message, throwable);
    }

    @Override
    public void copy(
            final String sourcePath, final String destinationPath, final CopyOptions options)
            throws StorageClientException {
        CopyUtils.checkPaths(sourcePath, destinationPath);
        final long deadline = System.nanoTime() + options.timeout().toNanos();
        try {
            final var source =
                    awaitCopy(
                            s3AsyncClient.headObject(
                                    HeadObjectRequest.builder()
                                            .bucket(bucket)
                                            .key(sourcePath)
                                            .overrideConfiguration(
                                                    copyRequestConfiguration(
                                                            CopyUtils.remaining(deadline)))
                                            .build()),
                            deadline);
            if (source.contentLength() > MAX_COPY_SIZE) {
                throw CopyUtils.failure(
                        "S3 native copy supports at most 5 GiB",
                        new IllegalArgumentException(sourcePath));
            }
            final var accessPolicy = options.accessPolicy();
            final var request =
                    CopyObjectRequest.builder()
                            .sourceBucket(bucket)
                            .sourceKey(sourcePath)
                            .destinationBucket(bucket)
                            .destinationKey(destinationPath)
                            .copySourceIfMatch(source.eTag())
                            .metadataDirective(MetadataDirective.COPY)
                            .acl(accessPolicy == null ? null : accessPolicy.toString())
                            .overrideConfiguration(
                                    copyRequestConfiguration(CopyUtils.remaining(deadline)))
                            .build();
            final var result = awaitCopy(s3AsyncClient.copyObject(request), deadline);
            if (result.copyObjectResult() == null || result.copyObjectResult().eTag() == null) {
                throw CopyUtils.failure(
                        "S3 did not confirm copy completion", new IllegalStateException());
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw CopyUtils.failure("S3 copy interrupted; inspect destination before retrying", e);
        } catch (final ExecutionException | TimeoutException | RuntimeException e) {
            throw CopyUtils.failure(
                    "S3 copy failed; destination outcome may be unknown. Inspect before retrying",
                    e);
        }
    }

    private static AwsRequestOverrideConfiguration copyRequestConfiguration(
            final Duration timeout) {
        return AwsRequestOverrideConfiguration.builder().apiCallTimeout(timeout).build();
    }

    private static <T> T awaitCopy(final CompletableFuture<T> future, final long deadline)
            throws InterruptedException,
                    ExecutionException,
                    TimeoutException,
                    StorageClientException {
        try {
            return future.get(CopyUtils.remaining(deadline).toNanos(), TimeUnit.NANOSECONDS);
        } finally {
            if (!future.isDone()) {
                future.cancel(true);
            }
        }
    }

    @Override
    public final void write(
            final Output output, final String path, @Nullable final OutputMetaData outputMetaData)
            throws StorageClientException {
        try {
            final var metaData =
                    OutputMetaData.resolveOutputMetaData(
                            outputMetaData, outputMetaDataProvider, output);
            final var contentType = Objects.requireNonNull(output.contentType());
            final var contentEncoding = output.contentEncoding().orElse(null);
            final var cacheControl =
                    Optional.ofNullable(outputMetaData)
                            .map(OutputMetaData::createCacheControl)
                            .orElse(null);
            final var expires =
                    Optional.ofNullable(outputMetaData)
                            .flatMap(OutputMetaData::expiresAsInstant)
                            .orElse(null);
            final var acl =
                    Optional.ofNullable(outputMetaData)
                            .flatMap(OutputMetaData::createCannedAcl)
                            .orElse(null);

            try (final var requestBodyAndClosable = createRequestBody(output)) {
                final var upload =
                        tm.upload(
                                uploadRequestBuilder ->
                                        uploadRequestBuilder
                                                .requestBody(requestBodyAndClosable.requestBody())
                                                .putObjectRequest(
                                                        req ->
                                                                req.bucket(bucket)
                                                                        .key(path)
                                                                        .contentType(contentType)
                                                                        .contentEncoding(
                                                                                contentEncoding)
                                                                        .cacheControl(cacheControl)
                                                                        .expires(expires)
                                                                        .acl(acl)
                                                                        .metadata(
                                                                                createUserMetadata(
                                                                                        metaData))));
                // Wait for the upload to finish - exceptions aren't thrown until 'join' is called
                upload.completionFuture().join();
            }
        } catch (final CancellationException | CompletionException | IOException e) {
            throw toStorageClientException(
                    MessageFormat.format(
                            "failed to write for [path={0}, outputMetaData={1}, output={2}]",
                            path, outputMetaData, output),
                    e,
                    output.canBeRetried());
        }
    }

    private AsyncRequestBodyAndCloseable createRequestBody(final Output output) throws IOException {
        if (output instanceof final FileOutput fileOutput) {
            return AsyncRequestBodyAndCloseable.of(AsyncRequestBody.fromFile(fileOutput.file()));
        }
        if (output instanceof final ByteOutput byteOutput) {
            return AsyncRequestBodyAndCloseable.of(AsyncRequestBody.fromBytes(byteOutput.value()));
        }
        if (output instanceof GZipOutput(final var uncompressed)
                && uncompressed instanceof final ByteOutput byteOutput) {
            final var compressedBytes = CompressionUtils.gzipCompress(byteOutput.value());
            return AsyncRequestBodyAndCloseable.of(AsyncRequestBody.fromBytes(compressedBytes));
        }

        final var inputStream = output.read();
        return AsyncRequestBodyAndCloseable.of(
                AsyncRequestBody.fromInputStream(
                        inputStream,
                        output.contentLength() < 0L ? null : output.contentLength(),
                        taskExecutor),
                inputStream);
    }

    private Map<String, String> createUserMetadata(final OutputMetaData outputMetaData) {
        return outputMetaData.createCustomHeadersWithKeyMapper(this::keyForCustomMetadata);
    }

    protected String keyForCustomMetadata(final HttpHeader httpHeader) {
        return httpHeader.withAmazonPrefix();
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
            final var downloadRequest =
                    DownloadRequest.builder()
                            .getObjectRequest(
                                    getObjectRequestBuilder ->
                                            getObjectRequestBuilder.bucket(bucket).key(path))
                            .responseTransformer(AsyncResponseTransformer.toBlockingInputStream())
                            .build();
            final var download = tm.download(downloadRequest).completionFuture().join().result();

            if (doUncompressIfNeeded) {
                final var contentEncoding = download.response().contentEncoding();
                return CompressionUtils.tryToUncompress(download, contentEncoding, path);
            }

            return download;
        } catch (final CancellationException | CompletionException | IOException e) {
            throw toStorageClientException(
                    MessageFormat.format(
                            "failed to read for [path={0}, doUncompressIfNeeded={1}]",
                            path, doUncompressIfNeeded),
                    e);
        }
    }

    @Override
    public boolean delete(final String path, final boolean recursive)
            throws StorageClientException {

        final var existing = list(path, true);

        if (!recursive && existing.stream().anyMatch(object -> !object.equals(path))) {
            throw toStorageClientException(
                    MessageFormat.format(
                            "cannot delete non-empty directory. [path={0}, recursive=false]", path),
                    new DirectoryNotEmptyException(path));
        }

        final var objectsToDelete =
                existing.stream().map(key -> ObjectIdentifier.builder().key(key).build()).toList();

        final var errors =
                StreamUtils.chunked(objectsToDelete, 1000)
                        .flatMap(
                                chunk ->
                                        s3AsyncClient
                                                .deleteObjects(
                                                        builder ->
                                                                builder.bucket(bucket)
                                                                        .delete(
                                                                                deleteBuilder ->
                                                                                        deleteBuilder
                                                                                                .objects(
                                                                                                        chunk)))
                                                .join()
                                                .errors()
                                                .stream())
                        .toList();

        if (errors.isEmpty()) {
            return true;
        }

        throw toStorageClientException(
                "Failed to delete object(s) from S3", new S3StorageClientException(errors));
    }

    @Override
    public Collection<String> list(final String path, final boolean recursive)
            throws StorageClientException {
        try {
            final var keys = new LinkedHashSet<String>();

            String continuationToken = null;
            do {
                final var builder =
                        ListObjectsV2Request.builder()
                                .bucket(bucket)
                                .prefix(path)
                                .continuationToken(continuationToken);

                // If not recursive, group by the common prefix
                if (!recursive) {
                    builder.delimiter("/");
                }

                final var listObjectsV2Response =
                        s3AsyncClient.listObjectsV2(builder.build()).join();

                listObjectsV2Response.contents().forEach(s3Object -> keys.add(s3Object.key()));

                // If not recursive, also list "folders" (i.e. common prefixes)
                if (!recursive) {
                    listObjectsV2Response
                            .commonPrefixes()
                            .forEach(prefix -> keys.add(prefix.prefix()));
                }

                continuationToken = listObjectsV2Response.nextContinuationToken();
            } while (continuationToken != null);

            return keys;
        } catch (final CancellationException | CompletionException e) {
            throw toStorageClientException(
                    MessageFormat.format(
                            "failed to list for [mappingPath={0}, recursive={1}]", path, recursive),
                    e);
        }
    }

    @Override
    public boolean exists(final String path) throws StorageClientException {
        /* NOTE: implemented using listObjects instead of headObject method as this method can also be used to check for the existence of sub-paths (i.e. 'directories') */
        return !list(path, false).isEmpty();
    }

    @Override
    public String getSignedUrl(final String path, final OffsetDateTime expiry)
            throws StorageClientException {
        return getSignedUrl(path, expiry, PresignedUrlMethod.GET);
    }

    @Override
    public String getSignedUploadUrl(final String path, final OffsetDateTime expiry)
            throws StorageClientException {
        return getSignedUrl(path, expiry, PresignedUrlMethod.PUT);
    }

    private String getSignedUrl(
            final String path, final OffsetDateTime expiry, final PresignedUrlMethod method)
            throws StorageClientException {
        try {
            final var presignedBuilder =
                    S3Presigner.builder()
                            .region(s3AsyncClient.serviceClientConfiguration().region())
                            .credentialsProvider(
                                    s3AsyncClient
                                            .serviceClientConfiguration()
                                            .credentialsProvider());

            final var endpointOverride =
                    s3AsyncClient.serviceClientConfiguration().endpointOverride();
            if (endpointOverride.isPresent()) {
                // endpoint overrides are only used for testing purposes - i.e. where path-style
                // access is required
                presignedBuilder.endpointOverride(endpointOverride.get());
                presignedBuilder.serviceConfiguration(
                        S3Configuration.builder().pathStyleAccessEnabled(true).build());
            }
            return switch (method) {
                case GET -> {
                    final PresignedGetObjectRequest presignedRequest;
                    try (final var presigner = presignedBuilder.build()) {
                        presignedRequest =
                                presigner.presignGetObject(
                                        builder ->
                                                builder.getObjectRequest(
                                                                objectRequestBuilder ->
                                                                        objectRequestBuilder
                                                                                .bucket(bucket)
                                                                                .key(path))
                                                        .signatureDuration(
                                                                Duration.between(
                                                                        OffsetDateTime.now(
                                                                                expiry.getOffset()),
                                                                        expiry)));
                    }

                    yield presignedRequest.url().toString();
                }
                case PUT -> {
                    final PresignedPutObjectRequest presignedRequest;
                    try (final var presigner = presignedBuilder.build()) {
                        presignedRequest =
                                presigner.presignPutObject(
                                        builder ->
                                                builder.putObjectRequest(
                                                                objectRequestBuilder ->
                                                                        objectRequestBuilder
                                                                                .bucket(bucket)
                                                                                .key(path))
                                                        .signatureDuration(
                                                                Duration.between(
                                                                        OffsetDateTime.now(
                                                                                expiry.getOffset()),
                                                                        expiry)));
                    }
                    yield presignedRequest.url().toString();
                }
            };
        } catch (final CancellationException | CompletionException e) {
            throw toStorageClientException(
                    MessageFormat.format(
                            "failed to get signed url for [mappingPath={0}, expiry={1}]",
                            path, expiry),
                    e);
        }
    }

    @Override
    public final String getUrl(final String path) {
        throw new NotImplementedException(
                "S3 does not support permanent share URLs? (Maximum is 7 days as per S3 spec)");
    }

    public void createBucket(final String bucketName) throws StorageClientException {
        try {
            s3AsyncClient.createBucket(builder -> builder.bucket(bucketName)).join();
        } catch (final CancellationException | CompletionException e) {
            throw toStorageClientException(
                    MessageFormat.format(
                            "failed to create bucket for [bucketName={0}]", bucketName),
                    e);
        }
    }

    private record AsyncRequestBodyAndCloseable(
            AsyncRequestBody requestBody, Optional<InputStream> closable) implements AutoCloseable {
        static AsyncRequestBodyAndCloseable of(final AsyncRequestBody requestBody) {
            return new AsyncRequestBodyAndCloseable(requestBody, Optional.empty());
        }

        static AsyncRequestBodyAndCloseable of(
                final AsyncRequestBody requestBody, final InputStream closable) {
            return new AsyncRequestBodyAndCloseable(requestBody, Optional.of(closable));
        }

        @Override
        public void close() throws IOException {
            if (closable.isPresent()) {
                closable.get().close();
            }
        }
    }
}
