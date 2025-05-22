package ch.ubique.backend.storage.builder;

import ch.ubique.backend.storage.StorageMappingType;
import ch.ubique.backend.storage.annotations.*;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

public record StorageMethodInfo(
        @NotNull Method storageMethod,
        @NotBlank String mappingPath,
        @NotNull StorageMappingType storageMappingType,
        @NotNull Annotation storageMappingAnnotation) {

    private <T extends Annotation> T castStorageMappingAnnotation() {
        //noinspection unchecked
        return (T) storageMappingAnnotation;
    }

    /**
     * Returns the {@link OutputMapping} annotation of the method if the {@link
     * #storageMappingType()} equals {@link OutputMapping}.
     *
     * @throws ClassCastException if the {@link #storageMappingType()} is not {@link OutputMapping}.
     */
    public OutputMapping outputMappingAnnotation() {
        return castStorageMappingAnnotation();
    }

    /**
     * Returns the {@link InputMapping} annotation of the method if the {@link
     * #storageMappingType()} equals {@link InputMapping}.
     *
     * @throws ClassCastException if the {@link #storageMappingType()} is not {@link InputMapping}.
     */
    public InputMapping inputMappingAnnotation() {
        return castStorageMappingAnnotation();
    }

    /**
     * Returns the {@link ListMapping} annotation of the method if the {@link #storageMappingType()}
     * equals {@link ListMapping}.
     *
     * @throws ClassCastException if the {@link #storageMappingType()} is not {@link ListMapping}.
     */
    public ListMapping listMappingAnnotation() {
        return castStorageMappingAnnotation();
    }

    /**
     * Returns the {@link DeleteMapping} annotation of the method if the {@link
     * #storageMappingType()} equals {@link DeleteMapping}.
     *
     * @throws ClassCastException if the {@link #storageMappingType()} is not {@link DeleteMapping}.
     */
    public DeleteMapping deleteMappingAnnotation() {
        return castStorageMappingAnnotation();
    }

    /**
     * Returns the {@link ExistsMapping} annotation of the method if the {@link
     * #storageMappingType()} equals {@link ExistsMapping}.
     *
     * @throws ClassCastException if the {@link #storageMappingType()} is not {@link ExistsMapping}.
     */
    public ExistsMapping existsMappingAnnotation() {
        return castStorageMappingAnnotation();
    }
}
