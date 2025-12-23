package ch.ubique.backend.storage.invocation;

import ch.ubique.backend.storage.StorageClient;

import tools.jackson.databind.ObjectMapper;

public record StorageMappingInvocationProperties(
        StorageClient storageClient,
        ObjectMapper objectMapper,
        String basePath,
        String documentationBaseUrl) {}
