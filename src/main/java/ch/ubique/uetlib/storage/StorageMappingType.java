package ch.ubique.uetlib.storage;

import ch.ubique.uetlib.storage.annotations.*;

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

    public abstract Class<? extends Annotation> mappingAnnotationType();
}
