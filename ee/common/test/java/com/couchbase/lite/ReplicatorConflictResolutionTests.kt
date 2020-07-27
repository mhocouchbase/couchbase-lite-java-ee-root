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

import com.couchbase.lite.internal.utils.FlakyTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import java.net.URI
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

fun CountDownLatch.stdWait(): Boolean {
    try {
        return this.await(2, TimeUnit.SECONDS)
    } catch (ignore: InterruptedException) {
    }
    return false
}

class ReplicatorConflictResolutionTests : BaseEEReplicatorTest() {

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

        run(pullConfigWitResolver(RemoteResolver))

        assertEquals(1, baseTestDb.count)

        val doc = baseTestDb.getDocument(DOC1)
        assertEquals(1, doc.count())
        assertEquals(VAL2, doc.getString(KEY2))

        val prePushSeq = otherDB.getDocument(DOC1).sequence

        run(pushConfig())

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
        val pullConfig = pullConfigWitResolver(TestConflictResolver { conflict ->
            remoteDoc = conflict.remoteDocument
            null
        })

        run(pullConfig)

        assertNull(remoteDoc)

        assertEquals(0, baseTestDb.count)
        val savedDoc = baseTestDb.getDocument(DOC1)
        assertNull(savedDoc)

        val prePushSeq = otherDB.c4Database.get(DOC1, false).sequence

        run(pushConfig())

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

        run(pullConfigWitResolver(LocalResolver))

        assertEquals(1, baseTestDb.count)

        val doc = baseTestDb.getDocument(DOC1)
        assertEquals(1, doc.count())
        assertEquals(VAL1, doc.getString(KEY1))

        val prePushSeq = otherDB.getDocument(DOC1).sequence

        run(pushConfig())

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
        val pullConfig = pullConfigWitResolver(TestConflictResolver { conflict ->
            localDoc = conflict.localDocument
            null
        })

        run(pullConfig)

        assertNull(localDoc)

        assertEquals(0, baseTestDb.count)

        val savedDoc = baseTestDb.getDocument(DOC1)
        assertNull(savedDoc)

        val prePushSeq = otherDB.getDocument(DOC1).sequence

        run(pushConfig())

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

        val pullConfig = pullConfigWitResolver(TestConflictResolver { conflict ->
            val doc = conflict.localDocument?.toMutable()
            doc?.setString(KEY3, VAL3)
            doc
        })

        run(pullConfig)

        assertEquals(1, baseTestDb.count)

        val doc = baseTestDb.getDocument(DOC1)
        assertEquals(2, doc.count())
        assertEquals(VAL1, doc.getString(KEY1))
        assertEquals(VAL3, doc.getString(KEY3))

        val prePushSeq = otherDB.getDocument(DOC1).sequence

        run(pushConfig())

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

        val pullConfig = pullConfigWitResolver(TestConflictResolver { conflict ->
            val doc = conflict.remoteDocument?.toMutable()
            doc?.setString(KEY3, VAL3)
            doc
        })

        run(pullConfig)

        assertEquals(1, baseTestDb.count)

        val doc = baseTestDb.getDocument(DOC1)
        assertEquals(2, doc.count())
        assertEquals(VAL2, doc.getString(KEY2))
        assertEquals(VAL3, doc.getString(KEY3))

        val prePushSeq = otherDB.getDocument(DOC1).sequence

        run(pushConfig())

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

        val pullConfig = pullConfigWitResolver(TestConflictResolver { conflict ->
            val doc = MutableDocument(conflict.documentId)
            doc.setString(KEY3, VAL3)
            doc
        })

        run(pullConfig)

        assertEquals(1, baseTestDb.count)

        val doc = baseTestDb.getDocument(DOC1)
        assertEquals(1, doc.count())
        assertEquals(VAL3, doc.getString(KEY3))

        val prePushSeq = otherDB.getDocument(DOC1).sequence

        run(pushConfig())

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

        run(pullConfigWitResolver(NullResolver))

        assertEquals(0, baseTestDb.count)

        val prePushSeq = otherDB.getDocument(DOC1).sequence

        run(pushConfig())

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

        run(pullConfigWitResolver(NullResolver))

        assertEquals(0, baseTestDb.count)

        val prePushSeq = otherDB.c4Database.get(DOC1, false).sequence

        run(pushConfig())

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

        run(pullConfigWitResolver(NullResolver))

        assertEquals(0, baseTestDb.count)

        val prePushSeq = otherDB.getDocument(DOC1).sequence

        run(pushConfig())

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
                val savedDoc = baseTestDb.getDocument(DOC1).toMutable()
                savedDoc.setString(KEY3, VAL3)
                baseTestDb.save(savedDoc)
            }

            val doc = conflict.localDocument?.toMutable()
            doc?.setString(KEY4, VAL4)
            doc
        }

        run(pullConfig)

        // verify that the resolver was called twice
        assertEquals(2, count)

        assertEquals(1, baseTestDb.count)

        val doc = baseTestDb.getDocument(DOC1)
        assertEquals(3, doc.count())
        assertEquals(VAL1, doc.getString(KEY1))
        assertEquals(VAL3, doc.getString(KEY3))
        assertEquals(VAL4, doc.getString(KEY4))
    }

    /**
     * #9
     * 1. Test that there could be multiple conflicts resolver running at the same time without blocking each other.
     */
    @FlakyTest
    @Test
    fun testConflictResolversRunConcurrently() {
        val barrier = CyclicBarrier(2)
        val latch1 = CountDownLatch(1)
        val latch2 = CountDownLatch(1)
        val latch3 = CountDownLatch(2)

        makeConflict(DOC1, hashMapOf(KEY1 to VAL1), hashMapOf(KEY2 to VAL2))

        val pullConfig1 = pullConfig()
        pullConfig1.conflictResolver = TestConflictResolver { conflict ->
            barrier.await(30, TimeUnit.SECONDS)
            Thread.sleep(100)
            latch1.countDown()
            latch2.await(2, TimeUnit.SECONDS)
            latch3.countDown()
            conflict.localDocument
        }
        val repl1 = Replicator(pullConfig1)

        val pullConfig2 = pullConfig()
        pullConfig2.conflictResolver = TestConflictResolver { conflict ->
            barrier.await(30, TimeUnit.SECONDS)
            latch1.await(2, TimeUnit.SECONDS)
            Thread.sleep(100)
            latch2.countDown()
            latch3.countDown()
            conflict.localDocument
        }
        val repl2 = Replicator(pullConfig2)

        repl1.start(false)
        repl2.start(false)

        assertTrue(latch3.await(2, TimeUnit.SECONDS))
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
        run(pullConfigWitResolver(TestConflictResolver { doc2 })) { r: Replicator ->
            replicator = r
            token = r.addDocumentReplicationListener { docRepl: DocumentReplication ->
                docRepl.documents[0].error?.let { errors.add(it) }
            }
        }
        replicator?.removeChangeListener(token!!)

        assertEquals(0, errors.size)

        val doc1 = baseTestDb.getDocument(DOC1)
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
        baseTestDb.save(doc)
        val doc3 = baseTestDb.getDocument(DOC3)

        var replicator: Replicator? = null
        var token: ListenerToken? = null
        val errors = mutableListOf<CouchbaseLiteException?>()
        run(pullConfigWitResolver(TestConflictResolver { doc3 })) { r: Replicator ->
            replicator = r
            token = r.addDocumentReplicationListener { docRepl: DocumentReplication ->
                docRepl.documents[0].error?.let { errors.add(it) }
            }
        }
        replicator?.removeChangeListener(token!!)

        assertEquals(0, errors.size)

        val doc1 = baseTestDb.getDocument(DOC1)
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
        run(pullConfigWitResolver(TestConflictResolver { otherDB.getDocument(DOC1) })) { r: Replicator ->
            replicator = r
            token = r.addDocumentReplicationListener { docRepl: DocumentReplication ->
                docRepl.documents[0].error?.let { errors.add(it) }
            }
        }

        assertEquals(1, errors.size)
        val error = errors[0]
        assertEquals(CBLError.Domain.CBLITE, error.domain)
        assertEquals(CBLError.Code.UNEXPECTED_ERROR, error.code)

        val doc1 = baseTestDb.getDocument(DOC1)
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

        val pullConfig =
            pullConfigWitResolver(TestConflictResolver { throw IllegalStateException("freak out!") })

        var replicator: Replicator? = null
        var token: ListenerToken? = null
        val errors = mutableListOf<CouchbaseLiteException>()
        run(pullConfig) { r: Replicator ->
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

        val doc1 = baseTestDb.getDocument(DOC1)
        assertEquals(1, doc1.count())
        assertEquals(VAL1, doc1?.getString(KEY1))

        run(pullConfigWitResolver(RemoteResolver))

        val doc2 = baseTestDb.getDocument(DOC1)
        assertEquals(1, doc2.count())
        assertEquals(VAL2, doc2?.getString(KEY2))
    }

    /**
     * #13
     * 1. Test that the DocumentReplicationEvent will not be notified until the conflicted document is resolved.
     */
    @Test
    fun testDocumentReplicationEventForConflictedDocs() {
        var ids = validateDocumentReplicationEventForConflictedDocs(TestConflictResolver {
            otherDB.getDocument(DOC1)
        })
        assertEquals(1, ids.size)
        assertEquals(ids[0], DOC1)

        ids = validateDocumentReplicationEventForConflictedDocs(TestConflictResolver {
            MutableDocument("hooey")
        })
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
        baseTestDb.delete(baseTestDb.getDocument(DOC1))

        run(pullConfig())

        assertTrue(baseTestDb.c4Database.get(DOC1, false).deleted())
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

        run(pullConfig())

        assertTrue(baseTestDb.c4Database.get(DOC1, false).deleted())
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
        val doc = baseTestDb.getDocument(DOC1).toMutable()
        doc.setString(KEY3, VAL3)
        baseTestDb.save(doc)

        run(pullConfig())

        assertTrue(baseTestDb.getDocument(DOC1).generation() > otherDB.getDocument(DOC1).generation())
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

        run(pullConfig())

        assertEquals(baseTestDb.getDocument(DOC1).generation(), otherDB.getDocument(DOC1).generation())
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
        baseTestDb.delete(baseTestDb.getDocument(DOC1))

        // higher remote generation
        val doc = otherDB.getDocument(DOC1).toMutable()
        doc.setString(KEY3, VAL3)
        otherDB.save(doc)

        run(pullConfig())

        assertTrue(baseTestDb.c4Database.get(DOC1, false).deleted())
    }

    /**
     * #14
     * 1. Test that the ConflictResolution.default behaves correct.
     * 2. We should already have tests for this already.
     * Case: delete and generation
     *
     */
    @Test
    fun testConflictResolutionDefault6() {
        makeConflict(DOC1, hashMapOf(KEY1 to VAL1), hashMapOf(KEY2 to VAL2))

        val localDoc = baseTestDb.getDocument(DOC1)
        val localDocRevId = localDoc.revisionID ?: ""
        val remoteDoc = otherDB.getDocument(DOC1)
        val remoteDocRevId = remoteDoc.revisionID ?: ""

        run(pullConfig())

        val resolvedDoc = baseTestDb.c4Database.get(DOC1, false)
        assertFalse(resolvedDoc.deleted())

        // This test is somewhat brittle.  There are two possibilities:
        //  -- The remote wins: In this case, the local document has never been seen elsewhere and it is ok
        //     simply to delete it and replace it with the remote. The local doc will have the remote's rev id
        if (remoteDocRevId > localDocRevId) {
            assertEquals(remoteDoc.toMap(), baseTestDb.getDocument(DOC1).toMap())
            assertEquals(remoteDocRevId, resolvedDoc.revID)
        }
        //  -- The local wins: In this case, we use the contents of the local doc but we have to create a new
        //     revision id for it.  The new doc will have a revision id that is neither the id of the remote
        //     nor the id of the local.
        else {
            assertEquals(localDoc.toMap(), baseTestDb.getDocument(DOC1).toMap())
            assertNotEquals(localDocRevId, resolvedDoc.revID)
            assertNotEquals(remoteDocRevId, resolvedDoc.revID)
        }
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

        run(pullConfigWitResolver(RemoteResolver))

        val doc = baseTestDb.getDocument(DOC1)
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

        run(pullConfigWitResolver(LocalResolver))

        val doc = baseTestDb.getDocument(DOC1)
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

        run(pullConfigWitResolver(RemoteResolver))

        val doc = baseTestDb.getDocument(DOC1)
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

        run(pullConfigWitResolver(LocalResolver))

        val doc = baseTestDb.getDocument(DOC1)
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

        val pullConfig = pullConfigWitResolver(TestConflictResolver { conflict ->
            val doc = MutableDocument(conflict.documentId)
            doc.setBlob(KEY5, blob)
            doc
        })

        run(pullConfig)

        val doc = baseTestDb.getDocument(DOC1)
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

        val pullConfig = pullConfigWitResolver(TestConflictResolver { conflict ->
            val doc = conflict.remoteDocument?.toMutable()
            doc?.setBlob(KEY5, conflict.localDocument?.getBlob(KEY5))
            doc
        })

        var replicator: Replicator? = null
        var token: ListenerToken? = null
        val errors = mutableListOf<CouchbaseLiteException>()
        run(pullConfig) { r: Replicator ->
            replicator = r
            token = r.addDocumentReplicationListener { docRepl: DocumentReplication ->
                docRepl.documents[0].error?.let { errors.add(it) }
            }
        }
        replicator?.removeChangeListener(token!!)

        assertEquals(0, errors.size)

        val doc = baseTestDb.getDocument(DOC1)
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

        val pullConfig = pullConfigWitResolver(TestConflictResolver { conflict ->
            val doc = conflict.localDocument?.toMutable()
            doc?.setBlob(KEY5, conflict.remoteDocument?.getBlob(KEY5))
            doc
        })

        var replicator: Replicator? = null
        var token: ListenerToken? = null
        val errors = mutableListOf<CouchbaseLiteException>()
        run(pullConfig) { r: Replicator ->
            replicator = r
            token = r.addDocumentReplicationListener { docRepl: DocumentReplication ->
                docRepl.documents[0].error?.let { errors.add(it) }
            }
        }
        replicator?.removeChangeListener(token!!)

        assertEquals(0, errors.size)

        val doc = baseTestDb.getDocument(DOC1)
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

        val pullConfig = pullConfigWitResolver(TestConflictResolver { conflict ->
            val doc = conflict.localDocument?.toMutable()
            doc?.setBlob(KEY5, otherDbDoc.getBlob(KEY5))
            doc
        })

        var replicator: Replicator? = null
        var token: ListenerToken? = null
        val errors = mutableListOf<CouchbaseLiteException>()
        run(pullConfig) { r: Replicator ->
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

        val doc = baseTestDb.getDocument(DOC1)
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
    @Ignore("!!! FAILING TEST")
    @Test
    fun testConflictResolverDoesntBlockTransactions() {
        makeConflict(DOC1, hashMapOf(KEY1 to VAL1), hashMapOf(KEY2 to VAL2))

        var count = 0
        var doc: Document? = null
        val pullConfig = pullConfigWitResolver(TestConflictResolver { conflict ->
            count++
            val newDoc = MutableDocument(DOC4)
            newDoc.setValue(KEY4, VAL4)
            baseTestDb.save(newDoc)
            doc = baseTestDb.getDocument(DOC4)
            conflict.remoteDocument
        })

        run(pullConfig)

        assertNotNull(doc)
        assertEquals(1, doc?.count())
        assertEquals(VAL4, doc?.getString(KEY4))

        val savedDoc = baseTestDb.getDocument(DOC4)
        assertNotNull(savedDoc)
        assertEquals(1, savedDoc?.count())
        assertEquals(VAL4, savedDoc?.getString(KEY4))

        val resolvedDoc = baseTestDb.getDocument(DOC1)
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
     *
     *    NOTE!: The actual behavior is not as described in the spec, above.
     *    This test, as currently written, documents expected behavior.
     *    See: https://issues.couchbase.com/browse/CBL-1050
     */
    @Test
    fun testConflictResolverSameConflictsTwice() {
        val latch1 = CountDownLatch(1)
        val latch2 = CountDownLatch(1)
        val latch3 = CountDownLatch(1)
        val latch4 = CountDownLatch(1)

        makeConflict(DOC1, hashMapOf(KEY1 to VAL1), hashMapOf(KEY2 to VAL2))

        // This replicator uses a conflict resolver that prefers the local doc
        // It will choose the version of the doc with KEY1->VAL1
        val resolver1 = object : ConflictResolver {
            var calls = 0
            override fun resolve(conflict: Conflict?): Document? {
                calls++
                latch1.countDown()
                // if the latch times out, return null
                // which will delete the doc an cause the test to fail
                return if (!latch2.stdWait()) null else conflict?.localDocument
            }
        }
        val repl1 = Replicator(pullConfigWitResolver(resolver1))
        // this is just here so that we can tell when this resolver is done.
        val token1c = repl1.addChangeListener { change ->
            if (change.status.activityLevel == AbstractReplicator.ActivityLevel.STOPPED) {
                latch4.countDown()
            }
        }
        var doc1: ReplicatedDocument? = null
        val token1e = repl1.addDocumentReplicationListener { repl ->
            val docs = repl.documents
            if (docs.size > 0) {
                doc1 = docs[0]
            }
        }
        repl1.start(false)
        assertTrue(latch1.stdWait())

        // At this point repl1 is running (latch #1 has popped)
        // but is hung (on latch #2) in its conflict resolver

        // This replicator will use the remote resolver
        // It will choose the version of the doc with KEY2->VAL2
        val resolver2 = object : ConflictResolver {
            var calls = 0
            override fun resolve(conflict: Conflict?): Document? {
                calls++
                return conflict?.remoteDocument
            }
        }
        val pullConfig2 = pullConfigWitResolver(resolver2)
        val repl2 = Replicator(pullConfig2)
        // Again, only here so that we know when this replicator is done
        val token2c = repl2.addChangeListener { change ->
            if (change.status.activityLevel == AbstractReplicator.ActivityLevel.STOPPED) {
                latch3.countDown()
            }
        }
        var doc2: ReplicatedDocument? = null
        val token2e = repl2.addDocumentReplicationListener { repl ->
            val docs = repl.documents
            var doc: ReplicatedDocument? = null
            if (docs.size > 0) {
                doc = docs[0]
            }
            doc2 = doc
        }
        repl2.start(false)

        assertTrue(latch3.stdWait())

        // repl1 is still hung but repl2 has run to completion

        repl2.removeChangeListener(token2c)
        repl2.removeChangeListener(token2e)

        // verify that repl2 replicated DOC1
        assertNotNull(doc2)
        assertEquals(DOC1, doc2!!.id)
        assertNull(doc2!!.error)

        // verify that DOC1 resolved to the remote version.
        var doc = baseTestDb.getDocument(DOC1)
        assertEquals(1, doc.count())
        assertEquals(VAL2, doc.getString(KEY2))

        // Unhang repl #1
        latch2.countDown()

        assertTrue(latch4.stdWait())

        // repl1 has now run to completion

        repl1.removeChangeListener(token1c)
        repl1.removeChangeListener(token1e)

        // verify that repl1 also replicated DOC1
        assertNotNull(doc1)
        assertEquals(DOC1, doc1!!.id)
        assertNull(doc1!!.error)

        // verify that the db contains the original local version
        // and not the remote version
        doc = baseTestDb.getDocument(DOC1)
        assertEquals(1, doc.count())
        assertEquals(VAL1, doc.getString(KEY1))
        assertNull(doc.getString(KEY2))

        // Each of the resolvers is called once
        assertEquals(1, resolver1.calls)
        assertEquals(1, resolver2.calls)
    }

    /**
     * #19
     * 1. Purge the original document inside the conflict resolver.
     * 2. Make sure, we receieve DocumentNotFound error in document-replication-listener.
     */
    @Test
    fun testConflictResolverWhenDocumentIsPurged() {
        makeConflict(DOC1, hashMapOf(KEY1 to VAL1), hashMapOf(KEY2 to VAL2))

        val pullConfig = pullConfigWitResolver(TestConflictResolver { conflict ->
            baseTestDb.purge(DOC1)
            conflict.remoteDocument
        })

        var replicator: Replicator? = null
        var token: ListenerToken? = null
        val errors = mutableListOf<CouchbaseLiteException>()
        run(pullConfig) { r: Replicator ->
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

        val pullConfig = pullConfigWitResolver(TestConflictResolver { conflict ->
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

        run(pullConfig)

        var doc = baseTestDb.getDocument(DOC1).toMutable()
        doc.setString(KEY1, VAL1)
        doc.setString(KEY3, VAL3)
        baseTestDb.save(doc)

        doc = otherDB.getDocument(DOC1).toMutable()
        doc.setString(KEY1, VAL1)
        doc.setString(KEY3, VAL4)
        otherDB.save(doc)

        run(pullConfig)

        var testDoc = baseTestDb.getDocument(DOC1)
        assertEquals(3, doc.count())
        assertEquals(VAL1, testDoc.getString(KEY1))
        assertEquals(VAL2, testDoc.getString(KEY2))
        var val3 = testDoc.getString(KEY3)
        assertTrue(val3?.contains(VAL3) ?: false)
        assertTrue(val3?.contains(VAL4) ?: false)

        run(pullConfig)

        testDoc = baseTestDb.getDocument(DOC1)
        assertEquals(3, doc.count())
        assertEquals(VAL1, testDoc.getString(KEY1))
        assertEquals(VAL2, testDoc.getString(KEY2))
        val3 = testDoc.getString(KEY3)
        assertTrue(val3?.contains(VAL3) ?: false)
        assertTrue(val3?.contains(VAL4) ?: false)
    }

    /**
     * CBL-511:
     *
     * Test the scenario that can cause to resolve a conflict of two deleted documents as follows:
     * 1. The resolver returned the remote document which is a deleted document.
     * 2. Right before the resolver saved the resolved document, the local document was deleted.
     * 3. As a result, another conflict happened when the replicator saved the resolved document.
     * The conflict was between two deleted document, one is from the resolver and another one is
     * from the local deletion.
     * 4. The conflict resolver was called with null value of both local and remote doc.
     *
     * Expected behavior:
     * two conflicted deleted documents shouldn't be treat as conflict. The replicator
     * should be able to resolve this scenario without calling the conflict resolver.
     */
    @Test
    fun testConflictResolverShouldNotGetBothDeletedLocalAndDeletedRemote() {
        makeConflict(DOC1, hashMapOf(KEY1 to VAL1), null)

        assertEquals(1, baseTestDb.count)

        var localDoc: Document? = null
        var remoteDoc: Document? = null
        val pullConfig = pullConfigWitResolver(TestConflictResolver { conflict ->
            localDoc = conflict.localDocument
            remoteDoc = conflict.remoteDocument
            baseTestDb.delete(baseTestDb.getDocument(DOC1))
            conflict.remoteDocument
        })

        run(pullConfig)
        assert(localDoc != null || remoteDoc != null)
        assertEquals(0, baseTestDb.count)
    }

    /**
     * CBL-623: Revision flags get cleared while saving resolved document
     */
    @Test
    fun testConflictResolverPreservesFlags() {
        var doc = MutableDocument(DOC1)
        doc.setString(KEY1, VAL1)
        baseTestDb.save(doc)

        // sync with the other db
        run(makeConfig(true, false, false, baseTestDb, DatabaseEndpoint(otherDB)))

        // add a blob in the local copy:
        doc = baseTestDb.getDocument(DOC1).toMutable()
        doc.setString(KEY1, VAL2)
        doc.setBlob(KEY2, Blob("text/plain", "i'm blob".toByteArray(Charsets.UTF_8)))
        baseTestDb.save(doc)
        val expectedFlags = doc.c4doc!!.selectedFlags
        assertNotEquals(0, expectedFlags)

        // add a string in the remote
        doc = otherDB.getDocument(DOC1).toMutable()
        doc.setString(KEY1, VAL3)
        doc.setString(KEY3, VAL4)
        otherDB.save(doc)
        assertNotEquals(expectedFlags, doc.c4doc!!.selectedFlags)

        var localFlags = 0
        val pullConfig = pullConfigWitResolver(TestConflictResolver { conflict ->
            localFlags = conflict.localDocument!!.c4doc!!.selectedFlags
            conflict.localDocument
        })
        run(pullConfig)

        // expected flags during conflict resolution
        assertEquals(expectedFlags, localFlags)

        // expected flags on saved doc
        val doc1 = baseTestDb.getDocument(DOC1)
        assertEquals(expectedFlags, doc1.c4doc!!.selectedFlags)
    }

    private fun makeConflict(
        docId: String,
        localData: Map<String, Any>?,
        remoteData: Map<String, Any>?
    ) {
        val doc = MutableDocument(docId)
        baseTestDb.save(doc)

        run(makeConfig(true, false, false, baseTestDb, DatabaseEndpoint(otherDB)))

        // Now make some changes in db and otherDB:
        val doc1 = baseTestDb.getDocument(docId).toMutable()
        if (localData == null) {
            baseTestDb.delete(doc1)
        } else {
            doc1.setData(localData)
            baseTestDb.save(doc1)
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

        val config = pullConfigWitResolver(resolver)

        var replicator: Replicator? = null
        var token: ListenerToken? = null

        val docIds = mutableListOf<String>()
        run(config) { r ->
            replicator = r
            token = r.addDocumentReplicationListener { docRepl ->
                for (replDoc in docRepl.documents) {
                    docIds.add(replDoc.id)
                }
            }
        }
        replicator?.removeChangeListener(token!!)

        run(pullConfig())

        return docIds
    }

    private fun pullConfigWitResolver(resolver: ConflictResolver?): ReplicatorConfiguration {
        return makeConfigTargetingOtherDb(false, true, resolver)
    }
}

