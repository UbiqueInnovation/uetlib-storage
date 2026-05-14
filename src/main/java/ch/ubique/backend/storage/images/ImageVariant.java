package ch.ubique.backend.storage.images;

public enum ImageVariant {
    ORIGINAL,
    THUMBNAIL(320),
    HIRES(1200);

    public final String key;
    private final Integer defaultSize;

    ImageVariant() {
        this(null);
    }

    ImageVariant(final Integer defaultSize) {
        this.key = name().toLowerCase();
        this.defaultSize = defaultSize;
    }

    public Integer getDefaultSize() {
        return defaultSize;
    }
}
