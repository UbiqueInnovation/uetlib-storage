package ch.ubique.uetlib.storage.utils;

import ch.ubique.uetlib.core.utils.InputStreamUtils;

import org.jspecify.annotations.Nullable;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipException;

public final class CompressionUtils {
    private CompressionUtils() {}

    public static InputStream tryToUncompress(
            final InputStream inputStream,
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

    public static InputStream markSupportedInputStream(final InputStream inputStream) {
        if (inputStream.markSupported()) {
            return inputStream;
        }
        return new BufferedInputStream(inputStream);
    }

    public static byte[] gzipCompress(final byte[] uncompressedBytes) throws IOException {
        try (final var outputStream = new ByteArrayOutputStream()) {
            try (final var gzipOutputStream = new GZIPOutputStream(outputStream)) {
                gzipOutputStream.write(uncompressedBytes);
            }
            return outputStream.toByteArray();
        }
    }
}
