package com.couchbase.lite.api;

import com.couchbase.lite.BaseTest;
import com.couchbase.lite.CouchbaseLiteException;
import com.couchbase.lite.Database;
import com.couchbase.lite.DatabaseConfiguration;
import com.couchbase.lite.EncryptionKey;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class EEDatabaseAPITest extends BaseTest {
    @Before
    public void setUp() throws Exception {
        super.setUp();
    }

    @After
    public void tearDown() { super.tearDown(); }

    @Test
    public void testDatabaseEncryption() throws CouchbaseLiteException {
        // # tag::database-encryption[]
        DatabaseConfiguration config = new DatabaseConfiguration();
        config.setEncryptionKey(new EncryptionKey("PASSWORD"));
        Database database = new Database("mydb", config);
        // # end::database-encryption[]

        database.delete();
    }
}
