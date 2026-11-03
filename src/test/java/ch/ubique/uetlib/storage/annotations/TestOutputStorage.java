package ch.ubique.uetlib.storage.annotations;

import ch.ubique.uetlib.storage.OutputMetaData;

import jakarta.validation.constraints.NotBlank;

import org.slf4j.event.Level;

import java.io.IOException;
import java.util.Map;

public interface TestOutputStorage {

    @OutputMapping(path = "files/{fileId}.png", contentType = "image/png", logLevel = Level.WARN)
    void saveImage(@StorageObject byte[] data, @StoragePathVariable @NotBlank String fileId)
            throws IOException;

    @OutputMapping(
            path = "files/{jsonId}.json",
            contentType = "application/json",
            documentedClass = TestUser.class,
            logLevel = Level.TRACE)
    void saveJson(
            @StorageObject TestUser json,
            @StoragePathVariable @NotBlank String jsonId,
            OutputMetaData outputMetaData)
            throws IOException;

    @OutputMapping(
            path = "files/maps/{mapId}.json",
            contentType = "application/json",
            documentedClass = TestUser.class,
            logLevel = Level.TRACE)
    void saveGenericMap(
            @StorageObject Map<Integer, String> map,
            @StoragePathVariable @NotBlank String mapId,
            OutputMetaData outputMetaData)
            throws IOException;
}
