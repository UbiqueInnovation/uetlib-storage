package ch.ubique.uetlib.storage.annotations;

import java.io.IOException;
import org.slf4j.event.Level;

public interface TestExistsStorage {

    @ExistsMapping(path = "files/{fileName}.png")
    boolean existsFile(@StoragePathVariable String fileName) throws IOException;

    @ExistsMapping(path = "files/{fileNamePrefix}", logLevel = Level.ERROR)
    boolean existsFilePrefix(@StoragePathVariable int fileNamePrefix) throws IOException;
}
