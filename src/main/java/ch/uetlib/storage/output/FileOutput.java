package ch.uetlib.storage.output;

import ch.uetlib.core.utils.StringUtils;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public record FileOutput(File file, String contentType) implements Output {

    public FileOutput(final File file) {
        this(file, null);
    }

    public FileOutput(final File file, @Nullable final String contentType) {
        this.file = file;
        this.contentType = StringUtils.requireNonBlankElse(contentType, "application/octet-stream");
    }

    @Override
    public void write(final OutputStream outputStream) throws IOException {
        FileUtils.copyFile(file, outputStream);
    }

    @Override
    public InputStream read() throws IOException {
        return new FileInputStream(file);
    }

    @Override
    public @NonNull String toString() {
        return new ToStringBuilder(this, ToStringStyle.SHORT_PREFIX_STYLE)
                .append("contentType", contentType)
                .append("file", file.getAbsolutePath())
                .toString();
    }

    @Override
    public long uncompressedContentLength() {
        return file.length();
    }

    @Override
    public boolean canBeRetried() {
        return true;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof final FileOutput that)) {
            return false;
        }
        return new EqualsBuilder()
                .append(file, that.file)
                .append(contentType, that.contentType)
                .isEquals();
    }

    @Override
    public int hashCode() {
        return new HashCodeBuilder(17, 37).append(file).append(contentType).toHashCode();
    }
}
