package ch.ubique.backend.storage.annotations;

import ch.ubique.backend.storage.OutputMetaData;

import org.slf4j.event.Level;

import java.io.IOException;

public interface TestOutputStorage {

    @OutputMapping(
            path = "files/{fileId}.png",
            contentType = "image/png",
            logLevel = Level.WARN)
    void saveImage(@StorageObject byte[] data, @StoragePathVariable final String fileId)
            throws IOException;

    @OutputMapping(
            path = "files/{jsonId}.json",
            contentType = "application/json",
            documentedClass = TestUser.class,
            logLevel = Level.TRACE)
    void saveJson(
            @StorageObject TestUser json,
            @StoragePathVariable final String jsonId,
            OutputMetaData outputMetaData)
            throws IOException;
}
