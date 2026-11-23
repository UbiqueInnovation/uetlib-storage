package ch.uetlib.storage.clients;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ch.uetlib.storage.CopyOptions;
import ch.uetlib.storage.StorageClient;
import ch.uetlib.storage.exception.NonRetryableStorageClientException;
import ch.uetlib.storage.exception.RetryableStorageClientException;

import io.minio.messages.RestoreRequest.CannedAcl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;

import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.*;

class CopyFailureTest {
    @TempDir Path directory;

    private static S3AsyncClient s3() {
        final var s3 = mock(S3AsyncClient.class);
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                HeadObjectResponse.builder()
                                        .contentLength(12L)
                                        .eTag("source-etag")
                                        .build()));
        when(s3.copyObject(any(CopyObjectRequest.class)))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                CopyObjectResponse.builder()
                                        .copyObjectResult(b -> b.eTag("copied-etag"))
                                        .build()));
        return s3;
    }

    @Test
    void explicitS3AclAndNativeMetadataDirective() throws Exception {
        final var s3 = s3();
        final var client = new AmazonS3StorageClient("bucket", null, s3);
        client.copy(
                "source",
                "destination",
                CopyOptions.defaults().accessPolicy(CannedAcl.PUBLIC_READ));
        final var capture = ArgumentCaptor.forClass(CopyObjectRequest.class);
        verify(s3).copyObject(capture.capture());
        final var request = capture.getValue();
        assertThat(request.aclAsString()).isEqualTo("public-read");
        assertThat(request.metadataDirective()).isEqualTo(MetadataDirective.COPY);
        assertThat(request.copySourceIfMatch()).isEqualTo("source-etag");
        assertThat(request.sourceBucket()).isEqualTo("bucket");
        assertThat(request.destinationBucket()).isEqualTo("bucket");
        assertThat(request.sourceKey()).isEqualTo("source");
        assertThat(request.destinationKey()).isEqualTo("destination");
        assertThat(request.metadata()).isEmpty();
        assertThat(request.cacheControl()).isNull();
    }

    @Test
    void defaultS3AclDoesNotGrantAccess() throws Exception {
        final var s3 = s3();
        new AmazonS3StorageClient("bucket", null, s3).copy("source", "destination");
        final var capture = ArgumentCaptor.forClass(CopyObjectRequest.class);
        verify(s3).copyObject(capture.capture());
        assertThat(capture.getValue().aclAsString()).isNull();
    }

    @Test
    void s3RejectsOversizeBeforeCopy() {
        final var s3 = s3();
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                HeadObjectResponse.builder()
                                        .contentLength(5L * 1024 * 1024 * 1024 + 1)
                                        .build()));
        final var client = new AmazonS3StorageClient("bucket", null, s3);
        assertThrows(
                NonRetryableStorageClientException.class,
                () -> client.copy("source", "destination"));
        verify(s3, never()).copyObject(any(CopyObjectRequest.class));
    }

    @Test
    void s3TimeoutCancelsLocalWaitAndDoesNotRestartOperation() {
        final var s3 = s3();
        final var pending = new CompletableFuture<CopyObjectResponse>();
        when(s3.copyObject(any(CopyObjectRequest.class))).thenReturn(pending);
        final var client = new AmazonS3StorageClient("bucket", null, s3).retryable();
        final var options = CopyOptions.defaults().timeout(Duration.ofMillis(100));
        assertThrows(
                NonRetryableStorageClientException.class,
                () -> client.copy("source", "destination", options));
        assertThat(pending.isCancelled()).isTrue();
        verify(s3).copyObject(any(CopyObjectRequest.class));
    }

    @Test
    void s3WaitsForActualCompletion() throws Exception {
        final var s3 = s3();
        final var pending = new CompletableFuture<CopyObjectResponse>();
        final var started = new CountDownLatch(1);
        when(s3.copyObject(any(CopyObjectRequest.class)))
                .thenAnswer(_ -> {
                    started.countDown();
                    return pending;
                });
        final var client = new AmazonS3StorageClient("bucket", null, s3);
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var task =
                    executor.submit(() -> {
                        client.copy("source", "destination");
                        return true;
                    });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(task.isDone()).isFalse();
            pending.complete(
                    CopyObjectResponse.builder().copyObjectResult(b -> b.eTag("complete")).build());
            assertThat(task.get(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void s3FailureCancellationAndMissingCompletionAreNotSuccess() {
        final var s3 = s3();
        final var client = new AmazonS3StorageClient("bucket", null, s3);
        when(s3.copyObject(any(CopyObjectRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(new IOException("failed")));
        assertThrows(
                NonRetryableStorageClientException.class,
                () -> client.copy("source", "destination"));
        final var cancelled = new CompletableFuture<CopyObjectResponse>();
        cancelled.cancel(true);
        when(s3.copyObject(any(CopyObjectRequest.class))).thenReturn(cancelled);
        assertThrows(
                NonRetryableStorageClientException.class,
                () -> client.copy("source", "destination"));
        when(s3.copyObject(any(CopyObjectRequest.class)))
                .thenReturn(
                        CompletableFuture.completedFuture(CopyObjectResponse.builder().build()));
        assertThrows(
                NonRetryableStorageClientException.class,
                () -> client.copy("source", "destination"));
    }

    @Test
    void retryWrapperNeverRetriesEvenWithBroadCustomPolicy() throws Exception {
        final var delegate = mock(StorageClient.class);
        final var options = CopyOptions.defaults();
        doThrow(new RetryableStorageClientException("uncertain", new IOException()))
                .when(delegate)
                .copy("source", "destination", options);
        when(delegate.withoutRetry()).thenReturn(delegate);
        final var retry =
                new RetryStorageClient(
                        delegate, new RetryTemplate(RetryPolicy.builder().maxRetries(3).build()));
        assertThrows(
                RetryableStorageClientException.class,
                () -> retry.copy("source", "destination", options));
        verify(delegate).copy("source", "destination", options);
    }

    @Test
    void prefixForwardsBothPathsAndOptionsThroughRetry() throws Exception {
        final var delegate = mock(StorageClient.class);
        when(delegate.withoutRetry()).thenReturn(delegate);
        final var options = CopyOptions.defaults().accessPolicy(CannedAcl.PRIVATE);
        final var client = new PrefixStorageClient("prefix", new RetryStorageClient(delegate));
        client.copy("source", "destination", options);
        verify(delegate).copy("prefix/source", "prefix/destination", options);
    }

    @Test
    void externalImplementationWithoutCopyFailsExplicitly() {
        final var oldImplementation = mock(StorageClient.class, CALLS_REAL_METHODS);
        assertThrows(
                NonRetryableStorageClientException.class,
                () -> oldImplementation.copy("source", "destination"));
    }

    @Test
    void identicalS3PathsNeverReachProvider() {
        final var s3 = s3();
        final var client = new AmazonS3StorageClient("bucket", null, s3);
        assertThrows(
                NonRetryableStorageClientException.class, () -> client.copy("source", "source"));
        verify(s3, never()).headObject(any(HeadObjectRequest.class));
        verify(s3, never()).copyObject(any(CopyObjectRequest.class));
    }

    @Test
    void filesystemRejectsAliasesAndDirectoriesWithoutChangingSource() throws Exception {
        final var source = directory.resolve("source");
        Files.write(source, new byte[] {1, 2, 3});
        Files.createLink(directory.resolve("hardlink"), source);
        Files.createSymbolicLink(directory.resolve("symlink"), source);
        final var client = new FileSystemStorageClient(directory.toString());
        for (final var alias : new String[] {"source", "./source", "hardlink", "symlink"}) {
            assertThrows(
                    NonRetryableStorageClientException.class, () -> client.copy("source", alias));
        }
        assertThrows(NonRetryableStorageClientException.class, () -> client.copy(".", "target"));
        assertThat(Files.readAllBytes(source)).containsExactly(1, 2, 3);
    }

    @Test
    void interruptionIsPreservedAndDoesNotWrite() throws Exception {
        Files.write(directory.resolve("source"), new byte[] {1});
        final var client = new FileSystemStorageClient(directory.toString());
        Thread.currentThread().interrupt();
        try {
            assertThrows(
                    NonRetryableStorageClientException.class,
                    () -> client.copy("source", "destination"));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(directory.resolve("destination")).doesNotExist();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void filesystemCopiesGzipVerbatimEvenWithDefaultUncompressedWrites() throws Exception {
        final var bytes =
                ch.uetlib.storage.utils.CompressionUtils.gzipCompress(new byte[] {0, -1, 2});
        Files.write(directory.resolve("source.gz"), bytes);
        final var client = new FileSystemStorageClient(directory.toString());
        client.copy("source.gz", "nested/destination.txt");
        assertThat(Files.readAllBytes(directory.resolve("nested/destination.txt")))
                .isEqualTo(bytes);
        assertThat(Files.readAllBytes(directory.resolve("source.gz"))).isEqualTo(bytes);
    }

    @Test
    void expiredFilesystemCopyDoesNotWrite() throws Exception {
        Files.write(directory.resolve("source"), new byte[] {1});
        final var client = new FileSystemStorageClient(directory.toString());
        final var options = CopyOptions.defaults().timeout(Duration.ofNanos(1));
        assertThrows(
                NonRetryableStorageClientException.class,
                () -> client.copy("source", "destination", options));
        assertThat(directory.resolve("destination")).doesNotExist();
    }
}
