package ch.uetlib.storage.invocation;

import static ch.uetlib.storage.invocation.InvocationHandlerUtil.*;
import static ch.uetlib.storage.logger.LogAtNullableLevel.atLevel;

import ch.uetlib.core.utils.ArrayUtils;
import ch.uetlib.storage.annotations.DeleteMapping;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import java.lang.reflect.Method;

public class DeleteMappingInvocationHandler implements StorageMappingInvocationHandler {

    private final Logger logger;

    public DeleteMappingInvocationHandler(final Logger logger) {
        this.logger = logger;
    }

    @Override
    public Object invoke(
            final StorageMappingInvocationProperties storageMappingInvocationProperties,
            final Method method,
            final Object @Nullable [] args)
            throws Throwable {
        final var storageClient = storageMappingInvocationProperties.storageClient();
        final var deleteMapping = findAnnotation(method, DeleteMapping.class);
        final var level = ArrayUtils.singletonOrNull(deleteMapping.logLevel());

        final var basePath = storageMappingInvocationProperties.basePath();
        final var path =
                getPath(
                        method.getParameters(),
                        args,
                        getPathTemplate(basePath, deleteMapping.path()));

        atLevel(logger, level)
                .log(
                        "deleting content from path {} with recursive={}",
                        path,
                        deleteMapping.recursive());
        return storageClient.delete(path, deleteMapping.recursive());
    }
}
