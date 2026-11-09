package ch.uetlib.storage.invocation;

import ch.uetlib.storage.StorageClient;

import org.jspecify.annotations.Nullable;

import tools.jackson.databind.ObjectMapper;

public record StorageMappingInvocationProperties(
        StorageClient storageClient,
        ObjectMapper objectMapper,
        @Nullable String basePath,
        @Nullable String documentationBaseUrl) {}
