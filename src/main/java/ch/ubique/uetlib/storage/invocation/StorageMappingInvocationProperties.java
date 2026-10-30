package ch.ubique.uetlib.storage.invocation;

import ch.ubique.uetlib.storage.StorageClient;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;

public record StorageMappingInvocationProperties(
        StorageClient storageClient,
        ObjectMapper objectMapper,
        @Nullable String basePath,
        @Nullable String documentationBaseUrl) {}
