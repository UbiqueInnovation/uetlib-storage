package ch.uetlib.storage.builder;

import ch.uetlib.storage.StorageMappingType;
import ch.uetlib.storage.annotations.*;

import jakarta.validation.constraints.NotBlank;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

public record StorageMethodInfo(
        Method storageMethod,
        @NotBlank String mappingPath,
        StorageMappingType storageMappingType,
        Annotation storageMappingAnnotation) {

    private <T extends Annotation> T castStorageMappingAnnotation(final Class<T> type) {
        return type.cast(storageMappingAnnotation);
    }

    /**
     * Returns the {@link OutputMapping} annotation of the method if the {@link
     * #storageMappingType()} equals {@link OutputMapping}.
     *
     * @throws ClassCastException if the {@link #storageMappingType()} is not {@link OutputMapping}.
     */
    public OutputMapping outputMappingAnnotation() {
        return castStorageMappingAnnotation(OutputMapping.class);
    }

    /**
     * Returns the {@link InputMapping} annotation of the method if the {@link
     * #storageMappingType()} equals {@link InputMapping}.
     *
     * @throws ClassCastException if the {@link #storageMappingType()} is not {@link InputMapping}.
     */
    public InputMapping inputMappingAnnotation() {
        return castStorageMappingAnnotation(InputMapping.class);
    }

    /**
     * Returns the {@link ListMapping} annotation of the method if the {@link #storageMappingType()}
     * equals {@link ListMapping}.
     *
     * @throws ClassCastException if the {@link #storageMappingType()} is not {@link ListMapping}.
     */
    public ListMapping listMappingAnnotation() {
        return castStorageMappingAnnotation(ListMapping.class);
    }

    /**
     * Returns the {@link DeleteMapping} annotation of the method if the {@link
     * #storageMappingType()} equals {@link DeleteMapping}.
     *
     * @throws ClassCastException if the {@link #storageMappingType()} is not {@link DeleteMapping}.
     */
    public DeleteMapping deleteMappingAnnotation() {
        return castStorageMappingAnnotation(DeleteMapping.class);
    }

    /**
     * Returns the {@link ExistsMapping} annotation of the method if the {@link
     * #storageMappingType()} equals {@link ExistsMapping}.
     *
     * @throws ClassCastException if the {@link #storageMappingType()} is not {@link ExistsMapping}.
     */
    public ExistsMapping existsMappingAnnotation() {
        return castStorageMappingAnnotation(ExistsMapping.class);
    }
}
