package ch.ubique.backend.storage.images;

import ch.ubique.backend.image.blurhash.BlurHashClient;
import ch.ubique.backend.image.blurhash.BlurHashRepresentation;
import ch.ubique.backend.image.util.ImageUtils;
import ch.ubique.backend.storage.StorageClient;
import ch.ubique.backend.storage.exception.StorageClientException;
import ch.ubique.backend.storage.output.ByteOutput;
import ch.ubique.backend.storage.output.InputStreamOutput;

import com.drew.imaging.ImageProcessingException;
import com.google.common.net.MediaType;

import jakarta.validation.constraints.NotNull;

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

    @NotNull
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

    @NotNull
    public BlurHashRepresentation processImageFromOriginal(
            final Function<ImageVariant, String> generatePath, final MediaType mediaType)
            throws IOException, ImageProcessingException {
        final var originalImageInputStream =
                storageClient.read(generatePath.apply(ImageVariant.ORIGINAL));
        final var originalImageBytes = originalImageInputStream.readAllBytes();
        // InputStream::readAllBytes does not close the imageInputStream
        originalImageInputStream.close();

        return processImageFromOriginalBytes(generatePath, originalImageBytes, mediaType);
    }

    @NotNull
    public BlurHashRepresentation processImageFromOriginalBytes(
            final Function<ImageVariant, String> generatePath,
            final byte[] originalImageBytes,
            final MediaType mediaType)
            throws IOException, ImageProcessingException {
        final var hires =
                ImageUtils.scaleAndFlush(
                        ImageUtils.imageFromBytes(originalImageBytes),
                        ImageVariant.HIRES.getDefaultSize());
        storageClient.write(
                new ByteOutput(
                        ImageUtils.imageToBytes(hires, mediaType.subtype()), mediaType.toString()),
                generatePath.apply(ImageVariant.HIRES));

        final var thumbnail =
                ImageUtils.scaleAndFlush(hires, ImageVariant.THUMBNAIL.getDefaultSize());
        storageClient.write(
                new ByteOutput(
                        ImageUtils.imageToBytes(thumbnail, mediaType.subtype()),
                        mediaType.toString()),
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
