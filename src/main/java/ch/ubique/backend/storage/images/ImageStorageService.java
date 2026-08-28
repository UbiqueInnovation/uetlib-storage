package ch.ubique.backend.storage.images;

import static java.util.Objects.requireNonNull;

import ch.ubique.backend.image.blurhash.BlurHashClient;
import ch.ubique.backend.image.blurhash.BlurHashRepresentation;
import ch.ubique.backend.image.util.ImageUtils;
import ch.ubique.backend.image.webp.Webp;
import ch.ubique.backend.storage.StorageClient;
import ch.ubique.backend.storage.exception.StorageClientException;
import ch.ubique.backend.storage.output.ByteOutput;
import ch.ubique.backend.storage.output.InputStreamOutput;

import com.drew.imaging.ImageProcessingException;
import com.google.common.net.MediaType;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.function.Function;

/**
 * Saves images in multiple, resized variants and retrieve singed access urls to the uploaded
 * resources using the provided {@link StorageClient} .
 */
@SuppressWarnings("unused")
public class ImageStorageService {

    private final StorageClient storageClient;
    private final Duration sasExpiry;
    private final BlurHashClient blurHashClient;

    public ImageStorageService(
            final StorageClient storageClient,
            final Duration sasExpiry,
            final BlurHashClient blurHashClient) {
        this.storageClient = storageClient;
        this.sasExpiry = sasExpiry;
        this.blurHashClient = blurHashClient;
    }

    /** Construct with default sasExpiry of 7 days. Useful if SAS is not used. */
    public ImageStorageService(
            final StorageClient storageClient, final BlurHashClient blurHashClient) {
        this.storageClient = storageClient;
        this.sasExpiry = Duration.ofDays(7);
        this.blurHashClient = blurHashClient;
    }

    public BlurHashRepresentation processImage(
            final InputStream imageInputStream,
            final Function<ImageVariant, String> generatePath,
            final MediaType mediaType)
            throws IOException, ImageProcessingException {
        uploadOriginalImage(imageInputStream, generatePath, mediaType);
        return processImageFromOriginal(generatePath, mediaType);
    }

    public void uploadOriginalImage(
            final byte[] imageBytes,
            final Function<ImageVariant, String> generatePath,
            final MediaType mediaType)
            throws IOException {
        storageClient.write(
                new ByteOutput(imageBytes, mediaType.toString()),
                generatePath.apply(ImageVariant.ORIGINAL));
    }

    public void uploadOriginalImage(
            final InputStream imageInputStream,
            final Function<ImageVariant, String> generatePath,
            final MediaType mediaType)
            throws IOException {
        storageClient.write(
                new InputStreamOutput(imageInputStream, mediaType.toString()),
                generatePath.apply(ImageVariant.ORIGINAL));
    }

    public BlurHashRepresentation processImageFromOriginal(
            final Function<ImageVariant, String> generatePath, final MediaType mediaType)
            throws IOException, ImageProcessingException {
        final var originalImageBytes =
                downloadBlobAsBytes(generatePath.apply(ImageVariant.ORIGINAL));
        return processImageFromOriginalBytes(generatePath, originalImageBytes, mediaType);
    }

    public BlurHashRepresentation processImageFromOriginalConvertToWebp(
            final Function<ImageVariant, String> generatePath, final MediaType mediaType)
            throws IOException, ImageProcessingException {
        final var originalImageBytes =
                downloadBlobAsBytes(generatePath.apply(ImageVariant.ORIGINAL));
        return processImageFromOriginalBytesConvertToWebp(generatePath, originalImageBytes);
    }

    public BlurHashRepresentation processImageFromOriginalBytes(
            final Function<ImageVariant, String> generatePath,
            final byte[] originalImageBytes,
            final MediaType mediaType)
            throws IOException, ImageProcessingException {
        final BufferedImage original = ImageUtils.imageFromBytes(originalImageBytes);
        try {
            return processImageFromDecodedOriginal(generatePath, original, mediaType, false);
        } finally {
            original.flush();
        }
    }

    public BlurHashRepresentation processImageFromOriginalBytesConvertToWebp(
            final Function<ImageVariant, String> generatePath, final byte[] originalImageBytes)
            throws IOException, ImageProcessingException {
        final BufferedImage original = ImageUtils.imageFromBytes(originalImageBytes);
        try {
            return processImageFromDecodedOriginal(generatePath, original, MediaType.WEBP, true);
        } finally {
            original.flush();
        }
    }

    /**
     * Same as {@link #processImageFromOriginalBytesConvertToWebp}, but takes an already-decoded
     * image. Caller retains ownership of {@code original} and is responsible for flushing it.
     *
     * <p>Note: this method scales {@code original} via {@link ImageUtils#scaleAndFlush}, which
     * flushes {@code original} as a side effect. Callers must not rely on {@code original} after
     * calling this method.
     */
    public BlurHashRepresentation processImageFromDecodedOriginalConvertToWebp(
            final Function<ImageVariant, String> generatePath, final BufferedImage original)
            throws IOException {
        return processImageFromDecodedOriginal(generatePath, original, MediaType.WEBP, true);
    }

    private BlurHashRepresentation processImageFromDecodedOriginal(
            final Function<ImageVariant, String> generatePath,
            final BufferedImage original,
            final MediaType mediaType,
            final boolean convertToWebp)
            throws IOException {
        final var hires =
                ImageUtils.scaleAndFlush(
                        original, requireNonNull(ImageVariant.HIRES.getDefaultSize()));

        final byte[] hiresBytes =
                convertToWebp
                        ? Webp.lossy(80).encode(hires)
                        : ImageUtils.imageToBytes(hires, mediaType.subtype());
        storageClient.write(
                new ByteOutput(hiresBytes, mediaType.toString()),
                generatePath.apply(ImageVariant.HIRES));

        final var thumbnail =
                ImageUtils.scaleAndFlush(
                        hires, requireNonNull(ImageVariant.THUMBNAIL.getDefaultSize()));
        final byte[] thumbnailBytes =
                convertToWebp
                        ? Webp.lossy(80).encode(thumbnail)
                        : ImageUtils.imageToBytes(thumbnail, mediaType.subtype());
        storageClient.write(
                new ByteOutput(thumbnailBytes, mediaType.toString()),
                generatePath.apply(ImageVariant.THUMBNAIL));

        final var blurHashRepresentation =
                blurHashClient.encodeToBlurHashRepresentationNoResize(thumbnail);
        // Flush clears native references so the image can be GC-ed. See the tip of Scalr::resize
        thumbnail.flush();
        return blurHashRepresentation;
    }

    public void deleteImageVariants(final Function<ImageVariant, String> generatePath)
            throws IOException {
        for (final var variant : ImageVariant.values()) {
            deleteImageVariant(generatePath.apply(variant));
        }
    }

    @SuppressWarnings("UnusedReturnValue")
    public boolean deleteImageVariant(final String path) throws IOException {
        return storageClient.delete(path);
    }

    public byte[] downloadBlobAsBytes(final String path) throws IOException {
        try (final var inputStream = storageClient.read(path)) {
            return inputStream.readAllBytes();
        }
    }

    @SuppressWarnings("UnusedReturnValue")
    public String getSignedUrl(final String path) throws StorageClientException {
        return storageClient.getSignedUrl(
                path, OffsetDateTime.now().plusNanos(sasExpiry.toNanos()));
    }

    public String getUrl(final String path) throws StorageClientException {
        return storageClient.getUrl(path);
    }
}
