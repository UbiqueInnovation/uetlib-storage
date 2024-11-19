package ch.ubique.backend.storage.annotations;

import ch.ubique.backend.storage.OutputMetaData;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.slf4j.event.Level;

import java.io.IOException;

public interface TestOutputStorage {

    @OutputMapping(path = "files/{fileId}.png", contentType = "image/png", logLevel = Level.WARN)
    void saveImage(
            @StorageObject @NotNull byte[] data, @StoragePathVariable @NotBlank String fileId)
            throws IOException;

    @OutputMapping(
            path = "files/{jsonId}.json",
            contentType = "application/json",
            documentedClass = TestUser.class,
            logLevel = Level.TRACE)
    void saveJson(
            @StorageObject TestUser json,
            @StoragePathVariable @NotBlank String jsonId,
            @NotNull OutputMetaData outputMetaData)
            throws IOException;
}
