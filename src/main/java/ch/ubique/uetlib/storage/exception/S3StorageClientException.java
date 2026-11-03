package ch.ubique.uetlib.storage.exception;

import software.amazon.awssdk.services.s3.model.S3Error;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

public class S3StorageClientException extends Exception {

    private final Collection<String> codes;

    public S3StorageClientException(final S3Error s3Error) {
        super(s3Error.message());
        this.codes = Set.of(s3Error.code());
    }

    public S3StorageClientException(final Collection<S3Error> errors) {
        super(String.join("; ", errors.stream().map(S3Error::message).toList()));
        this.codes = errors.stream().map(S3Error::code).collect(Collectors.toSet());
    }

    public Collection<String> getCodes() {
        return codes;
    }
}
