package ch.ubique.uetlib.storage.clients;

import static ch.ubique.uetlib.storage.clients.StorageClientTest.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ch.ubique.uetlib.storage.StorageClient;
import ch.ubique.uetlib.storage.exception.NonRetryableStorageClientException;
import ch.ubique.uetlib.storage.output.ByteOutput;
import ch.ubique.uetlib.storage.utils.CompressionUtils;
import com.azure.core.util.BinaryData;
import com.azure.core.util.Context;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.options.BlobParallelUploadOptions;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

class StorageCopyTest {
    private static final byte[] BINARY = new byte[] {0, 1, -1, -128, 42, 0};

    @BeforeAll
    static void start() {
        AZURE_BLOB_STORAGE_CONTAINER.start();
        MINIO_CONTAINER.start();
    }

    @AfterAll
    static void stop() {
        AZURE_BLOB_STORAGE_CONTAINER.stop();
        MINIO_CONTAINER.stop();
    }

    @ParameterizedTest
    @MethodSource("ch.ubique.uetlib.storage.clients.StorageClientTest#createStorageClients")
    void copiesRawBytesThroughWrappers(final StorageClient client) throws Exception {
        final var source = "copy/source + % ü?/#.bin";
        final var destination = "copy/pending/target.bin";
        try {
            for (final var bytes : new byte[][] {BINARY, CompressionUtils.gzipCompress(BINARY)}) {
                client.write(new ByteOutput(bytes), source);
                client.write(new ByteOutput(new byte[] {99}), destination);
                if (isUnsupported(client)) {
                    assertThrows(
                            NonRetryableStorageClientException.class,
                            () -> client.copy(source, destination));
                    try (final var unchanged = client.read(destination, false)) {
                        assertThat(unchanged.readAllBytes()).containsExactly(99);
                    }
                } else {
                    client.copy(source, destination);
                    try (final var copied = client.read(destination, false)) {
                        assertThat(copied.readAllBytes()).isEqualTo(bytes);
                    }
                }
                try (final var original = client.read(source, false)) {
                    assertThat(original.readAllBytes()).isEqualTo(bytes);
                }
                assertThrows(
                        NonRetryableStorageClientException.class,
                        () -> client.copy(source, source));
                assertThrows(
                        NonRetryableStorageClientException.class,
                        () -> client.copy("copy/missing", destination));
            }
        } finally {
            client.delete("", true);
        }
    }

    private static boolean isUnsupported(final StorageClient client) {
        return client instanceof MinioStorageClient
                || client instanceof RetryStorageClient retry && isUnsupported(retry.delegate())
                || client instanceof PrefixStorageClient prefix && isUnsupported(prefix.delegate());
    }

    static String azureConnection(final String host, final int port) {
        return "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;"
                + "AccountKey=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==;"
                + "BlobEndpoint=http://"
                + host
                + ":"
                + port
                + "/devstoreaccount1;";
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void azurePreservesPropertiesAndMetadata(final boolean withOptionalHeaders) throws Exception {
        final var connection =
                azureConnection(
                        AZURE_BLOB_STORAGE_CONTAINER.getHost(),
                        AZURE_BLOB_STORAGE_CONTAINER.getFirstMappedPort());
        final var containerName = "copy-" + UUID.randomUUID();
        final var container =
                new BlobServiceClientBuilder()
                        .connectionString(connection)
                        .buildClient()
                        .createBlobContainer(containerName);
        try {
            final var source = container.getBlobClient("source");
            final var destination = container.getBlobClient("destination");
            final var bytes = CompressionUtils.gzipCompress(BINARY);
            final var headers = new BlobHttpHeaders().setContentType("application/x-test");
            if (withOptionalHeaders) {
                headers.setContentEncoding("gzip")
                        .setCacheControl("max-age=17")
                        .setContentDisposition("attachment; filename=test.bin")
                        .setContentLanguage("de-CH");
            }
            source.uploadWithResponse(
                    new BlobParallelUploadOptions(BinaryData.fromBytes(bytes))
                            .setHeaders(headers)
                            .setMetadata(Map.of("custom", "value", "second", "two")),
                    null,
                    Context.NONE);
            destination.uploadWithResponse(
                    new BlobParallelUploadOptions(BinaryData.fromString("old"))
                            .setHeaders(new BlobHttpHeaders().setCacheControl("max-age=999"))
                            .setMetadata(Map.of("stale", "remove")),
                    null,
                    Context.NONE);
            final var before = source.getProperties();
            new AzureBlobStorageClient(containerName, connection).copy("source", "destination");
            final var copied = destination.getProperties();
            assertThat(destination.downloadContent().toBytes()).isEqualTo(bytes);
            assertThat(source.downloadContent().toBytes()).isEqualTo(bytes);
            assertThat(source.getProperties().getETag()).isEqualTo(before.getETag());
            assertThat(copied.getContentType()).isEqualTo(before.getContentType());
            assertThat(copied.getContentEncoding()).isEqualTo(before.getContentEncoding());
            assertThat(copied.getCacheControl()).isEqualTo(before.getCacheControl());
            assertThat(copied.getContentDisposition()).isEqualTo(before.getContentDisposition());
            assertThat(copied.getContentLanguage()).isEqualTo(before.getContentLanguage());
            assertThat(copied.getMetadata()).isEqualTo(before.getMetadata());
        } finally {
            container.delete();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void s3PreservesPropertiesAndMetadata(final boolean withOptionalHeaders) throws Exception {
        final var bucket = "copy-" + UUID.randomUUID();
        try (final var s3 =
                S3AsyncClient.builder()
                        .credentialsProvider(
                                StaticCredentialsProvider.create(
                                        AwsBasicCredentials.create("test", "test1234")))
                        .region(Region.EU_CENTRAL_1)
                        .forcePathStyle(true)
                        .endpointOverride(
                                URI.create(
                                        "http://"
                                                + MINIO_CONTAINER.getHost()
                                                + ":"
                                                + MINIO_CONTAINER.getFirstMappedPort()))
                        .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                        .build()) {
            s3.createBucket(b -> b.bucket(bucket)).join();
            final var client = new AmazonS3StorageClient(bucket, null, s3);
            try {
                final var bytes = CompressionUtils.gzipCompress(BINARY);
                final var put =
                        PutObjectRequest.builder()
                                .bucket(bucket)
                                .key("source")
                                .contentType("application/x-test")
                                .metadata(Map.of("custom", "value", "second", "two"));
                if (withOptionalHeaders) {
                    put.contentEncoding("gzip")
                            .cacheControl("max-age=17")
                            .contentDisposition("attachment; filename=test.bin")
                            .contentLanguage("de-CH");
                }
                s3.putObject(put.build(), AsyncRequestBody.fromBytes(bytes)).join();
                s3.putObject(
                                b ->
                                        b.bucket(bucket)
                                                .key("destination")
                                                .cacheControl("max-age=999")
                                                .metadata(Map.of("stale", "remove")),
                                AsyncRequestBody.fromString("old"))
                        .join();
                final var before = s3.headObject(b -> b.bucket(bucket).key("source")).join();
                client.copy("source", "destination");
                final var copied = s3.headObject(b -> b.bucket(bucket).key("destination")).join();
                try (final var stream = client.read("destination", false)) {
                    assertThat(stream.readAllBytes()).isEqualTo(bytes);
                }
                assertThat(s3.headObject(b -> b.bucket(bucket).key("source")).join().eTag())
                        .isEqualTo(before.eTag());
                assertThat(copied.contentType()).isEqualTo(before.contentType());
                assertThat(copied.contentEncoding()).isEqualTo(before.contentEncoding());
                assertThat(copied.cacheControl()).isEqualTo(before.cacheControl());
                assertThat(copied.contentDisposition()).isEqualTo(before.contentDisposition());
                assertThat(copied.contentLanguage()).isEqualTo(before.contentLanguage());
                assertThat(copied.metadata()).isEqualTo(before.metadata());
            } finally {
                client.delete("", true);
                s3.deleteBucket(b -> b.bucket(bucket)).join();
            }
        }
    }
}
