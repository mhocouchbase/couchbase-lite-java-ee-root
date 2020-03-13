//
// Local2LocalReplicatorTest.java
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

import android.support.annotation.NonNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.jetbrains.annotations.NotNull;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

import com.couchbase.lite.utils.Report;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;


public class Local2LocalReplicatorTest extends BaseEEReplicatorTest {

    @Test
    public void testPullRemovedDocWithFilter() throws CouchbaseLiteException {
        final Set<String> docIds = new HashSet<>();
        final Set<String> revIds = new HashSet<>();

        // Make a blob
        final Blob blob = new Blob("text/plain", BLOB_CONTENT.getBytes(StandardCharsets.UTF_8));

        // Create identical documents
        MutableDocument doc1 = new MutableDocument("doc1");
        doc1.setString("name", "pass");
        doc1.setString("species", "Tiger");
        doc1.setString("pattern", "Hobbes");
        doc1.setBlob("photo", blob);
        otherDB.save(doc1);

        MutableDocument doc2 = new MutableDocument("doc2");
        doc2.setString("name", "pass");
        doc2.setString("species", "Tiger");
        doc2.setString("pattern", "Striped");
        doc2.setBlob("photo", blob);
        otherDB.save(doc2);

        // Create replicator with pull filter
        ReplicatorConfiguration config = makeConfig(false, true, false);
        config.setPullFilter((document, flags) -> {
            docIds.add(document.getId());
            revIds.add(document.getRevisionID());

            return flags.isEmpty()
                || !flags.contains(DocumentFlag.DocumentFlagsAccessRemoved)
                || !document.getId().equals("doc1");
        });

        run(config, 0, null);

        // Check documents passed to the filter
        assertEquals(2, docIds.size());
        assertEquals(2, revIds.size());
        assertTrue(docIds.contains(doc1.getId()));
        assertTrue(revIds.contains(doc1.getRevisionID()));
        assertTrue(docIds.contains(doc2.getId()));
        assertTrue(revIds.contains(doc2.getRevisionID()));

        assertNotNull(baseTestDb.getDocument("doc1"));
        assertNotNull(baseTestDb.getDocument("doc2"));

        Map<String, Object> data = new HashMap<>();
        data.put("_removed", true);

        doc1.setData(data);
        otherDB.save(doc1);

        doc2.setData(data);
        otherDB.save(doc2);

        run(config, 0, null);

        // because doc1's removal should be rejected
        assertNotNull(baseTestDb.getDocument("doc1"));
        // because the next document's removal is not rejected
        assertNull(baseTestDb.getDocument("doc2"));
    }

    @Test
    public void testRestartPullFilter() throws CouchbaseLiteException {
        final Set<String> docIds = new HashSet<>();
        final Set<String> revIds = new HashSet<>();

        // Add a document to db database so that it can pull the deleted docs from
        MutableDocument doc0 = new MutableDocument("doc0");
        doc0.setString("species", "Cat");
        baseTestDb.save(doc0);

        // Create documents
        final Blob blob = new Blob("text/plain", BLOB_CONTENT.getBytes(StandardCharsets.UTF_8));

        MutableDocument doc1 = new MutableDocument("doc1");
        doc1.setString("species", "Tiger");
        doc1.setString("pattern", "Hobbes");
        doc1.setBlob("photo", blob);
        otherDB.save(doc1);
        docIds.add(doc1.getId());

        MutableDocument doc2 = new MutableDocument("doc2");
        doc2.setString("species", "Tiger");
        doc2.setString("pattern", "Striped");
        doc2.setBlob("photo", blob);
        otherDB.save(doc2);
        docIds.add(doc1.getId());

        // Create replicator with pull filter
        ReplicatorConfiguration config = makeConfig(false, true, false);
        config.setPullFilter((document, flags) -> {
            docIds.add(document.getId());
            revIds.add(document.getRevisionID());

            boolean isDeleted = flags.contains(DocumentFlag.DocumentFlagsDeleted);
            if (isDeleted) {
                assertEquals(document.getContent(), new Dictionary());
            }
            else {
                // Check content
                assertNotNull(document.getString("pattern"));
                assertEquals(document.getString("species"), "Tiger");

                // Check blob
                Blob photo = document.getBlob("photo");
                assertNotNull(photo);
                // Note: Cannot access content because there is no actual blob file saved on disk.
                // assertArrayEquals(photo.getContent(), blob.getContent());
            }

            // Reject deleting doc2
            return !(isDeleted && document.getId().equals("doc2"));
        });

        // Run the replicator
        run(config, 0, null);

        assertEquals(2, docIds.size());
        assertEquals(2, revIds.size());
        assertTrue(docIds.contains(doc1.getId()));
        assertTrue(revIds.contains(doc1.getRevisionID()));
        assertTrue(docIds.contains(doc2.getId()));
        assertTrue(revIds.contains(doc2.getRevisionID()));

        // Check replicated documents
        assertNotNull(baseTestDb.getDocument("doc1"));
        assertNotNull(baseTestDb.getDocument("doc2"));

        //this action will be rejected
        otherDB.delete(doc2);

        otherDB.delete(doc1);

        // Restart the replicator
        run(config, 0, null);

        // Check documents
        assertNull(baseTestDb.getDocument("doc1"));
        assertNotNull(baseTestDb.getDocument("doc2"));
    }

    @Test
    public void testRestartPushFilter() throws CouchbaseLiteException {
        final Set<String> docIds = new HashSet<>();
        final Set<String> revIds = new HashSet<>();

        // Create documents
        final Blob blob = new Blob("text/plain", BLOB_CONTENT.getBytes(StandardCharsets.UTF_8));

        MutableDocument doc1 = new MutableDocument("doc1");
        doc1.setString("species", "Tiger");
        doc1.setString("pattern", "Hobbes");
        doc1.setBlob("photo", blob);
        baseTestDb.save(doc1);

        MutableDocument doc2 = new MutableDocument("doc2");
        doc2.setString("species", "Tiger");
        doc2.setString("pattern", "Striped");
        doc2.setBlob("photo", blob);
        baseTestDb.save(doc2);

        MutableDocument doc3 = new MutableDocument("doc3");
        doc3.setString("species", "Tiger");
        doc3.setString("pattern", "None");
        doc3.setBlob("photo", blob);
        baseTestDb.save(doc3);

        // Create replicator with push filter
        ReplicatorConfiguration config = makeConfig(true, false, false);
        config.setPushFilter((document, flags) -> {
            String docId = document.getId();
            docIds.add(docId);
            revIds.add(document.getRevisionID());

            boolean isDeleted = flags.contains(DocumentFlag.DocumentFlagsDeleted);
            if (!isDeleted) {
                // Check content
                assertNotNull(document.getString("pattern"));
                assertEquals(document.getString("species"), "Tiger");

                // Check blob
                Blob photo = document.getBlob("photo");
                assertNotNull(photo);
                Assert.assertArrayEquals(photo.getContent(), blob.getContent());
            }
            else {
                assertEquals(document.getContent(), new Dictionary());
            }

            return !docId.equals("doc2") && !(docId.equals("doc3") && isDeleted);
        });

        // Create replicator"
        baseTestReplicator = new Replicator(config);

        // Run the replicator
        run(baseTestReplicator, 0, null);

        // Check documents passed to the filter
        assertEquals(3, docIds.size());
        assertEquals(3, revIds.size());
        assertTrue(docIds.contains(doc1.getId()));
        assertTrue(revIds.contains(doc1.getRevisionID()));
        assertTrue(docIds.contains(doc2.getId()));
        assertTrue(revIds.contains(doc2.getRevisionID()));
        assertTrue(docIds.contains(doc3.getId()));
        assertTrue(revIds.contains(doc3.getRevisionID()));

        // Check replicated documents
        assertNotNull(otherDB.getDocument("doc1"));
        assertNull(otherDB.getDocument("doc2"));
        assertNotNull(otherDB.getDocument("doc3"));

        // Delete doc1
        baseTestDb.delete(doc1);

        // Delete doc3 (Will be rejected by the filter)
        baseTestDb.delete(doc3);

        // Reset docIds
        docIds.clear();

        // Restart the replicator
        run(baseTestReplicator, 0, null);

        // Check docIds. Should be 2 (No doc2 updated).
        assertEquals(2, docIds.size());

        // Check documents
        assertNotNull(
            "Push to delete doc3 is rejected.",
            otherDB.getDocument("doc3"));
        assertNull(
            "Push to delete doc1 is passed.",
            otherDB.getDocument("doc1"));

        assertNull("Never received doc2", otherDB.getDocument("doc2"));
    }

    @Test
    public void testContinuousPullFilter() throws CouchbaseLiteException, InterruptedException { testPullFilter(true); }

    @Test
    public void testContinuousPushFilter() throws CouchbaseLiteException, InterruptedException { testPushFilter(true); }

    @Test
    public void testPullFilter() throws CouchbaseLiteException, InterruptedException { testPullFilter(false); }

    @Test
    public void testPushFilter() throws CouchbaseLiteException, InterruptedException { testPushFilter(false); }

    /*
     * https://github.com/couchbase/couchbase-lite-core/issues/383
     */
    @Test
    public void testEmptyPush() { run(makeConfig(true, false, false), 0, null); }

    @Test
    public void testPushDoc() throws CouchbaseLiteException {
        MutableDocument doc1 = new MutableDocument("doc1");
        doc1.setValue("name", "Tiger");
        saveDocInBaseTestDb(doc1);
        assertEquals(1, baseTestDb.getCount());

        MutableDocument doc2 = new MutableDocument("doc2");
        doc2.setValue("name", "Cat");
        otherDB.save(doc2);
        assertEquals(1, otherDB.getCount());

        run(makeConfig(true, false, false), 0, null);

        assertEquals(2, otherDB.getCount());
        Document doc2a = otherDB.getDocument("doc2");
        assertEquals("Cat", doc2a.getString("name"));
    }

    @Test
    public void testPushDocContinuous() throws CouchbaseLiteException, InterruptedException {
        Database anotherDB = createDb("push-cont-db");
        try {
            MutableDocument doc1 = new MutableDocument("doc1");
            doc1.setValue("name", "Tiger");
            anotherDB.save(doc1);
            assertEquals(1, anotherDB.getCount());

            MutableDocument doc2 = new MutableDocument("doc2");
            doc2.setValue("name", "Cat");
            otherDB.save(doc2);
            assertEquals(1, otherDB.getCount());

            ReplicatorConfiguration config = new ReplicatorConfiguration(anotherDB, new DatabaseEndpoint(otherDB));
            config.setReplicatorType(ReplicatorConfiguration.ReplicatorType.PUSH);
            config.setContinuous(true);
            Replicator repl = run(config, 0, null);

            assertEquals(2, otherDB.getCount());
            Document doc2a = otherDB.getDocument("doc2");
            assertEquals("Cat", doc2a.getString("name"));

            stopContinuousReplicator(repl);
        }
        finally {
            deleteDb(anotherDB);
        }
    }

    /*
     * For https://github.com/couchbase/couchbase-lite-core/issues/156
     */
    @Test
    public void testPullDoc() throws CouchbaseLiteException {
        MutableDocument doc1 = new MutableDocument("doc1");
        doc1.setValue("name", "Tiger");
        saveDocInBaseTestDb(doc1);
        assertEquals(1, baseTestDb.getCount());

        MutableDocument doc2 = new MutableDocument("doc2");
        doc2.setValue("name", "Cat");
        otherDB.save(doc2);
        assertEquals(1, otherDB.getCount());

        run(makeConfig(false, true, false), 0, null);

        assertEquals(2, baseTestDb.getCount());
        Document doc2a = baseTestDb.getDocument("doc2");
        assertEquals("Cat", doc2a.getString("name"));
    }

    /*
     * https://github.com/couchbase/couchbase-lite-core/issues/156
     */
    @Test
    public void testPullDocContinuous() throws CouchbaseLiteException, InterruptedException {
        Database anotherDB = createDb("pull-cont-db");
        try {
            MutableDocument doc1 = new MutableDocument("doc1");
            doc1.setValue("name", "Tiger");
            anotherDB.save(doc1);
            assertEquals(1, anotherDB.getCount());

            MutableDocument doc2 = new MutableDocument("doc2");
            doc2.setValue("name", "Cat");
            otherDB.save(doc2);
            assertEquals(1, otherDB.getCount());

            ReplicatorConfiguration config = new ReplicatorConfiguration(anotherDB, new DatabaseEndpoint(otherDB));
            config.setReplicatorType(ReplicatorConfiguration.ReplicatorType.PULL);
            config.setContinuous(true);
            run(config, 0, null);

            assertEquals(2, anotherDB.getCount());
            Document doc2a = anotherDB.getDocument("doc2");
            assertEquals("Cat", doc2a.getString("name"));

            stopContinuousReplicator(baseTestReplicator);
        }
        finally {
            deleteDb(anotherDB);
        }
    }

    @Test
    public void testPullConflict() throws CouchbaseLiteException {
        MutableDocument mDoc1 = new MutableDocument("doc");
        mDoc1.setValue("species", "Tiger");
        Document doc1 = saveDocInBaseTestDb(mDoc1);
        mDoc1 = doc1.toMutable();
        mDoc1.setValue("name", "Hobbes");
        doc1 = saveDocInBaseTestDb(mDoc1);
        assertEquals(1, baseTestDb.getCount());

        MutableDocument mDoc2 = new MutableDocument("doc");
        mDoc2.setValue("species", "Tiger");
        otherDB.save(mDoc2);
        Document doc2 = otherDB.getDocument(mDoc2.getId());
        mDoc2 = doc2.toMutable();
        mDoc2.setValue("pattern", "striped");
        otherDB.save(mDoc2);
        doc2 = otherDB.getDocument(mDoc2.getId());
        assertEquals(1, otherDB.getCount());

        run(makeConfig(false, true, false), 0, null);
        assertEquals(1, baseTestDb.getCount());

        String revId1 = doc1.getRevisionID();
        assertNotNull(revId1);
        String revId2 = doc2.getRevisionID();
        assertNotNull(revId2);
        Document doc1a = baseTestDb.getDocument("doc");
        if (revId1.compareTo(revId2) > 0) { assertEquals(doc1.toMap(), doc1a.toMap()); }
        else { assertEquals(doc2.toMap(), doc1a.toMap()); }
    }

    @Test
    public void testStopContinuousReplicator() throws InterruptedException {
        Replicator r = new Replicator(makeConfig(true, true, true, otherDB));

        final CountDownLatch latch = new CountDownLatch(1);
        ListenerToken token = r.addChangeListener(testSerialExecutor, change -> {
            if (change.getStatus().getActivityLevel() == Replicator.ActivityLevel.STOPPED) {
                Report.log(LogLevel.INFO, "*****Replicator stopped");
                latch.countDown();
            }
            else if (change.getStatus().getActivityLevel() == Replicator.ActivityLevel.CONNECTING
                || change.getStatus().getActivityLevel() == Replicator.ActivityLevel.BUSY
                || change.getStatus().getActivityLevel() == Replicator.ActivityLevel.IDLE) {
                Report.log(LogLevel.INFO, "***** Stopping replicator");
                change.getReplicator().stop();
            }
        });

        try {
            r.start();
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        }
        finally {
            r.removeChangeListener(token);
        }
    }

    @Test
    public void testDocIDFilter() throws CouchbaseLiteException {
        MutableDocument doc1 = new MutableDocument("doc1");
        doc1.setString("species", "Tiger");
        Document saved1 = saveDocInBaseTestDb(doc1);
        doc1 = saved1.toMutable();
        doc1.setString("name", "Hobbes");
        saveDocInBaseTestDb(doc1);

        MutableDocument doc2 = new MutableDocument("doc2");
        doc2.setString("species", "Tiger");
        Document saved2 = saveDocInBaseTestDb(doc2);
        doc2 = saved2.toMutable();
        doc2.setString("pattern", "striped");
        saveDocInBaseTestDb(doc2);

        MutableDocument doc3 = new MutableDocument("doc3");
        doc3.setString("species", "Tiger");
        otherDB.save(doc3);
        Document saved3 = otherDB.getDocument(doc3.getId());
        doc3 = saved3.toMutable();
        doc3.setString("name", "Hobbes");
        otherDB.save(doc3);
        otherDB.getDocument(doc3.getId());

        MutableDocument doc4 = new MutableDocument("doc4");
        doc4.setString("species", "Tiger");
        otherDB.save(doc4);
        Document saved4 = otherDB.getDocument(doc4.getId());
        doc4 = saved4.toMutable();
        doc4.setString("pattern", "striped");
        otherDB.save(doc4);
        otherDB.getDocument(doc4.getId());

        ReplicatorConfiguration config = makeConfig(true, true, false);
        config.setDocumentIDs(Arrays.asList("doc1", "doc3"));
        run(config, 0, null);
        assertEquals(3, baseTestDb.getCount());
        assertNotNull(baseTestDb.getDocument("doc3"));
        assertEquals(3, otherDB.getCount());
        assertNotNull(otherDB.getDocument("doc1"));
    }

    @Test
    public void testModifyingDocInFilterForbidden() throws CouchbaseLiteException, InterruptedException {
        MutableDocument doc0 = new MutableDocument("doc0");
        doc0.setString("species", "Cat");
        baseTestDb.save(doc0);

        boolean[] gotException = new boolean[1];
        ReplicatorConfiguration config = makeConfig(true, false, false);
        config.setPushFilter((document, flags) -> {
            try { document.toMutable(); }
            catch (UnsupportedOperationException e) { gotException[0] = true; }
            return false;
        });

        final CountDownLatch latch = new CountDownLatch(1);
        baseTestReplicator = new Replicator(config);
        ListenerToken token = baseTestReplicator.addChangeListener(testSerialExecutor, change -> {
            if (change.getStatus().getActivityLevel() == AbstractReplicator.ActivityLevel.STOPPED) {
                latch.countDown();
            }
        });

        try {
            baseTestReplicator.start();
            assertTrue(latch.await(STD_TIMEOUT_SECS, TimeUnit.SECONDS));
        }
        finally {
            baseTestReplicator.removeChangeListener(token);
        }

        assertTrue("Expected exception not thrown", gotException[0]);
    }

    @Test
    public void testCloseDatabaseWithActiveReplicator() throws InterruptedException {
        Replicator repl = new Replicator(makeConfig(true, true, true));
        repl.start();

        while (repl.getStatus().getActivityLevel() != Replicator.ActivityLevel.IDLE) {
            Report.log(LogLevel.WARNING, String.format(
                Locale.ENGLISH,
                "Replicator status is still %s, waiting for idle...",
                repl.getStatus().getActivityLevel()));

            Thread.sleep(500);
        }

        try { baseTestDb.close(); }
        catch (CouchbaseLiteException e) {
            assertEquals(CBLError.Domain.CBLITE, e.getDomain());
            assertEquals(CBLError.Code.BUSY, e.getCode());
        }

        repl.stop();

        int attemptCount = 0;
        while (attemptCount++ < 20 && repl.getStatus().getActivityLevel() != Replicator.ActivityLevel.STOPPED) {
            Report.log(
                LogLevel.WARNING,
                "Replicator status is still %s, waiting for stopped (remaining attempts %d)...",
                repl.getStatus().getActivityLevel(), 10 - attemptCount);

            Thread.sleep(500);
        }
        assertTrue(attemptCount < 20);
    }

    /*
     * Database to Database Push replication document has attachment
     * https://github.com/couchbase/couchbase-lite-core/issues/355
     */
    @Test
    public void testPushBlob() throws CouchbaseLiteException, IOException {
        Database anotherDB = createDb("push-blob-db");
        try {
            try (InputStream is = getAsset("image.jpg")) {
                Blob blob = new Blob("image/jpg", is);
                MutableDocument doc1 = new MutableDocument("doc1");
                doc1.setValue("name", "Tiger");
                doc1.setBlob("image.jpg", blob);
                anotherDB.save(doc1);
            }
            assertEquals(1, anotherDB.getCount());

            ReplicatorConfiguration config = new ReplicatorConfiguration(anotherDB, new DatabaseEndpoint(otherDB));
            config.setReplicatorType(ReplicatorConfiguration.ReplicatorType.PUSH);
            run(config, 0, null);

            assertEquals(1, otherDB.getCount());
            Document doc1a = otherDB.getDocument("doc1");
            Blob blob1a = doc1a.getBlob("image.jpg");
            assertNotNull(blob1a);
        }
        finally {
            deleteDb(anotherDB);
        }
    }

    /*
     * Database to Database Pull replication document has attachment
     * https://github.com/couchbase/couchbase-lite-core/issues/355
     */
    @Test
    public void testPullBlob() throws CouchbaseLiteException, IOException {
        Database anotherDB = createDb("pull-blob-db");
        try {
            try (InputStream is = getAsset("image.jpg")) {
                Blob blob = new Blob("image/jpg", is);
                MutableDocument doc1 = new MutableDocument("doc1");
                doc1.setValue("name", "Tiger");
                doc1.setBlob("image.jpg", blob);
                otherDB.save(doc1);
            }

            assertEquals(1, otherDB.getCount());

            ReplicatorConfiguration config = new ReplicatorConfiguration(anotherDB, new DatabaseEndpoint(otherDB));
            config.setReplicatorType(ReplicatorConfiguration.ReplicatorType.PULL);
            run(config, 0, null);

            assertEquals(1, anotherDB.getCount());
            Document doc1a = anotherDB.getDocument("doc1");
            Blob blob1a = doc1a.getBlob("image.jpg");
            assertNotNull(blob1a);
        }
        finally {
            deleteDb(anotherDB);
        }
    }

    @Test
    public void testDbCanBeClosed() throws CouchbaseLiteException {
        MutableDocument doc1 = new MutableDocument("doc1");
        doc1.setString("species", "Tiger");
        doc1.setString("name", "Hobbes");
        baseTestDb.save(doc1);

        ReplicatorConfiguration config = makeConfig(true, false, false);
        Replicator repl = new Replicator(config);

        CountDownLatch latch = new CountDownLatch(1);
        repl.addChangeListener(change -> {
            if (change.getStatus().getActivityLevel() == AbstractReplicator.ActivityLevel.STOPPED) {
                latch.countDown();
            }
        });

        repl.start();

        try { assertTrue(latch.await(STD_TIMEOUT_SECS, TimeUnit.SECONDS)); }
        catch (InterruptedException ignore) { }


        baseTestDb.delete();
        otherDB.delete();
    }

    /*
     * https://github.com/couchbase/couchbase-lite-core/issues/447
     */
    @Test
    public void testResetCheckpoint() throws CouchbaseLiteException {
        MutableDocument doc1 = new MutableDocument("doc1");
        doc1.setString("species", "Tiger");
        doc1.setString("name", "Hobbes");
        baseTestDb.save(doc1);

        MutableDocument doc2 = new MutableDocument("doc2");
        doc2.setString("species", "Tiger");
        doc2.setString("pattern", "striped");
        baseTestDb.save(doc2);

        // push
        ReplicatorConfiguration config = makeConfig(true, false, false);
        run(config, 0, null);

        // pull
        config = makeConfig(false, true, false);
        run(config, 0, null);

        assertEquals(2L, baseTestDb.getCount());
        assertEquals(2L, otherDB.getCount());

        Document doc = baseTestDb.getDocument("doc1");
        baseTestDb.purge(doc);

        doc = baseTestDb.getDocument("doc2");
        baseTestDb.purge(doc);

        // "because the documents were purged"
        assertEquals(0L, baseTestDb.getCount());
        run(config, 0, null);

        // "because the documents were purged and the replicator is already past them"
        assertEquals(0L, baseTestDb.getCount());
        run(config, 0, null, false, true, null);

        // "because the replicator was reset"
        assertEquals(2L, baseTestDb.getCount());
    }

    @Test
    public void testReplicationOnExpiredDocs() throws CouchbaseLiteException, InterruptedException {
        MutableDocument doc1 = new MutableDocument("doc1");
        doc1.setInt("answer", 42);
        doc1.setString("expired", "string");
        saveDocInBaseTestDb(doc1);

        MutableDocument doc2 = new MutableDocument("doc2");
        doc2.setInt("answer", 42);
        doc2.setString("pushDoc", "string");
        saveDocInBaseTestDb(doc2);

        MutableDocument doc3 = new MutableDocument("doc3");
        doc3.setInt("answer", 42);
        doc3.setString("expired", "string");
        otherDB.save(doc3);

        MutableDocument doc4 = new MutableDocument("doc4");
        doc4.setInt("answer", 42);
        doc4.setString("pullDoc", "string");
        otherDB.save(doc4);

        long now = System.currentTimeMillis();
        Date nowPlusHalfSec = new Date(now + 500);
        Date nowPlusTenSecs = new Date(now + (5 * 1000));

        baseTestDb.setDocumentExpiration("doc1", nowPlusHalfSec);
        otherDB.setDocumentExpiration("doc3", nowPlusHalfSec);

        baseTestDb.setDocumentExpiration("doc2", nowPlusTenSecs);
        otherDB.setDocumentExpiration("doc4", nowPlusTenSecs);

        Thread.sleep(1000);

        // push
        ReplicatorConfiguration config = makeConfig(true, false, false);
        run(config, 0, null);

        // pull
        config = makeConfig(false, true, false);
        run(config, 0, null);

        assertNull(otherDB.getDocument("doc1")); // expired; not copied
        assertNull(baseTestDb.getDocument("doc3")); // expired; not copied

        assertEquals(baseTestDb.getDocument("doc4").getString("pullDoc"), "string");
        assertEquals(otherDB.getDocument("doc2").getString("pushDoc"), "string");
    }

    @Test
    public void testDocumentReplicationForPurgedDoc() throws CouchbaseLiteException, InterruptedException {
        final List<DocumentReplication> replicationEvents = new ArrayList<>();

        MutableDocument doc1 = new MutableDocument("doc1");
        doc1.setString("name", "Tiger");
        baseTestDb.save(doc1);
        baseTestDb.purge(doc1);

        baseTestReplicator = new Replicator(makeConfig(true, false, false, otherDB));

        final CountDownLatch latch = new CountDownLatch(1);
        ListenerToken documentToken = baseTestReplicator.addDocumentReplicationListener(
            testSerialExecutor,
            replicationEvents::add);
        ListenerToken changeToken = baseTestReplicator.addChangeListener(change -> {
            if (change.getStatus().getActivityLevel() == AbstractReplicator.ActivityLevel.STOPPED) {
                latch.countDown();
            }
        });

        try {
            baseTestReplicator.start();
            baseTestReplicator.removeChangeListener(documentToken);

            assertTrue(latch.await(STD_TIMEOUT_SECS, TimeUnit.SECONDS));
        }
        finally {
            baseTestReplicator.removeChangeListener(documentToken);
            baseTestReplicator.removeChangeListener(changeToken);
        }

        int eventCount = replicationEvents.size();
        assertEquals(eventCount, 0);

        assertNull(baseTestDb.getDocument("doc1"));
        assertNull(otherDB.getDocument("doc1"));
    }

    @Test
    public void testDocumentReplicationEvent() throws CouchbaseLiteException, InterruptedException {
        final MutableDocument doc1 = new MutableDocument("doc1");
        doc1.setString("species", "Tiger");
        doc1.setString("pattern", "Hobbes");
        saveDocInBaseTestDb(doc1);

        final MutableDocument doc2 = new MutableDocument("doc2");
        doc2.setString("species", "Tiger");
        doc2.setString("pattern", "Striped");
        saveDocInBaseTestDb(doc2);

        final Map<String, ReplicatedDocument> docs = new HashMap<>();
        final boolean[] isPush = new boolean[1];

        // Push
        baseTestReplicator = new Replicator(makeConfig(true, false, false));

        final CountDownLatch latch1 = new CountDownLatch(1);
        ListenerToken token = baseTestReplicator.addDocumentReplicationListener(update -> {
            isPush[0] = update.isPush();
            for (ReplicatedDocument doc : update.getDocuments()) { docs.put(doc.getID(), doc); }
            latch1.countDown();
        });

        try {
            run(baseTestReplicator, 0, null);
            assertTrue(latch1.await(STD_TIMEOUT_SECS, TimeUnit.SECONDS));
        }
        finally {
            baseTestReplicator.removeChangeListener(token);
        }

        assertTrue(isPush[0]);

        // Check if getting two document replication events
        assertEquals("wrong size", docs.size(), 2);

        final ReplicatedDocument rDoc1 = docs.get("doc1");
        assertNotNull("missing doc1", rDoc1);
        assertNull("wrong error for doc1", rDoc1.getError());
        final EnumSet<DocumentFlag> rDoc1Flags = rDoc1.flags();
        assertFalse(
            "missing DocumentFlagsDeleted for doc1",
            rDoc1Flags.contains(DocumentFlag.DocumentFlagsDeleted));
        assertFalse(
            "missing DocumentFlagsAccessRemoved for doc1",
            rDoc1Flags.contains(DocumentFlag.DocumentFlagsAccessRemoved));

        final ReplicatedDocument rDoc2 = docs.get("doc2");
        assertNotNull("missing doc2", rDoc2);
        assertNull("wrong error for doc2", rDoc2.getError());
        final EnumSet<DocumentFlag> rDoc2Flags = rDoc1.flags();
        assertFalse(
            "missing DocumentFlagsDeleted for doc2",
            rDoc2Flags.contains(DocumentFlag.DocumentFlagsDeleted));
        assertFalse(
            "missing DocumentFlagsAccessRemoved for doc2",
            rDoc2Flags.contains(DocumentFlag.DocumentFlagsAccessRemoved));

        // Add another doc
        MutableDocument doc3 = new MutableDocument("doc3");
        doc3.setString("species", "Tiger");
        doc3.setString("pattern", "Star");
        baseTestDb.save(doc3);

        final CountDownLatch latch2 = new CountDownLatch(1);
        token = baseTestReplicator.addDocumentReplicationListener(update -> {
            isPush[0] = update.isPush();
            for (ReplicatedDocument doc : update.getDocuments()) { docs.put(doc.getID(), doc); }
            latch2.countDown();
        });

        // Run the replicator again
        try {
            run(baseTestReplicator, 0, null);
            assertTrue(latch2.await(STD_TIMEOUT_SECS, TimeUnit.SECONDS));
        }
        finally {
            baseTestReplicator.removeChangeListener(token);
        }

        // Check if getting a new document replication event
        assertEquals("wrong size", docs.size(), 3);

        final ReplicatedDocument rDoc3 = docs.get("doc1");
        assertNotNull("missing doc3", rDoc3);
        assertNull("wrong error for doc3", rDoc3.getError());
        final EnumSet<DocumentFlag> rDoc3Flags = rDoc1.flags();
        assertFalse(
            "missing DocumentFlagsDeleted for doc3",
            rDoc3Flags.contains(DocumentFlag.DocumentFlagsDeleted));
        assertFalse(
            "missing DocumentFlagsAccessRemoved for doc3",
            rDoc3Flags.contains(DocumentFlag.DocumentFlagsAccessRemoved));

        // Add another doc
        MutableDocument doc4 = new MutableDocument("doc4");
        doc4.setString("species", "Tiger");
        doc4.setString("pattern", "WhiteStriped");
        baseTestDb.save(doc4);

        final CountDownLatch latch3 = new CountDownLatch(1);
        token = baseTestReplicator.addChangeListener(change -> {
            if (change.getStatus().getActivityLevel() == AbstractReplicator.ActivityLevel.STOPPED) {
                latch3.countDown();
            }
        });

        // Run the replicator a third time
        try {
            run(baseTestReplicator, 0, null);
            assertTrue(latch3.await(STD_TIMEOUT_SECS, TimeUnit.SECONDS));
        }
        finally {
            baseTestReplicator.removeChangeListener(token);
        }

        // Should not getting a new document replication event
        assertEquals(docs.size(), 3);
    }

    @Test
    public void testDocumentReplicationEventWithPullConflict() throws CouchbaseLiteException, InterruptedException {
        final List<ReplicatedDocument> docs = new ArrayList<>();
        MutableDocument doc1a = new MutableDocument("doc1");
        doc1a.setValue("species", "Tiger");
        doc1a.setString("pattern", "Star");
        saveDocInBaseTestDb(doc1a);

        MutableDocument doc1b = new MutableDocument("doc1");
        doc1b.setValue("species", "Tiger");
        doc1b.setString("pattern", "Striped");
        otherDB.save(doc1b);

        baseTestReplicator = new Replicator(makeConfig(false, true, false));

        final CountDownLatch latch = new CountDownLatch(1);
        ListenerToken token = baseTestReplicator.addDocumentReplicationListener(
            testSerialExecutor,
            update -> {
                for (ReplicatedDocument d : update.getDocuments()) {
                    docs.add(d);
                    latch.countDown();
                }
            });

        try {
            run(baseTestReplicator, 0, null);
            assertTrue(latch.await(STD_TIMEOUT_SECS, TimeUnit.SECONDS));
        }
        finally {
            baseTestReplicator.removeChangeListener(token);
        }

        // Check
        assertEquals(docs.size(), 1);
        assertEquals(docs.get(0).getID(), "doc1");
        assertNull(docs.get(0).getError());
        assertFalse(docs.get(0).flags().contains(DocumentFlag.DocumentFlagsDeleted));
        assertFalse(docs.get(0).flags().contains(DocumentFlag.DocumentFlagsAccessRemoved));
    }

    @Test
    public void testDocumentReplicationEventWithPushConflict() throws CouchbaseLiteException, InterruptedException {
        final List<ReplicatedDocument> docs = new ArrayList<>();
        MutableDocument doc1a = new MutableDocument("doc1");
        doc1a.setString("species", "Tiger");
        doc1a.setString("pattern", "start");
        baseTestDb.save(doc1a);

        MutableDocument doc2 = new MutableDocument("doc1");
        doc2.setString("species", "Tiger");
        doc2.setString("pattern", "Striped");
        otherDB.save(doc2);

        // Push
        baseTestReplicator = new Replicator(makeConfig(true, false, false));

        final CountDownLatch latch = new CountDownLatch(1);
        ListenerToken token = baseTestReplicator.addDocumentReplicationListener(
            testSerialExecutor,
            update -> {
                for (ReplicatedDocument d : update.getDocuments()) {
                    docs.add(d);
                    latch.countDown();
                }
            });

        try {
            run(baseTestReplicator, 0, null);
            assertTrue(latch.await(STD_TIMEOUT_SECS, TimeUnit.SECONDS));
        }
        finally {
            baseTestReplicator.removeChangeListener(token);
        }

        // Check
        assertEquals(docs.size(), 1);
        assertEquals(docs.get(0).getID(), "doc1");
        assertNotNull(docs.get(0).getError());
        assertEquals(docs.get(0).getError().getDomain(), CBLError.Domain.CBLITE);
        assertEquals(docs.get(0).getError().getCode(), CBLError.Code.HTTP_CONFLICT);
        assertFalse(docs.get(0).flags().contains(DocumentFlag.DocumentFlagsDeleted));
        assertFalse(docs.get(0).flags().contains(DocumentFlag.DocumentFlagsAccessRemoved));
    }

    @Test
    public void testDocumentReplicationEventWithDeletion() throws CouchbaseLiteException, InterruptedException {
        final List<ReplicatedDocument> docs = new ArrayList<>();
        MutableDocument doc1 = new MutableDocument("doc1");
        doc1.setString("species", "Tiger");
        doc1.setString("pattern", "Star");
        baseTestDb.save(doc1);
        baseTestDb.delete(doc1);

        baseTestReplicator = new Replicator(makeConfig(true, false, false));

        final CountDownLatch latch = new CountDownLatch(1);
        ListenerToken token = baseTestReplicator.addDocumentReplicationListener(
            testSerialExecutor,
            update -> {
                for (ReplicatedDocument d : update.getDocuments()) {
                    docs.add(d);
                    latch.countDown();
                }
            });

        try {
            run(baseTestReplicator, 0, null);
            assertTrue(latch.await(STD_TIMEOUT_SECS, TimeUnit.SECONDS));
        }
        finally {
            baseTestReplicator.removeChangeListener(token);
        }

        // Check
        assertEquals(docs.size(), 1);
        assertEquals(docs.get(0).getID(), "doc1");
        assertNull(docs.get(0).getError());
        assertTrue(docs.get(0).flags().contains(DocumentFlag.DocumentFlagsDeleted));
        assertFalse(docs.get(0).flags().contains(DocumentFlag.DocumentFlagsAccessRemoved));
    }

    private void testPushFilter(boolean continuous) throws CouchbaseLiteException, InterruptedException {
        final Set<String> docIds = new HashSet<>();
        final Set<String> revIds = new HashSet<>();

        // Create documents
        final Blob blob = new Blob("text/plain", BLOB_CONTENT.getBytes(StandardCharsets.UTF_8));

        MutableDocument doc1 = new MutableDocument("doc1");
        doc1.setString("species", "Tiger");
        doc1.setString("pattern", "Hobbes");
        doc1.setBlob("photo", blob);
        baseTestDb.save(doc1);

        MutableDocument doc2 = new MutableDocument("doc2");
        doc2.setString("species", "Tiger");
        doc2.setString("pattern", "Striped");
        doc2.setBlob("photo", blob);
        baseTestDb.save(doc2);

        MutableDocument doc3 = new MutableDocument("doc3");
        doc3.setString("species", "Tiger");
        doc3.setString("pattern", "Star");
        doc3.setBlob("photo", blob);
        baseTestDb.save(doc3);
        baseTestDb.delete(doc3);

        // Create replicator with push filter
        ReplicatorConfiguration config = makeConfig(true, false, continuous);

        config.setPushFilter((document, flags) -> {
            String docId = document.getId();
            docIds.add(docId);
            revIds.add(document.getRevisionID());

            boolean isDeleted = flags.contains(DocumentFlag.DocumentFlagsDeleted);
            assertEquals(document.getId().equals("doc3"), isDeleted);
            if (isDeleted) { assertEquals(document.getContent(), new Dictionary()); }
            else {
                // Check content
                assertNotNull(document.getString("pattern"));
                assertEquals(document.getString("species"), "Tiger");

                // Check blob
                Blob photo = document.getBlob("photo");
                assertNotNull(photo);
                Assert.assertArrayEquals(photo.getContent(), blob.getContent());
            }

            // Reject doc2
            return !document.getId().equals("doc2");
        });

        Replicator repl = run(config, 0, null);

        // Check documents passed to the filter
        assertEquals(3, docIds.size());
        assertEquals(3, revIds.size());
        assertTrue(docIds.contains(doc1.getId()));
        assertTrue(revIds.contains(doc1.getRevisionID()));
        assertTrue(docIds.contains(doc2.getId()));
        assertTrue(revIds.contains(doc2.getRevisionID()));
        assertTrue(docIds.contains(doc3.getId()));
        assertTrue(revIds.contains(doc3.getRevisionID()));

        // Check replicated documents
        assertNotNull(otherDB.getDocument("doc1"));
        assertNull(otherDB.getDocument("doc2"));
        assertNull(otherDB.getDocument("doc3"));

        if (continuous) { stopContinuousReplicator(repl); }
        else { repl.stop(); }
    }

    private void testPullFilter(final boolean continuous) throws CouchbaseLiteException, InterruptedException {
        final Set<String> docIds = new HashSet<>();
        final Set<String> revIds = new HashSet<>();

        // Add a document to db database so that it can pull the deleted docs from
        MutableDocument doc0 = new MutableDocument("doc0");
        doc0.setString("species", "Cat");
        baseTestDb.save(doc0);

        // Create documents
        final Blob blob = new Blob("text/plain", BLOB_CONTENT.getBytes(StandardCharsets.UTF_8));

        MutableDocument doc1 = new MutableDocument("doc1");
        doc1.setString("species", "Tiger");
        doc1.setString("pattern", "Hobbes");
        doc1.setBlob("photo", blob);
        otherDB.save(doc1);

        MutableDocument doc2 = new MutableDocument("doc2");
        doc2.setString("species", "Tiger");
        doc2.setString("pattern", "Striped");
        doc2.setBlob("photo", blob);
        otherDB.save(doc2);

        MutableDocument doc3 = new MutableDocument("doc3");
        doc3.setString("species", "Tiger");
        doc3.setString("pattern", "Star");
        doc3.setBlob("photo", blob);
        otherDB.save(doc3);
        otherDB.delete(doc3);

        // Create replicator with pull filter
        ReplicatorConfiguration config = makeConfig(false, true, continuous);
        config.setPullFilter((document, flags) -> {
            String docId = document.getId();
            docIds.add(docId);
            revIds.add(document.getRevisionID());

            boolean isDeleted = flags.contains(DocumentFlag.DocumentFlagsDeleted);
            assertEquals(document.getId().equals("doc3"), isDeleted);
            if (isDeleted) { assertEquals(document.getContent(), new Dictionary()); }
            else {
                // Check content
                assertNotNull(document.getString("pattern"));
                assertEquals(document.getString("species"), "Tiger");

                // Check blob
                Blob photo = document.getBlob("photo");
                assertNotNull(photo);
                // Note: Cannot access content because there is no actual blob file saved on disk.
                // assertArrayEquals(photo.getContent(), blob.getContent());
            }

            // Reject doc2
            return !docId.equals("doc2");
        });

        // Run the replicator
        baseTestReplicator = new Replicator(config);
        final CountDownLatch doneLatch = new CountDownLatch(1);
        final CountDownLatch stoppedLatch = new CountDownLatch(1);
        ListenerToken token = baseTestReplicator.addChangeListener(
            testSerialExecutor,
            new ReplicatorChangeListener() {
                boolean started;

                @Override
                public void changed(@NotNull @NonNull ReplicatorChange change) {
                    switch (change.getStatus().getActivityLevel()) {
                        case BUSY:
                            started = true;
                            break;
                        case IDLE:
                            if (started && continuous) { doneLatch.countDown(); }
                            break;
                        case STOPPED:
                            if (started && !continuous) { doneLatch.countDown(); }
                            stoppedLatch.countDown();
                            break;
                        default:
                            break;
                    }
                }
            });

        // Run the replicator
        try {
            baseTestReplicator.start();
            assertTrue(doneLatch.await(STD_TIMEOUT_SECS, TimeUnit.SECONDS));

            // Check documents passed to the filter
            assertEquals(3, docIds.size());
            assertEquals(3, revIds.size());
            assertTrue(docIds.contains(doc1.getId()));
            assertTrue(revIds.contains(doc1.getRevisionID()));
            assertTrue(docIds.contains(doc2.getId()));
            assertTrue(revIds.contains(doc2.getRevisionID()));
            assertTrue(docIds.contains(doc3.getId()));
            assertTrue(revIds.contains(doc3.getRevisionID()));

            // Check replicated documents
            assertNotNull(baseTestDb.getDocument("doc1"));
            assertNull(baseTestDb.getDocument("doc2"));
            assertNull(baseTestDb.getDocument("doc3"));

            baseTestReplicator.stop();
            assertTrue(stoppedLatch.await(STD_TIMEOUT_SECS, TimeUnit.SECONDS));
        }
        finally {
            baseTestReplicator.removeChangeListener(token);
        }

    }

    private ReplicatorConfiguration makeConfig(
        boolean push,
        boolean pull,
        boolean continuous,
        Database targetDatabase) {
        return makeConfig(push, pull, continuous, this.baseTestDb, new DatabaseEndpoint(targetDatabase));
    }

    private ReplicatorConfiguration makeConfig(boolean push, boolean pull, boolean continuous) {
        return makeConfig(push, pull, continuous, this.otherDB);
    }
}
