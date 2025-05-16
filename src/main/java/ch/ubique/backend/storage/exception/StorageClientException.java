package ch.ubique.backend.storage.exception;

import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketException;

import java.net.UnknownHostException;
import javax.net.ssl.SSLHandshakeException;
import org.apache.commons.lang3.exception.ExceptionUtils;

public abstract sealed class StorageClientException extends IOException
        permits NonRetryableStorageClientException, RetryableStorageClientException {

    protected StorageClientException(final String message, final Throwable cause) {
        super(message, cause);
    }

    public static StorageClientException of(final String message, final Throwable cause) {
        final var rootCause = ExceptionUtils.getRootCause(ExceptionUtils.getRootCause(cause));
        if (isRetryable(cause) || isRetryable(rootCause)) {
            return new RetryableStorageClientException(message, cause);
        }
        return new NonRetryableStorageClientException(message, cause);
    }

    private static boolean isRetryable(final Throwable cause) {
        if (cause == null) {
            return false;
        }
        return cause instanceof InterruptedIOException
                || cause instanceof SSLHandshakeException
                || cause instanceof EOFException
                || cause instanceof SocketException
                || cause instanceof UnknownHostException;
    }

    public boolean isRetryable() {
        return this instanceof RetryableStorageClientException;
    }
}
