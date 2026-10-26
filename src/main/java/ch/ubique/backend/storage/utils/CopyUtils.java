package ch.ubique.backend.storage.utils;

import ch.ubique.backend.storage.exception.NonRetryableStorageClientException;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

/** Shared validation and monotonic deadline for native copies. */
public final class CopyUtils {
    private CopyUtils() {}

    public static void checkPaths(final String source, final String destination)
            throws NonRetryableStorageClientException {
        PathUtils.checkWritablePath(destination);
        if (source.equals(destination)) {
            throw failure(
                    "Copy source and destination must differ",
                    new IllegalArgumentException(source));
        }
        checkInterrupted();
    }

    public static void checkInterrupted() throws NonRetryableStorageClientException {
        if (Thread.currentThread().isInterrupted()) {
            throw failure("Copy interrupted", new InterruptedException());
        }
    }

    public static Duration remaining(final long deadline)
            throws NonRetryableStorageClientException {
        checkInterrupted();
        final long nanos = deadline - System.nanoTime();
        if (nanos <= 0) {
            throw failure(
                    "Copy timed out; inspect destination before retrying", new TimeoutException());
        }
        return Duration.ofNanos(nanos);
    }

    public static NonRetryableStorageClientException failure(
            final String message, final Throwable cause) {
        return new NonRetryableStorageClientException(message, cause);
    }
}
