package ch.ubique.backend.storage;

import io.minio.messages.RestoreRequest.CannedAcl;

import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.Objects;

/** Options for a native copy, independent of upload metadata and its defaults. */
public record CopyOptions(@Nullable CannedAcl accessPolicy, Duration timeout) {
    public CopyOptions {
        Objects.requireNonNull(timeout);
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("Copy timeout must be positive and at most one day");
        }
    }

    public static CopyOptions defaults() {
        return new CopyOptions(null, Duration.ofSeconds(60));
    }

    /** S3 destination ACL; ignored by Azure and the filesystem, as for upload access policies. */
    public CopyOptions accessPolicy(final CannedAcl value) {
        return new CopyOptions(Objects.requireNonNull(value), timeout);
    }

    public CopyOptions timeout(final Duration value) {
        return new CopyOptions(accessPolicy, value);
    }
}
