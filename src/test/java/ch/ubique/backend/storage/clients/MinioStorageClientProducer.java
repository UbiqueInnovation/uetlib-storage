package ch.ubique.backend.storage.clients;

import ch.ubique.backend.storage.StorageClient;
import ch.ubique.backend.storage.exception.StorageClientException;

public interface MinioStorageClientProducer {
    /**
     * Instantiates a new Minio storage client with a given bucket, endpoint, access key, and secret
     * key.
     */
    StorageClient create(
            final String bucket,
            final String endpoint,
            final String accessKey,
            final String secretKey)
            throws StorageClientException;
}
