package ch.ubique.backend.storage.annotations;

import org.slf4j.event.Level;

import java.io.IOException;

public interface TestDeleteStorage {

    @DeleteMapping(path = "files/{fileId}.png")
    boolean deleteFile(@StoragePathVariable String fileId) throws IOException;

    @DeleteMapping(path = "files/{folderId}", recursive = true, logLevel = Level.ERROR)
    void deleteFolder(@StoragePathVariable int folderId) throws IOException;
}
