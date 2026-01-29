package ch.ubique.backend.storage.exception;

import org.apache.commons.lang3.exception.ExceptionUtils;

import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;

import javax.net.ssl.SSLHandshakeException;

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
                || cause instanceof UnknownHostException
                || cause instanceof TimeoutException;
    }

    public boolean isRetryable() {
        return this instanceof RetryableStorageClientException;
    }
}
