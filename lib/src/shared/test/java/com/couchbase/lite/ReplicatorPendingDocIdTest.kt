//
// Copyright (c) 2019 Couchbase, Inc All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
package com.couchbase.lite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.EnumSet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit


const val TEST_KEY = "test-key"

class ReplicatorPendingDocIdTest : BaseEEReplicatorTest() {

    //    1. Create replicator config with pull only
    //    2. Call PendingDocumentIDs method
    //    3. Expect exception with "Pending Document IDs are not supported on pull-only replicators."
    //       in cross platform error messages
    @Test
    fun testPendingDocIDsPullOnlyException() {
        val replicator = Replicator(pullConfig())
        val latch = CountDownLatch(1)
        var token: ListenerToken? = null

        var expected: CouchbaseLiteException? = null
        var err: Exception? = null
        try {
            token = replicator.addChangeListener { change ->
                try {
                    when (change.status.activityLevel) {
                        AbstractReplicator.ActivityLevel.BUSY -> change.replicator.pendingDocumentIds
                        AbstractReplicator.ActivityLevel.STOPPED -> latch.countDown()
                        else -> Unit
                    }
                } catch (e: CouchbaseLiteException) {
                    expected = e
                } catch (e: Exception) {
                    err = err ?: e
                }
            }

            replicator.start()
            assertTrue(latch.await(15, TimeUnit.SECONDS))
        } finally {
            token?.let { replicator.removeChangeListener(it) }
        }

        assertNull(err)

        assertNotNull(expected)
        assertEquals(CBLError.Code.UNSUPPORTED, expected?.code ?: -1)
    }

    //    1. Create replicator config with pull only
    //    2. Call IsDocumentPending method
    //    3. Expect exception with "Pending Document IDs are not supported on pull-only replicators."
    //       in cross platform error messages
    @Test
    fun testIsDocumentPendingPullOnlyException() {
        val replicator = Replicator(pullConfig())
        val latch = CountDownLatch(1)
        var token: ListenerToken? = null

        var expected: CouchbaseLiteException? = null
        var err: Exception? = null
        try {
            token = replicator.addChangeListener { change ->
                try {
                    when (change.status.activityLevel) {
                        AbstractReplicator.ActivityLevel.BUSY -> change.replicator.isDocumentPending("some-doc")
                        AbstractReplicator.ActivityLevel.STOPPED -> latch.countDown()
                        else -> Unit
                    }
                } catch (e: CouchbaseLiteException) {
                    expected = e
                } catch (e: Exception) {
                    err = err ?: e
                }
            }

            replicator.start()
            assertTrue(latch.await(15, TimeUnit.SECONDS))
        } finally {
            token?.let { replicator.removeChangeListener(it) }
        }

        assertNull(err)

        assertNotNull(expected)
        assertEquals(CBLError.Code.UNSUPPORTED, expected?.code ?: -1)
    }

    //    1. Create replicator config with push/push and pull
    //    2. Call IsDocumentPending method with null parameter
    //    3. Expect platform exception (eg. ArgumentNullException in .Net)
    @Test
    fun testIsDocumentPendingNullIdException() {
        val replicator = Replicator(pullConfig())
        val latch = CountDownLatch(1)
        var token: ListenerToken? = null

        var expected: IllegalArgumentException? = null
        var err: Exception? = null
        try {
            token = replicator.addChangeListener { change ->
                try {
                    when (change.status.activityLevel) {
                        AbstractReplicator.ActivityLevel.BUSY -> callIsDocumentPendingWithNullId(change.replicator)
                        AbstractReplicator.ActivityLevel.STOPPED -> latch.countDown()
                        else -> Unit
                    }
                } catch (e: IllegalArgumentException) {
                    expected = e
                } catch (e: Exception) {
                    err = err ?: e
                }
            }

            replicator.start()
            assertTrue(latch.await(15, TimeUnit.SECONDS))
        } finally {
            token?.let { replicator.removeChangeListener(it) }
        }

        assertNull(err)
        assertNotNull(expected)
    }

    //    1. Create and save bunch of documents in database.
    //    2. Create replicator config with push/push and push
    //    3. Call PendingDocumentIDs method before replication start and expect all pending doc ids set count
    //       is equal to the total count of saved documents.
    //    4. Start replication and wait for activity status to busy, and check result is non-zero.
    //    5. When status is stopped, call PendingDocumentIDs method again and expect all pending doc ids set count is 0.
    @Test
    fun testPendingDocIDsWithCreate() {
        val ids = createDocs(5)
        validatePendingDocumentIds(ids)
    }

    //    1. Create, save and replicate bunch of documents in database.
    //    2. Create replicator config with push/push and pull
    //    3. Edit one doc in the saved docs.
    //    4. Call PendingDocumentIDs method before replication start and expect all pending doc ids count
    //       is equal to one.
    //    5. Start replication and wait for activity status to stop.
    //    6. Call PendingDocumentIDs method again and expect all pending doc ids set count is 0.
    @Test
    fun testPendingDocIDsWithEdit() {
        val ids = createDocs(5)
        val changed = ids.drop(3).toSet()

        pushPull()

        changed.forEach { id -> db.getDocument(id).let { db.save(it.toMutable().setString(TEST_KEY, "quiche")) } }

        validatePendingDocumentIds(changed)
    }

    //    1. Create, save and replicate bunch of documents in database.
    //    2. Create replicator config with push/push and pull
    //    3. Delete one doc in the saved docs.
    //    4. Call PendingDocumentIDs method before replication start and expect all pending doc ids count
    //       is equal to one.
    //    5. Start replication and wait for activity status to stop.
    //    6. Call PendingDocumentIDs method again and expect all pending doc ids set count is 0.
    @Test
    fun testPendingDocIDsWithDelete() {
        val ids = createDocs(5)
        val deleted = ids.drop(3).toSet()

        pushPull()

        deleted.forEach { id -> db.getDocument(id).let { db.delete(it) } }

        validatePendingDocumentIds(deleted)
    }

    //    1. Create and save bunch of documents in database.
    //    2. Create replicator config with push/push and pull
    //    3. Purge one doc in the saved docs.
    //    4. Call PendingDocumentIDs method before replication start and expect all pending doc ids set count
    //       is equal to the total count of saved documents - 1.
    //    5. Start replication and wait for activity status to stop.
    //    6. Call PendingDocumentIDs method again and expect all pending doc ids set count is 0.
    @Test
    fun testPendingDocIDsWithPurge() {
        val ids = createDocs(5)

        pushPull()

        ids.drop(3).forEach { id -> db.getDocument(id).let { db.purge(it) } }

        validatePendingDocumentIds(emptySet())
    }

    //    1. Create and save bunch of documents in database.
    //    2. Create replicator config with push/push and pull
    //    3. Call IsDocumentPending method before replication start and expect true when pass one of the doc id
    //       in saved doc as parameter.
    //    4. Start replication and wait for activity status to stop.
    //    5. Call IsDocumentPending method again and expect false when pass one of the doc id in saved doc as parameter.
    @Test
    fun testIsDocumentPendingWithCreate() {
        val id = createDocs(1).first()
        validateIsDocumentPending(setOf(id), setOf("foo"))
    }

    //    1. Create and save bunch of documents in database.
    //    2. Create replicator config with push/push and pull
    //    3. Edit one doc in the saved docs.
    //    4. Call IsDocumentPending method before replication start and expect true when pass the edited doc id
    //       in saved doc as parameter.
    //    5. Start replication and wait for activity status to stop.
    //    6. Call IsDocumentPending method again and expect false when pass the edited doc id in saved doc as parameter.
    @Test
    fun testIsDocumentPendingEdit() {
        val id = createDocs(1).first()

        pushPull()

        db.getDocument(id).let { db.save(it.toMutable().setString(TEST_KEY, "quiche")) }

        validateIsDocumentPending(setOf(id), setOf("foo"))
    }

    @Test
    fun testIsDocumentPendingDelete() {
        val ids = createDocs(2)
        val id = ids.first()

        pushPull()

        db.getDocument(id).let { db.delete(it) }

        validateIsDocumentPending(setOf(id), setOf("foo"))
    }

    //    1. Create and save bunch of documents in database.
    //    2. Create replicator config with push/push and pull
    //    3. Purge one doc in the saved docs.
    //    4. Call IsDocumentPending method before replication start and expect false when pass the edited doc id
    //       in saved doc as parameter.
    @Test
    fun testIsDocumentPendingPurge() {
        val ids = createDocs(2)
        val id = ids.first()

        pushPull()

        db.purge(id)

        validateIsDocumentPending(emptySet(), setOf(id))
    }

    //    1. Create and save bunch of documents in database.
    //    2. add a push filter which blocks some docs
    //    3. Push Replicate
    //    4. Make sure pending-document-ids shouldn't include any blocked document id.
    //    4. Make sure the unblocked ids are returned from pending-document-ids
    @Test
    fun testPendingDocIdsWithFilter() {
        val ids = createDocs(5)
        val unfiltered = ids.drop(3).toSet()

        val config = pushConfig()
        config.pushFilter = ReplicationFilter { doc: Document, _: EnumSet<DocumentFlag> -> unfiltered.contains(doc.id) }

        validatePendingDocumentIds(unfiltered, config)
    }

    //    1. Create and save bunch of documents in database.
    //    2. add a push filter which blocks some docs
    //    3. Push Replicate
    //    4. Make sure isPendingDocumentId shouldn't return true for any blocked document id.
    //    4. Make sure isPendingDocumentId shouldn't return false for any unblocked document id.
    @Test
    fun testIsDocumentPendingWithPushFilter() {
        val ids = createDocs(5)
        val unfiltered = setOf(ids.first())
        val filtered = ids.drop(1).toSet()

        val config = pushConfig()
        config.pushFilter = ReplicationFilter { doc: Document, _: EnumSet<DocumentFlag> -> unfiltered.contains(doc.id) }

        validateIsDocumentPending(unfiltered, filtered, config)
    }

    private fun createDocs(n: Int): Set<String> = createDocs(0, n)

    private fun createDocs(start: Int, count: Int): Set<String> {
        val ids = (start until (start + count)).map { "doc-${it}" }
        ids.map { MutableDocument(it) }
            .forEach {
                it.setString(TEST_KEY, "souffle")
                db.save(it)
            }
        return ids.toSet()
    }

    private fun validatePendingDocumentIds(changed: Set<String>, config: ReplicatorConfiguration? = null) {
        val replicator = Replicator(config ?: pushConfig())
        var token: ListenerToken? = null
        val latch = CountDownLatch(1)

        var err: Exception? = null
        var pendingIdBefore: Set<String>? = null
        var pendingIdAfter: Set<String>? = null

        try {
            token = replicator.addChangeListener { change ->
                try {
                    val ids = change.replicator.pendingDocumentIds

                    when (change.status.activityLevel) {
                        AbstractReplicator.ActivityLevel.CONNECTING -> pendingIdBefore = ids
                        AbstractReplicator.ActivityLevel.STOPPED -> {
                            pendingIdAfter = ids
                            latch.countDown()
                        }
                        else -> Unit
                    }
                } catch (e: Exception) {
                    err = e
                }
            }

            replicator.start()
            assertTrue(latch.await(15, TimeUnit.SECONDS))
        } finally {
            token?.let { replicator.removeChangeListener(it) }
        }

        assertNull(err)
        assertEquals(changed, pendingIdBefore)
        assertEquals(0, pendingIdAfter?.size ?: -1)
    }

    private fun validateIsDocumentPending(
        expectedPending: Set<String>,
        expectedNotPending: Set<String>? = null,
        config: ReplicatorConfiguration? = null
    ) {
        val replicator = Replicator(config ?: pushConfig())
        var token: ListenerToken? = null
        val latch = CountDownLatch(1)

        var err: Exception? = null
        var expectedPendingBefore: Boolean? = null
        var expectedNotPendingBefore: Boolean? = null
        var expectedPendingAfter: Boolean? = null
        var expectedNotPendingAfter: Boolean? = null
        try {
            token = replicator.addChangeListener { change ->
                try {
                    when (change.status.activityLevel) {
                        AbstractReplicator.ActivityLevel.CONNECTING -> {
                            expectedPendingBefore = validatePending(change.replicator, expectedPending, true)
                            expectedNotPendingBefore = validatePending(change.replicator, expectedNotPending, false)
                        }
                        AbstractReplicator.ActivityLevel.STOPPED -> {
                            expectedPendingAfter = validatePending(change.replicator, expectedPending, false)
                            expectedNotPendingAfter = validatePending(change.replicator, expectedNotPending, false)
                            latch.countDown()
                        }
                        else -> Unit
                    }
                } catch (e: Exception) {
                    err = e
                }
            }

            replicator.start()
            assertTrue(latch.await(15, TimeUnit.SECONDS))
        } finally {
            token?.let { replicator.removeChangeListener(it) }
        }

        assertNull(err)
        assertTrue("Unexpected pending before sync", expectedPendingBefore ?: false)
        assertTrue("Unexpected not pending before sync", expectedNotPendingBefore ?: false)
        assertTrue("Unexpected pending after sync", expectedPendingAfter ?: false)
        assertTrue("Unexpected not pending after sync", expectedNotPendingAfter ?: false)
    }

    fun validatePending(repl: Replicator, pending: Set<String>?, expected: Boolean) =
        pending?.map { docId -> repl.isDocumentPending(docId) }
            ?.fold(true) { acc: Boolean, isPending: Boolean -> acc && (isPending == expected) }
            ?: true
}

