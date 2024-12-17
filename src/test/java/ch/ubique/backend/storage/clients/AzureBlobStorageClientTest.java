package ch.ubique.backend.storage.clients;

import static org.junit.jupiter.api.Assertions.*;

import ch.ubique.backend.storage.exception.StorageClientException;
import ch.ubique.backend.storage.output.FileOutput;
import java.io.File;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

@Disabled("Integration Test, which requires a valid Azure Blob Storage connection")
class AzureBlobStorageClientTest {

    public static final String CONTAINER = "sac-private/v1";
    public static final String CONNECTION_STRING = "NOT_SET";
    final AzureBlobStorageClient azureBlobStorageClient =
            new AzureBlobStorageClient(CONTAINER, CONNECTION_STRING);

    @Test
    void getSignedUrl() {
        assertDoesNotThrow(
                () -> {
                    final var signedUrl =
                            azureBlobStorageClient.getSignedUrl(
                                    "tourreports/512/899-hires.jpeg",
                                    OffsetDateTime.now().plusHours(24));
                    assertNotNull(signedUrl);
                });
    }

    @Test
    void delete() throws StorageClientException {
        azureBlobStorageClient.write(
                new FileOutput(
                        new File("src/test/resources/data/ubique_main_image.jpg"), "image/jpeg"),
                "/tourreports/512/899-hires.jpeg");
        assertDoesNotThrow(
                () -> {
                    final var signedUrl =
                            azureBlobStorageClient.getSignedUrl(
                                    "tourreports/512/899-hires.jpeg",
                                    OffsetDateTime.now().plusHours(24));
                    assertNotNull(signedUrl);
                });
        assertDoesNotThrow(() -> azureBlobStorageClient.delete("tourreports/512/899-hires.jpeg"));
    }
}
