package ch.ubique.backend.storage.clients;

import static ch.ubique.backend.storage.clients.MinioStorageClient.storageClientException;
import static ch.ubique.backend.storage.clients.StorageClientTest.MINIO_CONTAINER;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.ubique.backend.storage.exception.NonRetryableStorageClientException;
import ch.ubique.backend.storage.exception.RetryableStorageClientException;
import ch.ubique.backend.storage.exception.StorageClientException;
import ch.ubique.backend.storage.output.ByteOutput;

import io.minio.errors.ErrorResponseException;
import io.minio.errors.InsufficientDataException;
import io.minio.errors.InternalException;
import io.minio.errors.InvalidResponseException;
import io.minio.errors.MinioException;
import io.minio.errors.ServerException;
import io.minio.messages.ErrorResponse;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.time.OffsetDateTime;

/** Tests the error handling of {@link MinioStorageClient}. */
class MinioStorageClientExceptionTest {

    private static final String BUCKET = "exception-test";

    private static String endpoint;
    private static MinioStorageClient validClient;

    @BeforeAll
    static void startContainer() throws StorageClientException {
        MINIO_CONTAINER.start();
        endpoint =
                String.format(
                        "http://%s:%s",
                        MINIO_CONTAINER.getHost(), MINIO_CONTAINER.getFirstMappedPort());
        validClient = new MinioStorageClient(BUCKET, endpoint, "test", "test1234", null, null);
        validClient.createBucket(BUCKET);
    }

    @AfterAll
    static void stopContainer() {
        MINIO_CONTAINER.stop();
    }

    private static MinioStorageClient clientWithInvalidCredentials() {
        return new MinioStorageClient(BUCKET, endpoint, "wrong", "wrong-secret", null, null);
    }

    private static MinioStorageClient clientForMissingBucket() {
        return new MinioStorageClient(
                "non-existing-bucket", endpoint, "test", "test1234", null, null);
    }

    private static MinioStorageClient clientForUnreachableEndpoint() {
        return new MinioStorageClient(
                BUCKET, "http://localhost:1", "test", "test1234", Duration.ofSeconds(1), null);
    }

    @Test
    void invalidCredentialsFailAllOperations() {
        final var client = clientWithInvalidCredentials();
        final var expiry = OffsetDateTime.now().plusHours(1);

        // HeadObject fails with a non-404 error
        assertThatThrownBy(() -> client.exists("foo"))
                .isInstanceOf(RetryableStorageClientException.class)
                .hasMessageStartingWith("failed to stat");
        assertThatThrownBy(() -> client.delete("foo"))
                .isInstanceOf(RetryableStorageClientException.class)
                .hasMessageStartingWith("failed to stat");

        // HeadObject is skipped for directories, list fails
        assertThatThrownBy(() -> client.exists("foo/"))
                .isInstanceOf(RetryableStorageClientException.class)
                .hasMessageStartingWith("failed to list");
        assertThatThrownBy(() -> client.delete("foo/"))
                .isInstanceOf(RetryableStorageClientException.class)
                .hasMessageStartingWith("failed to list");
        assertThatThrownBy(() -> client.delete("foo", true))
                .isInstanceOf(RetryableStorageClientException.class)
                .hasMessageStartingWith("failed to list");
        assertThatThrownBy(() -> client.list("", true))
                .isInstanceOf(RetryableStorageClientException.class)
                .hasMessageStartingWith("failed to list");

        assertThatThrownBy(() -> client.write(new ByteOutput(new byte[] {1}), "foo"))
                .isInstanceOf(RetryableStorageClientException.class)
                .hasMessageStartingWith("failed to write");
        assertThatThrownBy(() -> client.read("foo"))
                .isInstanceOf(RetryableStorageClientException.class)
                .hasMessageStartingWith("failed to read");
        assertThatThrownBy(() -> client.createBucket("another-bucket"))
                .isInstanceOf(RetryableStorageClientException.class)
                .hasMessageStartingWith("failed to create bucket");

        // Presigning needs the bucket region, whose lookup fails
        assertThatThrownBy(() -> client.getSignedUrl("foo", expiry))
                .isInstanceOf(RetryableStorageClientException.class)
                .hasMessageStartingWith("failed to get signed url");
        assertThatThrownBy(() -> client.getSignedUploadUrl("foo", expiry))
                .isInstanceOf(RetryableStorageClientException.class)
                .hasMessageStartingWith("failed to get signed url");
    }

    @Test
    void missingBucket() {
        final var client = clientForMissingBucket();

        // HeadObject on a missing bucket is a 404 -> treated as non-existing object, then list
        // fails
        assertThatThrownBy(() -> client.exists("foo"))
                .isInstanceOf(RetryableStorageClientException.class)
                .hasMessageStartingWith("failed to list");
        assertThatThrownBy(() -> client.delete("foo"))
                .isInstanceOf(RetryableStorageClientException.class)
                .hasMessageStartingWith("failed to list");
    }

    @Test
    void readMissingKeyIsNotRetryable() {
        assertThatThrownBy(() -> validClient.read("does/not/exist"))
                .isInstanceOf(NonRetryableStorageClientException.class)
                .hasMessageStartingWith("failed to read");
    }

    @Test
    void unreachableEndpoint() {
        final var client = clientForUnreachableEndpoint();

        assertThatThrownBy(() -> client.exists("foo"))
                .isInstanceOf(RetryableStorageClientException.class)
                .hasMessageStartingWith("failed to stat");
        assertThatThrownBy(() -> client.delete("foo"))
                .isInstanceOf(RetryableStorageClientException.class)
                .hasMessageStartingWith("failed to stat");
        assertThatThrownBy(() -> client.list("foo", false))
                .isInstanceOf(RetryableStorageClientException.class)
                .hasMessageStartingWith("failed to list");
    }

    @Test
    void storageClientExceptionMapping() {
        final var errorResponse =
                new ErrorResponse(
                        "NoSuchKey",
                        "The specified key does not exist.",
                        BUCKET,
                        "foo",
                        "/foo",
                        null,
                        null);
        assertThat(
                        storageClientException(
                                "m", new ErrorResponseException(errorResponse, null, null)))
                .isInstanceOf(NonRetryableStorageClientException.class);

        final var otherErrorResponse =
                new ErrorResponse(
                        "SlowDown",
                        "Please reduce your request rate.",
                        BUCKET,
                        "foo",
                        "/foo",
                        null,
                        null);
        assertThat(
                        storageClientException(
                                "m", new ErrorResponseException(otherErrorResponse, null, null)))
                .isInstanceOf(RetryableStorageClientException.class);

        assertThat(storageClientException("m", new InsufficientDataException("x")))
                .isInstanceOf(RetryableStorageClientException.class);
        assertThat(
                        storageClientException(
                                "m", new InvalidResponseException(500, "text/plain", "x", null)))
                .isInstanceOf(RetryableStorageClientException.class);
        assertThat(storageClientException("m", new InternalException("x")))
                .isInstanceOf(RetryableStorageClientException.class);
        assertThat(storageClientException("m", new ServerException("x", 500, null)))
                .isInstanceOf(RetryableStorageClientException.class);

        // Generic MinioException with and without a cause
        final var ioException = new IOException("io");
        assertThat(storageClientException("m", new MinioException(ioException)))
                .hasCause(ioException);
        final var withoutCause = new MinioException("no cause");
        assertThat(storageClientException("m", withoutCause)).hasCause(withoutCause);

        // I/O errors wrapped by minio
        assertThat(storageClientException("m", new IllegalStateException(ioException)))
                .isInstanceOf(RetryableStorageClientException.class)
                .hasCause(ioException);
        final var otherIllegalState = new IllegalStateException("other");
        assertThat(storageClientException("m", otherIllegalState))
                .isNotInstanceOf(RetryableStorageClientException.class)
                .hasCause(otherIllegalState);

        // Non-minio exception
        assertThat(storageClientException("m", ioException)).hasCause(ioException);
    }
}
