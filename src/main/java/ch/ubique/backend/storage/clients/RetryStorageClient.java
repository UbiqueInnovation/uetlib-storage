package ch.ubique.backend.storage.clients;

import static java.util.Objects.*;

import ch.ubique.backend.storage.OutputMetaData;
import ch.ubique.backend.storage.StorageClient;
import ch.ubique.backend.storage.output.Output;
import ch.ubique.backend.storage.output.OutputWithPath;

import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.concurrent.ThreadLocalRandom;

public class RetryStorageClient implements StorageClient {

    private static final Retry DEFAULT_RETRY =
            Retry.of(
                    "storage-retry",
                    RetryConfig.custom()
                            .retryExceptions(Exception.class)
                            .maxAttempts(10)
                            .intervalFunction(
                                    attempt -> ThreadLocalRandom.current().nextLong(1, 500))
                            .build());

    private final StorageClient storageClient;
    private final Retry retry;

    public RetryStorageClient(final StorageClient storageClient) {
        this(storageClient, null);
    }

    public RetryStorageClient(
            @NotNull final StorageClient storageClient, @Nullable final Retry retry) {
        this.storageClient = requireNonNull(storageClient, "Storage client must not be null.");
        this.retry = requireNonNullElse(retry, DEFAULT_RETRY);
    }

    @Override
    public StorageClient retryable(final Retry retry) {
        // Unwrap and wrap again with new retry template
        return withoutRetry().retryable(retry);
    }

    @Override
    public StorageClient withoutRetry() {
        return storageClient.withoutRetry();
    }

    @Override
    public void write(
            final @NotNull Output output,
            final @NotNull String path,
            @Nullable final OutputMetaData outputMetaData)
            throws IOException {
        if (!output.canBeRetried()) {
            storageClient.write(output, path, outputMetaData);
            return;
        }

        final var write =
                Retry.decorateCheckedRunnable(
                        retry, () -> storageClient.write(output, path, outputMetaData));
        write.unchecked().run();
    }

    @Override
    public void write(
            final Collection<OutputWithPath> outputs, @Nullable final OutputMetaData outputMetaData)
            throws IOException {
        if (!outputs.stream().map(OutputWithPath::output).allMatch(Output::canBeRetried)) {
            storageClient.write(outputs, outputMetaData);
            return;
        }

        final var write =
                Retry.decorateCheckedRunnable(
                        retry, () -> storageClient.write(outputs, outputMetaData));
        write.unchecked().run();
    }

    @Override
    public InputStream read(final @NotNull String path, final boolean doUncompressIfNeeded)
            throws IOException {
        final var read =
                Retry.decorateCheckedSupplier(
                        retry, () -> storageClient.read(path, doUncompressIfNeeded));
        return read.unchecked().get();
    }

    @Override
    public boolean delete(final @NotNull String path, final boolean recursive) throws IOException {
        final var delete =
                Retry.decorateCheckedSupplier(retry, () -> storageClient.delete(path, recursive));
        return delete.unchecked().get();
    }

    @Override
    public Collection<String> list(final @NotNull String path, final boolean recursive)
            throws IOException {
        final var list =
                Retry.decorateCheckedSupplier(retry, () -> storageClient.list(path, recursive));
        return list.unchecked().get();
    }

    @Override
    public boolean exists(@NotNull final String path) throws IOException {
        final var exists = Retry.decorateCheckedSupplier(retry, () -> storageClient.exists(path));
        return exists.unchecked().get();
    }

    @Override
    public String getSignedUrl(final @NotNull String path, final @NotNull OffsetDateTime expiry)
            throws IOException {
        final var getSignedUrl =
                Retry.decorateCheckedSupplier(
                        retry, () -> storageClient.getSignedUrl(path, expiry));
        return getSignedUrl.unchecked().get();
    }

    @Override
    public String getUrl(final @NotNull String path) {
        final var getUrl = Retry.decorateCheckedSupplier(retry, () -> storageClient.getUrl(path));
        return getUrl.unchecked().get();
    }
}
