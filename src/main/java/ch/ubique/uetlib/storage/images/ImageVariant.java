package ch.ubique.uetlib.storage.images;

import org.jspecify.annotations.Nullable;

public enum ImageVariant {
    ORIGINAL,
    THUMBNAIL(320),
    HIRES(1200);

    public final String key;
    private final @Nullable Integer defaultSize;

    ImageVariant() {
        this(null);
    }

    ImageVariant(final @Nullable Integer defaultSize) {
        this.key = name().toLowerCase();
        this.defaultSize = defaultSize;
    }

    public @Nullable Integer getDefaultSize() {
        return defaultSize;
    }
}
