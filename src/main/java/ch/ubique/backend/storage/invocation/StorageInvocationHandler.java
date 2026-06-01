package ch.ubique.backend.storage.invocation;

import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class StorageInvocationHandler implements InvocationHandler {

    private static final Logger logger = LoggerFactory.getLogger(StorageInvocationHandler.class);

    private final Map<Method, StorageMappingInvocationHandler> dispatch;
    private final StorageMappingInvocationProperties storageMappingInvocationProperties;

    public StorageInvocationHandler(
            final Map<Method, StorageMappingInvocationHandler> dispatch,
            final StorageMappingInvocationProperties storageMappingInvocationProperties) {
        this.dispatch = dispatch;
        this.storageMappingInvocationProperties = storageMappingInvocationProperties;
    }

    @Override
    public Object invoke(final Object proxy, final Method method, final Object @Nullable [] args)
            throws Throwable {
        return switch (method.getName()) {
            case "equals" -> args != null && equals(args[0]);
            case "hashCode" -> hashCode();
            case "toString" -> toString();
            default -> {
                logger.trace(
                        "invoking call to method {} to {} with {} args ({})",
                        method.getName(),
                        dispatch.get(method),
                        args == null ? null : args.length,
                        Optional.ofNullable(args).stream()
                                .flatMap(Stream::of)
                                .map(
                                        o ->
                                                Optional.ofNullable(o)
                                                        .map(Object::getClass)
                                                        .map(Class::getCanonicalName)
                                                        .orElse("null"))
                                .collect(Collectors.joining(", ")));
                try {
                    yield Objects.requireNonNull(dispatch.get(method))
                            .invoke(storageMappingInvocationProperties, method, args);
                } catch (final InvocationTargetException e) {
                    // ANY (checked or unchecked) exception thrown by the underlying method will be
                    // wrapped in an InvocationTargetException (a checked exception), which will be
                    // then wrapped by the Proxy in an UndeclaredThrowableException, UNLESS we do
                    // this ;)
                    // See:
                    // https://amitstechblog.wordpress.com/2011/07/24/java-proxies-and-undeclaredthrowableexception/
                    throw e.getCause();
                }
            }
        };
    }
}
