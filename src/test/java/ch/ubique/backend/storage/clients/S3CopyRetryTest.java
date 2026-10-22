package ch.ubique.backend.storage.clients;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.*;

import ch.ubique.backend.storage.CopyOptions;
import ch.ubique.backend.storage.exception.NonRetryableStorageClientException;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.*;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Real SDK retries against scripted HTTP responses, not a production S3 service test. */
class S3CopyRetryTest {
    private HttpServer server;
    private ExecutorService executor;
    private S3AsyncClient s3;
    private AmazonS3StorageClient client;
    private final AtomicInteger inspections = new AtomicInteger();
    private final AtomicInteger copies = new AtomicInteger();
    private final List<String> sourceConditions = new CopyOnWriteArrayList<>();
    private volatile int inspectionFailures;
    private volatile int copyFailures;
    private volatile long inspectionDelay;
    private volatile long copyDelay;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", this::respond);
        server.start();
        s3 =
                S3AsyncClient.builder()
                        .credentialsProvider(
                                StaticCredentialsProvider.create(
                                        AwsBasicCredentials.create("test", "test")))
                        .region(Region.EU_CENTRAL_1)
                        .forcePathStyle(true)
                        .endpointOverride(
                                URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
                        .build();
        client = new AmazonS3StorageClient("test", null, s3);
    }

    @AfterEach
    void stopServer() {
        s3.close();
        server.stop(0);
        executor.close();
    }

    private void respond(final HttpExchange exchange) throws IOException {
        try (exchange) {
            if ("HEAD".equals(exchange.getRequestMethod())) {
                final int attempt = inspections.incrementAndGet();
                delay(inspectionDelay);
                exchange.getResponseHeaders().set("Content-Length", "4");
                exchange.getResponseHeaders().set("ETag", "\"source-etag\"");
                exchange.sendResponseHeaders(attempt <= inspectionFailures ? 500 : 200, -1);
            } else {
                final int attempt = copies.incrementAndGet();
                sourceConditions.add(
                        exchange.getRequestHeaders().getFirst("x-amz-copy-source-if-match"));
                if (attempt <= copyFailures) {
                    sendXml(
                            exchange,
                            500,
                            "<Error><Code>InternalError</Code><Message>Retry</Message></Error>");
                } else {
                    delay(copyDelay);
                    sendXml(
                            exchange,
                            200,
                            "<CopyObjectResult><ETag>\"copied-etag\"</ETag></CopyObjectResult>");
                }
            }
        }
    }

    private static void sendXml(final HttpExchange exchange, final int status, final String xml)
            throws IOException {
        final var body = xml.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/xml");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    // Deliberately simulate server latency for deadline tests; this is not test synchronization.
    @SuppressWarnings("java:S2925")
    private static void delay(final long millis) {
        if (millis > 0) {
            try {
                Thread.sleep(millis);
            } catch (final InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Test
    void sdkRetriesInspectionAndCopyWithSameSourceCondition() throws Exception {
        inspectionFailures = 1;
        copyFailures = 1;
        client.copy("source", "destination");
        assertThat(inspections.get()).isEqualTo(2);
        assertThat(copies.get()).isEqualTo(2);
        assertThat(sourceConditions).containsExactly("\"source-etag\"", "\"source-etag\"");
    }

    @Test
    void copyRetrySharesDeadlineWithSourceInspection() {
        inspectionDelay = 1000;
        copyFailures = 1;
        copyDelay = 4000;
        final var options = CopyOptions.defaults().timeout(Duration.ofSeconds(3));
        final var retry = client.retryable();
        final long started = System.nanoTime();
        assertThrows(
                NonRetryableStorageClientException.class,
                () -> retry.copy("source", "destination", options));
        final var elapsed = Duration.ofNanos(System.nanoTime() - started);
        // Restarting the budget after HEAD would take at least four seconds.
        assertThat(elapsed).isBetween(Duration.ofMillis(2500), Duration.ofMillis(3750));
        assertThat(inspections.get()).isEqualTo(1);
        assertThat(copies.get()).isEqualTo(2);
        assertThat(sourceConditions).containsExactly("\"source-etag\"", "\"source-etag\"");
    }
}
