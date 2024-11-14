package ch.ubique.backend.storage.builder;

import static ch.ubique.backend.core.utils.AnnotationUtils.findAnnotation;

import ch.ubique.backend.storage.annotations.*;
import ch.ubique.backend.storage.invocation.*;

import org.slf4j.LoggerFactory;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.text.MessageFormat;
import java.util.*;
import java.util.stream.Collectors;

public final class StorageBuilderUtil {

    private StorageBuilderUtil() {}

    static Map<Method, StorageMappingInvocationHandler> getStorageMethodInvocationByMethodName(
            final Class<?> storageClass) {

        final var storageMethodsByAnnotation = getStorageMethodsByAnnotation(storageClass);

        final var storageClassLogger = LoggerFactory.getLogger(storageClass);

        final var outputMappingInvocationHandler =
                new OutputMappingInvocationHandler(storageClassLogger);
        final var inputMappingInvocationHandler =
                new InputMappingInvocationHandler(storageClassLogger);
        final var deleteMappingInvocationHandler =
                new DeleteMappingInvocationHandler(storageClassLogger);
        final var listMappingInvocationHandler =
                new ListMappingInvocationHandler(storageClassLogger);
        final var existsMappingInvocationHandler =
                new ExistsMappingInvocationHandler(storageClassLogger);

        final var invocationHandlersByMethodName =
                new HashMap<Method, StorageMappingInvocationHandler>();
        for (final var storageMethod :
                storageMethodsByAnnotation.getOrDefault(OutputMapping.class, List.of())) {
            invocationHandlersByMethodName.put(
                    storageMethod.storageMethod(), outputMappingInvocationHandler);
        }
        for (final var storageMethod :
                storageMethodsByAnnotation.getOrDefault(InputMapping.class, List.of())) {
            invocationHandlersByMethodName.put(
                    storageMethod.storageMethod(), inputMappingInvocationHandler);
        }
        for (final var storageMethod :
                storageMethodsByAnnotation.getOrDefault(DeleteMapping.class, List.of())) {
            invocationHandlersByMethodName.put(
                    storageMethod.storageMethod(), deleteMappingInvocationHandler);
        }
        for (final var storageMethod :
                storageMethodsByAnnotation.getOrDefault(ListMapping.class, List.of())) {
            invocationHandlersByMethodName.put(
                    storageMethod.storageMethod(), listMappingInvocationHandler);
        }
        for (final var storageMethod :
                storageMethodsByAnnotation.getOrDefault(ExistsMapping.class, List.of())) {
            invocationHandlersByMethodName.put(
                    storageMethod.storageMethod(), existsMappingInvocationHandler);
        }
        return invocationHandlersByMethodName;
    }

    private static StorageMethodInfo findStorageMethodInfo(final Method method) {
        final var outputMapping = findAnnotation(method, OutputMapping.class);
        if (outputMapping.isPresent()) {
            return new StorageMethodInfo(method, outputMapping.get().path(), OutputMapping.class);
        }

        final var inputMapping = findAnnotation(method, InputMapping.class);
        if (inputMapping.isPresent()) {
            return new StorageMethodInfo(method, inputMapping.get().path(), InputMapping.class);
        }

        final var listMapping = findAnnotation(method, ListMapping.class);
        if (listMapping.isPresent()) {
            return new StorageMethodInfo(method, listMapping.get().path(), ListMapping.class);
        }

        final var existsMapping = findAnnotation(method, ExistsMapping.class);
        if (existsMapping.isPresent()) {
            return new StorageMethodInfo(method, existsMapping.get().path(), ExistsMapping.class);
        }

        final var deleteMapping = findAnnotation(method, DeleteMapping.class);
        //noinspection OptionalIsPresent
        if (deleteMapping.isPresent()) {
            return new StorageMethodInfo(method, deleteMapping.get().path(), DeleteMapping.class);
        }

        return null;
    }

    public static Map<Class<? extends Annotation>, List<StorageMethodInfo>>
            getStorageMethodsByAnnotation(final Class<?> storageClass) {
        return Arrays.stream(storageClass.getMethods())
                // only those meta-annotated by @StorageMapping
                .filter(method -> findAnnotation(method, StorageMapping.class).isPresent())
                .map(
                        method -> {
                            final var storageMethodInfo = findStorageMethodInfo(method);

                            if (storageMethodInfo == null) {
                                return null;
                            }

                            final var mappingPath = storageMethodInfo.mappingPath();
                            if (!mappingPath.startsWith("/")) {
                                return storageMethodInfo;
                            }

                            final var storageMethod = storageMethodInfo.storageMethod();
                            throw new IllegalArgumentException(
                                    MessageFormat.format(
                                            "''{0}.mappingPath()'' cannot start with ''/'' for method {1}::{2}. Got mappingPath: ''{3}''",
                                            storageMethodInfo
                                                    .mappingAnnotationType()
                                                    .getSimpleName(),
                                            storageMethod.getDeclaringClass().getSimpleName(),
                                            storageMethod.getName(),
                                            mappingPath));
                        })
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(StorageMethodInfo::mappingAnnotationType));
    }
}
