package ch.ubique.uetlib.storage.invocation;

import java.lang.reflect.Method;
import org.jspecify.annotations.Nullable;

public interface StorageMappingInvocationHandler {
    Object invoke(
            StorageMappingInvocationProperties storageMappingInvocationProperties,
            Method method,
            Object @Nullable [] args)
            throws Throwable;
}
