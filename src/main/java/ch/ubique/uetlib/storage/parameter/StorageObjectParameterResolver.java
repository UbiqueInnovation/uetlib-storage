package ch.ubique.uetlib.storage.parameter;

import ch.ubique.uetlib.storage.annotations.StorageObject;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.stream.IntStream;

public final class StorageObjectParameterResolver {

    private StorageObjectParameterResolver() {}

    public static int getStorageObjectParameterIndex(final Method method) {
        final var parameterAnnotations = method.getParameterAnnotations();
        return IntStream.range(0, parameterAnnotations.length)
                .filter(
                        i ->
                                Arrays.stream(parameterAnnotations[i])
                                        .anyMatch(StorageObject.class::isInstance))
                .findFirst()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "OutputMapping requires one argument to be"
                                                + " annotated with '@StorageObject'"));
    }
}
