package ch.ubique.backend.storage.clients;

import ch.ubique.backend.core.http.header.HttpHeader;
import ch.ubique.backend.storage.OutputMetaDataProvider;

import io.minio.credentials.Jwt;
import io.minio.credentials.WebIdentityProvider;

import jakarta.annotation.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public final class AmazonS3StorageClient extends MinioStorageClient {

    /**
     * S3 storage client that loads an AWS Web Identity Token from a file and uses it to assume a
     * role. See <a
     * href="https://docs.aws.amazon.com/STS/latest/APIReference/API_AssumeRoleWithWebIdentity.html">AssumeRoleWithWebIdentity
     * API</a> for details.
     *
     * @param bucket The bucket name
     * @param region The endpoint URL of the S3 service. If null, the default endpoint, eu-central-1
     * @param awsWebIdentityTokenFile The path to the AWS Web Identity Token file
     * @param awsRoleArn The ARN of the role to assume
     * @param awsDurationSeconds The duration in seconds for which the credentials are valid
     */
    public AmazonS3StorageClient(
            final String bucket,
            @Nullable final String region,
            final Path awsWebIdentityTokenFile,
            final String awsRoleArn,
            final Integer awsDurationSeconds) {
        super(
                bucket,
                "s3." + Objects.requireNonNullElse(region, "eu-central-1") + ".amazonaws.com",
                new WebIdentityProvider(
                        () -> {
                            try {
                                return new Jwt(
                                        Files.readString(awsWebIdentityTokenFile),
                                        Optional.ofNullable(awsDurationSeconds).orElse(3600));
                            } catch (final IOException e) {
                                throw new RuntimeException(
                                        "Failed to load AWS Web Identity Token", e);
                            }
                        },
                        // NOTE: MinIO storage client doesn't prepend URL scheme to host
                        "https://"
                                // NOTE: assume regional (as opposed to global) STS endpoint
                                + "sts."
                                + Objects.requireNonNullElse(region, "eu-central-1")
                                + ".amazonaws.com",
                        null,
                        null,
                        awsRoleArn,
                        null,
                        null),
                null,
                null);
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
     * @param region The endpoint URL of the S3 service. If null, the default endpoint, eu-central-1
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
            final Duration timeout,
            final OutputMetaDataProvider outputMetaDataProvider) {
        super(
                bucket,
                "s3." + Objects.requireNonNullElse(region, "eu-central-1") + ".amazonaws.com",
                accessKey,
                secretKey,
                timeout,
                outputMetaDataProvider);
    }

    @Override
    protected String keyForCustomMetadata(final HttpHeader httpHeader) {
        return httpHeader.withAmazonPrefix();
    }
}
