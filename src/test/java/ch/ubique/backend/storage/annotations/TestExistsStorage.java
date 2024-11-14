package ch.ubique.backend.storage.annotations;

import org.slf4j.event.Level;

import java.io.IOException;

public interface TestExistsStorage {

    @ExistsMapping(path = "files/{fileId}.png")
    boolean existsFile(@StoragePathVariable String fileId) throws IOException;

    @ExistsMapping(path = "files/{folderId}", logLevel = Level.ERROR)
    boolean existsFolder(@StoragePathVariable int folderId) throws IOException;
}
