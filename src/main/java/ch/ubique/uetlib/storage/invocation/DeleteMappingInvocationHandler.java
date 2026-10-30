package ch.ubique.uetlib.storage.invocation;

import static ch.ubique.uetlib.storage.invocation.InvocationHandlerUtil.*;
import static ch.ubique.uetlib.storage.logger.LogAtNullableLevel.atLevel;

import ch.ubique.uetlib.core.utils.ArrayUtils;
import ch.ubique.uetlib.storage.annotations.DeleteMapping;
import java.lang.reflect.Method;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

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
