package ch.ubique.backend.storage.invocation;

import org.jspecify.annotations.Nullable;

import java.lang.reflect.Method;

public interface StorageMappingInvocationHandler {
    Object invoke(
            StorageMappingInvocationProperties storageMappingInvocationProperties,
            Method method,
            Object @Nullable [] args)
            throws Throwable;
}
