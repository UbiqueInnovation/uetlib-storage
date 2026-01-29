package ch.ubique.backend.storage.annotations;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import ch.ubique.backend.storage.invocation.DeleteMappingInvocationHandler;

import org.slf4j.event.Level;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Implementations of methods annotated by this function are generated at runtime using byte-buddy.
 * The implementation will call {@link DeleteMappingInvocationHandler#invoke}. You can debug this
 * method if there are any issues with this mapping.
 */
@Target(METHOD)
@Retention(RUNTIME)
@StorageMapping
public @interface DeleteMapping {

    /**
     * The mappingPath mapping URIs (e.g. {@code "file.json"}).
     *
     * <p>Cannot start with a `/` and startup will fail if it does.
     *
     * <p><strong>NOTE</strong>: A handler method that is not mapped to any mappingPath explicitly
     * is invalid.
     */
    String path() default "";

    /**
     * Useful for directories. If the directory is not empty, an exception will be thrown if
     * recursive is set to {@code false}. If recursive is set to {@code true} all files in the
     * directory will be deleted.
     */
    boolean recursive() default false;

    /**
     * Set to {@link CodeGenerationType#IMPLEMENTATION} or {@link CodeGenerationType#DOCUMENTATION}
     * to generate only implementation code or documentation code, respectively.
     */
    CodeGenerationType codeGenerationType() default CodeGenerationType.ALL;

    /**
     * Specify the log level as a single-element array. We use an array to have {@code {}} emulate
     * null. Passing an array with more than one element results in an exception at application
     * startup.
     */
    Level[] logLevel() default {};
}
