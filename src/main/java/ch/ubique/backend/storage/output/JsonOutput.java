package ch.ubique.backend.storage.output;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pivovarit.function.exception.WrappedException;

import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

public final class JsonOutput<T> extends ByteOutput implements Output {

    private final T value;
    private final ObjectMapper objectMapper;

    public JsonOutput(final T value) {
        this(value, new ObjectMapper());
    }

    public JsonOutput(final T value, final ObjectMapper objectMapper) {
        super(encode(value, objectMapper), "application/json");
        this.value = value;
        this.objectMapper = objectMapper;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this, ToStringStyle.SHORT_PREFIX_STYLE)
                .append("value", value)
                .append("objectMapper", objectMapper)
                .toString();
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof final JsonOutput<?> that)) {
            return false;
        }
        return new EqualsBuilder()
                .append(value, that.value)
                .append(objectMapper, that.objectMapper)
                .isEquals();
    }

    @Override
    public int hashCode() {
        return new HashCodeBuilder(17, 37).append(value).append(objectMapper).toHashCode();
    }

    private static byte[] encode(final Object value, final ObjectMapper objectMapper) {
        try {
            return objectMapper.writeValueAsBytes(value);
        } catch (final JsonProcessingException e) {
            throw new WrappedException(e);
        }
    }
}
