package ch.ubique.backend.storage;

import ch.ubique.backend.storage.annotations.*;

import jakarta.validation.constraints.NotNull;

import java.lang.annotation.Annotation;

public enum StorageMappingType {
    OUTPUT {
        @Override
        public Class<? extends Annotation> mappingAnnotationType() {
            return OutputMapping.class;
        }
    },

    INPUT {
        @Override
        public Class<? extends Annotation> mappingAnnotationType() {
            return InputMapping.class;
        }
    },

    DELETE {
        @Override
        public Class<? extends Annotation> mappingAnnotationType() {
            return DeleteMapping.class;
        }
    },

    LIST {
        @Override
        public Class<? extends Annotation> mappingAnnotationType() {
            return ListMapping.class;
        }
    },

    EXISTS {
        @Override
        public Class<? extends Annotation> mappingAnnotationType() {
            return ExistsMapping.class;
        }
    };

    public abstract @NotNull Class<? extends Annotation> mappingAnnotationType();
}
