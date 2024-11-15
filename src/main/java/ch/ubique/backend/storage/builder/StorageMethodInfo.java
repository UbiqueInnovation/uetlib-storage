package ch.ubique.backend.storage.builder;

import ch.ubique.backend.storage.StorageMappingType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.lang.reflect.Method;

public record StorageMethodInfo(
        @NotNull Method storageMethod,
        @NotBlank String mappingPath,
        @NotNull StorageMappingType storageMappingType) {}
