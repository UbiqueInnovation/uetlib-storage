package ch.uetlib.storage.clients;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.*;

import ch.uetlib.storage.CopyOptions;
import ch.uetlib.storage.exception.NonRetryableStorageClientException;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Scripted HTTP responses exercise the real SDK; these do not prove Azure service behavior. */
class AzureCopyFailureTest {
    private HttpServer server;
    private ExecutorService executor;
    private AzureBlobStorageClient client;
    private final AtomicInteger copies = new AtomicInteger();
    private final AtomicInteger polls = new AtomicInteger();
    private final AtomicInteger aborts = new AtomicInteger();
    private volatile String status = "pending";
    private volatile String polledStatus = "success";
    private volatile String polledId = "our-copy";
    private volatile boolean failAbort;
    private volatile int startStatus = 202;
    private volatile long sourceSize = 4;
    private volatile String blobType = "BlockBlob";
    private volatile String abortId;
    private volatile String requiresSync;
    private volatile long startDelay;
    private volatile long sourceDelay;
    private volatile int transientStartFailures;
    private final List<String> sourceConditions = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", this::respond);
        server.start();
        client =
                new AzureBlobStorageClient(
                        "test",
                        StorageCopyTest.azureConnection(
                                "127.0.0.1", server.getAddress().getPort()));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        executor.close();
    }

    private void respond(final HttpExchange exchange) throws IOException {
        try (exchange) {
            final var headers = exchange.getResponseHeaders();
            headers.set("ETag", "\"etag\"");
            headers.set("Last-Modified", "Tue, 13 Oct 2026 00:00:00 GMT");
            headers.set("x-ms-blob-type", blobType);
            headers.set("x-ms-copy-id", polledId);
            headers.set("x-ms-copy-status", polledStatus);
            final var query = exchange.getRequestURI().getQuery();
            if (query != null && query.contains("comp=copy")) {
                aborts.incrementAndGet();
                abortId = query;
                exchange.sendResponseHeaders(failAbort ? 500 : 204, -1);
            } else if ("PUT".equals(exchange.getRequestMethod())) {
                final int attempt = copies.incrementAndGet();
                sourceConditions.add(exchange.getRequestHeaders().getFirst("x-ms-source-if-match"));
                requiresSync = exchange.getRequestHeaders().getFirst("x-ms-requires-sync");
                delay(startDelay);
                headers.set("x-ms-copy-id", "our-copy");
                headers.set("x-ms-copy-status", status);
                exchange.sendResponseHeaders(
                        attempt <= transientStartFailures ? 503 : startStatus, -1);
            } else {
                if (exchange.getRequestURI().getPath().endsWith("destination")) {
                    polls.incrementAndGet();
                } else {
                    delay(sourceDelay);
                }
                headers.set("Content-Length", String.valueOf(sourceSize));
                exchange.sendResponseHeaders(200, -1);
            }
        }
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
    void pendingResponseRequiresConfirmedCompletion() throws Exception {
        client.copy("source", "destination");
        assertThat(copies.get()).isEqualTo(1);
        assertThat(polls.get()).isEqualTo(1);
        assertThat(aborts.get()).isZero();
        assertThat(requiresSync).isEqualTo("true");
    }

    @ParameterizedTest
    @ValueSource(strings = {"failed", "aborted"})
    void terminalFailureIsNotSuccess(final String failureStatus) {
        polledStatus = failureStatus;
        assertThrows(
                NonRetryableStorageClientException.class,
                () -> client.copy("source", "destination"));
        assertThat(copies.get()).isEqualTo(1);
    }

    @Test
    void pendingTimeoutAbortsOnlyOwnCopyAndNeverRetries() {
        polledStatus = "pending";
        final var options = CopyOptions.defaults().timeout(Duration.ofSeconds(2));
        final var retry = client.retryable();
        final var failure =
                assertThrows(
                        NonRetryableStorageClientException.class,
                        () -> retry.copy("source", "destination", options));
        assertThat(failure.getMessage()).contains("our-copy", "inspect before retrying");
        assertThat(copies.get()).isEqualTo(1);
        assertThat(aborts.get()).isEqualTo(1);
        assertThat(abortId).contains("copyid=our-copy");
    }

    @Test
    void failedAbortIsReportedWithoutDeletingDestination() {
        polledStatus = "pending";
        failAbort = true;
        final var options = CopyOptions.defaults().timeout(Duration.ofSeconds(2));
        final var failure =
                assertThrows(
                        NonRetryableStorageClientException.class,
                        () -> client.copy("source", "destination", options));
        assertThat(failure.getSuppressed()).hasSize(1);
        assertThat(aborts.get()).isGreaterThanOrEqualTo(1);
        assertThat(abortId).contains("copyid=our-copy");
        assertThat(copies.get()).isEqualTo(1);
    }

    @Test
    void unknownOutcomeAfterOverallTimeoutIsNotRestartedOrBlindlyAborted() {
        startDelay = 4000;
        final var options = CopyOptions.defaults().timeout(Duration.ofSeconds(2));
        final var failure =
                assertThrows(
                        NonRetryableStorageClientException.class,
                        () -> client.copy("source", "destination", options));
        assertThat(failure.getMessage()).contains("copyId=null", "may still be running");
        assertThat(copies.get()).isEqualTo(1);
        assertThat(aborts.get()).isZero();
    }

    @Test
    void sdkRetriesTransientStartFailureWithSameSourceCondition() throws Exception {
        transientStartFailures = 1;
        final var options = CopyOptions.defaults().timeout(Duration.ofSeconds(20));
        client.copy("source", "destination", options);
        assertThat(copies.get()).isEqualTo(2);
        assertThat(sourceConditions).containsExactly("etag", "etag");
        assertThat(polls.get()).isEqualTo(1);
        assertThat(aborts.get()).isZero();
    }

    @Test
    void sdkBackoffSharesDeadlineWithSourceInspection() {
        sourceDelay = 1000;
        startStatus = 503;
        final var options = CopyOptions.defaults().timeout(Duration.ofSeconds(3));
        final long started = System.nanoTime();
        assertThrows(
                NonRetryableStorageClientException.class,
                () -> client.copy("source", "destination", options));
        final var elapsed = Duration.ofNanos(System.nanoTime() - started);
        // A fresh three-second budget after the delayed HEAD would take at least four seconds.
        assertThat(elapsed).isBetween(Duration.ofMillis(2500), Duration.ofMillis(3750));
        assertThat(copies.get()).isGreaterThanOrEqualTo(1);
        assertThat(aborts.get()).isZero();
    }

    @Test
    void replacedJobCannotCountAsOurSuccess() {
        polledId = "someone-elses-copy";
        assertThrows(
                NonRetryableStorageClientException.class,
                () -> client.copy("source", "destination"));
        assertThat(abortId).contains("copyid=our-copy");
    }

    @Test
    void unsupportedSizeAndBlobTypeFailBeforeCopy() {
        sourceSize = 256L * 1024 * 1024 + 1;
        assertThrows(
                NonRetryableStorageClientException.class,
                () -> client.copy("source", "destination"));
        sourceSize = 4;
        blobType = "PageBlob";
        assertThrows(
                NonRetryableStorageClientException.class,
                () -> client.copy("source", "destination"));
        assertThat(copies.get()).isZero();
    }

    @Test
    void identicalPathsDoNotReachProvider() {
        assertThrows(
                NonRetryableStorageClientException.class, () -> client.copy("source", "source"));
        assertThat(copies.get()).isZero();
        assertThat(polls.get()).isZero();
    }
}
