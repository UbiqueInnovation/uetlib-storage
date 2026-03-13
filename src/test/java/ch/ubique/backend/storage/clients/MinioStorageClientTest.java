package ch.ubique.backend.storage.clients;

import static ch.ubique.backend.storage.clients.MinioStorageClient.partSize;

import static org.assertj.core.api.Assertions.assertThat;

import io.minio.ObjectWriteArgs;

import org.junit.jupiter.api.Test;

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
}
