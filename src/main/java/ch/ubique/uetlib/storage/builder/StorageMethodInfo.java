package ch.ubique.uetlib.storage.builder;

import ch.ubique.uetlib.storage.StorageMappingType;
import ch.ubique.uetlib.storage.annotations.*;
import jakarta.validation.constraints.NotBlank;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

public record StorageMethodInfo(
        Method storageMethod,
        @NotBlank String mappingPath,
        StorageMappingType storageMappingType,
        Annotation storageMappingAnnotation) {

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
