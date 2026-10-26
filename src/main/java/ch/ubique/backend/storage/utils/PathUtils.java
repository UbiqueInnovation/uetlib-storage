package ch.ubique.backend.storage.utils;

import ch.ubique.backend.storage.exception.NonRetryableStorageClientException;

import java.text.MessageFormat;

/** Shared validation of object paths. */
public final class PathUtils {
    private PathUtils() {}

    /**
     * Paths ending in "/" denote directories (prefixes). Writing objects with such keys is not
     * supported, as not all implementations (e.g. the file system) can represent them.
     */
    public static void checkWritablePath(final String path)
            throws NonRetryableStorageClientException {
        if (path.isEmpty() || path.endsWith("/")) {
            throw new NonRetryableStorageClientException(
                    MessageFormat.format(
                            "cannot write to an empty path or a path ending in \"/\" [path={0}]",
                            path),
                    new IllegalArgumentException(path));
        }
    }
}
