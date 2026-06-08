package ch.ubique.backend.storage;

import ch.ubique.backend.storage.clients.RetryStorageClient;
import ch.ubique.backend.storage.exception.StorageClientException;
import ch.ubique.backend.storage.output.Output;
import ch.ubique.backend.storage.output.OutputWithPath;

import org.jspecify.annotations.Nullable;
import org.springframework.core.retry.RetryTemplate;

import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.Collection;

public interface StorageClient {

    void write(Output output, String path, @Nullable OutputMetaData outputMetaData)
            throws StorageClientException;

    void write(
            final Collection<OutputWithPath> outputs, @Nullable final OutputMetaData outputMetaData)
            throws StorageClientException;

    InputStream read(String path, boolean doUncompressIfNeeded) throws StorageClientException;

    boolean delete(String path, boolean recursive) throws StorageClientException;

    Collection<String> list(String path, boolean recursive) throws StorageClientException;

    boolean exists(String path) throws StorageClientException;

    String getSignedUrl(String path, OffsetDateTime expiry) throws StorageClientException;

    /**
     * Create a Presigned Upload URL. The returned URL can be used to make a PUT-request to, which
     * includes the file. Note that the Blob Storage Provider may impose further restrictions, note
     * the Javadoc on the specific implementations and their documentation.
     */
    String getSignedUploadUrl(final String path, final OffsetDateTime expiry)
            throws StorageClientException;

    String getUrl(String path) throws StorageClientException;

    default void write(final Output output, final String path) throws StorageClientException {
        write(output, path, null);
    }

    default InputStream read(final String path) throws StorageClientException {
        return read(path, true);
    }

    default boolean delete(final String path) throws StorageClientException {
        return delete(path, false);
    }

    default Collection<String> list() throws StorageClientException {
        return list("");
    }

    default Collection<String> list(final boolean recursive) throws StorageClientException {
        return list("", recursive);
    }

    default Collection<String> list(final String path) throws StorageClientException {
        return list(path, false);
    }

    default StorageClient retryable(@Nullable final RetryTemplate retryTemplate) {
        return new RetryStorageClient(this, retryTemplate);
    }

    default StorageClient retryable() {
        return retryable(null);
    }

    default StorageClient withoutRetry() {
        return this;
    }
}
