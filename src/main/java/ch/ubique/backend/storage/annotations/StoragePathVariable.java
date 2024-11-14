package ch.ubique.backend.storage.annotations;

import java.lang.annotation.*;

@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface StoragePathVariable {

    /** The name of the mappingPath variable to bind to. */
    String name() default "";
}
