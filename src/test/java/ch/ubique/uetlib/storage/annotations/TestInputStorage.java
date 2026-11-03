package ch.ubique.uetlib.storage.annotations;

import org.slf4j.event.Level;

import java.io.IOException;

public interface TestInputStorage {

    @InputMapping(
            path = "files/{jsonId}.json",
            contentType = "application/json",
            logLevel = Level.DEBUG)
    TestUser getJson(@StoragePathVariable final int jsonId) throws IOException;

    @InputMapping(path = "files/{fileId}.png", contentType = "image/png", logLevel = Level.INFO)
    byte[] getImage(@StoragePathVariable final int fileId) throws IOException;

    @InputMapping(
            path = "files/{fileId}.png",
            doUncompressIfNeeded = false,
            contentType = "image/png")
    byte[] getImageCompressed(@StoragePathVariable final int fileId) throws IOException;
}
