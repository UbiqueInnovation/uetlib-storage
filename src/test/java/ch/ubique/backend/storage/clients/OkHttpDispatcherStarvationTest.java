package ch.ubique.backend.storage.clients;

import static ch.ubique.backend.storage.clients.MinioStorageClient.DEFAULT_MAX_REQUESTS;
import static ch.ubique.backend.storage.clients.MinioStorageClient.DEFAULT_MAX_REQUESTS_PER_HOST;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class OkHttpDispatcherStarvationTest {

    private static final int OKHTTP_DEFAULT_MAX_REQUESTS_PER_HOST = 5;

    @Test
    @Timeout(10)
    void defaultDispatcherStarvesNestedSameHostCallsWhenCallbacksWaitForThem()
            throws Exception {
        try (final var scenario = new Scenario(defaultClient())) {
            scenario.startCalls(OKHTTP_DEFAULT_MAX_REQUESTS_PER_HOST);

            assertThat(scenario.awaitCallbacksEntered()).isTrue();
            assertThat(scenario.awaitAbortCallsEnqueued()).isTrue();

            // This is the deadlock shape seen with Minio 9 multipart failures: all per-host slots
            // are occupied by callbacks waiting for another call to the same host. The abort calls
            // are queued, but cannot run because no callback can return before its abort completes.
            assertThat(scenario.client.dispatcher().runningCallsCount())
                    .isEqualTo(OKHTTP_DEFAULT_MAX_REQUESTS_PER_HOST);
            assertThat(scenario.client.dispatcher().queuedCallsCount())
                    .isEqualTo(OKHTTP_DEFAULT_MAX_REQUESTS_PER_HOST);
            assertThat(scenario.abortRequestsSeen()).isZero();

            scenario.cancelAllCalls();
            scenario.awaitCallbacksDone();
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "runManualDeadlockTest", matches = "true")
    void defaultDispatcherDeadlocksForeverForManualDebugging() throws Exception {
        try (final var scenario = new Scenario(new OkHttpClient.Builder().build(), true)) {
            scenario.startCalls(OKHTTP_DEFAULT_MAX_REQUESTS_PER_HOST);

            assertThat(scenario.awaitCallbacksEntered()).isTrue();
            assertThat(scenario.awaitAbortCallsEnqueued()).isTrue();

            assertThat(scenario.client.dispatcher().runningCallsCount())
                    .isEqualTo(OKHTTP_DEFAULT_MAX_REQUESTS_PER_HOST);
            assertThat(scenario.client.dispatcher().queuedCallsCount())
                    .isEqualTo(OKHTTP_DEFAULT_MAX_REQUESTS_PER_HOST);
            assertThat(scenario.abortRequestsSeen()).isZero();

            // Keep the test JVM alive so this state can be inspected with jcmd/jstack.
            // Expected state:
            // - 5 OkHttp callbacks are running for /part.
            // - each callback has queued a nested /abort request to the same host.
            // - each callback waits for its /abort future.
            // - the /abort requests cannot run because maxRequestsPerHost=5 is exhausted.
            new CountDownLatch(1).await();
        }
    }

    @Test
    @Timeout(10)
    void minioStorageClientDispatcherLeavesCapacityForNestedSameHostCalls() throws Exception {
        try (final var scenario =
                new Scenario(MinioStorageClient.createHttpClient(Duration.ofSeconds(5)))) {
            scenario.startCalls(OKHTTP_DEFAULT_MAX_REQUESTS_PER_HOST);

            assertThat(scenario.awaitCallbacksEntered()).isTrue();
            assertThat(scenario.awaitAbortCallsEnqueued()).isTrue();

            assertThat(scenario.awaitAbortRequestsSeen(OKHTTP_DEFAULT_MAX_REQUESTS_PER_HOST))
                    .isTrue();
            assertThat(scenario.awaitCallbacksDone()).isTrue();
            assertThat(scenario.callbackFailures()).isZero();
        }
    }

    @Test
    @Timeout(10)
    void minioStorageClientUsesExpandedDispatcherLimits() {
        final var client = MinioStorageClient.createHttpClient(Duration.ofSeconds(5));

        assertThat(client.dispatcher().getMaxRequests()).isEqualTo(DEFAULT_MAX_REQUESTS);
        assertThat(client.dispatcher().getMaxRequestsPerHost())
                .isEqualTo(DEFAULT_MAX_REQUESTS_PER_HOST);
    }

    private static OkHttpClient defaultClient() {
        return new OkHttpClient.Builder().callTimeout(Duration.ofSeconds(5)).build();
    }

    private static final class Scenario implements AutoCloseable {

        private final OkHttpClient client;
        private final HttpServer server;
        private final ExecutorService serverExecutor;
        private final String baseUrl;
        private final CountDownLatch callbacksEntered;
        private final CountDownLatch abortCallsEnqueued;
        private final CountDownLatch callbacksDone;
        private final boolean waitForeverForAbort;
        private final AtomicInteger abortRequestsSeen = new AtomicInteger();
        private final AtomicInteger callbackFailures = new AtomicInteger();
        private final AtomicReference<Throwable> unexpectedFailure = new AtomicReference<>();

        private Scenario(final OkHttpClient client) throws IOException {
            this(client, false);
        }

        private Scenario(final OkHttpClient client, final boolean waitForeverForAbort) throws IOException {
            this.client = client;
            this.waitForeverForAbort = waitForeverForAbort;
            this.serverExecutor = Executors.newCachedThreadPool();
            this.callbacksEntered = new CountDownLatch(OKHTTP_DEFAULT_MAX_REQUESTS_PER_HOST);
            this.abortCallsEnqueued = new CountDownLatch(OKHTTP_DEFAULT_MAX_REQUESTS_PER_HOST);
            this.callbacksDone = new CountDownLatch(OKHTTP_DEFAULT_MAX_REQUESTS_PER_HOST);
            this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/part", exchange -> {
                final var body = "part".getBytes();
                exchange.sendResponseHeaders(500, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.createContext("/abort", exchange -> {
                abortRequestsSeen.incrementAndGet();
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
            });
            server.setExecutor(serverExecutor);
            server.start();
            this.baseUrl = "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
        }

        private void startCalls(final int count) {
            for (int i = 0; i < count; i++) {
                client.newCall(request("/part")).enqueue(new Callback() {
                    @Override
                    public void onFailure(final Call call, final IOException e) {
                        unexpectedFailure.compareAndSet(null, e);
                        callbackFailures.incrementAndGet();
                        callbacksDone.countDown();
                    }

                    @Override
                    public void onResponse(final Call call, final Response response) throws IOException {
                        response.close();
                        callbacksEntered.countDown();
                        try {
                            if (!callbacksEntered.await(2, TimeUnit.SECONDS)) {
                                throw new AssertionError("callbacks did not all enter");
                            }

                            final var abortCompleted = new CompletableFuture<Void>();
                            client.newCall(request("/abort")).enqueue(new Callback() {
                                @Override
                                public void onFailure(final Call call, final IOException e) {
                                    abortCompleted.completeExceptionally(e);
                                }

                                @Override
                                public void onResponse(final Call call, final Response response) {
                                    response.close();
                                    abortCompleted.complete(null);
                                }
                            });
                            abortCallsEnqueued.countDown();
                            if (waitForeverForAbort) {
                                abortCompleted.join();
                            } else {
                                abortCompleted.get(2, TimeUnit.SECONDS);
                            }
                        } catch (final Exception e) {
                            if (!call.isCanceled()) {
                                unexpectedFailure.compareAndSet(null, e);
                                callbackFailures.incrementAndGet();
                            }
                        } finally {
                            callbacksDone.countDown();
                        }
                    }
                });
            }
        }

        private boolean awaitCallbacksEntered() throws InterruptedException {
            return callbacksEntered.await(2, TimeUnit.SECONDS);
        }

        private boolean awaitAbortCallsEnqueued() throws InterruptedException {
            return abortCallsEnqueued.await(2, TimeUnit.SECONDS);
        }

        private boolean awaitCallbacksDone() throws InterruptedException {
            return callbacksDone.await(2, TimeUnit.SECONDS);
        }

        private int abortRequestsSeen() {
            return abortRequestsSeen.get();
        }

        private int callbackFailures() {
            return callbackFailures.get();
        }

        private boolean awaitAbortRequestsSeen(final int expected) throws InterruptedException {
            final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (System.nanoTime() < deadline) {
                if (abortRequestsSeen.get() == expected) {
                    return true;
                }
                Thread.sleep(10);
            }
            return false;
        }

        private Request request(final String path) {
            return new Request.Builder().url(baseUrl + path).build();
        }

        private void cancelAllCalls() {
            client.dispatcher().cancelAll();
        }

        @Override
        public void close() {
            cancelAllCalls();
            client.dispatcher().executorService().shutdownNow();
            client.connectionPool().evictAll();
            server.stop(0);
            serverExecutor.shutdownNow();
            final var failure = unexpectedFailure.get();
            if (failure != null) {
                throw new AssertionError("unexpected callback failure", failure);
            }
        }
    }
}
