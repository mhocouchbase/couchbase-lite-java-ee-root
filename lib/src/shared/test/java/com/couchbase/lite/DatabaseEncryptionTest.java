//
// DatabaseEncryptionTest.java
//
// Copyright (c) 2018 Couchbase, Inc.  All rights reserved.
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

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.junit.After;
import org.junit.Test;

import com.couchbase.lite.internal.core.C4Key;
import com.couchbase.lite.utils.IOUtils;
import com.couchbase.lite.utils.Report;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;


public class DatabaseEncryptionTest extends BaseTest {
    private Database seekrit;

    @After
    public void tearDown() {
        if (seekrit != null) {
            try { seekrit.close(); }
            catch (CouchbaseLiteException e) {
                Report.log(LogLevel.ERROR, "Failed to close seekrit DB", e);
            }
        }

        // database exist, delete it
        if (Database.exists("seekrit", getDbDir())) {
            // sometimes, db is still in used, wait for a while. Maximum 3 sec
            for (int i = 0; i < 10; i++) {
                try {
                    Database.delete("seekrit", getDbDir());
                    break;
                }
                catch (CouchbaseLiteException e) {
                    if (e.getCode() != CBLError.Code.BUSY) {
                        Report.log(LogLevel.ERROR, "Failed to delete seekrit DB", e);
                        break;
                    }

                    try { Thread.sleep(300); }
                    catch (InterruptedException ignore) { }
                }
            }
        }

        super.tearDown();
    }

    @Test
    public void testCreateConfiguration() {
        // Default:
        DatabaseConfiguration config1 = new DatabaseConfiguration();
        config1.setDirectory("/tmp");
        assertNotNull(config1.getDirectory());
        assertTrue(config1.getDirectory().length() > 0);
        assertNull(config1.getEncryptionKey());

        // Custom
        EncryptionKey key = new EncryptionKey("key");
        DatabaseConfiguration config2 = new DatabaseConfiguration();
        config2.setDirectory("/tmp/mydb");
        config2.setEncryptionKey(key);
        assertEquals("/tmp/mydb", config2.getDirectory());
        assertEquals(key, config2.getEncryptionKey());
    }

    @Test
    public void testGetSetConfiguration() throws CouchbaseLiteException {
        DatabaseConfiguration config = new DatabaseConfiguration();
        config.setDirectory(this.db.getConfig().getDirectory());
        Database db = new Database("db", config);
        try {
            assertNotNull(db.getConfig());
            assertNotSame(db.getConfig(), config);
            assertEquals(db.getConfig().getDirectory(), config.getDirectory());
            assertEquals(db.getConfig().getEncryptionKey(), config.getEncryptionKey());
        }
        finally {
            db.delete();
        }
    }

    @Test
    public void testUnEncryptedDatabase() throws CouchbaseLiteException {
        // Create unencrypted database:
        seekrit = openSeekrit(null);
        assertNotNull(seekrit);

        Map<String, Object> map = new HashMap<>();
        map.put("answer", 42);
        MutableDocument doc = new MutableDocument(null, map);
        seekrit.save(doc);
        seekrit.close();
        seekrit = null;

        // Try to reopen with password (fails):
        try {
            openSeekrit("wrong");
            fail();
        }
        catch (CouchbaseLiteException e) {
            assertEquals(CBLError.Domain.CBLITE, e.getDomain());
            assertEquals(CBLError.Code.NOT_A_DATABSE_FILE, e.getCode());
            assertEquals("The provided encryption key was incorrect.", e.getMessage());
        }

        // Reopen with no password:
        seekrit = openSeekrit(null);
        assertNotNull(seekrit);
        assertEquals(1, seekrit.getCount());
    }

    @Test
    public void testEncryptedDatabase() throws CouchbaseLiteException {
        // Create encrypted database:
        seekrit = openSeekrit("letmein");
        assertNotNull(seekrit);

        Map<String, Object> map = new HashMap<>();
        map.put("answer", 42);
        MutableDocument doc = new MutableDocument(null, map);
        seekrit.save(doc);
        seekrit.close();
        seekrit = null;

        // Reopen without password (fails):
        try {
            openSeekrit(null);
            fail();
        }
        catch (CouchbaseLiteException e) {
            assertEquals(CBLError.Domain.CBLITE, e.getDomain());
            assertEquals(CBLError.Code.NOT_A_DATABSE_FILE, e.getCode());
            assertEquals("The provided encryption key was incorrect.", e.getMessage());
        }

        // Reopen with wrong password (fails):
        try {
            openSeekrit("wrong");
            fail();
        }
        catch (CouchbaseLiteException e) {
            assertEquals(CBLError.Domain.CBLITE, e.getDomain());
            assertEquals(CBLError.Code.NOT_A_DATABSE_FILE, e.getCode());
            assertEquals("The provided encryption key was incorrect.", e.getMessage());
        }

        // Reopen with correct password:
        seekrit = openSeekrit("letmein");
        assertNotNull(seekrit);
        assertEquals(1, seekrit.getCount());
    }

    @Test
    public void testDeleteEncryptedDatabase() throws CouchbaseLiteException {
        // Create encrypted database:
        seekrit = openSeekrit("letmein");
        assertNotNull(seekrit);

        // Delete database:
        seekrit.delete();

        // Re-create database:
        seekrit = openSeekrit(null);
        assertNotNull(seekrit);
        assertEquals(0, seekrit.getCount());
        seekrit.close();
        seekrit = null;

        // Make sure it doesn't need a password now:
        seekrit = openSeekrit(null);
        assertNotNull(seekrit);
        assertEquals(0, seekrit.getCount());
        seekrit.close();
        seekrit = null;

        // Make sure old password doesn't work:
        try {
            seekrit = openSeekrit("letmein");
            fail();
        }
        catch (CouchbaseLiteException e) {
            assertEquals(CBLError.Domain.CBLITE, e.getDomain());
            assertEquals(CBLError.Code.NOT_A_DATABSE_FILE, e.getCode());
            assertEquals("The provided encryption key was incorrect.", e.getMessage());
        }
    }

    @Test
    public void testCompactEncryptedDatabase() throws CouchbaseLiteException {
        // Create encrypted database:
        seekrit = openSeekrit("letmein");
        assertNotNull(seekrit);

        // Create a doc and then update it:
        Map<String, Object> map = new HashMap<>();
        map.put("answer", 42);
        MutableDocument doc = new MutableDocument(null, map);
        seekrit.save(doc);
        Document savedDoc = seekrit.getDocument(doc.getId());
        doc = savedDoc.toMutable();
        doc.setValue("answer", 84);
        seekrit.save(doc);
        savedDoc = seekrit.getDocument(doc.getId());

        // Compact:
        seekrit.compact();

        // Update the document again:
        doc = savedDoc.toMutable();
        doc.setValue("answer", 85);
        seekrit.save(doc);
        seekrit.getDocument(doc.getId());

        // Close and re-open:
        seekrit.close();
        seekrit = openSeekrit("letmein");
        assertNotNull(seekrit);
        assertEquals(1, seekrit.getCount());
    }

    @Test
    public void testCopyEncryptedDatabase() throws CouchbaseLiteException {
        final String dbName = "seekritDB";
        final String dbPass = "letmein";
        final File dbDir = getDbDir();

        // Create encrypted database:
        DatabaseConfiguration config = new DatabaseConfiguration();
        config.setDirectory(dbDir.getAbsolutePath());
        config.setEncryptionKey(new EncryptionKey(dbPass));
        Database db = new Database(dbName, config);
        assertNotNull(db);

        final String dbPath = db.getPath();

        // add a doc
        MutableDocument doc = new MutableDocument();
        doc.setString("foo", "bar");
        db.save(doc);

        db.close();

        final String nuName = "testDb2";

        // Make sure no an existing database at the new location:
        if (Database.exists(nuName, dbDir)) { Database.delete(nuName, dbDir); }

        // Copy
        Database.copy(new File(dbPath), nuName, config);
        assertTrue(Database.exists(nuName, dbDir));

        try {
            db = new Database(dbName, config);
            assertNotNull(db);
            assertEquals(1, db.getCount());
        }
        finally {
            db.close();
            Database.delete(nuName, dbDir);
        }
    }

    @Test
    public void testEncryptedBlobs() throws CouchbaseLiteException, IOException {
        testEncryptedBlobs("letmein");
    }

    @Test
    public void testLegacyKeyGeneration() throws CouchbaseLiteException {
        final String dbName = "Cream";
        final String pwd = "you rode upon a steamer to the violence of the sun";
        final String docId = "Disraeli Gears";

        DatabaseConfiguration config = new DatabaseConfiguration();
        config.setDirectory(this.getDbDir().getAbsolutePath());
        config.setEncryptionKey(new EncryptionKey(pwd));
        Database db = new Database(dbName, config);
        final MutableDocument mDoc = new MutableDocument(docId);
        mDoc.setString("guitar", "Eric");
        mDoc.setString("bass", "Jack");
        mDoc.setString("drums", "Ginger");
        db.save(mDoc);
        db.close();

        config = new DatabaseConfiguration();
        config.setDirectory(this.getDbDir().getAbsolutePath());
        config.setEncryptionKey(new EncryptionKey(C4Key.getPbkdf2Key(pwd)));
        db = new Database(dbName, config);
        Document doc2 = db.getDocument(docId);
        assertEquals("Eric", doc2.getString("guitar"));
        assertEquals("Jack", doc2.getString("bass"));
        assertEquals("Ginger", doc2.getString("drums"));
        db.delete();
    }

    @Test
    public void testCoreKeyGeneration() throws CouchbaseLiteException {
        final String dbName = "Cream";
        final String pwd = "you rode upon a steamer to the violence of the sun";
        final String docId = "Disraeli Gears";

        DatabaseConfiguration config = new DatabaseConfiguration();
        config.setDirectory(this.getDbDir().getAbsolutePath());
        config.setEncryptionKey(new EncryptionKey(C4Key.getCoreKey(pwd)));
        Database db = new Database(dbName, config);
        final MutableDocument mDoc = new MutableDocument(docId);
        mDoc.setString("guitar", "Eric");
        mDoc.setString("bass", "Jack");
        mDoc.setString("drums", "Ginger");
        db.save(mDoc);
        db.close();

        config = new DatabaseConfiguration();
        config.setDirectory(this.getDbDir().getAbsolutePath());
        config.setEncryptionKey(new EncryptionKey(C4Key.getCoreKey(pwd)));
        db = new Database(dbName, config);
        Document doc2 = db.getDocument(docId);
        assertEquals("Eric", doc2.getString("guitar"));
        assertEquals("Jack", doc2.getString("bass"));
        assertEquals("Ginger", doc2.getString("drums"));
        db.delete();
    }

    @Test
    public void testMultipleDatabases() throws CouchbaseLiteException {
        // Create encrypted database:
        seekrit = openSeekrit("seekrit");

        // Get another instance of the database:
        Database seekrit2 = openSeekrit("seekrit");
        assertNotNull(seekrit2);
        seekrit2.close();

        // Try rekey:
        EncryptionKey newKey = new EncryptionKey("foobar");
        seekrit.changeEncryptionKey(newKey);
    }

    @Test
    public void testAddKey() throws CouchbaseLiteException, IOException {
        rekeyUsingOldPassword(null, "letmein");
    }

    @Test
    public void testReKey() throws CouchbaseLiteException, IOException {
        rekeyUsingOldPassword("letmein", "letmeout");
    }

    @Test
    public void testRemoveKey() throws CouchbaseLiteException, IOException {
        rekeyUsingOldPassword("letmein", (String) null);
    }

    // https://github.com/couchbase/couchbase-lite-android/issues/1720
    @Test
    public void testRemoveKey2() throws CouchbaseLiteException, IOException {
        rekeyUsingOldPassword("letmein", (EncryptionKey) null);
    }

    private void rekeyUsingOldPassword(String oldPass, String newPass) throws CouchbaseLiteException, IOException {
        rekeyUsingOldPassword(oldPass, newPass != null ? new EncryptionKey(newPass) : null);
    }

    private void rekeyUsingOldPassword(String oldPass, EncryptionKey newKey)
        throws CouchbaseLiteException, IOException {
        // First run the encryped blobs test to populate the database:
        testEncryptedBlobs(oldPass);

        // Create some documents:
        seekrit.inBatch(() -> {
            for (int i = 0; i < 100; i++) {
                Map<String, Object> map = new HashMap<>();
                map.put("seq", i);
                MutableDocument doc = new MutableDocument(null, map);
                try { seekrit.save(doc); }
                catch (CouchbaseLiteException e) { fail(); }
            }
        });

        // Rekey:
        seekrit.changeEncryptionKey(newKey);

        // Close & reopen seekrit:
        seekrit.close();
        seekrit = null;

        // Reopen the database with the new key:
        Database seekrit2 = openSeekritByKey(newKey);
        assertNotNull(seekrit2);
        seekrit = seekrit2;

        // Check the document and its attachment:
        Document doc = seekrit.getDocument("att");
        Blob blob = doc.getBlob("blob");
        assertNotNull(blob.getContent());
        String content = new String(blob.getContent());
        assertEquals("This is a blob!", content);

        // Query documents:
        Expression SEQ = Expression.property("seq");
        Query query = QueryBuilder
            .select(SelectResult.expression(SEQ))
            .from(DataSource.database(seekrit))
            .where(SEQ.notNullOrMissing())
            .orderBy(Ordering.expression(SEQ));
        ResultSet rs = query.execute();
        assertNotNull(rs);
        int i = 0;
        for (Result r : rs) {
            assertEquals(i, r.getInt(0));
            i++;
        }
    }

    private Database openSeekritByKey(EncryptionKey key) throws CouchbaseLiteException {
        DatabaseConfiguration config = new DatabaseConfiguration();
        if (key != null) { config.setEncryptionKey(key); }
        config.setDirectory(this.getDbDir().getAbsolutePath());
        return new Database("seekrit", config);
    }

    private Database openSeekrit(String password) throws CouchbaseLiteException {
        DatabaseConfiguration config = new DatabaseConfiguration();
        if (password != null) { config.setEncryptionKey(new EncryptionKey(password)); }
        config.setDirectory(this.getDbDir().getAbsolutePath());
        return new Database("seekrit", config);
    }

    private void testEncryptedBlobs(String password) throws CouchbaseLiteException, IOException {
        // Create database with the password:
        seekrit = openSeekrit(password);
        assertNotNull(seekrit);

        // Save a doc with a blob:
        byte[] body = "This is a blob!".getBytes();
        MutableDocument mDoc = new MutableDocument("att");
        Blob blob = new Blob("text/plain", body);
        mDoc.setBlob("blob", blob);
        seekrit.save(mDoc);
        Document doc = seekrit.getDocument(mDoc.getId());

        // Read content from the raw blob file:
        blob = doc.getBlob("blob");
        assertNotNull(blob.digest());
        assertArrayEquals(body, blob.getContent());

        String filename = blob.digest().substring(5);
        filename = filename.replaceAll("/", "_");
        String path = String.format(Locale.ENGLISH, "%s/Attachments/%s.blob", seekrit.getPath(), filename);
        File file = new File(path);
        assertTrue(file.exists());
        byte[] raw = IOUtils.toByteArray(file);
        assertNotNull(raw);
        if (password != null) { assertFalse(Arrays.equals(raw, body)); }
        else { assertArrayEquals(raw, body); }
    }
}
