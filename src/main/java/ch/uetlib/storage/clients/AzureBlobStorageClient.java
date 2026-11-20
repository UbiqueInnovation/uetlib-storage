package ch.uetlib.storage.clients;

import ch.uetlib.core.executor.VirtualThreadUtils;
import ch.uetlib.storage.CopyOptions;
import ch.uetlib.storage.OutputMetaData;
import ch.uetlib.storage.OutputMetaDataProvider;
import ch.uetlib.storage.StorageClient;
import ch.uetlib.storage.exception.StorageClientException;
import ch.uetlib.storage.output.Output;
import ch.uetlib.storage.output.OutputWithPath;
import ch.uetlib.storage.utils.CompressionUtils;
import ch.uetlib.storage.utils.CopyUtils;
import ch.uetlib.storage.utils.PathUtils;

import com.azure.core.http.HttpClient;
import com.azure.core.http.RequestConditions;
import com.azure.core.http.rest.PagedIterable;
import com.azure.core.util.Context;
import com.azure.core.util.HttpClientOptions;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.*;
import com.azure.storage.blob.options.BlobCopyFromUrlOptions;
import com.azure.storage.blob.options.BlobParallelUploadOptions;
import com.azure.storage.blob.sas.BlobSasPermission;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;
import com.azure.storage.common.policy.RequestRetryOptions;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.text.MessageFormat;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public class AzureBlobStorageClient implements StorageClient {

    private final BlobServiceClient blobServiceClient;
    private final BlobContainerClient blobContainerClient;
    @Nullable private final OutputMetaDataProvider outputMetaDataProvider;
    private final long blockSize;
    private final Duration timeout;

    public AzureBlobStorageClient(final String container, final String connectionString) {
        this(container, connectionString, null, null);
    }

    public AzureBlobStorageClient(
            final String container,
            final String connectionString,
            @Nullable final Duration timeout,
            @Nullable final OutputMetaDataProvider outputMetaDataProvider) {
        this(container, connectionString, timeout, 1024L, outputMetaDataProvider);
    }

    public AzureBlobStorageClient(
            final String container,
            final String connectionString,
            @Nullable final Duration timeout,
            final long blockSize,
            @Nullable final OutputMetaDataProvider outputMetaDataProvider) {
        this.timeout = Objects.requireNonNullElse(timeout, Duration.ofSeconds(60));
        final var httpClient =
                HttpClient.createDefault(
                        new HttpClientOptions()
                                .setConnectTimeout(this.timeout)
                                .setReadTimeout(this.timeout)
                                .setWriteTimeout(this.timeout));
        this.blobServiceClient =
                new BlobServiceClientBuilder()
                        .connectionString(connectionString)
                        .retryOptions(new RequestRetryOptions())
                        .httpClient(httpClient)
                        .buildClient();
        this.blobContainerClient = blobServiceClient.getBlobContainerClient(container);
        this.outputMetaDataProvider = outputMetaDataProvider;
        this.blockSize = blockSize;
    }

    @Override
    public void copy(
            final String sourcePath, final String destinationPath, final CopyOptions options)
            throws StorageClientException {
        CopyUtils.checkPaths(sourcePath, destinationPath);
        final long deadline = System.nanoTime() + options.timeout().toNanos();
        final var source = blobContainerClient.getBlobClient(sourcePath);
        final var destination = blobContainerClient.getBlobClient(destinationPath);
        String copyId = null;
        try {
            final var properties =
                    source.getPropertiesWithResponse(
                                    null, CopyUtils.remaining(deadline), Context.NONE)
                            .getValue();
            if (properties.getBlobType() != BlobType.BLOCK_BLOB
                    || properties.getBlobSize() > 256L * 1024 * 1024) {
                throw CopyUtils.failure(
                        "Azure synchronous copy supports block blobs up to 256 MiB",
                        new IllegalArgumentException(sourcePath));
            }
            // The per-call timeout covers SDK retries and backoff within the shared deadline.
            final var response =
                    destination.copyFromUrlWithResponse(
                            new BlobCopyFromUrlOptions(source.getBlobUrl())
                                    .setSourceRequestConditions(
                                            new RequestConditions()
                                                    .setIfMatch(properties.getETag())),
                            CopyUtils.remaining(deadline),
                            Context.NONE);
            copyId = response.getValue();
            awaitCopy(
                    destination,
                    copyId,
                    response.getHeaders().getValue("x-ms-copy-status"),
                    deadline);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw copyFailure(destination, copyId, e);
        } catch (final StorageClientException | RuntimeException e) {
            throw copyFailure(destination, copyId, e);
        }
    }

    private static void awaitCopy(
            final BlobClient destination,
            final String copyId,
            @Nullable final String initialStatus,
            final long deadline)
            throws StorageClientException, InterruptedException {
        String status = initialStatus;
        while (!"success".equals(status)) {
            if (status != null && !"pending".equals(status)) {
                throw CopyUtils.failure(
                        "Azure copy ended with status " + status,
                        new IllegalStateException(copyId));
            }
            final var properties =
                    destination
                            .getPropertiesWithResponse(
                                    null, CopyUtils.remaining(deadline), Context.NONE)
                            .getValue();
            if (!copyId.equals(properties.getCopyId())) {
                throw CopyUtils.failure(
                        "Azure destination copy ID changed", new IllegalStateException(copyId));
            }
            status =
                    properties.getCopyStatus() == null
                            ? "unknown"
                            : properties.getCopyStatus().toString();
            if ("pending".equals(status)) {
                Thread.sleep(
                        Duration.ofMillis(
                                Math.clamp(CopyUtils.remaining(deadline).toMillis(), 1L, 250L)));
            }
        }
        CopyUtils.remaining(deadline);
    }

    private static StorageClientException copyFailure(
            final BlobClient destination, @Nullable final String copyId, final Exception cause) {
        final var failure =
                CopyUtils.failure(
                        "Azure copy failed [destination="
                                + destination.getBlobName()
                                + ", copyId="
                                + copyId
                                + "]. Destination may be incomplete or the request may still be running; inspect before retrying",
                        cause);
        if (copyId != null) {
            try {
                // Bounded cleanup grace period; abort only our job, never delete the destination.
                destination.abortCopyFromUrlWithResponse(
                        copyId, null, Duration.ofSeconds(5), Context.NONE);
            } catch (final RuntimeException abortFailure) {
                failure.addSuppressed(abortFailure);
            }
        }
        return failure;
    }

    private static BlobHttpHeaders createHeaders(
            final Output output, @Nullable final OutputMetaData metaData) {
        final var headers = new BlobHttpHeaders();

        // contentType
        headers.setContentType(Objects.requireNonNull(output.contentType()));

        // contentEncoding
        output.contentEncoding().ifPresent(headers::setContentEncoding);

        // cacheControl
        Optional.ofNullable(metaData)
                .map(OutputMetaData::createCacheControl)
                .ifPresent(headers::setCacheControl);

        return headers;
    }

    @Override
    public void write(
            final Output output, final String path, @Nullable final OutputMetaData outputMetaData)
            throws StorageClientException {
        PathUtils.checkWritablePath(path);
        final var metaData =
                OutputMetaData.resolveOutputMetaData(
                        outputMetaData, outputMetaDataProvider, output);

        try (final var inputStream = output.read()) {

            // Define upload
            final var uploadOptions = new BlobParallelUploadOptions(inputStream);
            uploadOptions.setHeaders(createHeaders(output, metaData));
            uploadOptions.setMetadata(metaData.createAzureMetaDataMap());
            uploadOptions.setRequestConditions(new BlobRequestConditions());
            uploadOptions.setParallelTransferOptions(
                    new ParallelTransferOptions().setMaxConcurrency(4).setBlockSizeLong(blockSize));

            // Upload
            blobContainerClient
                    .getBlobClient(path)
                    .uploadWithResponse(uploadOptions, null, Context.NONE);
        } catch (final IOException | RuntimeException e) {
            throw StorageClientException.of(
                    MessageFormat.format("failed to write [path={0}]", path), e);
        }
    }

    @Override
    public void write(
            final Collection<OutputWithPath> outputs, @Nullable final OutputMetaData outputMetaData)
            throws StorageClientException {
        // The SDK unfortunately does not support a bulk upload.
        VirtualThreadUtils.throwingForEach(
                item -> write(item.output(), item.path(), outputMetaData), outputs);
    }

    @Override
    @SuppressWarnings("java:S2095")
    public InputStream read(final String path, final boolean doUncompressIfNeeded)
            throws StorageClientException {
        try {
            final var inputStream = blobContainerClient.getBlobClient(path).openInputStream();
            if (doUncompressIfNeeded) {
                final var contentEncoding = getProperties(path).getContentEncoding();
                return CompressionUtils.tryToUncompress(inputStream, contentEncoding, path);
            }
            return inputStream;
        } catch (final Exception e) {
            throw StorageClientException.of(
                    MessageFormat.format("failed to read for [path={0}]", path), e);
        }
    }

    private BlobProperties getProperties(final String path) throws StorageClientException {
        try {
            return blobContainerClient.getBlobClient(path).getProperties();
        } catch (final Exception e) {
            throw StorageClientException.of(
                    MessageFormat.format("could not read properties for [path={0}]", path), e);
        }
    }

    @Override
    public boolean delete(final String path, final boolean recursive)
            throws StorageClientException {
        if (recursive) {
            return deleteRecursive(path);
        }
        return deleteNonRecursive(path);
    }

    private boolean deleteNonRecursive(final String path) throws StorageClientException {
        if (blobExists(path)) {
            return deleteIfExists(path);
        }

        // If we arrive here, a single blob matching the path exactly does not exist:
        // - If there are some files that match the path, we throw
        // - Otherwise we return false, meaning nothing was deleted
        final var existing = list(path, true);
        if (!existing.isEmpty()) {
            throw StorageClientException.of(
                    MessageFormat.format(
                            "cannot delete non-empty directory. [path={0}, recursive=false]", path),
                    new DirectoryNotEmptyException(path));
        }

        return false;
    }

    private boolean deleteRecursive(final String path) throws StorageClientException {
        final var existing = list(path, true);
        var deletedAny = false;
        for (final var pathToDelete : existing) {
            final var deleted = deleteIfExists(pathToDelete);
            deletedAny = deletedAny || deleted;
        }
        return deletedAny;
    }

    @Override
    public Collection<String> list(final String path, final boolean recursive)
            throws StorageClientException {

        try {
            final PagedIterable<BlobItem> blobItems;
            if (recursive) {
                final var options = new ListBlobsOptions().setPrefix(path);
                blobItems = blobContainerClient.listBlobs(options, timeout);
            } else {
                blobItems = blobContainerClient.listBlobsByHierarchy(path);
            }
            return blobItems.stream()
                    .map(BlobItem::getName)
                    .collect(Collectors.toUnmodifiableSet());
        } catch (final RuntimeException e) {
            throw StorageClientException.of(
                    MessageFormat.format(
                            "failed to list for [path={0}, recursive={1}]", path, recursive),
                    e);
        }
    }

    @Override
    public boolean exists(final String path) throws StorageClientException {
        return blobExists(path) || !list(path, false).isEmpty();
    }

    private boolean blobExists(final String path) throws StorageClientException {
        try {
            return blobContainerClient.getBlobClient(path).exists();
        } catch (final RuntimeException e) {
            throw StorageClientException.of(
                    MessageFormat.format("failed to check existence for [path={0}]", path), e);
        }
    }

    private boolean deleteIfExists(final String path) throws StorageClientException {
        try {
            return blobContainerClient.getBlobClient(path).deleteIfExists();
        } catch (final RuntimeException e) {
            throw StorageClientException.of(
                    MessageFormat.format("failed to delete for [path={0}]", path), e);
        }
    }

    @Override
    public String getSignedUrl(final String path, final OffsetDateTime expiry) {
        return getSignedUrl(path, expiry, p -> p.setReadPermission(true));
    }

    /**
     * Note: On the `PUT` request, Azure requires the following Header to be present.
     *
     * <p>{@code x-ms-blob-type: "BlockBlob"}
     */
    @Override
    public String getSignedUploadUrl(final String path, final OffsetDateTime expiry) {
        return getSignedUrl(
                path,
                expiry,
                p -> p.setCreatePermission(true).setWritePermission(true).setReadPermission(true));
    }

    private String getSignedUrl(
            final String path,
            final OffsetDateTime expiry,
            final Function<BlobSasPermission, BlobSasPermission> configurer) {
        final var blobSasPermission = configureSasPermission(configurer);
        final var values =
                new BlobServiceSasSignatureValues(expiry, blobSasPermission)
                        .setStartTime(OffsetDateTime.now(expiry.getOffset()));

        final var blobClient = blobContainerClient.getBlobClient(path);

        return URLDecoder.decode(blobClient.getBlobUrl(), StandardCharsets.UTF_8)
                + "?"
                + blobClient.generateSas(values);
    }

    private BlobSasPermission configureSasPermission(
            final Function<BlobSasPermission, BlobSasPermission> function) {
        return function.apply(new BlobSasPermission());
    }

    @Override
    public String getUrl(final String path) {
        final var blobUrlEncoded = URLEncoder.encode(path, StandardCharsets.UTF_8);
        final var blobClient = blobContainerClient.getBlobClient(blobUrlEncoded);
        return URLDecoder.decode(blobClient.getBlobUrl(), StandardCharsets.UTF_8);
    }

    public void createContainer(final String containerName) {
        blobServiceClient.createBlobContainer(containerName);
    }
}
