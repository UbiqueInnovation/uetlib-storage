package ch.ubique.uetlib.storage.builder;

import static ch.ubique.uetlib.core.utils.AnnotationUtils.findAnnotation;
import static java.util.stream.Collectors.toSet;

import ch.ubique.uetlib.storage.OutputMetaData;
import ch.ubique.uetlib.storage.StorageMappingType;
import ch.ubique.uetlib.storage.annotations.*;
import ch.ubique.uetlib.storage.invocation.*;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.text.MessageFormat;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jetbrains.annotations.Contract;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

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
                storageMethodsByAnnotation.getOrDefault(StorageMappingType.OUTPUT, List.of())) {
            invocationHandlersByMethodName.put(
                    storageMethod.storageMethod(), outputMappingInvocationHandler);
        }
        for (final var storageMethod :
                storageMethodsByAnnotation.getOrDefault(StorageMappingType.INPUT, List.of())) {
            invocationHandlersByMethodName.put(
                    storageMethod.storageMethod(), inputMappingInvocationHandler);
        }
        for (final var storageMethod :
                storageMethodsByAnnotation.getOrDefault(StorageMappingType.DELETE, List.of())) {
            invocationHandlersByMethodName.put(
                    storageMethod.storageMethod(), deleteMappingInvocationHandler);
        }
        for (final var storageMethod :
                storageMethodsByAnnotation.getOrDefault(StorageMappingType.LIST, List.of())) {
            invocationHandlersByMethodName.put(
                    storageMethod.storageMethod(), listMappingInvocationHandler);
        }
        for (final var storageMethod :
                storageMethodsByAnnotation.getOrDefault(StorageMappingType.EXISTS, List.of())) {
            invocationHandlersByMethodName.put(
                    storageMethod.storageMethod(), existsMappingInvocationHandler);
        }
        return invocationHandlersByMethodName;
    }

    private static StorageMethodInfo findStorageMethodInfo(final Method storageMethod) {
        final var outputMappingMaybe = findAnnotation(storageMethod, OutputMapping.class);
        if (outputMappingMaybe.isPresent()) {
            final var outputMapping = outputMappingMaybe.get();
            return new StorageMethodInfo(
                    storageMethod, outputMapping.path(), StorageMappingType.OUTPUT, outputMapping);
        }

        final var inputMappingMaybe = findAnnotation(storageMethod, InputMapping.class);
        if (inputMappingMaybe.isPresent()) {
            final var inputMapping = inputMappingMaybe.get();
            return new StorageMethodInfo(
                    storageMethod, inputMapping.path(), StorageMappingType.INPUT, inputMapping);
        }

        final var listMappingMaybe = findAnnotation(storageMethod, ListMapping.class);
        if (listMappingMaybe.isPresent()) {
            final var listMapping = listMappingMaybe.get();
            return new StorageMethodInfo(
                    storageMethod, listMapping.path(), StorageMappingType.LIST, listMapping);
        }

        final var existsMappingMaybe = findAnnotation(storageMethod, ExistsMapping.class);
        if (existsMappingMaybe.isPresent()) {
            final var existsMapping = existsMappingMaybe.get();
            return new StorageMethodInfo(
                    storageMethod, existsMapping.path(), StorageMappingType.EXISTS, existsMapping);
        }

        final var deleteMappingMaybe = findAnnotation(storageMethod, DeleteMapping.class);
        if (deleteMappingMaybe.isPresent()) {
            final var deleteMapping = deleteMappingMaybe.get();
            return new StorageMethodInfo(
                    storageMethod, deleteMapping.path(), StorageMappingType.DELETE, deleteMapping);
        }

        throw new IllegalArgumentException(
                MessageFormat.format(
                        "Storage method {0}::{1} is not valid: Method is (meta-)annotated with"
                                + " @{2}, but noy by with of {3}",
                        storageMethod.getDeclaringClass().getSimpleName(),
                        storageMethod.getName(),
                        StorageMapping.class.getSimpleName(),
                        List.of(
                                OutputMapping.class.getSimpleName(),
                                InputMapping.class.getSimpleName(),
                                ListMapping.class.getSimpleName(),
                                ExistsMapping.class.getSimpleName(),
                                DeleteMapping.class.getSimpleName())));
    }

    public static Map<StorageMappingType, List<StorageMethodInfo>> getStorageMethodsByAnnotation(
            final Class<?> storageClass) {
        return Arrays.stream(storageClass.getMethods())
                .map(
                        method -> {
                            final var maybeStorageMapping =
                                    findAnnotation(method, StorageMapping.class);
                            if (maybeStorageMapping.isEmpty()) {
                                final var validStorageMappingAnnotationTypes =
                                        Arrays.stream(StorageMappingType.values())
                                                .map(StorageMappingType::mappingAnnotationType)
                                                .toList();
                                throw new IllegalArgumentException(
                                        MessageFormat.format(
                                                "Storage interface {0} is not valid: Method"
                                                        + " ''{0}::{1}'' is not annotated by one of"
                                                        + " {2}",
                                                method.getDeclaringClass().getSimpleName(),
                                                method.getName(),
                                                validStorageMappingAnnotationTypes.stream()
                                                        .map(
                                                                a ->
                                                                        MessageFormat.format(
                                                                                "@{0}",
                                                                                a.getSimpleName()))
                                                        .toList()));
                            }
                            final var storageMethodInfo = findStorageMethodInfo(method);
                            validateMappingMethod(storageMethodInfo);
                            return storageMethodInfo;
                        })
                .collect(Collectors.groupingBy(StorageMethodInfo::storageMappingType));
    }

    private static void validateMappingMethod(final StorageMethodInfo storageMethodInfo) {
        final var mappingPath = storageMethodInfo.mappingPath();
        final var storageMethod = storageMethodInfo.storageMethod();
        final var storageMappingType = storageMethodInfo.storageMappingType();

        if (mappingPath.startsWith("/")) {
            throw new IllegalArgumentException(
                    MessageFormat.format(
                            "Storage method {0}::{1} is not valid: ''{2}.path()'' cannot start with"
                                    + " ''/'' Got path: ''{3}''",
                            storageMethod.getDeclaringClass().getSimpleName(),
                            storageMethod.getName(),
                            storageMappingType.mappingAnnotationType().getSimpleName(),
                            mappingPath));
        }

        for (final var parameter : storageMethod.getParameters()) {
            final var parameterType = parameter.getType();
            final var parameterName = parameter.getName();

            final var parameterAnnotations = parameter.getAnnotations();
            final var parameterAnnotationTypes =
                    Arrays.stream(parameterAnnotations).map(Annotation::annotationType).toList();

            final var validParameterTypes = List.of(OutputMetaData.class, ObjectMapper.class);
            final var validParameterAnnotationTypes =
                    List.of(StorageObject.class, StoragePathVariable.class);

            final var validParameterTypeNames =
                    validParameterTypes.stream().map(Class::getSimpleName).toList();
            final var validParameterAnnotationTypeNames =
                    validParameterAnnotationTypes.stream()
                            .map(a -> MessageFormat.format("@{0}", a.getSimpleName()))
                            .toList();

            if (parameterAnnotations.length == 0 && !validParameterTypes.contains(parameterType)) {
                throwMethodParameterNotValid(
                        storageMethod.getDeclaringClass().getSimpleName(),
                        storageMethod.getName(),
                        parameterName,
                        validParameterTypeNames,
                        validParameterAnnotationTypeNames);
            }

            if (parameterAnnotations.length > 0) {
                // All jakarta annotations are also accepted
                final var parameterAnnotationTypesExcludeJakarta =
                        parameterAnnotationTypes.stream()
                                .filter(
                                        annotation ->
                                                !annotation.getPackageName().startsWith("jakarta"))
                                .collect(toSet());
                if (!validParameterAnnotationTypes.containsAll(
                        parameterAnnotationTypesExcludeJakarta)) {
                    throwMethodParameterNotValid(
                            storageMethod.getDeclaringClass().getSimpleName(),
                            storageMethod.getName(),
                            parameterName,
                            validParameterTypeNames,
                            validParameterAnnotationTypeNames);
                }
            }

            final var outputMetaDataParametersCount =
                    countParametersWithType(
                            storageMethodInfo.storageMethod(), OutputMetaData.class);
            if (outputMetaDataParametersCount > 1) {
                throw new IllegalArgumentException(
                        MessageFormat.format(
                                "Storage method {0}::{1} is not valid: There must be at most one"
                                        + " parameter of type @{2}, found {3}",
                                storageMethodInfo
                                        .storageMethod()
                                        .getDeclaringClass()
                                        .getSimpleName(),
                                storageMethodInfo.storageMethod().getName(),
                                OutputMetaData.class,
                                outputMetaDataParametersCount));
            }

            final var objectMapperParametersCount =
                    countParametersWithType(storageMethodInfo.storageMethod(), ObjectMapper.class);
            if (objectMapperParametersCount > 1) {
                throw new IllegalArgumentException(
                        MessageFormat.format(
                                "Storage method {0}::{1} is not valid: There must be at most one"
                                        + " parameter of type @{2}, found {3}",
                                storageMethodInfo
                                        .storageMethod()
                                        .getDeclaringClass()
                                        .getSimpleName(),
                                storageMethodInfo.storageMethod().getName(),
                                ObjectMapper.class,
                                objectMapperParametersCount));
            }
        }

        switch (storageMappingType) {
            case OUTPUT -> validateOutputMappingMethod(storageMethodInfo);
            case INPUT -> validateInputMappingMethod(storageMethodInfo);
            case DELETE -> validateDeleteMappingMethod(storageMethodInfo);
            case LIST -> validateListMappingMethod(storageMethodInfo);
            case EXISTS -> validateExistsMappingMethod(storageMethodInfo);
        }
    }

    @Contract("_, _, _, _, _ -> fail")
    private static void throwMethodParameterNotValid(
            final String storageClassName,
            final String storageMethodName,
            final String parameterName,
            final List<String> validParameterTypeNames,
            final List<String> validParameterAnnotationTypeNames) {
        throw new IllegalArgumentException(
                MessageFormat.format(
                        "Storage method {0}::{1} is not valid: Method parameter ''{2}'' must either"
                                + " be one of type ''{3}'' or be annotated with one of {4}",
                        storageClassName,
                        storageMethodName,
                        parameterName,
                        validParameterTypeNames,
                        validParameterAnnotationTypeNames));
    }

    private static void validateOutputMappingMethod(final StorageMethodInfo storageMethodInfo) {
        final var storageObjectParametersCount =
                countParametersWithAnnotation(
                        storageMethodInfo.storageMethod(), StorageObject.class);
        if (storageObjectParametersCount != 1) {
            throw new IllegalArgumentException(
                    MessageFormat.format(
                            "Storage OutputMapping method {0}::{1} is not valid: There must be"
                                    + " exactly one parameter annotated with @{2}, found {3}",
                            storageMethodInfo.storageMethod().getDeclaringClass().getSimpleName(),
                            storageMethodInfo.storageMethod().getName(),
                            StorageObject.class.getSimpleName(),
                            storageObjectParametersCount));
        }

        if (storageMethodInfo.storageMethod().getReturnType() != void.class) {
            throw new IllegalArgumentException(
                    MessageFormat.format(
                            "Storage OutputMapping method {0}::{1} is not valid: @OutputMapping"
                                    + " mapping method must return void",
                            storageMethodInfo.storageMethod().getDeclaringClass().getSimpleName(),
                            storageMethodInfo.storageMethod().getName()));
        }
    }

    private static void validateInputMappingMethod(final StorageMethodInfo storageMethodInfo) {
        final var invalidReturnTypes = List.of(void.class);
        if (invalidReturnTypes.contains(storageMethodInfo.storageMethod().getReturnType())) {
            throw new IllegalArgumentException(
                    MessageFormat.format(
                            "Storage InputMapping method {0}::{1} is not valid: @InputMapping"
                                    + " mapping method return type must not be one of {2}",
                            storageMethodInfo.storageMethod().getDeclaringClass().getSimpleName(),
                            storageMethodInfo.storageMethod().getName(),
                            invalidReturnTypes.stream().map(Class::getSimpleName).toList()));
        }
    }

    private static void validateDeleteMappingMethod(final StorageMethodInfo storageMethodInfo) {
        final var validReturnTypes = List.of(void.class, boolean.class);
        if (!validReturnTypes.contains(storageMethodInfo.storageMethod().getReturnType())) {
            throw new IllegalArgumentException(
                    MessageFormat.format(
                            "Storage DeleteMapping method {0}::{1} is not valid: @DeleteMapping"
                                    + " mapping method return type must be one of {2}",
                            storageMethodInfo.storageMethod().getDeclaringClass().getSimpleName(),
                            storageMethodInfo.storageMethod().getName(),
                            validReturnTypes.stream().map(Class::getSimpleName).toList()));
        }
    }

    private static void validateListMappingMethod(final StorageMethodInfo storageMethodInfo) {
        final var validReturnTypes = List.of(Collection.class, List.class, Set.class, Stream.class);
        if (!validReturnTypes.contains(storageMethodInfo.storageMethod().getReturnType())) {
            throw new IllegalArgumentException(
                    MessageFormat.format(
                            "Storage ListMapping method {0}::{1} is not valid: @ListMapping mapping"
                                    + " method return type must be one of {2}",
                            storageMethodInfo.storageMethod().getDeclaringClass().getSimpleName(),
                            storageMethodInfo.storageMethod().getName(),
                            validReturnTypes.stream().map(Class::getSimpleName).toList()));
        }
    }

    private static void validateExistsMappingMethod(final StorageMethodInfo storageMethodInfo) {
        final var validReturnTypes = List.of(boolean.class);
        if (!validReturnTypes.contains(storageMethodInfo.storageMethod().getReturnType())) {
            throw new IllegalArgumentException(
                    MessageFormat.format(
                            "Storage ExistsMapping method {0}::{1} is not valid: @ExistsMapping"
                                    + " mapping method return type must be one of {2}",
                            storageMethodInfo.storageMethod().getDeclaringClass().getSimpleName(),
                            storageMethodInfo.storageMethod().getName(),
                            validReturnTypes.stream().map(Class::getSimpleName).toList()));
        }
    }

    @SuppressWarnings("SameParameterValue")
    private static long countParametersWithAnnotation(
            final Method method, final Class<? extends Annotation> annotationType) {
        return Arrays.stream(method.getParameters())
                .filter(parameter -> parameter.isAnnotationPresent(annotationType))
                .count();
    }

    private static long countParametersWithType(final Method method, final Class<?> parameterType) {
        return Arrays.stream(method.getParameters())
                .filter(parameter -> parameter.getType().equals(parameterType))
                .count();
    }
}
