package ch.ubique.uetlib.storage.invocation;

import ch.ubique.uetlib.core.utils.AnnotationUtils;
import ch.ubique.uetlib.core.utils.StreamUtils;
import ch.ubique.uetlib.storage.annotations.StoragePathVariable;
import com.google.common.collect.MoreCollectors;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Objects;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;

public final class InvocationHandlerUtil {

    private InvocationHandlerUtil() {}

    static ObjectMapper getObjectMapper(
            final ObjectMapper fallbackObjectMapper, final Object @Nullable [] args) {
        return StreamUtils.emptyIfNull(args)
                // objectMapper can be passed as an argument
                .filter(ObjectMapper.class::isInstance)
                .map(ObjectMapper.class::cast)
                .collect(MoreCollectors.toOptional())
                // or else get the objectMapper from the class
                .orElse(fallbackObjectMapper);
    }

    public static String getPath(
            final Parameter[] parameters,
            final Object @Nullable [] allArguments,
            final String pathTemplate) {
        var replacedPath = pathTemplate;
        for (var i = 0; i < parameters.length; i++) {
            Objects.requireNonNull(allArguments);
            final var parameter = parameters[i];
            final var pathVariableAnnotation =
                    Optional.ofNullable(parameter.getAnnotation(StoragePathVariable.class));
            if (pathVariableAnnotation.isEmpty()) {
                continue;
            }

            final var parameterName = parameter.getName();
            final var pathVariableName =
                    pathVariableAnnotation
                            .map(StoragePathVariable::name)
                            .filter(StringUtils::isNotBlank)
                            .orElse(parameterName);
            final var pathVariableReplaceNameString = "{" + pathVariableName + "}";
            final var parameterValue =
                    parameter.getType().isPrimitive()
                            ? String.valueOf(allArguments[i])
                            : allArguments[i].toString();
            replacedPath = replacedPath.replace(pathVariableReplaceNameString, parameterValue);
        }
        return replacedPath;
    }

    public static String getPathTemplate(final @Nullable String basePath, final String path) {
        return StringUtils.isBlank(basePath) ? path : basePath + "/" + path;
    }

    public static <T extends Annotation> T findAnnotation(
            final Method method, final Class<T> annotationClass) {
        return AnnotationUtils.findAnnotation(method, annotationClass).orElseThrow();
    }
}
