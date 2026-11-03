package ch.ubique.uetlib.storage.clients;

import static java.util.Objects.*;

import ch.ubique.uetlib.storage.CopyOptions;
import ch.ubique.uetlib.storage.OutputMetaData;
import ch.ubique.uetlib.storage.StorageClient;
import ch.ubique.uetlib.storage.exception.RetryableStorageClientException;
import ch.ubique.uetlib.storage.exception.StorageClientException;
import ch.ubique.uetlib.storage.output.Output;
import ch.ubique.uetlib.storage.output.OutputWithPath;
import ch.ubique.uetlib.storage.utils.CopyUtils;

import com.pivovarit.function.ThrowingRunnable;

import jakarta.annotation.Nullable;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;

import java.io.InputStream;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Collection;

public class RetryStorageClient implements StorageClient {

    private static final RetryTemplate DEFAULT_RETRY_TEMPLATE =
            new RetryTemplate(
                    RetryPolicy.builder()
                            .includes(RetryableStorageClientException.class)
                            .maxRetries(10)
                            .delay(Duration.ofMillis(250))
                            .jitter(Duration.ofMillis(250))
                            .build());

    private final StorageClient storageClient;
    private final RetryTemplate retryTemplate;

    public RetryStorageClient(final StorageClient storageClient) {
        this(storageClient, null);
    }

    public RetryStorageClient(
            final StorageClient storageClient, @Nullable final RetryTemplate retryTemplate) {
        this.storageClient =
                requireNonNull(storageClient, "Storage client must not be null.").withoutRetry();
        this.retryTemplate = requireNonNullElse(retryTemplate, DEFAULT_RETRY_TEMPLATE);
    }

    StorageClient delegate() {
        return storageClient;
    }

    @Override
    public StorageClient withoutRetry() {
        return storageClient;
    }

    @Override
    public void copy(
            final String sourcePath, final String destinationPath, final CopyOptions options)
            throws StorageClientException {
        CopyUtils.checkPaths(sourcePath, destinationPath);
        storageClient.copy(sourcePath, destinationPath, options);
    }

    @Override
    public void write(
            final Output output, final String path, @Nullable final OutputMetaData outputMetaData)
            throws StorageClientException {
        if (output.canBeRetried()) {
            retryRunnable(() -> storageClient.write(output, path, outputMetaData));
        } else {
            storageClient.write(output, path, outputMetaData);
        }
    }

    @Override
    public void write(
            final Collection<OutputWithPath> outputs, @Nullable final OutputMetaData outputMetaData)
            throws StorageClientException {
        if (outputs.stream().map(OutputWithPath::output).allMatch(Output::canBeRetried)) {
            retryRunnable(() -> storageClient.write(outputs, outputMetaData));
        } else {
            storageClient.write(outputs, outputMetaData);
        }
    }

    @Override
    public InputStream read(final String path, final boolean doUncompressIfNeeded)
            throws StorageClientException {
        try {
            return retryTemplate.execute(() -> storageClient.read(path, doUncompressIfNeeded));
        } catch (final RetryException e) {
            throw createStorageClientException(e);
        }
    }

    @Override
    public boolean delete(final String path, final boolean recursive)
            throws StorageClientException {
        try {
            return retryTemplate.execute(() -> storageClient.delete(path, recursive));
        } catch (final RetryException e) {
            throw createStorageClientException(e);
        }
    }

    @Override
    public Collection<String> list(final String path, final boolean recursive)
            throws StorageClientException {
        try {
            return retryTemplate.execute(() -> storageClient.list(path, recursive));
        } catch (final RetryException e) {
            throw createStorageClientException(e);
        }
    }

    @Override
    public boolean exists(final String path) throws StorageClientException {
        try {
            return retryTemplate.execute(() -> storageClient.exists(path));
        } catch (final RetryException e) {
            throw createStorageClientException(e);
        }
    }

    @Override
    public String getSignedUrl(final String path, final OffsetDateTime expiry)
            throws StorageClientException {
        try {
            return retryTemplate.execute(() -> storageClient.getSignedUrl(path, expiry));
        } catch (final RetryException e) {
            throw createStorageClientException(e);
        }
    }

    @Override
    public String getSignedUploadUrl(final String path, final OffsetDateTime expiry)
            throws StorageClientException {
        try {
            return retryTemplate.execute(() -> storageClient.getSignedUploadUrl(path, expiry));
        } catch (final RetryException e) {
            throw createStorageClientException(e);
        }
    }

    @Override
    public String getUrl(final String path) throws StorageClientException {
        try {
            return retryTemplate.execute(() -> storageClient.getUrl(path));
        } catch (final RetryException e) {
            throw createStorageClientException(e);
        }
    }

    private void retryRunnable(final ThrowingRunnable<StorageClientException> runnable)
            throws StorageClientException {
        try {
            retryTemplate.execute(
                    () -> {
                        runnable.run();
                        return null;
                    });
        } catch (final RetryException e) {
            throw createStorageClientException(e);
        }
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this, ToStringStyle.SHORT_PREFIX_STYLE)
                .append("retryTemplate", retryTemplate)
                .append("storageClient", storageClient)
                .toString();
    }

    private static StorageClientException createStorageClientException(
            final RetryException retryException) {
        final var cause = requireNonNull(retryException.getCause());
        final var message = requireNonNullElse(cause.getMessage(), cause.toString());
        return StorageClientException.of(message, cause);
    }
}
