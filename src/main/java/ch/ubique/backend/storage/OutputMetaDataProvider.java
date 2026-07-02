package ch.ubique.backend.storage;

import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElse;

import io.minio.messages.RestoreRequest.CannedAcl;

import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;

public final class OutputMetaDataProvider {

    private final Duration maxAge;
    private final Duration sMaxAge;

    private @Nullable Duration expiresOffset;
    private @Nullable Duration bestBeforeOffset;
    private @Nullable Duration nextRefreshOffset;
    private @Nullable Duration backoff;

    private @Nullable CannedAcl accessPolicy;
    private @Nullable String customAccessPolicy;

    /**
     * @param maxAge Duration must not be null
     * @param sMaxAge Duration will be set to maxAge if null
     */
    public OutputMetaDataProvider(final Duration maxAge, @Nullable final Duration sMaxAge) {
        this.maxAge = requireNonNull(maxAge, "maxAge must not be null");
        this.sMaxAge = requireNonNullElse(sMaxAge, maxAge);
    }

    /**
     * The predefined offset for the {@code Expires} header. The offset is added to the current
     * timestamp at time of the time of calling {@link #createOutputMetaData()} on this provider.
     *
     * @param expiresOffset the offset for the {@code Expires} header
     * @return this to allow chaining
     */
    public OutputMetaDataProvider expiresOffset(final Duration expiresOffset) {
        this.expiresOffset = requireNonNull(expiresOffset);
        return this;
    }

    /**
     * The predefined offset for the {@code Best-Before} header. The offset is added to the current
     * timestamp at time of the time of calling {@link #createOutputMetaData()} on this provider.
     *
     * @param bestBeforeOffset the offset for the {@code Best-Before} header
     * @return this to allow chaining
     */
    public OutputMetaDataProvider bestBeforeOffset(final Duration bestBeforeOffset) {
        requireNonNull(bestBeforeOffset);
        this.bestBeforeOffset = bestBeforeOffset;
        return this;
    }

    /**
     * The predefined offset for the {@code Next-Refresh} header. The offset is added to the current
     * timestamp at time of the time of calling {@link #createOutputMetaData()} on this provider.
     *
     * @param nextRefreshOffset the offset for the {@code Next-Refresh} header
     * @return this to allow chaining
     */
    public OutputMetaDataProvider nextRefreshOffset(final Duration nextRefreshOffset) {
        requireNonNull(nextRefreshOffset);
        this.nextRefreshOffset = nextRefreshOffset;
        return this;
    }

    public OutputMetaDataProvider backoff(final Duration backoff) {
        requireNonNull(backoff);
        this.backoff = backoff;
        return this;
    }

    /**
     * Set an access policy for the object. This is only used for AWS S3. To set a custom access
     * policy, use {@link #customAccessPolicy(String)}.
     *
     * @throws IllegalStateException if {@link #customAccessPolicy(String)} was already called
     * @param accessPolicy The access policy
     * @return this to allow chaining
     */
    public OutputMetaDataProvider accessPolicy(final CannedAcl accessPolicy)
            throws IllegalStateException {
        if (customAccessPolicy != null) {
            throw new IllegalStateException(
                    "Cannot set access policy and custom access policy at the same time");
        }
        requireNonNull(accessPolicy);
        this.accessPolicy = accessPolicy;
        return this;
    }

    /**
     * Set a custom access policy for the object. This is only used for AWS S3. To set a predefined
     * access policy, use {@link #accessPolicy(CannedAcl)}. This method allows every string value,
     * thus the user is responsible for the correctness of the value.
     *
     * @throws IllegalStateException if {@link #accessPolicy(CannedAcl)} was already called
     * @param customAccessPolicy The custom access policy
     * @return this to allow chaining
     */
    public OutputMetaDataProvider customAccessPolicy(final String customAccessPolicy)
            throws IllegalStateException {
        if (accessPolicy != null) {
            throw new IllegalStateException(
                    "Cannot set access policy and custom access policy at the same time");
        }
        requireNonNull(customAccessPolicy);
        this.customAccessPolicy = customAccessPolicy;
        return this;
    }

    public OutputMetaData createOutputMetaData() {
        final var outputMetaData = new OutputMetaData(maxAge, sMaxAge);

        final var now = Instant.now();
        if (expiresOffset != null) {
            outputMetaData.expires(now.plus(expiresOffset));
        }
        if (bestBeforeOffset != null) {
            outputMetaData.bestBefore(now.plus(bestBeforeOffset));
        }
        if (nextRefreshOffset != null) {
            outputMetaData.nextRefresh(now.plus(nextRefreshOffset));
        }
        if (backoff != null) {
            outputMetaData.backoff(backoff);
        }

        return outputMetaData;
    }
}
