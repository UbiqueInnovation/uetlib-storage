package ch.ubique.uetlib.storage.exception;

public final class NonRetryableStorageClientException extends StorageClientException {

    public NonRetryableStorageClientException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
