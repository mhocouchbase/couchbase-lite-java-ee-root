//
// Copyright (c) 2020, 2018 Couchbase, Inc.  All rights reserved.
//
// Licensed under the Couchbase License Agreement (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
// https://info.couchbase.com/rs/302-GJY-034/images/2017-10-30_License_Agreement.pdf
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
package com.couchbase.lite;

import android.support.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.junit.After;
import org.junit.Test;

import com.couchbase.lite.internal.CouchbaseLiteInternal;
import com.couchbase.lite.internal.core.C4Key;
import com.couchbase.lite.internal.utils.FileUtils;
import com.couchbase.lite.internal.utils.IOUtils;
import com.couchbase.lite.internal.utils.TestUtils;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;


public class DatabaseEncryptionTest extends BaseTest {
    private static final String TEST_PWD = "sekrit";
    private static final Map<String, EncryptionKey> KEYS = new HashMap<>();


    private Database encryptionTestDb;

    @After
    public final void tearDownDatabaseEncryptionTest() { deleteDb(encryptionTestDb); }

    @Test
    public void testCreateConfiguration() {
        // Default:
        DatabaseConfiguration config = new DatabaseConfiguration();
        assertNotNull(config.getDirectory());
        assertFalse(config.getDirectory().isEmpty());
        assertNull(config.getEncryptionKey());

        // Custom
        config = new DatabaseConfiguration();
        String dbDir = getScratchDirectoryPath(getUniqueName("create-config-dir"));
        config.setDirectory(dbDir);
        EncryptionKey key = getEncryptionKey(TEST_PWD);
        config.setEncryptionKey(key);
        assertEquals(dbDir, config.getDirectory());
        assertEquals(key, config.getEncryptionKey());
    }

    @Test
    public void testGetSetConfiguration() throws CouchbaseLiteException {
        final EncryptionKey key = getEncryptionKey(TEST_PWD);
        final String path = getScratchDirectoryPath(getUniqueName("get-set-config-dir"));

        final DatabaseConfiguration config = new DatabaseConfiguration().setEncryptionKey(key).setDirectory(path);
        createTestDbWithConfig(config);

        final DatabaseConfiguration dbConfig = encryptionTestDb.getConfig();
        assertNotNull(dbConfig);
        assertNotSame(dbConfig, config);
        assertEquals(key, dbConfig.getEncryptionKey());
        assertEquals(path, dbConfig.getDirectory());
    }

    @Test
    public void testUnEncryptedDatabase() throws CouchbaseLiteException {
        // Create unencrypted database:
        createEncryptedTestDbWithPassword(null);

        createDocInTestDb();

        // Try to reopen with password (fails):
        TestUtils.assertThrowsCBL(
            CBLError.Domain.CBLITE,
            CBLError.Code.NOT_A_DATABASE_FILE,
            () -> reopenTestDbWithPassword("foo"));

        // Reopen with no password:
        reopenTestDbWithPassword(null);
        assertNotNull(encryptionTestDb);
        assertEquals(1, encryptionTestDb.getCount());
    }

    @Test
    public void testEncryptedDatabase() throws CouchbaseLiteException {
        // Create encrypted database:
        createEncryptedTestDbWithPassword(TEST_PWD);

        createDocInTestDb();

        // Reopen without password (fails):
        TestUtils.assertThrowsCBL(
            CBLError.Domain.CBLITE,
            CBLError.Code.NOT_A_DATABASE_FILE,
            () -> reopenTestDbWithPassword(null));

        // Reopen with wrong password (fails):
        TestUtils.assertThrowsCBL(
            CBLError.Domain.CBLITE,
            CBLError.Code.NOT_A_DATABASE_FILE,
            () -> reopenTestDbWithPassword("wrong"));

        // Reopen with correct password:
        reopenTestDbWithPassword(TEST_PWD);
        assertNotNull(encryptionTestDb);
        assertEquals(1, encryptionTestDb.getCount());
    }

    @Test
    public void testDeleteEncryptedDatabase() throws CouchbaseLiteException {
        // Create encrypted database:
        createEncryptedTestDbWithPassword(TEST_PWD);

        // Create a new database with the same name but no password
        // This deletes the original
        encryptionTestDb = recreateDb(encryptionTestDb);
        assertNotNull(encryptionTestDb);
        assertEquals(0, encryptionTestDb.getCount());

        // Make sure it doesn't need a password now:
        reopenTestDbWithPassword(null);
        assertNotNull(encryptionTestDb);
        assertEquals(0, encryptionTestDb.getCount());

        // Make sure old password doesn't work:
        TestUtils.assertThrowsCBL(
            CBLError.Domain.CBLITE,
            CBLError.Code.NOT_A_DATABASE_FILE,
            () -> reopenTestDbWithPassword(TEST_PWD));
    }

    @Test
    public void testCompactEncryptedDatabase() throws CouchbaseLiteException {
        // Create encrypted database:
        createEncryptedTestDbWithPassword(TEST_PWD);

        // Create a doc and then update it:
        MutableDocument mDoc = createDocInTestDb().toMutable();
        mDoc.setValue("answer", 84);
        encryptionTestDb.save(mDoc);

        mDoc = encryptionTestDb.getDocument(mDoc.getId()).toMutable();

        // Compact:
        encryptionTestDb.performMaintenance(MaintenanceType.COMPACT);

        // Update the document again:
        mDoc.setValue("answer", 85);
        encryptionTestDb.save(mDoc);
        encryptionTestDb.getDocument(mDoc.getId());

        // Close and re-open:
        reopenTestDbWithPassword(TEST_PWD);
        assertNotNull(encryptionTestDb);
        assertEquals(1, encryptionTestDb.getCount());
    }

    @Test
    public void testCopyEncryptedDatabase() throws CouchbaseLiteException {
        // Create encrypted database:
        final DatabaseConfiguration config = new DatabaseConfiguration().setEncryptionKey(getEncryptionKey(TEST_PWD));
        encryptionTestDb = createDb("copy-encrypted-1-db", config);
        assertNotNull(encryptionTestDb);

        final File dbDir = encryptionTestDb.getFilePath();
        assertNotNull(dbDir);

        // add a doc
        createDocInTestDb();

        encryptionTestDb.close();

        // Copy
        final String newName = getUniqueName("copy-encrypted-2-db");
        Database.copy(dbDir, newName, config);
        Database newDb = null;
        try {
            assertTrue(Database.exists(newName, dbDir.getParentFile()));

            newDb = new Database(newName, config);
            assertNotNull(newDb);
            assertEquals(1, newDb.getCount());
        }
        finally {
            deleteDb(newDb);
        }
    }

    @Test
    public void testDefaultKeyGeneration() throws CouchbaseLiteException {
        final String pwd = "You rode upon a steamer ~ 2 the violence of the sun!!";

        createEncryptedTestDbWithPassword(pwd);

        final Document doc = createDocInTestDb();

        DatabaseConfiguration config
            = new DatabaseConfiguration().setEncryptionKey(new EncryptionKey(C4Key.getPbkdf2Key(pwd)));

        encryptionTestDb = reopenDb(encryptionTestDb, config);
        Document doc2 = encryptionTestDb.getDocument(doc.getId());
        assertNotNull(doc2);
        for (String key: doc.getKeys()) { assertEquals(doc.getString(key), doc2.getString(key)); }
    }

    @Test
    public void testCoreKeyGeneration() throws CouchbaseLiteException {
        final String pwd = "You rode upon a steamer 2 the violence of the sun";
        final DatabaseConfiguration config
            = new DatabaseConfiguration().setEncryptionKey(new EncryptionKey(C4Key.getCoreKey(pwd)));

        encryptionTestDb = createDb("key-gen-db", config);

        final Document doc = createDocInTestDb();


        encryptionTestDb = reopenDb(encryptionTestDb, config);
        Document doc2 = encryptionTestDb.getDocument(doc.getId());
        assertNotNull(doc2);
        for (String key: doc.getKeys()) { assertEquals(doc.getString(key), doc2.getString(key)); }
    }

    @Test
    public void testMultipleDatabases() throws CouchbaseLiteException {
        // Create encrypted database:
        createEncryptedTestDbWithPassword(TEST_PWD);

        // Get another instance of the database:
        final Database db = duplicateDb(encryptionTestDb, encryptionTestDb.getConfig());
        try { assertNotNull(db); }
        finally { closeDb(db); }

        // Try rekey:
        EncryptionKey newKey = getEncryptionKey("foo");
        encryptionTestDb.changeEncryptionKey(newKey);

        reopenTestDbWithPassword("foo");
    }

    @Test
    public void testCreateEncryptedBlob() throws CouchbaseLiteException, IOException {
        createAndVerifyEncryptedBlob(TEST_PWD);
    }

    @Test
    public void testAddKey() throws CouchbaseLiteException, IOException { rekeyAndVerifyDb(null, TEST_PWD); }

    @Test
    public void testReKey() throws CouchbaseLiteException, IOException { rekeyAndVerifyDb(TEST_PWD, "foo"); }

    // https://github.com/couchbase/couchbase-lite-android/issues/1720
    @Test
    public void testRemoveKey() throws CouchbaseLiteException, IOException { rekeyAndVerifyDb(TEST_PWD, null); }

    // Verify that the 2.8.0 bug fix works on an encrypted DB
    // There are four more test for this in DatabaseTest

    @Test
    public void testReOpenExistingEncrypted2Dot8DotOhDb() throws CouchbaseLiteException {
        final String dbName = getUniqueName("test-db");
        final String twoDot8DotOhDirPath = CouchbaseLiteInternal.getRootDir() + "/.couchbase";

        Database db = null;
        try {
            // Configure an encrypted db
            final DatabaseConfiguration config1 = new DatabaseConfiguration();
            config1.setEncryptionKey(new EncryptionKey("rub-a-dub-dub"));

            // Copy the cnfig
            final DatabaseConfiguration config2 = new DatabaseConfiguration(config1);

            // Create a database in the misguided 2.8.0 directory
            config1.setDirectory(twoDot8DotOhDirPath);
            db = new Database(dbName, config1);
            final MutableDocument mDoc = new MutableDocument();
            mDoc.setString("foo", "bar");
            db.save(mDoc);
            db.close();
            db = null;

            // This should open the database created above
            // despite the fact that the duplicate config points at the default directory.
            db = new Database(dbName, config2);
            assertEquals(1L, db.getCount());
            final Document doc = db.getDocument(mDoc.getId());
            assertEquals("bar", doc.getString("foo"));
        }
        finally {
            try {
                FileUtils.eraseFileOrDir(twoDot8DotOhDirPath);
                if (db != null) { db.delete(); }
            }
            catch (Exception ignore) { }
        }
    }

    // This is purely a time optimization.
    // Creating keys is pretty expensive and caching them makes the tests run much faster.
    // If you use this in a test, be sure that you are still testing what you intend to test!
    private EncryptionKey getEncryptionKey(String password) {
        EncryptionKey key = KEYS.get(password);
        if (key == null) {
            key = new EncryptionKey(password);
            KEYS.put(password, key);
        }
        return key;
    }

    private void createEncryptedTestDbWithPassword(String password) throws CouchbaseLiteException {
        final DatabaseConfiguration config = new DatabaseConfiguration();
        if (password != null) { config.setEncryptionKey(getEncryptionKey(password)); }
        createTestDbWithConfig(config);
    }

    private void createTestDbWithConfig(DatabaseConfiguration config) throws CouchbaseLiteException {
        encryptionTestDb = createDb("test-w-config", config);
        assertNotNull(encryptionTestDb);
    }

    private void reopenTestDbWithPassword(@Nullable String password) throws CouchbaseLiteException {
        DatabaseConfiguration config = new DatabaseConfiguration();
        if (password != null) { config.setEncryptionKey(getEncryptionKey(password)); }
        encryptionTestDb = reopenDb(encryptionTestDb, config);
        assertNotNull(encryptionTestDb);
    }

    private void rekeyAndVerifyDb(String oldPass, String newPass) throws CouchbaseLiteException, IOException {
        // First run the encrypted blobs test to populate the database:
        final String docId = createAndVerifyEncryptedBlob(oldPass);

        // Create some documents:
        encryptionTestDb.inBatch(() -> {
            for (int i = 0; i < 100; i++) {
                Map<String, Object> map = new HashMap<>();
                map.put("seq", i);
                encryptionTestDb.save(new MutableDocument(null, map));
            }
        });

        // Rekey:
        encryptionTestDb.changeEncryptionKey((newPass == null) ? null : getEncryptionKey(newPass));

        // Close & reopen with the new key:
        reopenTestDbWithPassword(newPass);
        assertNotNull(encryptionTestDb);

        // Check the document and its attachment:
        Document doc = encryptionTestDb.getDocument(docId);
        assertNotNull(doc);
        final Blob blob = doc.getBlob("blob");
        assertNotNull(blob);
        final byte[] body = blob.getContent();
        assertNotNull(body);
        assertEquals(BLOB_CONTENT, new String(body));

        // Query documents:
        Expression SEQ = Expression.property("seq");
        Query query = QueryBuilder
            .select(SelectResult.expression(SEQ))
            .from(DataSource.database(encryptionTestDb))
            .where(SEQ.notNullOrMissing())
            .orderBy(Ordering.expression(SEQ));
        try (ResultSet rs = query.execute()) {
            assertNotNull(rs);
            int i = 0;
            for (Result r: rs) { assertEquals(i++, r.getInt(0)); }
        }
    }

    private String createAndVerifyEncryptedBlob(String password) throws CouchbaseLiteException, IOException {
        final String docId = getUniqueName("create-encrypted-blob-doc");

        // Create database with the password:
        createEncryptedTestDbWithPassword(password);
        assertNotNull(encryptionTestDb);

        // Save a doc with a blob:
        final byte[] body = BLOB_CONTENT.getBytes(StandardCharsets.UTF_8);

        MutableDocument mDoc = new MutableDocument(docId);
        mDoc.setBlob("blob", new Blob("text/plain", body));
        encryptionTestDb.save(mDoc);

        Document doc = encryptionTestDb.getDocument(docId);
        assertEquals(docId, doc.getId());

        // Read content from the raw blob file:
        final Blob blob = doc.getBlob("blob");
        assertNotNull(blob);
        assertNotNull(blob.digest());
        assertArrayEquals(body, blob.getContent());

        File file = new File(encryptionTestDb.getPath()
            + "/Attachments/" + blob.digest().substring(5).replaceAll("/", "_") + ".blob");
        assertTrue(file.exists());

        byte[] raw = IOUtils.toByteArray(file);
        assertNotNull(raw);
        if (password == null) { assertArrayEquals(raw, body); }
        else { assertFalse(Arrays.equals(raw, body)); }

        return docId;
    }

    private Document createDocInTestDb() throws CouchbaseLiteException {
        final Map<String, Object> map = new HashMap<>();
        map.put("guitar", "Eric");
        map.put("bass", "Jack");
        map.put("drums", "Ginger");

        final MutableDocument mDoc = new MutableDocument(null, map);
        encryptionTestDb.save(mDoc);

        final Document doc = encryptionTestDb.getDocument(mDoc.getId());
        assertNotNull(doc);

        return doc;
    }
}
