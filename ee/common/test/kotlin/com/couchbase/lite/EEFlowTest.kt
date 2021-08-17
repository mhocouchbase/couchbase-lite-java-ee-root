//
// Copyright (c) 2020, 2017 Couchbase, Inc All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// https://info.couchbase.com/rs/302-GJY-034/images/2017-10-30_License_Agreement.pdf
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
package com.couchbase.lite

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// These tests actually test stuff that is in the CE API
// but they need DatabaseEndpoint, from the EE API to do it
class EEFlowTest : BaseEEReplicatorTest() {

    @ExperimentalCoroutinesApi
    @Test
    fun testReplicatorChangeFlow() {
        val levels = mutableSetOf<ReplicatorActivityLevel>()

        runBlocking {
            val repl = Replicator(makeConfig(DatabaseEndpoint(otherDB), ReplicatorType.PUSH_AND_PULL, false))
            val latch = CountDownLatch(1)

            val collector = launch(Dispatchers.Default) {
                repl.replicatorChangesFlow(testSerialExecutor)
                    .map {
                        val status = it.status
                        val err = status.error
                        if (err != null) {
                            throw err
                        }
                        status.activityLevel
                    }
                    .onEach { level ->
                        levels.add(level)
                        if (level == ReplicatorActivityLevel.STOPPED) {
                            latch.countDown()
                        }
                    }
                    .catch {
                        latch.countDown()
                        throw it
                    }
                    .collect()
            }

            launch(Dispatchers.Default) {
                // Hate this: wait until the collector starts
                delay(20L)

                run(repl)
            }

            Assert.assertTrue("Timeout", latch.await(STD_TIMEOUT_SEC, TimeUnit.SECONDS))
            collector.cancel()
        }

        Assert.assertEquals(3, levels.size)
        Assert.assertTrue(levels.contains(ReplicatorActivityLevel.CONNECTING))
        Assert.assertTrue(levels.contains(ReplicatorActivityLevel.BUSY))
        Assert.assertTrue(levels.contains(ReplicatorActivityLevel.STOPPED))
    }

    @ExperimentalCoroutinesApi
    @Test
    fun testDocumentReplicationFlow() {
        val allDocs = mutableMapOf<String, ReplicatedDocument>()

        runBlocking {
            val repl = Replicator(makeConfig(DatabaseEndpoint(otherDB), ReplicatorType.PUSH, false))
            val latch = CountDownLatch(1)

            val collector = launch(Dispatchers.Default) {
                repl.documentReplicationFlow(testSerialExecutor)
                    .map { update -> update.documents }
                    .onEach { docs ->
                        for (doc in docs) {
                            allDocs[doc.id] = doc
                        }
                        if (allDocs.size >= 2) {
                            latch.countDown()
                        }
                    }
                    .catch {
                        latch.countDown()
                        throw it
                    }
                    .collect()
            }

            var doc = MutableDocument("doc-1")
            doc.setString("species", "Tiger")
            baseTestDb.save(doc)
            doc = MutableDocument("doc-2")
            doc.setString("species", "Poozygat")
            baseTestDb.save(doc)
            baseTestDb.delete(doc)

            launch(Dispatchers.Default) {
                // Hate this: wait until the collector starts
                delay(20L)

                run(repl)
            }

            Assert.assertTrue("Timeout", latch.await(STD_TIMEOUT_SEC, TimeUnit.SECONDS))
            collector.cancel()
        }

        val allIds = allDocs.values.map { it.id }
        Assert.assertEquals(2, allIds.size)
        Assert.assertTrue(allIds.contains("doc-1"))
        Assert.assertTrue(allIds.contains("doc-2"))

        var doc = allDocs["doc-1"]!!
        Assert.assertNull(doc.error)
        var flags = doc.flags
        Assert.assertFalse(flags.contains(DocumentFlag.DELETED))
        Assert.assertFalse(flags.contains(DocumentFlag.ACCESS_REMOVED))

        doc = allDocs["doc-2"]!!
        Assert.assertNull(doc.error)
        flags = doc.flags
        Assert.assertTrue(flags.contains(DocumentFlag.DELETED))
        Assert.assertFalse(flags.contains(DocumentFlag.ACCESS_REMOVED))
    }
}
