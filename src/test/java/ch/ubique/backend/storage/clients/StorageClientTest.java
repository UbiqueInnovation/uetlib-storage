package ch.ubique.backend.storage.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCollection;
import static org.junit.jupiter.api.Assertions.*;

import ch.ubique.backend.core.utils.InputStreamUtils;
import ch.ubique.backend.storage.StorageClient;
import ch.ubique.backend.storage.exception.NonRetryableStorageClientException;
import ch.ubique.backend.storage.output.ByteOutput;

import jakarta.validation.constraints.NotNull;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.images.PullPolicy;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

@SuppressWarnings("resource")
class StorageClientTest {

    private static final GenericContainer<?> AZURE_BLOB_STORAGE_CONTAINER =
            new GenericContainer<>("mcr.microsoft.com/azure-storage/azurite")
                    .withImagePullPolicy(PullPolicy.ageBased(Duration.ofDays(30)))
                    .withExposedPorts(10000);

    private static final GenericContainer<?> MINIO_CONTAINER =
            new GenericContainer<>("minio/minio")
                    .withImagePullPolicy(PullPolicy.ageBased(Duration.ofDays(30)))
                    .withEnv("MINIO_ACCESS_KEY", "test")
                    .withEnv("MINIO_SECRET_KEY", "test1234")
                    .withCommand("server /data")
                    .withExposedPorts(9000)
                    .waitingFor(
                            new HttpWaitStrategy()
                                    .forPath("/minio/health/ready")
                                    .forPort(9000)
                                    .withStartupTimeout(Duration.ofSeconds(10)));

    @BeforeAll
    static void createContainers() {
        AZURE_BLOB_STORAGE_CONTAINER.start();
        MINIO_CONTAINER.start();
    }

    @AfterAll
    static void stopContainers() {
        AZURE_BLOB_STORAGE_CONTAINER.stop();
        MINIO_CONTAINER.stop();
    }

    @ParameterizedTest
    @MethodSource("createStorageClients")
    void test(final StorageClient storageClient) throws IOException {

        assertThatCollection(storageClient.list()).isEmpty();
        assertThatCollection(storageClient.list(true)).isEmpty();

        storageClient.write(new ByteOutput("Hello World".getBytes(StandardCharsets.UTF_8)), "foo");
        assertThatCollection(storageClient.list()).containsExactlyElementsOf(Set.of("foo"));

        assertEquals("Hello World", readStringAndClose(storageClient.read("foo")));

        assertEquals(
                "Hello World",
                readStringAndClose(
                        URI.create(
                                        storageClient.getSignedUrl(
                                                "foo", OffsetDateTime.now().plusHours(1)))
                                .toURL()
                                .openStream()));

        storageClient.write(new ByteOutput(new byte[0]), "bar/foo1");
        storageClient.write(new ByteOutput(new byte[0]), "bar/foo2");

        assertTrue(storageClient.exists("foo"));
        assertTrue(storageClient.exists("bar/"));
        assertTrue(storageClient.exists("bar/foo1"));
        assertTrue(storageClient.exists("bar/foo2"));
        assertFalse(storageClient.exists("nonExisting"));

        assertThatCollection(storageClient.list("bar/foo1", false))
                .containsExactlyInAnyOrderElementsOf(Set.of("bar/foo1"));
        assertThatCollection(storageClient.list("bar/foo1", true))
                .containsExactlyInAnyOrderElementsOf(Set.of("bar/foo1"));
        assertThatCollection(storageClient.list("bar/nonExisting", false)).isEmpty();
        assertThatCollection(storageClient.list("bar/nonExisting", true)).isEmpty();
        assertThatCollection(storageClient.list("nonExisting/bar", false)).isEmpty();
        assertThatCollection(storageClient.list("nonExisting/bar", true)).isEmpty();

        assertThatCollection(storageClient.list())
                .containsExactlyInAnyOrderElementsOf(Set.of("foo", "bar/"));
        assertThatCollection(storageClient.list())
                .containsExactlyInAnyOrderElementsOf(Set.of("foo", "bar/"));
        assertThatCollection(storageClient.list(true))
                .containsExactlyInAnyOrderElementsOf(Set.of("foo", "bar/foo1", "bar/foo2"));
        assertThatCollection(storageClient.list("bar", true))
                .containsExactlyInAnyOrderElementsOf(Set.of("bar/foo1", "bar/foo2"));
        assertThatCollection(storageClient.list("bar/", true))
                .containsExactlyInAnyOrderElementsOf(Set.of("bar/foo1", "bar/foo2"));
        assertThatCollection(storageClient.list("bar"))
                .containsExactlyInAnyOrderElementsOf(Set.of("bar/"));
        assertThatCollection(storageClient.list("bar/"))
                .containsExactlyInAnyOrderElementsOf(Set.of("bar/foo1", "bar/foo2"));

        assertDoesNotThrow(() -> storageClient.delete("bar/nonExisting"));
        assertDoesNotThrow(() -> storageClient.delete("nonExisting/bar"));
        assertDoesNotThrow(() -> storageClient.delete("bar/foo1"));
        assertFalse(storageClient.exists("bar/foo1"));
        assertThatCollection(storageClient.list(true))
                .containsExactlyInAnyOrderElementsOf(Set.of("foo", "bar/foo2"));

        assertThrows(IOException.class, () -> storageClient.delete("bar"));
        assertTrue(storageClient.exists("bar/"));
        assertTrue(storageClient.exists("bar/foo2"));
        assertThatCollection(storageClient.list())
                .containsExactlyInAnyOrderElementsOf(Set.of("foo", "bar/"));

        assertDoesNotThrow(() -> storageClient.delete("bar", true));
        assertTrue(storageClient.exists("foo"));
        assertFalse(storageClient.exists("bar/"));
        assertFalse(storageClient.exists("bar/foo1"));
        assertFalse(storageClient.exists("bar/foo2"));
        assertThatCollection(storageClient.list())
                .containsExactlyInAnyOrderElementsOf(Set.of("foo"));

        assertDoesNotThrow(() -> storageClient.delete("foo"));
        assertTrue(storageClient.list().isEmpty());

        storageClient.write(
                new ByteOutput("Hello World".getBytes(StandardCharsets.UTF_8)), "foo/bar-baz");
        assertEquals(
                "Hello World",
                readStringAndClose(
                        URI.create(
                                        storageClient.getSignedUrl(
                                                "foo/bar-baz", OffsetDateTime.now().plusHours(1)))
                                .toURL()
                                .openStream()));

        storageClient.write(
                new ByteOutput("Hello World".getBytes(StandardCharsets.UTF_8)).gZip(),
                "foo/bar-baz.gz");
        assertEquals("Hello World", readStringAndClose(storageClient.read("foo/bar-baz.gz")));

        storageClient.write(
                new ByteOutput("Hello World".getBytes(StandardCharsets.UTF_8)).gZip(),
                "foo/bar-baz.other-extension");
        assertEquals(
                "Hello World",
                readStringAndClose(storageClient.read("foo/bar-baz.other-extension")));

        for (final var extension : Set.of(".gz", ".bz")) {
            final var path = "foo/uncompressed" + extension;
            storageClient.write(
                    new ByteOutput("Hello World".getBytes(StandardCharsets.UTF_8)), path);
            assertEquals("Hello World", readStringAndClose(storageClient.read(path)));
        }

        assertTrue(storageClient.exists("foo/uncompressed"));
        assertThatCollection(storageClient.list("foo/uncompressed"))
                .containsExactlyInAnyOrderElementsOf(
                        Set.of("foo/uncompressed.gz", "foo/uncompressed.bz"));
        final var exception =
                assertThrows(
                        NonRetryableStorageClientException.class,
                        () -> storageClient.delete("foo/uncompressed"));
        assertThat(exception.getMessage()).startsWith("cannot delete non-empty directory.");

        // Delete by partial path
        assertThatCollection(storageClient.list(true)).contains("foo/bar-baz.other-extension");
        assertThrows(
                NonRetryableStorageClientException.class,
                () -> storageClient.delete("foo/bar-baz.other", false));
        assertDoesNotThrow(() -> storageClient.delete("foo/bar-baz.other-extension"));
        assertThatCollection(storageClient.list(true))
                .doesNotContain("foo/bar-baz.other-extension");
        assertThatCollection(storageClient.list("foo/uncompressed", true))
                .containsExactlyInAnyOrderElementsOf(
                        Set.of("foo/uncompressed.bz", "foo/uncompressed.gz"));
        assertDoesNotThrow(() -> storageClient.delete("foo/uncompressed", true));
        assertThatCollection(storageClient.list("foo/b", true))
                .containsExactlyInAnyOrderElementsOf(Set.of("foo/bar-baz.gz", "foo/bar-baz"));
        assertDoesNotThrow(() -> storageClient.delete("foo/b", true));
        assertThatCollection(storageClient.list(true)).isEmpty();

        // Test deletion of a nested object
        final var nestedPath = "foo/bar/baz/qux.txt";
        storageClient.write(new ByteOutput(new byte[0]), nestedPath);
        assertThatCollection(storageClient.list(true))
                .containsExactlyElementsOf(Set.of(nestedPath));
        assertDoesNotThrow(() -> storageClient.delete(nestedPath, true));
        assertThatCollection(storageClient.list(true)).isEmpty();
        assertThatCollection(storageClient.list()).isEmpty();
        assertFalse(storageClient.exists("foo/"));
        assertFalse(storageClient.exists("foo/bar/"));
        assertFalse(storageClient.exists("foo/bar/baz"));

        // Cleanup for the next run
        assertDoesNotThrow(() -> storageClient.delete("", true));
    }

    static Collection<StorageClient> createStorageClients() throws IOException {
        final var tempDir = Files.createTempDirectory(null);
        final var clients =
                Stream.of(
                                createLocalStorageClients(tempDir),
                                createAzureBlobStorageClient(),
                                createMinioAndAwsStorageClients())
                        .flatMap(Function.identity());

        return clients.flatMap(StorageClientTest::thisPlusRetryClient)
                .flatMap(StorageClientTest::thisPlusPrefixClients)
                .toList();
    }

    private static Stream<StorageClient> thisPlusRetryClient(final StorageClient client) {
        final var retryTemplate =
                new RetryTemplate(
                        RetryPolicy.builder().maxRetries(2).delay(Duration.ofMillis(1)).build());
        return Stream.of(client, client.retryable(retryTemplate));
    }

    private static Stream<StorageClient> thisPlusPrefixClients(final StorageClient client) {
        return Stream.concat(
                Stream.of(client),
                Stream.of("foo", "bar/", "foo/bar//baz")
                        .map(prefix -> new PrefixStorageClient(prefix, client)));
    }

    private static Stream<StorageClient> createLocalStorageClients(final Path tempDir) {
        return Stream.of(new FileSystemStorageClient(tempDir.toString(), false));
    }

    static Stream<StorageClient> createAzureBlobStorageClient() {
        final var mappedPort = AZURE_BLOB_STORAGE_CONTAINER.getFirstMappedPort();
        Testcontainers.exposeHostPorts(mappedPort);

        final var connectionString =
                String.format(
                        "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;"
                                + "AccountKey=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==;"
                                + "BlobEndpoint=http://%s:%s/devstoreaccount1;",
                        AZURE_BLOB_STORAGE_CONTAINER.getHost(), mappedPort);

        final var storageClient = new AzureBlobStorageClient("test", connectionString);
        storageClient.createContainer("test");
        return Stream.of(storageClient);
    }

    static Stream<StorageClient> createMinioAndAwsStorageClients() throws IOException {
        final var mappedPort = MINIO_CONTAINER.getFirstMappedPort();
        Testcontainers.exposeHostPorts(mappedPort);
        final var endpoint = String.format("http://%s:%s", MINIO_CONTAINER.getHost(), mappedPort);
        final var bucket = "test";
        final var accessKey = "test";
        final var secretKey = "test1234";

        final var minioStorageClient =
                new MinioStorageClient(bucket, endpoint, accessKey, secretKey, null, null);
        minioStorageClient.createBucket(bucket);

        final var awsStorageClient =
                new AmazonS3StorageClient(
                        bucket,
                        null,
                        S3AsyncClient.builder()
                                .credentialsProvider(
                                        StaticCredentialsProvider.create(
                                                AwsBasicCredentials.create(accessKey, secretKey)))
                                .region(Region.EU_CENTRAL_1)
                                .multipartEnabled(true)
                                .endpointOverride(URI.create(endpoint))
                                .forcePathStyle(true)
                                .requestChecksumCalculation(
                                        RequestChecksumCalculation.WHEN_REQUIRED)
                                .build());
        return Stream.of(minioStorageClient, awsStorageClient);
    }

    @NotNull
    private static String readStringAndClose(final InputStream storageClient) throws IOException {
        return new String(
                InputStreamUtils.readBytesAndClose(storageClient), StandardCharsets.UTF_8);
    }
}
