package ch.ubique.backend.storage.utils;

import ch.ubique.backend.core.utils.InputStreamUtils;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotNull;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipException;

public final class CompressionUtils {
    private CompressionUtils() {}

    public static @NotNull InputStream tryToUncompress(
            @NotNull final InputStream inputStream,
            @Nullable final String contentEncoding,
            @Nullable final String path)
            throws IOException {
        final var markSupportedInputStream = markSupportedInputStream(inputStream);
        try {
            return InputStreamUtils.uncompress(markSupportedInputStream, contentEncoding, path);
        } catch (final ZipException e) {
            markSupportedInputStream.reset();
            return markSupportedInputStream;
        }
    }

    public static @NotNull InputStream markSupportedInputStream(
            @NotNull final InputStream inputStream) {
        if (inputStream.markSupported()) {
            return inputStream;
        }
        return new BufferedInputStream(inputStream);
    }
}
