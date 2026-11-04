package ch.uetlib.storage.annotations;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.*;

/**
 * This is just a meta annotation. The annotations to use in a {@link Storage} class are in the same
 * package. These are:
 *
 * <ul>
 *   <li>{@link InputMapping}
 *   <li>{@link OutputMapping}
 *   <li>{@link DeleteMapping}
 *   <li>{@link ExistsMapping}
 *   <li>{@link ListMapping}
 * </ul>
 */
@Target(ANNOTATION_TYPE)
@Retention(RUNTIME)
@Documented
@Inherited
public @interface StorageMapping {}
