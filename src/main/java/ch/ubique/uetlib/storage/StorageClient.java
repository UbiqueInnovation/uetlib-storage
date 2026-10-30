package ch.ubique.uetlib.storage;

import ch.ubique.uetlib.core.io.FileProvider;
import ch.ubique.uetlib.storage.clients.RetryStorageClient;
import ch.ubique.uetlib.storage.exception.NonRetryableStorageClientException;
import ch.ubique.uetlib.storage.exception.StorageClientException;
import ch.ubique.uetlib.storage.output.Output;
import ch.ubique.uetlib.storage.output.OutputWithPath;
import ch.ubique.uetlib.storage.utils.CopyUtils;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.Collection;
import org.jspecify.annotations.Nullable;
import org.springframework.core.retry.RetryTemplate;

public interface StorageClient {

    /**
     * Copy one object within this client, overwriting the destination without changing the source.
     * Preserves stored bytes, HTTP properties and user metadata, including absent properties.
     * Returns only after completion. Identical paths and unsupported providers fail explicitly.
     * Provider size limits apply.
     */
    default void copy(final String sourcePath, final String destinationPath)
            throws StorageClientException {
        copy(sourcePath, destinationPath, CopyOptions.defaults());
    }

    /**
     * Copy with an explicit destination access policy and timeout. No upload defaults are applied.
     * Failures may leave a destination behind; reconcile it before retrying an uncertain outcome.
     * The default preserves compatibility with external implementations without losing metadata.
     */
    default void copy(
            final String sourcePath, final String destinationPath, final CopyOptions options)
            throws StorageClientException {
        CopyUtils.checkPaths(sourcePath, destinationPath);
        throw new NonRetryableStorageClientException(
                "Native copy is not supported by " + getClass().getName(),
                new UnsupportedOperationException(
                        "Native copy not implemented. Consider reading the content and writing it back."));
    }

    void write(Output output, String path, @Nullable OutputMetaData outputMetaData)
            throws StorageClientException;

    void write(
            final Collection<OutputWithPath> outputs, @Nullable final OutputMetaData outputMetaData)
            throws StorageClientException;

    InputStream read(String path, boolean doUncompressIfNeeded) throws StorageClientException;

    /**
     * @return {@code true} if the path was deleted, {@code false} if it did not exist.
     */
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

    /**
     * Convience method to create a {@link FileProvider} for the given path. Automatic uncompression
     * is disabled.
     */
    default FileProvider fileProvider(final String path) {
        return FileProvider.of(() -> read(path, false));
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
