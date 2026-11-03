package ch.ubique.uetlib.storage.output;

import static java.util.Objects.requireNonNull;

import ch.ubique.uetlib.core.file.ByteFile;
import ch.ubique.uetlib.core.utils.StringUtils;

import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public sealed class ByteOutput implements Output permits JsonOutput {

    private final byte[] value;
    private final String contentType;

    public ByteOutput(final byte[] value) {
        this(value, null);
    }

    public ByteOutput(final ByteFile byteFile) {
        this(requireNonNull(byteFile.getBytes()), byteFile.getContentType());
    }

    public ByteOutput(final byte[] value, @Nullable final String contentType) {
        this.value = value;
        this.contentType = StringUtils.requireNonBlankElse(contentType, "application/octet-stream");
    }

    public final byte[] value() {
        return value;
    }

    @Override
    public final void write(final OutputStream outputStream) throws IOException {
        outputStream.write(value);
    }

    @Override
    public final InputStream read() throws IOException {
        return new ByteArrayInputStream(value);
    }

    @Override
    public final String contentType() {
        return contentType;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this, ToStringStyle.SHORT_PREFIX_STYLE)
                .append("contentType", contentType)
                .append("value.length", value.length)
                .toString();
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof final ByteOutput that)) {
            return false;
        }

        return new EqualsBuilder()
                .append(value, that.value)
                .append(contentType, that.contentType)
                .isEquals();
    }

    @Override
    public int hashCode() {
        return new HashCodeBuilder(17, 37).append(value).append(contentType).toHashCode();
    }

    @Override
    public final long uncompressedContentLength() {
        return value.length;
    }

    @Override
    public final boolean canBeRetried() {
        return true;
    }
}
