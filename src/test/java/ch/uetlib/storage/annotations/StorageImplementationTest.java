package ch.uetlib.storage.annotations;

import static ch.uetlib.test.assertion.AssertionUtils.assertThrowsWithMessage;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import static java.util.Objects.requireNonNull;

import ch.uetlib.storage.OutputMetaData;
import ch.uetlib.storage.StorageClient;
import ch.uetlib.storage.builder.StorageBuilder;
import ch.uetlib.storage.clients.FileSystemStorageClient;
import ch.uetlib.storage.output.ByteOutput;
import ch.uetlib.storage.output.GZipOutput;
import ch.uetlib.storage.output.JsonOutput;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.*;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@SuppressWarnings("unused")
class StorageImplementationTest {

    private final ObjectMapper objectMapper;
    private final StorageClient storageClient;
    private final TestStorage testStorage;

    private final TestStorageReferenceImplementation referenceImplementation;

    private final byte[] png2;

    StorageImplementationTest() throws IOException {
        objectMapper =
                JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
        storageClient =
                spy(new FileSystemStorageClient(Files.createTempDirectory(null).toString()));
        testStorage =
                StorageBuilder.builder(TestStorage.class)
                        .storageClient(storageClient)
                        .classLoader(getClass().getClassLoader())
                        .objectMapper(objectMapper)
                        .basePath("storage")
                        .build();
        referenceImplementation =
                new TestStorageReferenceImplementation(storageClient, objectMapper);
        png2 =
                requireNonNull(
                                getClass()
                                        .getClassLoader()
                                        .getResourceAsStream("storage/files/2.png"))
                        .readAllBytes();
    }

    @BeforeEach
    void beforeEach() throws IOException {
        MockitoAnnotations.openMocks(this);
        storageClient.delete("", true);
        storageClient.write(
                new JsonOutput<>(new TestUser(17, "Professor", "Jaffe Presley")),
                "storage/files/17.json");
        storageClient.write(new ByteOutput(png2), "storage/files/2.png");
        storageClient.write(new ByteOutput(png2), "storage/files/61.png");
        storageClient.write(new ByteOutput(png2), "storage/files/5/54.png");
        storageClient.write(new ByteOutput(png2), "storage/files/5/42.png");
    }

    @Test
    void testInputStorage() throws IOException {
        assertNotNull(testStorage);
        assertNotNull(referenceImplementation);

        final var testUser = testStorage.getJson(17);
        assertEquals(new TestUser(17, "Professor", "Jaffe Presley"), testUser);
        verify(storageClient).read("storage/files/17.json", true);

        final var imageCompressed = testStorage.getImageCompressed(2);
        assertArrayEquals(png2, imageCompressed);
        verify(storageClient).read("storage/files/2.png", false);

        final var image = testStorage.getImage(2);
        assertArrayEquals(png2, image);
        verify(storageClient).read("storage/files/2.png", true);
    }

    @Test
    void testOutputStorage() throws IOException {
        assertNotNull(testStorage);
        assertNotNull(referenceImplementation);

        final var testUser = new TestUser(32, "Student", "Seong-Hyeon Lebogang");
        testStorage.saveJson(testUser, "32", null);
        verify(storageClient)
                .write(
                        new GZipOutput(new JsonOutput<>(testUser, objectMapper)),
                        "storage/files/32.json",
                        null);
        assertEquals(
                testUser,
                objectMapper.readValue(
                        storageClient.read("storage/files/32.json", true), TestUser.class));

        final var outputMetaData =
                new OutputMetaData(Duration.ofSeconds(48654), Duration.ofMillis(87623))
                        .backoff(Duration.ofHours(2))
                        .nextRefresh(Instant.now().plusMillis(629235637873L));
        testStorage.saveJson(testUser, "32", outputMetaData);
        verify(storageClient)
                .write(
                        new GZipOutput(new JsonOutput<>(testUser, objectMapper)),
                        "storage/files/32.json",
                        outputMetaData);
        assertEquals(
                testUser,
                objectMapper.readValue(
                        storageClient.read("storage/files/32.json", true), TestUser.class));

        testStorage.saveImage(png2, "8");
        verify(storageClient)
                .write(
                        new GZipOutput(new ByteOutput(png2, "image/png")),
                        "storage/files/8.png",
                        null);
    }

    @Test
    void testDeleteStorage() throws IOException {
        final var deleted62 = testStorage.deleteFile("62");
        verify(storageClient).delete("storage/files/62.png", false);
        assertFalse(deleted62);
        assertEquals(
                Set.of(
                        "storage/files/2.png",
                        "storage/files/5/",
                        "storage/files/17.json",
                        "storage/files/61.png"),
                storageClient.list("storage/files/"));

        final var deleted61 = testStorage.deleteFile("61");
        verify(storageClient).delete("storage/files/61.png", false);
        assertTrue(deleted61);
        assertFalse(
                storageClient
                        .list("storage/files/")
                        .containsAll(
                                Set.of(
                                        "storage/files/2.png",
                                        "storage/files/17.json",
                                        "storage/files/61.png")));
        assertEquals(
                Set.of("storage/files/2.png", "storage/files/5/", "storage/files/17.json"),
                storageClient.list("storage/files/"));

        assertEquals(
                storageClient.list("storage/files/5/"),
                Set.of("storage/files/5/54.png", "storage/files/5/42.png"));
        assertDoesNotThrow(() -> testStorage.deleteFolder(4));
        verify(storageClient).delete("storage/files/4", true);
        assertEquals(
                Set.of("storage/files/5/54.png", "storage/files/5/42.png"),
                storageClient.list("storage/files/5/"));

        assertDoesNotThrow(() -> testStorage.deleteFolder(5));
        verify(storageClient).delete("storage/files/5", true);
        assertEquals(Set.of(), storageClient.list("storage/files/5/"));
        assertFalse(storageClient.list("storage/files/").contains("storage/files/5/"));
    }

    @Test
    void testListStorage() throws IOException {
        assertEquals(
                Set.of(
                        "storage/files/2.png",
                        "storage/files/5/",
                        "storage/files/17.json",
                        "storage/files/61.png"),
                testStorage.listFiles("files"));
        verify(storageClient).list("storage/files/", false);

        assertEquals(
                Set.of(
                        "storage/files/2.png",
                        "storage/files/5/54.png",
                        "storage/files/5/42.png",
                        "storage/files/17.json",
                        "storage/files/61.png"),
                testStorage.listFilesRecursive());
        verify(storageClient).list("storage/files/", true);
    }

    @Test
    void testExistsStorage() throws IOException {
        assertTrue(testStorage.existsFile("2"));
        verify(storageClient).exists("storage/files/2.png");

        assertFalse(testStorage.existsFile("5"));
        verify(storageClient).exists("storage/files/5.png");

        assertTrue(testStorage.existsFilePrefix(5));
        verify(storageClient).exists("storage/files/5");

        assertTrue(testStorage.existsFilePrefix(2));
        verify(storageClient).exists("storage/files/2");
    }

    @Test
    void testInvalidStorage() {
        assertThrowsWithMessage(
                IllegalArgumentException.class,
                () ->
                        StorageBuilder.builder(NonStorageMappingMethodStorage.class)
                                .storageClient(storageClient)
                                .classLoader(getClass().getClassLoader())
                                .objectMapper(objectMapper)
                                .basePath("storage")
                                .build(),
                "Storage interface NonStorageMappingMethodStorage is not valid: Method"
                        + " 'NonStorageMappingMethodStorage::mappingMethod' is not annotated by one of"
                        + " [@OutputMapping, @InputMapping, @DeleteMapping, @ListMapping,"
                        + " @ExistsMapping]");

        assertThrowsWithMessage(
                IllegalArgumentException.class,
                () ->
                        StorageBuilder.builder(OutputMappingMethodIllegalReturnTypeString.class)
                                .storageClient(storageClient)
                                .classLoader(getClass().getClassLoader())
                                .objectMapper(objectMapper)
                                .basePath("storage")
                                .build(),
                "Storage OutputMapping method"
                        + " OutputMappingMethodIllegalReturnTypeString::mappingMethod is not valid:"
                        + " @OutputMapping mapping method must return void");

        assertThrowsWithMessage(
                IllegalArgumentException.class,
                () ->
                        StorageBuilder.builder(InputMappingMethodIllegalReturnTypeVoid.class)
                                .storageClient(storageClient)
                                .classLoader(getClass().getClassLoader())
                                .objectMapper(objectMapper)
                                .basePath("storage")
                                .build(),
                "Storage InputMapping method InputMappingMethodIllegalReturnTypeVoid::mappingMethod"
                        + " is not valid: @InputMapping mapping method return type must not be one of"
                        + " [void]");

        assertThrowsWithMessage(
                IllegalArgumentException.class,
                () ->
                        StorageBuilder.builder(OutputMappingMethodNoStorageObject.class)
                                .storageClient(storageClient)
                                .classLoader(getClass().getClassLoader())
                                .objectMapper(objectMapper)
                                .basePath("storage")
                                .build(),
                "Storage OutputMapping method OutputMappingMethodNoStorageObject::mappingMethod is"
                        + " not valid: There must be exactly one parameter annotated with"
                        + " @StorageObject, found 0");

        assertThrowsWithMessage(
                IllegalArgumentException.class,
                () ->
                        StorageBuilder.builder(OutputMappingMethodIllegalReturnTypeString.class)
                                .storageClient(storageClient)
                                .classLoader(getClass().getClassLoader())
                                .objectMapper(objectMapper)
                                .basePath("storage")
                                .build(),
                "Storage OutputMapping method"
                        + " OutputMappingMethodIllegalReturnTypeString::mappingMethod is not valid:"
                        + " @OutputMapping mapping method must return void");

        assertThrowsWithMessage(
                IllegalArgumentException.class,
                () ->
                        StorageBuilder.builder(OutputMappingMethodTooManyStorageObject.class)
                                .storageClient(storageClient)
                                .classLoader(getClass().getClassLoader())
                                .objectMapper(objectMapper)
                                .basePath("storage")
                                .build(),
                "Storage OutputMapping method"
                        + " OutputMappingMethodTooManyStorageObject::mappingMethod is not valid: There"
                        + " must be exactly one parameter annotated with @StorageObject, found 2");

        assertThrowsWithMessage(
                IllegalArgumentException.class,
                () ->
                        StorageBuilder.builder(OutputMappingMethodIllegalParameterTypeObject.class)
                                .storageClient(storageClient)
                                .classLoader(getClass().getClassLoader())
                                .objectMapper(objectMapper)
                                .basePath("storage")
                                .build(),
                "Storage method OutputMappingMethodIllegalParameterTypeObject::mappingMethod is not"
                        + " valid: Method parameter 'parameter' must either be one of type"
                        + " '[OutputMetaData, ObjectMapper]' or be annotated with one of"
                        + " [@StorageObject, @StoragePathVariable]");

        assertThrowsWithMessage(
                IllegalArgumentException.class,
                () ->
                        StorageBuilder.builder(
                                        OutputMappingMethodIllegalParameterAnnotationTypeObject
                                                .class)
                                .storageClient(storageClient)
                                .classLoader(getClass().getClassLoader())
                                .objectMapper(objectMapper)
                                .basePath("storage")
                                .build(),
                "Storage OutputMapping method"
                        + " OutputMappingMethodIllegalParameterAnnotationTypeObject::mappingMethod is"
                        + " not valid: There must be exactly one parameter annotated with"
                        + " @StorageObject, found 0");

        assertThrowsWithMessage(
                IllegalArgumentException.class,
                () ->
                        StorageBuilder.builder(OutputMappingTooManyObjectMapper.class)
                                .storageClient(storageClient)
                                .classLoader(getClass().getClassLoader())
                                .objectMapper(objectMapper)
                                .basePath("storage")
                                .build(),
                "Storage method OutputMappingTooManyObjectMapper::mappingMethod is not valid: There"
                        + " must be at most one parameter of type @class"
                        + " tools.jackson.databind.ObjectMapper, found 2");

        assertThrowsWithMessage(
                IllegalArgumentException.class,
                () ->
                        StorageBuilder.builder(OutputMappingTooManyOutputMetaData.class)
                                .storageClient(storageClient)
                                .classLoader(getClass().getClassLoader())
                                .objectMapper(objectMapper)
                                .basePath("storage")
                                .build(),
                "Storage method OutputMappingTooManyOutputMetaData::mappingMethod is not valid:"
                        + " There must be at most one parameter of type @class"
                        + " ch.uetlib.storage.OutputMetaData, found 2");

        assertThrowsWithMessage(
                IllegalArgumentException.class,
                () ->
                        StorageBuilder.builder(ListMappingIllegalReturnTypeIterable.class)
                                .storageClient(storageClient)
                                .classLoader(getClass().getClassLoader())
                                .objectMapper(objectMapper)
                                .basePath("storage")
                                .build(),
                "Storage ListMapping method ListMappingIllegalReturnTypeIterable::mappingMethod is"
                        + " not valid: @ListMapping mapping method return type must be one of"
                        + " [Collection, List, Set, Stream]");

        assertThrowsWithMessage(
                IllegalArgumentException.class,
                () ->
                        StorageBuilder.builder(ListMappingIllegalReturnTypeHashSet.class)
                                .storageClient(storageClient)
                                .classLoader(getClass().getClassLoader())
                                .objectMapper(objectMapper)
                                .basePath("storage")
                                .build(),
                "Storage ListMapping method ListMappingIllegalReturnTypeHashSet::mappingMethod is"
                        + " not valid: @ListMapping mapping method return type must be one of"
                        + " [Collection, List, Set, Stream]");

        assertThrowsWithMessage(
                IllegalArgumentException.class,
                () ->
                        StorageBuilder.builder(ExistsMappingIllegalReturnTypeBoolean.class)
                                .storageClient(storageClient)
                                .classLoader(getClass().getClassLoader())
                                .objectMapper(objectMapper)
                                .basePath("storage")
                                .build(),
                "Storage ExistsMapping method ExistsMappingIllegalReturnTypeBoolean::mappingMethod"
                        + " is not valid: @ExistsMapping mapping method return type must be one of"
                        + " [boolean]");
    }

    interface NonStorageMappingMethodStorage {
        void mappingMethod();
    }

    interface OutputMappingMethodIllegalReturnTypeString {
        @OutputMapping
        String mappingMethod(@StorageObject String storageObject);
    }

    interface InputMappingMethodIllegalReturnTypeVoid {
        @InputMapping
        void mappingMethod();
    }

    interface OutputMappingMethodNoStorageObject {
        @OutputMapping
        String mappingMethod();
    }

    interface OutputMappingMethodTooManyStorageObject {
        @OutputMapping
        String mappingMethod(
                @StorageObject String storageObject1, @StorageObject String storageObject2);
    }

    interface OutputMappingMethodIllegalParameterTypeObject {
        @OutputMapping
        String mappingMethod(String parameter);
    }

    interface OutputMappingMethodIllegalParameterAnnotationTypeObject {
        @OutputMapping
        String mappingMethod(OutputMetaData parameter);
    }

    interface OutputMappingTooManyObjectMapper {
        @OutputMapping
        String mappingMethod(ObjectMapper objectMapper1, ObjectMapper objectMapper2);
    }

    interface OutputMappingTooManyOutputMetaData {
        @OutputMapping
        String mappingMethod(OutputMetaData outputMetaData1, OutputMetaData outputMetaData2);
    }

    interface DeleteMappingIllegalReturnTypeBoolean {
        @DeleteMapping
        Boolean mappingMethod();
    }

    interface ListMappingIllegalReturnTypeIterable {
        @ListMapping
        Iterable<Object> mappingMethod();
    }

    interface ListMappingIllegalReturnTypeHashSet {
        @ListMapping
        HashSet<Object> mappingMethod();
    }

    interface ExistsMappingIllegalReturnTypeBoolean {
        @ExistsMapping
        Boolean mappingMethod();
    }
}
