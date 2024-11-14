package ch.ubique.backend.storage.invocation;

import ch.ubique.backend.storage.StorageClient;

import com.fasterxml.jackson.databind.ObjectMapper;

public record StorageMappingInvocationProperties(
        StorageClient storageClient,
        ObjectMapper objectMapper,
        String basePath,
        String documentationBaseUrl) {}
