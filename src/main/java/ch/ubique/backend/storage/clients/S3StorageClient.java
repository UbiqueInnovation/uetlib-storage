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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.auth.credentials.WebIdentityTokenFileCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.DirectoryNotEmptyException;
import java.text.MessageFormat;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;

public class S3StorageClient implements StorageClient {

    private static final Logger logger = LoggerFactory.getLogger(S3StorageClient.class);
    private static final String DEFAULT_REGION = "eu-central-1";
    private static final int DEFAULT_MAX_PART_SIZE = 8 * 1024 * 1024;

    private final String bucket;
    private final S3Client s3Client;
    @Nullable private final OutputMetaDataProvider outputMetaDataProvider;
    private final Region region;
    private final AwsCredentialsProvider credentialsProvider;
    @Nullable private final URI endpointOverride;

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
        this.region = Region.of(Objects.requireNonNullElse(region, DEFAULT_REGION));
        this.credentialsProvider = WebIdentityTokenFileCredentialsProvider.create();
        this.endpointOverride = null;
        this.s3Client =
                S3Client.builder()
                        .credentialsProvider(credentialsProvider)
                        .region(this.region)
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
     * @param region The region of the S3 service. If null, the default region eu-central-1 is used
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
        this.region = Region.of(Objects.requireNonNullElse(region, DEFAULT_REGION));
        this.credentialsProvider =
                StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
        this.endpointOverride = null;
        this.s3Client =
                S3Client.builder()
                        .credentialsProvider(credentialsProvider)
                        .region(this.region)
                        .build();
    }

    /**
     * Constructor only used for testing purposes.
     *
     * @param bucket The bucket name
     * @param region The region of the S3 service. If null, the default region eu-central-1 is used
     * @param endpoint The endpoint URL of the S3 service. If null, the default region eu-central-1
     *     is used to create an S3 endpoint.
     * @param accessKey The AWS auth access key
     * @param secretKey The AWS auth secret key
     */
    protected S3StorageClient(
            final String bucket,
            @Nullable final String region,
            final String endpoint,
            final String accessKey,
            final String secretKey) {
        this.bucket = bucket;
        this.outputMetaDataProvider = null;
        this.region = Region.of(Objects.requireNonNullElse(region, DEFAULT_REGION));
        this.credentialsProvider =
                StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
        this.endpointOverride = URI.create(endpoint);
        this.s3Client =
                S3Client.builder()
                        .credentialsProvider(credentialsProvider)
                        .region(this.region)
                        .endpointOverride(endpointOverride)
                        .forcePathStyle(true)
                        .build();
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
        final var contentType = Objects.requireNonNull(output.contentType());
        final var contentEncoding = output.contentEncoding().orElse(null);
        final var cacheControl =
                Optional.ofNullable(outputMetaData)
                        .map(OutputMetaData::createCacheControl)
                        .orElse(null);
        final var expires =
                Optional.ofNullable(outputMetaData).flatMap(OutputMetaData::expires).orElse(null);
        final var acl =
                Optional.ofNullable(outputMetaData)
                        .flatMap(OutputMetaData::createCannedAcl)
                        .orElse(null);

        try (final var inputStream = output.read()) {
            if (output.contentLength() < 0L || output.contentLength() > DEFAULT_MAX_PART_SIZE) {
                logger.info(
                        "Initiating multipart upload for object [path={}; size={}]",
                        path,
                        output.contentLength());

                // object size is unknown or too large for a single PUT request
                final var uploadId =
                        s3Client.createMultipartUpload(
                                        builder ->
                                                builder.bucket(bucket)
                                                        .key(path)
                                                        .contentType(contentType)
                                                        .contentEncoding(contentEncoding)
                                                        .cacheControl(cacheControl)
                                                        .expires(expires)
                                                        .acl(acl)
                                                        .metadata(createUserMetadata(metaData)))
                                .uploadId();

                final List<CompletedPart> completedParts = new ArrayList<>();
                final var buffer = new byte[DEFAULT_MAX_PART_SIZE];
                var partNumber = 1;

                int bytesRead;
                var totalBytesRead = 0;
                while ((bytesRead =
                                inputStream.read(
                                        buffer, totalBytesRead, buffer.length - totalBytesRead))
                        != -1) {
                    totalBytesRead += bytesRead;

                    if (totalBytesRead >= buffer.length) {
                        // buffer is full
                        uploadPart(path, partNumber, bytesRead, uploadId, buffer, completedParts);
                        partNumber++;
                        totalBytesRead = 0; // reset buffer
                    }
                }

                if (totalBytesRead > 0) {
                    // upload remaining bytes
                    uploadPart(path, partNumber, totalBytesRead, uploadId, buffer, completedParts);
                }

                logger.info("Completing multipart upload for object [path={}]", path);

                // Complete the multipart upload
                s3Client.completeMultipartUpload(
                        requestBuilder ->
                                requestBuilder
                                        .bucket(bucket)
                                        .key(path)
                                        .uploadId(uploadId)
                                        .multipartUpload(
                                                multipartUploadBuilder ->
                                                        multipartUploadBuilder.parts(
                                                                completedParts)));
            } else {
                // object size is known and reasonable
                s3Client.putObject(
                        builder ->
                                builder.bucket(bucket)
                                        .key(path)
                                        .contentType(contentType)
                                        .contentEncoding(contentEncoding)
                                        .cacheControl(cacheControl)
                                        .expires(expires)
                                        .acl(acl)
                                        .metadata(createUserMetadata(metaData)),
                        RequestBody.fromInputStream(inputStream, output.contentLength()));
            }
        } catch (final AwsServiceException | SdkClientException | IOException e) {
            throw StorageClientException.of(
                    MessageFormat.format(
                            "failed to write for [path={0}, outputMetaData={1}, output={2}]",
                            path, outputMetaData, output),
                    e);
        }
    }

    private void uploadPart(
            final String path,
            final int partNumber,
            final int bytesRead,
            final String uploadId,
            final byte[] buffer,
            final List<CompletedPart> completedParts) {
        logger.info("Uploading part [partNumber={}; bytesRead={}]", partNumber, bytesRead);
        final var uploadPartRequest =
                UploadPartRequest.builder()
                        .bucket(bucket)
                        .key(path)
                        .uploadId(uploadId)
                        .partNumber(partNumber)
                        .build();

        final var uploadPartResponse =
                s3Client.uploadPart(
                        uploadPartRequest, RequestBody.fromBytes(Arrays.copyOf(buffer, bytesRead)));

        completedParts.add(
                CompletedPart.builder()
                        .partNumber(partNumber)
                        .eTag(uploadPartResponse.eTag())
                        .build());
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
                final var contentEncoding = response.response().contentEncoding();
                return CompressionUtils.tryToUncompress(response, contentEncoding, path);
            }

            return response;
        } catch (final AwsServiceException | SdkClientException | IOException e) {
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

        final var delete = Delete.builder().objects(objectsToDelete).build();

        final var deleteObjectsResponse =
                s3Client.deleteObjects(builder -> builder.bucket(bucket).delete(delete));

        if (!deleteObjectsResponse.errors().isEmpty()) {
            throw StorageClientException.of(
                    "Failed to delete object(s) from S3",
                    new S3StorageClientException(deleteObjectsResponse.errors()));
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
            final var listObjectsV2Response = s3Client.listObjectsV2(listObjectsV2Request);

            final var keys = new HashSet<String>();
            listObjectsV2Response.contents().forEach(s3Object -> keys.add(s3Object.key()));

            // If not recursive, also list "folders" (i.e. common prefixes)
            if (!recursive) {
                listObjectsV2Response.commonPrefixes().forEach(prefix -> keys.add(prefix.prefix()));
            }
            return keys;
        } catch (final AwsServiceException | SdkClientException e) {
            throw StorageClientException.of(
                    MessageFormat.format(
                            "failed to list for [mappingPath={0}, recursive={1}]", path, recursive),
                    e);
        }
    }

    @Override
    public boolean exists(final @NotNull String path) throws StorageClientException {
        return !list(path, false).isEmpty();
    }

    @Override
    public String getSignedUrl(final @NotNull String path, final @NotNull OffsetDateTime expiry)
            throws StorageClientException {
        try {
            final PresignedGetObjectRequest presignedRequest;
            try (final var presigner =
                    S3Presigner.builder()
                            .region(s3Client.serviceClientConfiguration().region())
                            .credentialsProvider(
                                    s3Client.serviceClientConfiguration().credentialsProvider())
                            .endpointOverride(
                                    s3Client.serviceClientConfiguration()
                                            .endpointOverride()
                                            .orElse(null))
                            .build()) {
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

            if (s3Client.serviceClientConfiguration().endpointOverride().isPresent()) {
                // endpoint overrides are used for testing purposes
                // local S3 instances require path-style URLs - replace url
                return presignedRequest
                        .url()
                        .toString()
                        .replace(bucket + ".", "")
                        .replace("/" + path, "/" + bucket + "/" + path);
            }

            return presignedRequest.url().toString();
        } catch (final AwsServiceException | SdkClientException e) {
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
            s3Client.createBucket(builder -> builder.bucket(bucketName));
        } catch (final AwsServiceException | SdkClientException e) {
            throw StorageClientException.of(
                    MessageFormat.format(
                            "failed to create bucket for [bucketName={0}]", bucketName),
                    e);
        }
    }
}
