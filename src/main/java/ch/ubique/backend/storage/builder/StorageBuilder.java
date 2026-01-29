package ch.ubique.backend.storage.builder;

import static ch.ubique.backend.storage.builder.StorageBuilderUtil.getStorageMethodInvocationByMethodName;

import ch.ubique.backend.storage.StorageClient;
import ch.ubique.backend.storage.invocation.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Proxy;

public final class StorageBuilder<T> {

    private static final Logger logger = LoggerFactory.getLogger(StorageBuilder.class);

    private final Class<T> type;

    private ClassLoader classLoader;
    private StorageClient storageClient;
    private ObjectMapper objectMapper;
    private String basePath;
    private String documentationBaseUrl;

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
                        storageClient, objectMapper, basePath, documentationBaseUrl);

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
