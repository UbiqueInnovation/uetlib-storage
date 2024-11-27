package ch.ubique.backend.storage.exception;

import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketException;

import javax.net.ssl.SSLHandshakeException;

public abstract sealed class StorageClientException extends IOException
        permits NonRetryableStorageClientException, RetryableStorageClientException {

    protected StorageClientException(final String message, final Throwable cause) {
        super(message, cause);
    }

    public static StorageClientException of(final String message, final Throwable cause) {
        if (cause instanceof InterruptedIOException
                || cause instanceof SSLHandshakeException
                || cause instanceof EOFException
                || cause instanceof SocketException) {
            return new RetryableStorageClientException(message, cause);
        }
        return new NonRetryableStorageClientException(message, cause);
    }

    public boolean isRetryable() {
        return this instanceof RetryableStorageClientException;
    }
}
