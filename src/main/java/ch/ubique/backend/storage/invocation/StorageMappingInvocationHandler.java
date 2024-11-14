package ch.ubique.backend.storage.invocation;

import java.lang.reflect.Method;

public interface StorageMappingInvocationHandler {
    Object invoke(
            StorageMappingInvocationProperties storageMappingInvocationProperties,
            Method method,
            Object[] args)
            throws Throwable;
}
