//
// Copyright (c) 2019 Couchbase, Inc All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License")
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.util.concurrent.BrokenBarrierException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit


private const val DOC1 = "doc1"
private const val DOC2 = "doc2"
private const val DOC3 = "doc3"
private const val DOC4 = "doc4"

private const val KEY1 = "Ann Arbor"
private const val VAL1 = "Harpst"
private const val KEY2 = "Oberlin"
private const val VAL2 = "Woodland"
private const val KEY3 = "Hanover"
private const val VAL3 = "Wheelock"
private const val KEY4 = "Boston"
private const val VAL4 = "Pleasant"
private const val KEY5 = "Oakland"
private const val VAL5 = "Bay Forest"

private open class TestConflictResolver(private var resolver: (Conflict) -> Document?) : ConflictResolver {
    override fun resolve(conflict: Conflict): Document? {
        return resolver(conflict)
    }
}

private object NullResolver : TestConflictResolver({ null })
private object LocalResolver : TestConflictResolver({ conflict -> conflict.localDocument })
private object RemoteResolver : TestConflictResolver({ conflict -> conflict.remoteDocument })

class ReplicatorConflictResolutionTests : BaseReplicatorTest() {

    /**
     * #1
     * 1. Test whether a custom conflict resolver can be set to/get from the ReplicatorConfiguration.
     * 2. After the ReplicatorConfiguration set to the replicator, the conflictResolver property should be readonly.
     */
    @Test(expected = IllegalStateException::class)
    fun testConflictResolverConfigProperty() {
        val resolver = ConflictResolver { null }

        val config = makeConfig(true, false, false, URLEndpoint(URI("wss://foo")))
        config.conflictResolver = resolver

        assertNotNull(config.conflictResolver)
        assertEquals(config.conflictResolver, resolver)

        val repl = Replicator(config)
        assertNotNull(repl.config.conflictResolver)
        assertEquals(repl.config.conflictResolver, resolver)

        // should throw ISE
        repl.config.conflictResolver = null
    }

    /**
     * #2
     * 1. Test a custom conflict resolver that returns the remote document.
     * 2. Make sure that the remote document wins the conflict and is saved to the database.
     * 3. Make sure that the resolved doc doesn't need to be push back to the remote database.
     */
    @Test
    fun testConflictResolverRemoteWins() {
        makeConflict(DOC1, hashMapOf(KEY1 to VAL1), hashMapOf(KEY2 to VAL2))

        run(pullConfig(RemoteResolver), 0, null)

        assertEquals(1, db.count)

        val doc = db.getDocument(DOC1)
        assertEquals(1, doc.count())
        assertEquals(VAL2, doc.getString(KEY2))

        val prePushSeq = otherDB.getDocument(DOC1).sequence

        run(pushConfig(), 0, null)

        assertEquals(prePushSeq, otherDB.getDocument(DOC1).sequence)
    }

    /**
     * #3
     * 1. Test a custom conflict resolver that returns the remote document which is a deleted document.
     * 2. Make sure that the remote document given to the conflict resolver is null.
     * 3. Make sure that document is deleted from the database.
     * 4. Make sure that the resolved doc doesn't need to be push back to the remote database
     *    so the remote doc stays deleted.
     */
    @Test
    fun testConflictResolverDeletedRemoteWins() {
        makeConflict(DOC1, hashMapOf(KEY1 to VAL1), null)

        var remoteDoc: Document? = null
        val pullConfig = pullConfig(TestConflictResolver { conflict ->
            remoteDoc = conflict.remoteDocument
            null
        })

        run(pullConfig, 0, null)

        assertNull(remoteDoc)

        assertEquals(0, db.count)
        val savedDoc = db.getDocument(DOC1)
        assertNull(savedDoc)

        val prePushSeq = otherDB.c4Database.get(DOC1, false).sequence

        run(pushConfig(), 0, null)

        assertEquals(prePushSeq, otherDB.c4Database.get(DOC1, false).sequence)
    }

    /**
     * #4
     * 1. Test a custom conflict resolver that returns the local document.
     * 2. Make sure that the local document wins the conflict and is saved to the database.
     * 3. Make sure that the resolved doc can be push to the remote database.
     */
    @Test
    fun testConflictResolverLocalWins() {
        makeConflict(DOC1, hashMapOf(KEY1 to VAL1), hashMapOf(KEY2 to VAL2))

        run(pullConfig(LocalResolver), 0, null)

        assertEquals(1, db.count)

        val doc = db.getDocument(DOC1)
        assertEquals(1, doc.count())
        assertEquals(VAL1, doc.getString(KEY1))

        val prePushSeq = otherDB.getDocument(DOC1).sequence

        run(pushConfig(), 0, null)

        assertTrue(prePushSeq < otherDB.getDocument(DOC1).sequence)
    }

    /**
     * #5
     * 1. Test a custom conflict resolver that returns the local document which is a deleted document.
     * 2. Make sure that the local document given to the conflict resolver is null.
     * 3. Make sure that document is deleted from the database.
     * 4. Make sure that the resolved doc can be push to the remote database.
     */
    @Test
    fun testConflictResolverDeletedLocalWins() {
        makeConflict(DOC1, null, hashMapOf(KEY1 to VAL1))

        var localDoc: Document? = null
        val pullConfig = pullConfig(TestConflictResolver { conflict ->
            localDoc = conflict.localDocument
            null
        })

        run(pullConfig, 0, null)

        assertNull(localDoc)

        assertEquals(0, db.count)

        val savedDoc = db.getDocument(DOC1)
        assertNull(savedDoc)

        val prePushSeq = otherDB.getDocument(DOC1).sequence

        run(pushConfig(), 0, null)

        assertTrue(prePushSeq < otherDB.c4Database.get(DOC1, false).sequence)
    }

    /**
     * #6
     * 1. Test a custom conflict resolver that returns a merged document.
     * 2. The merge document could a mutated local doc, a mutated remote doc, or a new doc.
     * 3. Make sure that the merged doc wins and is saved to the database.
     * 4. Make sure that the resolved doc can be push to the remote database.
     * Case: Mutated local doc
     */
    @Test
    fun testConflictResolverMergeLocal() {
        makeConflict(DOC1, hashMapOf(KEY1 to VAL1), hashMapOf(KEY2 to VAL2))

        val pullConfig = pullConfig(TestConflictResolver { conflict ->
            val doc = conflict.localDocument?.toMutable()
            doc?.setString(KEY3, VAL3)
            doc
        })

        run(pullConfig, 0, null)

        assertEquals(1, db.count)

        val doc = db.getDocument(DOC1)
        assertEquals(2, doc.count())
        assertEquals(VAL1, doc.getString(KEY1))
        assertEquals(VAL3, doc.getString(KEY3))

        val prePushSeq = otherDB.getDocument(DOC1).sequence

        run(pushConfig(), 0, null)

        assertTrue(prePushSeq < otherDB.c4Database.get(DOC1, false).sequence)
    }

    /**
     * #6
     * 1. Test a custom conflict resolver that returns a merged document.
     * 2. The merge document could a mutated local doc, a mutated remote doc, or a new doc.
     * 3. Make sure that the merged doc wins and is saved to the database.
     * 4. Make sure that the resolved doc can be push to the remote database.
     * Case: Mutated Remote doc
     */
    @Test
    fun testConflictResolverMergeRemote() {
        makeConflict(DOC1, hashMapOf(KEY1 to VAL1), hashMapOf(KEY2 to VAL2))

        val pullConfig = pullConfig(TestConflictResolver { conflict ->
            val doc = conflict.remoteDocument?.toMutable()
            doc?.setString(KEY3, VAL3)
            doc
        })

        run(pullConfig, 0, null)

        assertEquals(1, db.count)

        val doc = db.getDocument(DOC1)
        assertEquals(2, doc.count())
        assertEquals(VAL2, doc.getString(KEY2))
        assertEquals(VAL3, doc.getString(KEY3))

        val prePushSeq = otherDB.getDocument(DOC1).sequence

        run(pushConfig(), 0, null)

        assertTrue(prePushSeq < otherDB.c4Database.get(DOC1, false).sequence)
    }

    /**
     * #6
     * 1. Test a custom conflict resolver that returns a merged document.
     * 2. The merge document could a mutated local doc, a mutated remote doc, or a new doc.
     * 3. Make sure that the merged doc wins and is saved to the database.
     * 4. Make sure that the resolved doc can be push to the remote database.
     * Case: New doc
     */
    @Test
    fun testConflictResolverMergeNew() {
        makeConflict(DOC1, hashMapOf(KEY1 to VAL1), hashMapOf(KEY2 to VAL2))

        val pullConfig = pullConfig(TestConflictResolver { conflict ->
            val doc = MutableDocument(conflict.documentId)
            doc.setString(KEY3, VAL3)
            doc
        })

        run(pullConfig, 0, null)

        assertEquals(1, db.count)

        val doc = db.getDocument(DOC1)
        assertEquals(1, doc.count())
        assertEquals(VAL3, doc.getString(KEY3))

        val prePushSeq = otherDB.getDocument(DOC1).sequence

        run(pushConfig(), 0, null)

        assertTrue(prePushSeq < otherDB.c4Database.get(DOC1, false).sequence)
    }

    /**
     * #7
     * 1. Test a custom conflict resolver that returns a null document.
     * 2. Make sure to test returning null when both remote and local doc are not null
     *    and when remote or local doc is null.
     * 3. Make sure that the document is deleted from the database.
     * 4. Make sure that the resolved doc can be push to the remote database.
     * Case: Local is deleted
     */
    @Test
    fun testConflictResolverNullLocalDeleted() {
        makeConflict(DOC1, null, hashMapOf(KEY2 to VAL2))

        run(pullConfig(NullResolver), 0, null)

        assertEquals(0, db.count)

        val prePushSeq = otherDB.getDocument(DOC1).sequence

        run(pushConfig(), 0, null)

        assertTrue(prePushSeq < otherDB.c4Database.get(DOC1, false).sequence)
    }

    /**
     * #7
     * 1. Test a custom conflict resolver that returns a null document.
     * 2. Make sure to test returning null when both remote and local doc are not null
     *    and when remote or local doc is null.
     * 3. Make sure that the document is deleted from the database.
     * 4. Make sure that the resolved doc can be push to the remote database.
     * Case: Remote is deleted
     */
    @Test
    fun testConflictResolverNullRemoteDeleted() {
        makeConflict(DOC1, hashMapOf(KEY1 to VAL1), null)

        run(pullConfig(NullResolver), 0, null)

        assertEquals(0, db.count)

        val prePushSeq = otherDB.c4Database.get(DOC1, false).sequence

        run(pushConfig(), 0, null)

        assertEquals(prePushSeq, otherDB.c4Database.get(DOC1, false).sequence)
    }

    /**
     * #7
     * 1. Test a custom conflict resolver that returns a null document.
     * 2. Make sure to test returning null when both remote and local doc are not null
     *    and when remote or local doc is null.
     * 3. Make sure that the document is deleted from the database.
     * 4. Make sure that the resolved doc can be push to the remote database.
     * Case: Local and remote exist
     */
    @Test
    fun testConflictResolverNullBothExist() {
        makeConflict(DOC1, hashMapOf(KEY1 to VAL1), hashMapOf(KEY2 to VAL2))

        run(pullConfig(NullResolver), 0, null)

        assertEquals(0, db.count)

        val prePushSeq = otherDB.getDocument(DOC1).sequence

        run(pushConfig(), 0, null)

        assertTrue(prePushSeq < otherDB.c4Database.get(DOC1, false).sequence)
    }

    /**
     * #8
     * 1. The conflict resolver is running asynchronously outside database transaction.
     *    It's possible that the conflict might happen again after trying to save the resolved document to the database.
     * 2. We could simulate this situation by update the local document before returning a resolved doc
     *    and make sure that the conflict resolver is called again.
     */
    @Test
    fun testConflictResolverCalledTwice() {
        makeConflict(DOC1, hashMapOf(KEY1 to VAL1), hashMapOf(KEY2 to VAL2))

        val pullConfig = pullConfig()
        var count = 0
        pullConfig.conflictResolver = TestConflictResolver { conflict ->
            if (count++ <= 0) {
                val savedDoc = db.getDocument(DOC1).toMutable()
                savedDoc.setString(KEY3, VAL3)
                db.save(savedDoc)
            }

            val doc = conflict.localDocument?.toMutable()
            doc?.setString(KEY4, VAL4)
            doc
        }

        run(pullConfig, 0, null)

        // verify that the resolver was called twice
        assertEquals(2, count)

        assertEquals(1, db.count)

        val doc = db.getDocument(DOC1)
        assertEquals(3, doc.count())
        assertEquals(VAL1, doc.getString(KEY1))
        assertEquals(VAL3, doc.getString(KEY3))
        assertEquals(VAL4, doc.getString(KEY4))
    }

    /**
     * #9
     * 1. Test that there could be multiple conflicts resolver running at the same time without blocking each other.
     */
    @Test
    fun testConflictResolversRunConcurrently() {
        val barrier = CyclicBarrier(2)
        val latch = CountDownLatch(2)

        makeConflict(DOC1, hashMapOf(KEY1 to VAL1), hashMapOf(KEY2 to VAL2))

        val pullConfig1 = pullConfig()
        pullConfig1.conflictResolver = TestConflictResolver { conflict ->
            try {
                barrier.await(10, TimeUnit.SECONDS)
            } catch (ignore: BrokenBarrierException) {
            }
            conflict.localDocument
        }
        val repl1 = Replicator(pullConfig1)
        val token1 = repl1.addChangeListener { change ->
            if (change.status.activityLevel == AbstractReplicator.ActivityLevel.STOPPED) {
                latch.countDown()
            }
        }

        val pullConfig2 = pullConfig()
        pullConfig2.conflictResolver = TestConflictResolver { conflict ->
            try {
                barrier.await(10, TimeUnit.SECONDS)
            } catch (ignore: BrokenBarrierException) {
            }
            conflict.localDocument
        }
        val repl2 = Replicator(pullConfig1)
        val token2 = repl2.addChangeListener { change ->
            if (change.status.activityLevel == AbstractReplicator.ActivityLevel.STOPPED) {
                latch.countDown()
            }
        }

        repl1.start()
        repl2.start()

        // ??? 30s seems like a long time but this test fails, occasionally, at 10s
        assertTrue(latch.await(30, TimeUnit.SECONDS))

        repl1.removeChangeListener(token1)
        repl2.removeChangeListener(token2)
    }

    /**
     * #10
     * 1. Test that returning a document with a wrong document ID should be OK,
     *    however, here should be a warning message logged about this
     *    Resolved document is mutable.
     */
    @Test
    fun testConflictResolverWrongDocIdMutable() {
        makeConflict(DOC1, hashMapOf(KEY1 to VAL1), hashMapOf(KEY2 to VAL2))

        // the document that the resolver will return: it has a wrong ID
        val doc2 = MutableDocument(DOC3)
        doc2.setString(KEY3, VAL3)

        var replicator: Replicator? = null
        var token: ListenerToken? = null
        val errors = mutableListOf<CouchbaseLiteException?>()
        run(pullConfig(TestConflictResolver { doc2 }), 0, null, false, false) { r: Replicator ->
            replicator = r
            token = r.addDocumentReplicationListener { docRepl: DocumentReplication ->
                docRepl.documents[0].error?.let { errors.add(it) }
            }
        }
        replicator?.removeChangeListener(token!!)

        assertEquals(0, errors.size)

        val doc1 = db.getDocument(DOC1)
        assertEquals(doc2.count(), doc1.count())
        assertEquals(VAL3, doc1.getString(KEY3))
    }

    /**
     * #10
     * 1. Test that returning a document with a wrong document ID should be OK,
     *    however, here should be a warning message logged about this
     *    Resolved document is not mutable.
     */
    @Test
    fun testConflictResolverWrongDocIdImmutable() {
        makeConflict(DOC1, hashMapOf(KEY1 to VAL1), hashMapOf(KEY2 to VAL2))

        // the document that the resolver will return: it has a wrong ID
        val doc = MutableDocument(DOC3)
        doc.setString(KEY3, VAL3)
        db.save(doc)
        val doc3 = db.getDocument(DOC3)

        var replicator: Replicator? = null
        var token: ListenerToken? = null
        val errors = mutableListOf<CouchbaseLiteException?>()
        run(pullConfig(TestConflictResolver { doc3 }), 0, null, false, false) { r: Replicator ->
            replicator = r
            token = r.addDocumentReplicationListener { docRepl: DocumentReplication ->
                docRepl.documents[0].error?.let { errors.add(it) }
            }
        }
        replicator?.removeChangeListener(token!!)

        assertEquals(0, errors.size)

        val doc1 = db.getDocument(DOC1)
        assertEquals(doc3.count(), doc1.count())
        assertEquals(VAL3, doc1.getString(KEY3))
    }

    /**
     * #11
     * 1. Test that returning a document from a different db instance (from local db)
     *    should cause a runtime exception to be thrown.
     * 2. Make sure that the document will be resolved after restarting the replicator.
     */
    @Test
    fun testConflictResolverWrongDB() {
        val otherDoc = MutableDocument(DOC1)
        otherDoc.setString(KEY1, VAL1)
        otherDB.save(otherDoc)

        makeConflict(DOC1, hashMapOf(KEY1 to VAL1), hashMapOf(KEY2 to VAL2))

        var replicator: Replicator? = null
        var token: ListenerToken? = null
        val errors = mutableListOf<CouchbaseLiteException>()
        run(pullConfig(TestConflictResolver { otherDB.getDocument(DOC1) }), 0, null, false, false) { r: Replicator ->
            replicator = r
            token = r.addDocumentReplicationListener { docRepl: DocumentReplication ->
                docRepl.documents[0].error?.let { errors.add(it) }
            }
        }

        assertEquals(1, errors.size)
        val error = errors[0]
        assertEquals(CBLError.Domain.CBLITE, error.domain)
        assertEquals(CBLError.Code.UNEXPECTED_ERROR, error.code)

        val doc1 = db.getDocument(DOC1)
        assertEquals(1, doc1.count())
        assertEquals(VAL1, doc1?.getString(KEY1))

        replicator?.removeChangeListener(token!!)
    }

    /**
     * #12
     * 1. Test that an exception thrown from the conflict resolver is handled correctly.
     * 2. The exception should be captured and a conflict error should be reported in a DocumentReplication event.
     * 3. Make sure that the document will be resolved after restarting the replicator.
     */
    @Test
    fun testConflictResolverThrows() {
        val otherDoc = MutableDocument(DOC1)
        otherDoc.setString(KEY1, VAL1)
        otherDB.save(otherDoc)

        makeConflict(DOC1, hashMapOf(KEY1 to VAL1), hashMapOf(KEY2 to VAL2))

        val pullConfig = pullConfig(TestConflictResolver { throw IllegalStateException("freak out!") })

        var replicator: Replicator? = null
        var token: ListenerToken? = null
        val errors = mutableListOf<CouchbaseLiteException>()
        run(pullConfig, 0, null, false, false) { r: Replicator ->
            replicator = r
            token = r.addDocumentReplicationListener { docRepl: DocumentReplication ->
                docRepl.documents[0].error?.let { errors.add(it) }
            }
        }
        replicator?.removeChangeListener(token!!)

        assertEquals(1, errors.size)
        val error = errors[0]
        assertEquals(CBLError.Domain.CBLITE, error.domain)
        assertEquals(CBLError.Code.UNEXPECTED_ERROR, error.code)

        val doc1 = db.getDocument(DOC1)
        assertEquals(1, doc1.count())
        assertEquals(VAL1, doc1?.getString(KEY1))

        run(pullConfig(RemoteResolver), 0, null)

        val doc2 = db.getDocument(DOC1)
        assertEquals(1, doc2.count())
        assertEquals(VAL2, doc2?.getString(KEY2))
    }

    /**
     * #13
     * 1. Test that the DocumentReplicationEvent will not be notified until the conflicted document is resolved.
     */
    @Test
    fun testDocumentReplicationEventForConflictedDocs() {
        var ids = validateDocumentReplicationEventForConflictedDocs(TestConflictResolver { otherDB.getDocument(DOC1) })
        assertEquals(1, ids.size)
        assertEquals(ids[0], DOC1)

        ids = validateDocumentReplicationEventForConflictedDocs(TestConflictResolver { MutableDocument("hooey") })
        assertEquals(1, ids.size)
        assertEquals(ids[0], DOC1)

        ids = validateDocumentReplicationEventForConflictedDocs(RemoteResolver)
        assertEquals(1, ids.size)
        assertEquals(ids[0], DOC1)
    }

    /**
     * #14
     * 1. Test that the ConflictResolution.default behaves correct.
     * 2. We should already have tests for this already.
     * Case: deleted local
     */
    @Test
    fun testConflictResolutionDefault1() {
        makeConflict(DOC1, hashMapOf(KEY1 to VAL1), hashMapOf(KEY2 to VAL2))

        // delete local
        db.delete(db.getDocument(DOC1))

        run(pullConfig(), 0, null)

        assertTrue(db.c4Database.get(DOC1, false).deleted())
    }

    /**
     * #14
     * 1. Test that the ConflictResolution.default behaves correct.
     * 2. We should already have tests for this already.
     * Case: deleted remote
     */
    @Test
    fun testConflictResolutionDefault2() {
        makeConflict(DOC1, hashMapOf(KEY1 to VAL1), hashMapOf(KEY2 to VAL2))

        // delete remote
        otherDB.delete(otherDB.getDocument(DOC1))

        run(pullConfig(), 0, null)

        assertTrue(db.c4Database.get(DOC1, false).deleted())
    }

    /**
     * #14
     * 1. Test that the ConflictResolution.default behaves correct.
     * 2. We should already have tests for this already.
     * Case: return newer doc
     */
    @Test
    fun testConflictResolutionDefaul3() {
        makeConflict(DOC1, hashMapOf(KEY1 to VAL1), hashMapOf(KEY2 to VAL2))

        // local has higher generation
        val doc = db.getDocument(DOC1).toMutable()
        doc.setString(KEY3, VAL3)
        db.save(doc)

        run(pullConfig(), 0, null)

        assertTrue(db.getDocument(DOC1).generation() > otherDB.getDocument(DOC1).generation())
    }

    /**
     * #14
     * 1. Test that the ConflictResolution.default behaves correct.
     * 2. We should already have tests for this already.
     * Case: return newer doc
     */
    @Test
    fun testConflictResolutionDefault4() {
        makeConflict(DOC1, hashMapOf(KEY1 to VAL1), hashMapOf(KEY2 to VAL2))

        // remote has higher generation
        val doc = otherDB.getDocument(DOC1).toMutable()
        doc.setString(KEY3, VAL3)
        otherDB.save(doc)

        run(pullConfig(), 0, null)

        assertEquals(db.getDocument(DOC1).generation(), otherDB.getDocument(DOC1).generation())
    }

    /**
     * #14
     * 1. Test that the ConflictResolution.default behaves correct.
     * 2. We should already have tests for this already.
     * Case: delete and generation
     */
    @Test
    fun testConflictResolutionDefault5() {
        makeConflict(DOC1, hashMapOf(KEY1 to VAL1), hashMapOf(KEY2 to VAL2))

        // delete local
        db.delete(db.getDocument(DOC1))

        // higher remote generation
        val doc = otherDB.getDocument(DOC1).toMutable()
        doc.setString(KEY3, VAL3)
        otherDB.save(doc)

        run(pullConfig(), 0, null)

        assertTrue(db.c4Database.get(DOC1, false).deleted())
    }

    /**
     * #14
     * 1. Test that the ConflictResolution.default behaves correct.
     * 2. We should already have tests for this already.
     * Case: delete and generation
     */
    @Test
    fun testConflictResolutionDefault6() {
        makeConflict(DOC1, hashMapOf(KEY1 to VAL1), hashMapOf(KEY2 to VAL2))

        val localDocRevId = db.getDocument(DOC1).revisionID
        val remoteDocRevId = otherDB.getDocument(DOC1).revisionID

        run(pullConfig(), 0, null)

        val resolvedDoc = otherDB.c4Database.get(DOC1, false)
        assertFalse(resolvedDoc.deleted())
        assertEquals(
                if (localDocRevId.compareTo(remoteDocRevId) > 0) localDocRevId else remoteDocRevId,
                resolvedDoc.revID)
    }

    /**
     * #15
     * 1. Test that the blob objects in the resolved document can be saved to the database.
     * 2. We should test blob for all returning cases including localDoc, remoteDoc, and new doc with a blob object.
     * Case: Local has blob; Remote wins
     */
    @Test
    fun testConflictResolverRemoteWithLocalBlob() {
        val blob = Blob("text/plain", "I'm a blob".toByteArray())

        makeConflict(DOC1, hashMapOf(KEY1 to VAL1, KEY5 to blob), hashMapOf(KEY2 to VAL2))

        run(pullConfig(RemoteResolver), 0, null)

        val doc = db.getDocument(DOC1)
        assertNull(doc.getBlob(KEY5)) // redundant check to verify no blob
        assertEquals(1, doc.count())
        assertEquals(VAL2, doc.getString(KEY2))
    }

    /**
     * #15
     * 1. Test that the blob objects in the resolved document can be saved to the database.
     * 2. We should test blob for all returning cases including localDoc, remoteDoc, and new doc with a blob object.
     * Case: Local has blob; Local wins
     */
    @Test
    fun testConflictResolverLocalWithLocalBlob() {
        val blob = Blob("text/plain", "I'm a blob".toByteArray())

        makeConflict(DOC1, hashMapOf(KEY1 to VAL1, KEY5 to blob), hashMapOf(KEY2 to VAL2))

        run(pullConfig(LocalResolver), 0, null)

        val doc = db.getDocument(DOC1)
        assertEquals(2, doc.count())
        assertEquals(VAL1, doc.getString(KEY1))
        assertEquals(blob, doc.getBlob(KEY5))
    }

    /**
     * #15
     * 1. Test that the blob objects in the resolved document can be saved to the database.
     * 2. We should test blob for all returning cases including localDoc, remoteDoc, and new doc with a blob object.
     * Case: Remote has blob; remote wins
     */
    @Test
    fun testConflictResolverRemoteWithRemoteBlob() {
        val blob = Blob("text/plain", "I'm a blob".toByteArray())

        makeConflict(DOC1, hashMapOf(KEY1 to VAL1), hashMapOf(KEY2 to VAL2, KEY5 to blob))

        run(pullConfig(RemoteResolver), 0, null)

        val doc = db.getDocument(DOC1)
        assertEquals(2, doc.count())
        assertEquals(VAL2, doc.getString(KEY2))
        assertEquals(blob, doc.getBlob(KEY5))
    }


    /**
     * #15
     * 1. Test that the blob objects in the resolved document can be saved to the database.
     * 2. We should test blob for all returning cases including localDoc, remoteDoc, and new doc with a blob object.
     * Case: Remote has blob; local wins
     */
    @Test
    fun testConflictResolverLocalWithRemoteBlob() {
        val blob = Blob("text/plain", "I'm a blob".toByteArray())

        makeConflict(DOC1, hashMapOf(KEY1 to VAL1), hashMapOf(KEY2 to VAL2, KEY5 to blob))

        run(pullConfig(LocalResolver), 0, null)

        val doc = db.getDocument(DOC1)
        assertNull(doc.getBlob(KEY5)) // redundant check to verify no blob
        assertEquals(1, doc.count())
        assertEquals(VAL1, doc.getString(KEY1))
    }

    /**
     * #15
     * 1. Test that the blob objects in the resolved document can be saved to the database.
     * 2. We should test blob for all returning cases including localDoc, remoteDoc, and new doc with a blob object.
     * Case: Neither has blob new doc with blob wins
     */
    @Test
    fun testConflictResolverNewWithBlob() {
        val blob = Blob("text/plain", "I'm a blob".toByteArray())

        makeConflict(DOC1, hashMapOf(KEY1 to VAL1), hashMapOf(KEY2 to VAL2))

        val pullConfig = pullConfig(TestConflictResolver { conflict ->
            val doc = MutableDocument(conflict.documentId)
            doc.setBlob(KEY5, blob)
            doc
        })

        run(pullConfig, 0, null)

        val doc = db.getDocument(DOC1)
        assertEquals(1, doc.count())
        assertEquals(blob, doc.getBlob(KEY5))
    }

    /**
     * #16
     * 1. Using blob object from a different database is currently not allowed.
     *    Test whether the error has been captured and thrown or not
     * Case: Blob from Local DB
     */
    @Test
    fun testConflictResolverReturnsBlobFromLocalDB() {
        val blob = Blob("text/plain", "I'm a blob".toByteArray())

        makeConflict(DOC1, hashMapOf(KEY1 to VAL1, KEY5 to blob), hashMapOf(KEY2 to VAL2))

        val pullConfig = pullConfig(TestConflictResolver { conflict ->
            val doc = conflict.remoteDocument?.toMutable()
            doc?.setBlob(KEY5, conflict.localDocument?.getBlob(KEY5))
            doc
        })

        var replicator: Replicator? = null
        var token: ListenerToken? = null
        val errors = mutableListOf<CouchbaseLiteException>()
        run(pullConfig, 0, null, false, false) { r: Replicator ->
            replicator = r
            token = r.addDocumentReplicationListener { docRepl: DocumentReplication ->
                docRepl.documents[0].error?.let { errors.add(it) }
            }
        }
        replicator?.removeChangeListener(token!!)

        assertEquals(0, errors.size)

        val doc = db.getDocument(DOC1)
        assertEquals(2, doc.count())
        assertEquals(VAL2, doc.getString(KEY2))
        assertNotNull(doc.getBlob(KEY5))
    }

    /**
     * #16
     * 1. Using blob object from a different database is currently not allowed.
     *    Test whether the error has been captured and thrown or not
     * Case: Blob from Remote DB
     */
    @Test
    fun testConflictResolverReturnsBlobFromRemoteDB() {
        val blob = Blob("text/plain", "I'm a blob".toByteArray())

        makeConflict(DOC1, hashMapOf(KEY1 to VAL1), hashMapOf(KEY2 to VAL2, KEY5 to blob))

        val pullConfig = pullConfig(TestConflictResolver { conflict ->
            val doc = conflict.localDocument?.toMutable()
            doc?.setBlob(KEY5, conflict.remoteDocument?.getBlob(KEY5))
            doc
        })

        var replicator: Replicator? = null
        var token: ListenerToken? = null
        val errors = mutableListOf<CouchbaseLiteException>()
        run(pullConfig, 0, null, false, false) { r: Replicator ->
            replicator = r
            token = r.addDocumentReplicationListener { docRepl: DocumentReplication ->
                docRepl.documents[0].error?.let { errors.add(it) }
            }
        }
        replicator?.removeChangeListener(token!!)

        assertEquals(0, errors.size)

        val doc = db.getDocument(DOC1)
        assertEquals(2, doc.count())
        assertEquals(VAL1, doc.getString(KEY1))
        assertNotNull(doc.getBlob(KEY5))
    }

    /**
     * #16
     * 1. Using blob object from a different database is currently not allowed.
     *    Test whether the error has been captured and thrown or not
     */
    @Test
    fun testConflictResolverReturnsBlobFromWrongDB() {
        val blob = Blob("text/plain", "I'm a blob".toByteArray())
        val otherDbDoc = MutableDocument(DOC4)
        otherDbDoc.setBlob(KEY5, blob)
        otherDB.save(otherDbDoc)

        makeConflict(DOC1, hashMapOf(KEY1 to VAL1), hashMapOf(KEY2 to VAL2))

        val pullConfig = pullConfig(TestConflictResolver { conflict ->
            val doc = conflict.localDocument?.toMutable()
            doc?.setBlob(KEY5, otherDbDoc.getBlob(KEY5))
            doc
        })

        var replicator: Replicator? = null
        var token: ListenerToken? = null
        val errors = mutableListOf<CouchbaseLiteException>()
        run(pullConfig, 0, null, false, false) { r: Replicator ->
            replicator = r
            token = r.addDocumentReplicationListener { docRepl: DocumentReplication ->
                docRepl.documents[0].error?.let { errors.add(it) }
            }
        }
        replicator?.removeChangeListener(token!!)

        assertEquals(1, errors.size)
        val error = errors[0]
        assertEquals(CBLError.Domain.CBLITE, error.domain)
        assertEquals(CBLError.Code.UNEXPECTED_ERROR, error.code)

        val doc = db.getDocument(DOC1)
        assertEquals(1, doc.count())
        assertEquals(VAL1, doc.getString(KEY1))
        assertNull(doc.getBlob(KEY5))
    }

    /**
     * #17
     * 1. Test that there could be database operations possible when conflicts resolver
     *    is running at the same time without blocking each other.
     * 2. Try to do a database operation from inside the conflict resolver.
     *    Make sure the document operation is successfull before the custom conflict is resolved.
     */
    @Test
    fun testConflictResolverDoesntBlockTransactions() {
        makeConflict(DOC1, hashMapOf(KEY1 to VAL1), hashMapOf(KEY2 to VAL2))

        var count = 0
        var doc: Document? = null
        val pullConfig = pullConfig(TestConflictResolver { conflict ->
            count++
            val newDoc = MutableDocument(DOC4)
            newDoc.setValue(KEY4, VAL4)
            db.save(newDoc)
            doc = db.getDocument(DOC4)
            conflict.remoteDocument
        })

        run(pullConfig, 0, null)

        assertNotNull(doc)
        assertEquals(1, doc?.count())
        assertEquals(VAL4, doc?.getString(KEY4))

        val savedDoc = db.getDocument(DOC4)
        assertNotNull(savedDoc)
        assertEquals(1, savedDoc?.count())
        assertEquals(VAL4, savedDoc?.getString(KEY4))

        val resolvedDoc = db.getDocument(DOC1)
        assertNotNull(resolvedDoc)
        assertEquals(1, resolvedDoc?.count())
        assertEquals(VAL2, resolvedDoc?.getString(KEY2))
    }

    /**
     * #18
     * 1. It's possible that the same conflicts might get resolved more than one time.
     *    For example, the conflict resolving tasks in the queue are still in progress
     *    while the replicator becomes offline. Then the replicator becomes right online which results
     *    to a new replicator gets started. When the replicator is started, it will try to resolve
     *    the pending conflicts which could be duplicatied to the ones are currently in the queue.
     * 2. The expected behavior would be the the duplicated ones that are resolved after should be ignored.
     *    There will be two document replication notifications without errors.
     */
    @Test
    fun testConflictResolverSameConflictsTwice() {
        val latch1 = CountDownLatch(1)
        val latch2 = CountDownLatch(1)
        val latch3 = CountDownLatch(1)
        val latch4 = CountDownLatch(1)

        makeConflict(DOC1, hashMapOf(KEY1 to VAL1), hashMapOf(KEY2 to VAL2))

        val pullConfig1 = pullConfig(TestConflictResolver { conflict ->
            latch1.countDown()
            latch2.await(10, TimeUnit.SECONDS)
            conflict.localDocument
        })
        val repl1 = Replicator(pullConfig1)
        val token1c = repl1.addChangeListener { change ->
            if (change.status.activityLevel == AbstractReplicator.ActivityLevel.STOPPED) {
                latch4.countDown()
            }
        }
        var docRepl1: DocumentReplication? = null
        val token1e = repl1.addDocumentReplicationListener({ repl -> docRepl1 = repl })
        repl1.start()
        assertTrue(latch1.await(10, TimeUnit.SECONDS))

        // the first replicator is running but stuck.

        val pullConfig2 = pullConfig(RemoteResolver)
        val repl2 = Replicator(pullConfig2)
        val token2c = repl2.addChangeListener { change ->
            if (change.status.activityLevel == AbstractReplicator.ActivityLevel.STOPPED) {
                latch3.countDown()
            }
        }
        var docRepl2: DocumentReplication? = null
        val token2e = repl2.addDocumentReplicationListener({ repl -> docRepl2 = repl })
        repl2.start()

        assertTrue(latch3.await(10, TimeUnit.SECONDS))
        // the second replicator is complete.
        repl2.removeChangeListener(token2c)
        repl2.removeChangeListener(token2e)

        assertNotNull(docRepl2)
        assertEquals(1, docRepl2!!.documents.size)
        assertEquals(DOC1, docRepl2!!.documents[0].id)
        assertNull(docRepl2!!.documents[0].error)

        var doc = db.getDocument(DOC1)
        assertEquals(1, doc.count())
        assertEquals(VAL2, doc.getString(KEY2))

        // restart the first replicator.
        latch2.countDown()

        assertTrue(latch4.await(10, TimeUnit.SECONDS))
        // first replicator is complete
        repl1.removeChangeListener(token1c)
        repl1.removeChangeListener(token1e)

        assertNotNull(docRepl1)
        assertEquals(1, docRepl1!!.documents.size)
        assertEquals(DOC1, docRepl1!!.documents[0].id)
        assertNull(docRepl1!!.documents[0].error)

        doc = db.getDocument(DOC1)
        assertEquals(1, doc.count())
        assertEquals(VAL2, doc.getString(KEY2))
    }

    /**
     * #19
     * 1. Purge the original document inside the conflict resolver.
     * 2. Make sure, we receieve DocumentNotFound error in document-replication-listener.
     */
    @Test
    fun testConflictResolverWhenDocumentIsPurged() {
        makeConflict(DOC1, hashMapOf(KEY1 to VAL1), hashMapOf(KEY2 to VAL2))

        val pullConfig = pullConfig(TestConflictResolver { conflict ->
            db.purge(DOC1)
            conflict.remoteDocument
        })

        var replicator: Replicator? = null
        var token: ListenerToken? = null
        val errors = mutableListOf<CouchbaseLiteException>()
        run(pullConfig, 0, null, false, false) { r: Replicator ->
            replicator = r
            token = r.addDocumentReplicationListener { docRepl: DocumentReplication ->
                docRepl.documents[0].error?.let { errors.add(it) }
            }
        }
        replicator?.removeChangeListener(token!!)

        assertEquals(1, errors.size)
        val error = errors[0]
        assertEquals(CBLError.Domain.CBLITE, error.domain)
        assertEquals(CBLError.Code.NOT_FOUND, error.code)
    }

    /**
     * @borrrden's merge test, for good measure.
     */
    @Test
    fun testConflictResolverMergeDoc() {
        makeConflict(DOC1, hashMapOf(KEY1 to VAL1), hashMapOf(KEY1 to VAL1, KEY2 to VAL2))

        val pullConfig = pullConfig(TestConflictResolver { conflict ->
            val updateDocDict = conflict.localDocument!!.toMap()
            val curDocDict = conflict.remoteDocument!!.toMap()

            for (entry in curDocDict) {
                if (!updateDocDict.containsKey(entry.key)) {
                    updateDocDict[entry.key] = entry.value
                } else if (entry.value != updateDocDict[entry.key]) {
                    updateDocDict[entry.key] = "${entry.value}, ${updateDocDict[entry.key]}"
                }
            }

            val mergedDoc = MutableDocument(conflict.documentId)
            mergedDoc.setData(updateDocDict)

            mergedDoc
        })

        run(pullConfig, 0, null)

        var doc = db.getDocument(DOC1).toMutable()
        doc.setString(KEY1, VAL1)
        doc.setString(KEY3, VAL3)
        db.save(doc)

        doc = otherDB.getDocument(DOC1).toMutable()
        doc.setString(KEY1, VAL1)
        doc.setString(KEY3, VAL4)
        otherDB.save(doc)

        run(pullConfig, 0, null)

        var testDoc = db.getDocument(DOC1)
        assertEquals(3, doc.count())
        assertEquals(VAL1, testDoc.getString(KEY1))
        assertEquals(VAL2, testDoc.getString(KEY2))
        var val3 = testDoc.getString(KEY3)
        assertTrue(val3.contains(VAL3))
        assertTrue(val3.contains(VAL4))

        run(pullConfig, 0, null)

        testDoc = db.getDocument(DOC1)
        assertEquals(3, doc.count())
        assertEquals(VAL1, testDoc.getString(KEY1))
        assertEquals(VAL2, testDoc.getString(KEY2))
        val3 = testDoc.getString(KEY3)
        assertTrue(val3.contains(VAL3))
        assertTrue(val3.contains(VAL4))
    }

    private fun pushConfig() = makeConfig(true, false, null)
    private fun pullConfig(resolver: ConflictResolver? = null) = makeConfig(false, true, resolver)
    private fun makeConfig(push: Boolean, pull: Boolean, resolver: ConflictResolver?) = makeConfig(push, pull, false, db, DatabaseEndpoint(otherDB), resolver)

    private fun makeConflict(docId: String, localData: Map<String, Any>?, remoteData: Map<String, Any>?) {
        val doc = MutableDocument(docId)
        db.save(doc)

        run(makeConfig(true, false, false, db, DatabaseEndpoint(otherDB)), 0, null)

        // Now make some changes in db and otherDB:
        val doc1 = db.getDocument(docId).toMutable()
        if (localData == null) {
            db.delete(doc1)
        } else {
            doc1.setData(localData)
            db.save(doc1)
        }

        // ... and otherDB:
        val doc2 = otherDB.getDocument(docId).toMutable()
        if (remoteData == null) {
            otherDB.delete(doc2)
        } else {
            doc2.setData(remoteData)
            otherDB.save(doc2)
        }
    }

    private fun validateDocumentReplicationEventForConflictedDocs(resolver: TestConflictResolver): List<String> {
        makeConflict(DOC1, hashMapOf(KEY1 to VAL1), hashMapOf(KEY2 to VAL2))

        val config = pullConfig(resolver)

        var replicator: Replicator? = null
        var token: ListenerToken? = null

        val docIds = mutableListOf<String>()
        run(config, 0, null, false, false) { r ->
            replicator = r
            token = r.addDocumentReplicationListener { docRepl ->
                for (replDoc in docRepl.documents) {
                    docIds.add(replDoc.id)
                }
            }
        }
        replicator?.removeChangeListener(token!!)

        run(pullConfig(), 0, null)

        return docIds
    }
}

