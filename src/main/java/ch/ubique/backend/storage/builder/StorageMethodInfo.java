package ch.ubique.backend.storage.builder;

import jakarta.validation.constraints.NotNull;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

public record StorageMethodInfo(
        @NotNull Method storageMethod,
        @NotNull String mappingPath,
        @NotNull Class<? extends Annotation> mappingAnnotationType) {}
