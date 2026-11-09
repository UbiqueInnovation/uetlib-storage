package ch.uetlib.storage.annotations;

import org.slf4j.event.Level;

import java.io.IOException;
import java.util.Collection;

public interface TestListStorage {

    @ListMapping(path = "{folderName}/")
    Collection<String> listFiles(@StoragePathVariable final String folderName) throws IOException;

    @ListMapping(path = "files/", recursive = true, logLevel = Level.INFO)
    Collection<String> listFilesRecursive() throws IOException;
}
