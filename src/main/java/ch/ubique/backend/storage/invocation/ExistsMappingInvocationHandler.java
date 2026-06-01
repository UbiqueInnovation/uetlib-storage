package ch.ubique.backend.storage.invocation;

import static ch.ubique.backend.storage.invocation.InvocationHandlerUtil.*;
import static ch.ubique.backend.storage.logger.LogAtNullableLevel.atLevel;

import ch.ubique.backend.core.utils.ArrayUtils;
import ch.ubique.backend.storage.annotations.ExistsMapping;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import java.lang.reflect.Method;

public class ExistsMappingInvocationHandler implements StorageMappingInvocationHandler {

    private final Logger logger;

    public ExistsMappingInvocationHandler(final Logger logger) {
        this.logger = logger;
    }

    @Override
    public Object invoke(
            final StorageMappingInvocationProperties storageMappingInvocationProperties,
            final Method method,
            final Object @Nullable [] args)
            throws Throwable {
        final var storageClient = storageMappingInvocationProperties.storageClient();
        final var existsMapping = findAnnotation(method, ExistsMapping.class);
        final var level = ArrayUtils.singletonOrNull(existsMapping.logLevel());

        final var basePath = storageMappingInvocationProperties.basePath();
        final var path =
                getPath(
                        method.getParameters(),
                        args,
                        getPathTemplate(basePath, existsMapping.path()));

        atLevel(logger, level).log("Checking if path {} exists", path);
        return storageClient.exists(path);
    }
}
