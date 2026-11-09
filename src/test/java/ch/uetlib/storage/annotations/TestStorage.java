package ch.uetlib.storage.annotations;

public interface TestStorage
        extends TestOutputStorage,
                TestInputStorage,
                TestListStorage,
                TestDeleteStorage,
                TestExistsStorage {}
