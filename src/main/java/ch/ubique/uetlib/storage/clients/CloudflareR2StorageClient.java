package ch.ubique.uetlib.storage.clients;

import ch.ubique.uetlib.storage.OutputMetaDataProvider;

import org.jspecify.annotations.Nullable;

import java.time.Duration;

public final class CloudflareR2StorageClient extends RetryStorageClient {

    public CloudflareR2StorageClient(
            final String bucket,
            final String accountId,
            final String accessKey,
            final String secretKey) {
        this(bucket, accountId, accessKey, secretKey, null, null);
    }

    public CloudflareR2StorageClient(
            final String bucket,
            final String accountId,
            final String accessKey,
            final String secretKey,
            @Nullable final Duration timeout,
            @Nullable final OutputMetaDataProvider outputMetaDataProvider) {
        super(
                new MinioStorageClient(
                        bucket,
                        "https://" + accountId + ".r2.cloudflarestorage.com",
                        accessKey,
                        secretKey,
                        timeout,
                        outputMetaDataProvider));
    }
}
