package ch.ubique.uetlib.storage.builder;

import static ch.ubique.uetlib.storage.builder.StorageBuilderUtil.getStorageMethodInvocationByMethodName;

import ch.ubique.uetlib.storage.StorageClient;
import ch.ubique.uetlib.storage.invocation.*;
import java.lang.reflect.Proxy;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;

public final class StorageBuilder<T> {

    private final Class<T> type;

    private @Nullable ClassLoader classLoader;
    private @Nullable StorageClient storageClient;
    private @Nullable ObjectMapper objectMapper;
    private @Nullable String basePath;
    private @Nullable String documentationBaseUrl;

    private StorageBuilder(final Class<T> type) {
        this.type = type;
    }

    public static <T> StorageBuilder<T> builder(final Class<T> type) {
        return new StorageBuilder<>(type);
    }

    public StorageBuilder<T> classLoader(final ClassLoader classLoader) {
        this.classLoader = classLoader;
        return this;
    }

    public StorageBuilder<T> storageClient(final StorageClient storageClient) {
        this.storageClient = storageClient;
        return this;
    }

    public StorageBuilder<T> objectMapper(final ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        return this;
    }

    public StorageBuilder<T> basePath(final String basePath) {
        this.basePath = basePath;
        return this;
    }

    public StorageBuilder<T> documentationBaseUrl(final String documentationBaseUrl) {
        this.documentationBaseUrl = documentationBaseUrl;
        return this;
    }

    public T build() {
        final var storageInvocationProperties =
                new StorageMappingInvocationProperties(
                        Objects.requireNonNull(storageClient),
                        Objects.requireNonNull(objectMapper),
                        basePath,
                        documentationBaseUrl);

        final var storageMethodInvocationByMethodName =
                getStorageMethodInvocationByMethodName(type);

        //noinspection unchecked
        return (T)
                Proxy.newProxyInstance(
                        classLoader,
                        new Class[] {type},
                        new StorageInvocationHandler(
                                storageMethodInvocationByMethodName, storageInvocationProperties));
    }
}
