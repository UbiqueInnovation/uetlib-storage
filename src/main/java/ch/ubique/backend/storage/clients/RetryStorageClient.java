package ch.ubique.backend.storage.clients;

import static java.util.Objects.*;

import ch.ubique.backend.storage.OutputMetaData;
import ch.ubique.backend.storage.StorageClient;
import ch.ubique.backend.storage.exception.NonRetryableStorageClientException;
import ch.ubique.backend.storage.exception.RetryableStorageClientException;
import ch.ubique.backend.storage.exception.StorageClientException;
import ch.ubique.backend.storage.output.Output;
import ch.ubique.backend.storage.output.OutputWithPath;

import com.pivovarit.function.ThrowingRunnable;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotNull;

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
            @NotNull final StorageClient storageClient,
            @Nullable final RetryTemplate retryTemplate) {
        this.storageClient =
                requireNonNull(storageClient, "Storage client must not be null.").withoutRetry();
        this.retryTemplate = requireNonNullElse(retryTemplate, DEFAULT_RETRY_TEMPLATE);
    }

    @Override
    public StorageClient withoutRetry() {
        return storageClient;
    }

    @Override
    public void write(
            final @NotNull Output output,
            final @NotNull String path,
            @Nullable final OutputMetaData outputMetaData)
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
    public InputStream read(final @NotNull String path, final boolean doUncompressIfNeeded)
            throws StorageClientException {
        try {
            return retryTemplate.execute(() -> storageClient.read(path, doUncompressIfNeeded));
        } catch (final RetryException e) {
            throw new NonRetryableStorageClientException(e.getMessage(), e.getCause());
        }
    }

    @Override
    public boolean delete(final @NotNull String path, final boolean recursive)
            throws StorageClientException {
        try {
            return retryTemplate.execute(() -> storageClient.delete(path, recursive));
        } catch (final RetryException e) {
            throw new NonRetryableStorageClientException(e.getMessage(), e.getCause());
        }
    }

    @Override
    public Collection<String> list(final @NotNull String path, final boolean recursive)
            throws StorageClientException {
        try {
            return retryTemplate.execute(() -> storageClient.list(path, recursive));
        } catch (final RetryException e) {
            throw new NonRetryableStorageClientException(e.getMessage(), e.getCause());
        }
    }

    @Override
    public boolean exists(@NotNull final String path) throws StorageClientException {
        try {
            return retryTemplate.execute(() -> storageClient.exists(path));
        } catch (final RetryException e) {
            throw new NonRetryableStorageClientException(e.getMessage(), e.getCause());
        }
    }

    @Override
    public String getSignedUrl(final @NotNull String path, final @NotNull OffsetDateTime expiry)
            throws StorageClientException {
        try {
            return retryTemplate.execute(() -> storageClient.getSignedUrl(path, expiry));
        } catch (final RetryException e) {
            throw new NonRetryableStorageClientException(e.getMessage(), e.getCause());
        }
    }

    @Override
    public String getUrl(final @NotNull String path) throws StorageClientException {
        try {
            return retryTemplate.execute(() -> storageClient.getUrl(path));
        } catch (final RetryException e) {
            throw new NonRetryableStorageClientException(e.getMessage(), e.getCause());
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
            throw new NonRetryableStorageClientException(e.getMessage(), e.getCause());
        }
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this, ToStringStyle.SHORT_PREFIX_STYLE)
                .append("storageClient", storageClient)
                .toString();
    }
}
