package ch.ubique.backend.storage.clients;

import ch.ubique.backend.storage.OutputMetaData;
import ch.ubique.backend.storage.StorageClient;
import ch.ubique.backend.storage.exception.StorageClientException;
import ch.ubique.backend.storage.output.Output;
import ch.ubique.backend.storage.output.OutputWithPath;
import ch.ubique.backend.storage.utils.CompressionUtils;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotNull;

import java.util.stream.Collectors;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.DirectoryFileFilter;
import org.apache.commons.io.filefilter.PrefixFileFilter;
import org.apache.commons.io.filefilter.TrueFileFilter;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.stream.Stream;

@SuppressWarnings("unused")
public class FileSystemStorageClient implements StorageClient {

    private static final Logger logger = LoggerFactory.getLogger(FileSystemStorageClient.class);

    private final String basePath;
    private final boolean writeUncompressed;

    public FileSystemStorageClient(final String basePath) {
        this(basePath, true);
    }

    public FileSystemStorageClient(final String basePath, final boolean writeUncompressed) {
        this.basePath = basePath;
        this.writeUncompressed = writeUncompressed;
    }

    @Override
    public void write(
            final @NotNull Output output,
            final @NotNull String path,
            @Nullable final OutputMetaData outputMetaData)
            throws StorageClientException {
        final var completePath = getCompletePath(path);
        final var file = new File(completePath.toUri());
        final var parent = file.getParentFile();
        if (!parent.exists()) {
            synchronized (this) {
                if (!parent.exists()) {
                    logger.info("Creating parent directory {}", parent);
                    final var createdDir = parent.mkdirs();
                    if (!createdDir) {
                        throw StorageClientException.of(
                                "Could not create directory for path " + parent,
                                new FileNotFoundException());
                    }
                }
            }
        }
        logger.trace("writing output to {} (complete path {})", path, completePath);
        try (final var outputStream = new FileOutputStream(file)) {
            if (writeUncompressed) {
                output.uncompressed().write(outputStream);
            } else {
                output.write(outputStream);
            }
        } catch (final IOException e) {
            throw StorageClientException.of(
                    MessageFormat.format("failed to write for [path={0}]", path), e);
        }
    }

    @Override
    public void write(
            final Collection<OutputWithPath> outputs, @Nullable final OutputMetaData outputMetaData)
            throws StorageClientException {
        for (final var outputWithPath : outputs) {
            write(outputWithPath.output(), outputWithPath.path(), outputMetaData);
        }
    }

    @Override
    @SuppressWarnings("java:S2095")
    public InputStream read(final @NotNull String path, final boolean doUncompressIfNeeded)
            throws StorageClientException {
        final var completePath = getCompletePath(path);
        final var file = new File(completePath.toUri());
        logger.trace("reading input from {} (complete path {})", path, completePath);

        try {
            final var fileInputStream = new FileInputStream(file);

            if (doUncompressIfNeeded) {
                final var contentEncoding = isGzipFile(completePath) ? "gzip" : null;
                return CompressionUtils.tryToUncompress(fileInputStream, contentEncoding, path);
            }

            return fileInputStream;
        } catch (final IOException e) {
            throw StorageClientException.of(MessageFormat.format("failed to read {0}", file), e);
        }
    }

    @Override
    public boolean delete(final @NotNull String path, final boolean recursive)
            throws StorageClientException {

        final var existing = list(path, true);

        if (!recursive && existing.stream().anyMatch(object -> !object.equals(path))) {
            throw StorageClientException.of(
                    MessageFormat.format(
                            "cannot delete non-empty directory. [path={0}, recursive=false]", path),
                    new DirectoryNotEmptyException(path));
        }

        final var completePath = getCompletePath(path);
        logger.trace("deleting from {} (complete path {})", path, completePath);
        if (recursive) {
            return FileUtils.deleteQuietly(completePath.toFile());
        }

        try {
            return Files.deleteIfExists(completePath);
        } catch (final IOException e) {
            throw StorageClientException.of(
                    MessageFormat.format("failed to delete for [path={0}, recursive=false]", path),
                    e);
        }
    }

    @Override
    public Collection<String> list(@NotNull final String path, final boolean recursive)
            throws StorageClientException {
        final var completePath = getCompletePath(path);
        logger.trace("listing files from {} (complete path {})", path, completePath);
        final var files =
                FileUtils.listFiles(
                        completePath.toFile(), TrueFileFilter.TRUE, DirectoryFileFilter.DIRECTORY);
        final var prefixMatches = listFilesWithPrefix(completePath);
        final var matches = Stream.concat(files.stream(), prefixMatches.stream());
        final var relativePaths = mapToRelativePaths(matches);
        if (!recursive) {
            return relativePaths
                    .map(p -> nonRecursiveMatch(path, p))
                    .collect(Collectors.toUnmodifiableSet());
        }
        return relativePaths.collect(Collectors.toUnmodifiableSet());
    }

    private static String nonRecursiveMatch(final String queryPath, final String relativePath) {
        final var subPath = relativePath.substring(queryPath.length());
        if (!subPath.contains("/")) {
            return relativePath;
        }
        return queryPath + subPath.split("/")[0] + "/";
    }

    @Override
    public boolean exists(final @NotNull String path) {
        final var completePath = getCompletePath(path);
        logger.trace("checking file existing at {} (complete path {})", path, completePath);

        if (completePath.toFile().isDirectory()) {
            return completePath.toFile().exists();
        }

        final var prefixMatches = listFilesWithPrefix(completePath);
        return !prefixMatches.isEmpty();
    }

    private static Collection<File> listFilesWithPrefix(final Path completePath) {
        final var directory = completePath.getParent();
        final var prefix = completePath.getFileName().toString();
        return FileUtils.listFiles(directory.toFile(), new PrefixFileFilter(prefix), null);
    }

    private Stream<String> mapToRelativePaths(final Stream<File> stream) {
        final var baseUri = Path.of(basePath).toUri();
        return stream.map(file -> baseUri.relativize(file.toURI()).getPath())
                .filter(StringUtils::isNotBlank);
    }

    @Override
    public String getSignedUrl(final @NotNull String path, final @NotNull OffsetDateTime expiry) {
        return getUrl(path);
    }

    @Override
    public String getUrl(final @NotNull String path) {
        return getCompletePath(path).toUri().toString();
    }

    @NotNull
    private Path getCompletePath(@NotNull final String path) {
        return Path.of(basePath, path);
    }

    public String basePath() {
        return basePath;
    }

    private static boolean isGzipFile(final Path path) {
        try (final InputStream inputStream = new FileInputStream(path.toFile())) {
            final var header = new byte[2];
            final var bytesRead = inputStream.read(header);
            // See https://en.wikipedia.org/wiki/Gzip
            // The header of gzip always starts with the magic number 1f 8b
            return bytesRead == 2 && header[0] == (byte) 0x1F && header[1] == (byte) 0x8B;
        } catch (final IOException e) {
            return false;
        }
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this, ToStringStyle.SHORT_PREFIX_STYLE)
                .append("basePath", basePath)
                .append("writeUncompressed", writeUncompressed)
                .toString();
    }
}
