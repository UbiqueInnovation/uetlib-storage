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
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.auth.credentials.WebIdentityTokenFileCredentialsProvider;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.transfer.s3.S3TransferManager;
import software.amazon.awssdk.transfer.s3.model.DownloadRequest;
import software.amazon.awssdk.transfer.s3.progress.LoggingTransferListener;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryNotEmptyException;
import java.text.MessageFormat;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;

public class AmazonS3StorageClient implements StorageClient {

    private static final String DEFAULT_REGION = "eu-central-1";

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
     * @param timeout Timeout for requests (ignored for AWS S3)
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
        this.s3AsyncClient =
                S3AsyncClient.builder()
                        .credentialsProvider(
                                StaticCredentialsProvider.create(
                                        AwsBasicCredentials.create(accessKey, secretKey)))
                        .region(Region.of(Objects.requireNonNullElse(region, DEFAULT_REGION)))
                        .multipartEnabled(true)
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

    @Override
    public final void write(
            final @NotNull Output output,
            final @NotNull String path,
            @Nullable final OutputMetaData outputMetaData)
            throws StorageClientException {
        try (final var inputStream = output.read()) {
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
            final var upload =
                    tm.upload(
                            uploadRequestBuilder ->
                                    uploadRequestBuilder
                                            .requestBody(
                                                    AsyncRequestBody.fromInputStream(
                                                            inputStream,
                                                            output.contentLength() < 0L
                                                                    ? null
                                                                    : output.contentLength(),
                                                            taskExecutor))
                                            .addTransferListener(LoggingTransferListener.create())
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
        } catch (final CancellationException | CompletionException | IOException e) {
            // TODO: (meinen) handle exceptions properly
            throw StorageClientException.of(
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
        return httpHeader.withAmazonPrefix();
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
            throw StorageClientException.of(
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
            throw StorageClientException.of(
                    MessageFormat.format("can not delete not empty directory. [path={0}]", path),
                    new DirectoryNotEmptyException(path));
        }

        final var objectsToDelete =
                recursive
                        ? existing.stream()
                                .map(key -> ObjectIdentifier.builder().key(key).build())
                                .toList()
                        : List.of(ObjectIdentifier.builder().key(path).build());

        final var errors = new ArrayList<S3Error>();
        for (var chunkIdx = 0; chunkIdx < objectsToDelete.size(); chunkIdx += 1000) {
            // delete doesn't accept more than 1000 objects at once
            final var chunkEnd = Math.min(chunkIdx + 1000, objectsToDelete.size());
            final var chunk = objectsToDelete.subList(chunkIdx, chunkEnd);
            final var deleteObjectsResponse =
                    s3AsyncClient
                            .deleteObjects(
                                    builder ->
                                            builder.bucket(bucket)
                                                    .delete(
                                                            deleteBuilder ->
                                                                    deleteBuilder.objects(chunk)))
                            .join();
            errors.addAll(deleteObjectsResponse.errors());
        }

        if (!errors.isEmpty()) {
            throw StorageClientException.of(
                    "Failed to delete object(s) from S3", new S3StorageClientException(errors));
        }

        return true;
    }

    @Override
    public Collection<String> list(@NotNull final String path, final boolean recursive)
            throws StorageClientException {
        try {
            final var builder = ListObjectsV2Request.builder().bucket(bucket).prefix(path);

            // If not recursive, group by common prefix
            if (!recursive) {
                builder.delimiter("/");
            }

            final var listObjectsV2Request = builder.build();
            final var listObjectsV2Response =
                    s3AsyncClient.listObjectsV2(listObjectsV2Request).join();

            final var keys = new HashSet<String>();
            listObjectsV2Response.contents().forEach(s3Object -> keys.add(s3Object.key()));

            // If not recursive, also list "folders" (i.e. common prefixes)
            if (!recursive) {
                listObjectsV2Response.commonPrefixes().forEach(prefix -> keys.add(prefix.prefix()));
            }
            return keys;
        } catch (final CancellationException | CompletionException e) {
            throw StorageClientException.of(
                    MessageFormat.format(
                            "failed to list for [mappingPath={0}, recursive={1}]", path, recursive),
                    e);
        }
    }

    @Override
    public boolean exists(final @NotNull String path) throws StorageClientException {
        /* NOTE: implemented using listObjects instead of headObject method as this method can also be used to check for the existence of sub-paths (i.e. 'directories') */
        return !list(path, false).isEmpty();
    }

    @Override
    public String getSignedUrl(final @NotNull String path, final @NotNull OffsetDateTime expiry)
            throws StorageClientException {
        try {
            final PresignedGetObjectRequest presignedRequest;
            final var presignedBuilder =
                    S3Presigner.builder()
                            .region(s3AsyncClient.serviceClientConfiguration().region())
                            .credentialsProvider(
                                    s3AsyncClient
                                            .serviceClientConfiguration()
                                            .credentialsProvider());

            if (s3AsyncClient.serviceClientConfiguration().endpointOverride().isPresent()) {
                // endpoint overrides are only used for testing purposes - i.e. where path-style
                // access is required
                presignedBuilder.endpointOverride(
                        s3AsyncClient.serviceClientConfiguration().endpointOverride().get());
                presignedBuilder.serviceConfiguration(
                        S3Configuration.builder().pathStyleAccessEnabled(true).build());
            }
            try (final var presigner = presignedBuilder.build()) {
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
        } catch (final CancellationException | CompletionException e) {
            throw StorageClientException.of(
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

    public void createBucket(final String bucketName) throws StorageClientException {
        try {
            s3AsyncClient.createBucket(builder -> builder.bucket(bucketName)).join();
        } catch (final CancellationException | CompletionException e) {
            throw StorageClientException.of(
                    MessageFormat.format(
                            "failed to create bucket for [bucketName={0}]", bucketName),
                    e);
        }
    }
}
