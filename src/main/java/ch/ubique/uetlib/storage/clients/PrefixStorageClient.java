package ch.ubique.uetlib.storage.clients;

import ch.ubique.uetlib.core.io.FileProvider;
import ch.ubique.uetlib.core.utils.StringUtils;
import ch.ubique.uetlib.storage.CopyOptions;
import ch.ubique.uetlib.storage.OutputMetaData;
import ch.ubique.uetlib.storage.StorageClient;
import ch.ubique.uetlib.storage.exception.StorageClientException;
import ch.ubique.uetlib.storage.output.Output;
import ch.ubique.uetlib.storage.output.OutputWithPath;
import ch.ubique.uetlib.storage.utils.CopyUtils;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Objects;

public class PrefixStorageClient implements StorageClient {

    private final String pathPrefix;
    private final StorageClient storageClient;

    public PrefixStorageClient(final String pathPrefix, final StorageClient storageClient) {
        StringUtils.requireNonBlank(pathPrefix, "Path prefix must not be blank.");
        if (pathPrefix.startsWith("/")) {
            throw new IllegalArgumentException("Path prefix must not start with '/'");
        }
        if (storageClient instanceof final PrefixStorageClient prefixStorageClient) {
            this.pathPrefix = normalizePrefix(pathPrefix + prefixStorageClient.pathPrefix);
            this.storageClient = prefixStorageClient.storageClient;
        } else {
            this.pathPrefix = normalizePrefix(pathPrefix);
            this.storageClient = storageClient;
        }
    }

    private static String normalizePrefix(final String pathPrefix) {
        final var clean = Path.of(pathPrefix).normalize().toString();
        return clean + "/";
    }

    StorageClient delegate() {
        return storageClient;
    }

    @Override
    public StorageClient withoutRetry() {
        return new PrefixStorageClient(pathPrefix, storageClient.withoutRetry());
    }

    @Override
    public void copy(
            final String sourcePath, final String destinationPath, final CopyOptions options)
            throws StorageClientException {
        CopyUtils.checkPaths(sourcePath, destinationPath);
        storageClient.copy(prefixedPath(sourcePath), prefixedPath(destinationPath), options);
    }

    @Override
    public void write(
            final Output output, final String path, @Nullable final OutputMetaData outputMetaData)
            throws StorageClientException {
        storageClient.write(output, prefixedPath(path), outputMetaData);
    }

    @Override
    public void write(
            final Collection<OutputWithPath> outputs, @Nullable final OutputMetaData outputMetaData)
            throws StorageClientException {
        storageClient.write(outputs.stream().map(this::prefixOutputPath).toList(), outputMetaData);
    }

    @Override
    public InputStream read(final String path, final boolean doUncompressIfNeeded)
            throws StorageClientException {
        return storageClient.read(prefixedPath(path), doUncompressIfNeeded);
    }

    @Override
    public FileProvider fileProvider(final String path) {
        return storageClient.fileProvider(prefixedPath(path));
    }

    @Override
    public boolean delete(final String path, final boolean recursive)
            throws StorageClientException {
        return storageClient.delete(prefixedPath(path), recursive);
    }

    @Override
    public Collection<String> list(final String path, final boolean recursive)
            throws StorageClientException {
        return storageClient.list(prefixedPath(path), recursive).stream()
                .map(this::stripPrefix)
                .toList();
    }

    @Override
    public boolean exists(final String path) throws StorageClientException {
        return storageClient.exists(prefixedPath(path));
    }

    @Override
    public String getSignedUrl(final String path, final OffsetDateTime expiry)
            throws StorageClientException {
        return storageClient.getSignedUrl(prefixedPath(path), expiry);
    }

    @Override
    public String getSignedUploadUrl(final String path, final OffsetDateTime expiry)
            throws StorageClientException {
        return storageClient.getSignedUploadUrl(prefixedPath(path), expiry);
    }

    @Override
    public String getUrl(final String path) throws StorageClientException {
        return storageClient.getUrl(prefixedPath(path));
    }

    private OutputWithPath prefixOutputPath(final OutputWithPath outputWithPath) {
        return new OutputWithPath(outputWithPath.output(), prefixedPath(outputWithPath.path()));
    }

    public String prefixedPath(final String path) {
        return pathPrefix + path;
    }

    private String stripPrefix(final String path) {
        if (!Objects.requireNonNull(path).startsWith(pathPrefix)) {
            throw new IllegalStateException(
                    "Path must start with " + pathPrefix + ". Got " + path + ".");
        }
        return path.substring(pathPrefix.length());
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this, ToStringStyle.SHORT_PREFIX_STYLE)
                .append("pathPrefix", pathPrefix)
                .append("storageClient", storageClient)
                .toString();
    }
}
