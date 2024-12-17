package ch.ubique.backend.storage.clients;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

@Disabled("Integration Test, which requires a valid Azure Blob Storage connection")
class AzureBlobStorageClientTest {

    public static final String CONTAINER = "sac-private/v1";
    public static final String CONNECTION_STRING = "NOT_SET";

    @Test
    void getSignedUrl() {
        final AzureBlobStorageClient azureBlobStorageClient =
                new AzureBlobStorageClient(CONTAINER, CONNECTION_STRING);
        assertDoesNotThrow(
                () -> {
                    final var signedUrl =
                            azureBlobStorageClient.getSignedUrl(
                                    "tourreports/512/899-hires.jpeg",
                                    OffsetDateTime.now().plusHours(24));
                    assertNotNull(signedUrl);
                });
    }
}
