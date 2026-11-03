package ch.ubique.uetlib.storage.clients;

import static ch.ubique.uetlib.storage.clients.MinioStorageClient.partSize;
import static ch.ubique.uetlib.storage.clients.MinioStorageClient.storageClientException;

import static org.assertj.core.api.Assertions.assertThat;

import ch.ubique.uetlib.storage.exception.NonRetryableStorageClientException;
import ch.ubique.uetlib.storage.exception.RetryableStorageClientException;

import io.minio.ObjectWriteArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.errors.InsufficientDataException;
import io.minio.errors.InternalException;
import io.minio.errors.InvalidResponseException;
import io.minio.errors.MinioException;
import io.minio.errors.ServerException;
import io.minio.messages.ErrorResponse;

import org.junit.jupiter.api.Test;

import java.io.IOException;

class MinioStorageClientTest {

    private static final long MiB = 1024 * 1024;
    private static final long GiB = 1024 * MiB;

    @Test
    void testExamples() {
        assertThat(partSize(5 * MiB)).isEqualTo(ObjectWriteArgs.MIN_MULTIPART_SIZE);
        assertThat(partSize(2 * GiB)).isEqualTo(16 * MiB);
        assertThat(partSize(5 * GiB)).isEqualTo(32 * MiB);
        assertThat(partSize(10 * GiB)).isEqualTo(64 * MiB);
        assertThat(partSize(35 * GiB)).isEqualTo(256 * MiB);

        assertThat((int) Math.ceil(35.0 * GiB / partSize(35 * GiB))).isEqualTo(140);
    }

    @Test
    void storageClientExceptionMapping() {
        final var errorResponse =
                new ErrorResponse(
                        "NoSuchKey",
                        "The specified key does not exist.",
                        "test",
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
                        "test",
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
