//
//  Copyright (c) 2020 Couchbase, Inc. All rights reserved.
//
//  Licensed under the Couchbase License Agreement (the "License");
//  you may not use this file except in compliance with the License.
//  You may obtain a copy of the License at
//  https://info.couchbase.com/rs/302-GJY-034/images/2017-10-30_License_Agreement.pdf
//
//  Unless required by applicable law or agreed to in writing, software
//  distributed under the License is distributed on an "AS IS" BASIS,
//  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//  See the License for the specific language governing permissions and
//  limitations under the License.
//
package com.couchbase.lite

import com.couchbase.lite.CBLError.Code.TLS_HANDSHAKE_FAILED
import com.couchbase.lite.internal.AbstractTLSIdentity
import com.couchbase.lite.internal.SecurityBaseTest
import com.couchbase.lite.internal.utils.FlakyTest
import com.couchbase.lite.internal.utils.Fn
import com.couchbase.lite.internal.utils.StringUtils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import java.net.URI
import java.security.cert.Certificate
import java.util.Arrays
import java.util.Calendar
import java.util.concurrent.BrokenBarrierException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

class URLEndpointListenerTest : BaseReplicatorTest() {
    companion object {
        @JvmStatic
        private val portFactory = AtomicInteger(30000)
        fun getPort() = portFactory.getAndIncrement()
    }

    private val listeners = mutableListOf<URLEndpointListener>()

    @Before
    fun setupURLEndpointListenerTest() {
        SecurityBaseTest.deleteTestAliases()
    }

    @After
    fun cleanupURLEndpointListenerTest() {
        listeners.forEach {
            it.stop()
            val alias = AbstractTLSIdentity.getAlias(it.tlsIdentity)
            if (alias != null) {
                PlatformSecurityTest.deleteEntries(Fn.Predicate { a -> alias == a })
            }
        }
        listeners.clear()
        SecurityBaseTest.deleteTestAliases()
    }

    @Test
    fun testPort() {
        val config = URLEndpointListenerConfiguration(otherDB)

        val port = getPort()

        config.port = port
        val listener = URLEndpointListener(config)
        listeners.add(listener)
        assertEquals(-1, listener.port)

        listener.start()
        assertEquals(port, listener.port)

        listener.stop()
        assertEquals(-1, listener.port)
    }

    @Test
    fun testEmptyPort() {
        val config = URLEndpointListenerConfiguration(otherDB)

        val listener = URLEndpointListener(config)
        listeners.add(listener)
        assertEquals(-1, listener.port)

        listener.start()
        assertTrue(listener.port > 1024)

        listener.stop()
        assertEquals(-1, listener.port)
    }

    @Test(expected = CouchbaseLiteException::class)
    fun testBusyPort() {
        val config = URLEndpointListenerConfiguration(otherDB)

        val port = getPort()

        config.port = port
        val listener1 = URLEndpointListener(config)
        listeners.add(listener1)
        listener1.start()

        val listener2 = URLEndpointListener(config)
        listeners.add(listener2)
        listener2.start()
    }

    @Test
    fun testURLs() {
        val config = URLEndpointListenerConfiguration(otherDB)

        val listener = URLEndpointListener(config)
        listeners.add(listener)
        assertEquals(0, listener.urls.count())

        listener.start()
        assertTrue(listener.urls.count() > 0)

        listener.stop()
        assertEquals(0, listener.urls.count())
    }

    @Test
    fun testPasswordAuthenticatorSucceeds() {
        val listener = listenHttp(
            false,
            ListenerPasswordAuthenticator { username, password ->
                (username == "daniel") && (String(password) == "123")
            }
        )
        listeners.add(listener)

        run(listener.endpointUri(), true, true, false, BasicAuthenticator("daniel", "123"))
    }

    @Test
    fun testPasswordAuthenticatorNullServerAuthenticator() {
        val listener = listenHttp(false, null)
        listeners.add(listener)

        run(listener.endpointUri(), true, true, false, BasicAuthenticator("Bandersnatch", "twas brillig"))
    }

    @Test
    fun testPasswordAuthenticatorBadUser() {
        val listener = listenHttp(false, ListenerPasswordAuthenticator { username, password ->
            "daniel" == username && ("123" == String(password))
        })
        listeners.add(listener)

        try {
            run(listener.endpointUri(), true, true, false, BasicAuthenticator("daneil", "123"))
        } catch (e: CouchbaseLiteException) {
            assertEquals(10401, e.code)
        }
    }

    @Test
    fun testPasswordAuthenticatorBadPassword() {
        val listener = listenHttp(
            false,
            ListenerPasswordAuthenticator { username, password ->
                "daniel" == username && ("123" == String(password))
            }
        )
        listeners.add(listener)

        try {
            run(listener.endpointUri(), true, true, false, BasicAuthenticator("daniel", "456"))
        } catch (e: CouchbaseLiteException) {
            assertEquals(10401, e.code)
        }
    }

    @Test
    fun testPasswordAuthenticatorWithTls() {
        val identity = createIdentity()
        val listener = listenTls(identity, ListenerPasswordAuthenticator { _, _ -> true })
        listeners.add(listener)

        run(listener.endpointUri(), true, true, false, BasicAuthenticator("daniel", "123"), identity.certs[0])
    }

    @Test
    fun testReplicatorServerCertWithTLSDisabled() {
        val listener = listenHttp(false, null)
        listeners.add(listener)

        val config = makeConfig(true, true, false, listener.endpoint(), null, false)
        val repl = run(config, 0, null, false, false) { r: Replicator -> assertNull(r.serverCertificates) }

        assertNull(repl.serverCertificates)
    }

    @Test
    fun testReplicationWithAnonymousTLSIdentity() {
        val doc = MutableDocument("doc1")
        doc.setString("foo", "bar")
        otherDB.save(doc)

        assertEquals(0, baseTestDb.count)

        val listener = listenTls(null, null)
        listeners.add(listener)

        assertNotNull(listener.tlsIdentity)

        val config = makeConfig(true, true, false, listener.endpoint(), null, true)
        run(config, 0, null, false, false, null)

        assertEquals(1, baseTestDb.count)
        assertNotNull(baseTestDb.getDocument("doc1"))
    }

    @Test
    fun testReplicationWithTLSIdentity() {
        val identity = createIdentity()

        val doc = MutableDocument("doc1")
        doc.setString("foo", "bar")
        otherDB.save(doc)

        assertEquals(0, baseTestDb.count)

        val listener = listenTls(identity, null)
        listeners.add(listener)

        val certs = identity.certs
        assertEquals(1, certs.size)

        val cert = certs[0]
        run(listener.endpointUri(), true, true, false, null, cert)

        assertEquals(1, baseTestDb.count)
        assertNotNull(baseTestDb.getDocument("doc1"))
    }

    @Test
    fun testCertAuthenticatorWithRootCerts() {
        val serverIdentity = createIdentity()
        val clientIdentity = createIdentity(false)

        val listener = listenTls(serverIdentity, ListenerCertificateAuthenticator(clientIdentity.certs))
        listeners.add(listener)

        run(
            listener.endpointUri(),
            true,
            true,
            false,
            ClientCertificateAuthenticator(clientIdentity),
            serverIdentity.certs[0]
        )
    }

    @Test
    fun testCertAuthenticatorWithCallbackSucceeds() {
        val serverIdentity = createIdentity()
        val clientIdentity = createIdentity(false)

        val listener = listenTls(serverIdentity, ListenerCertificateAuthenticator { true })
        listeners.add(listener)

        run(
            listener.endpointUri(),
            true,
            true,
            false,
            ClientCertificateAuthenticator(clientIdentity),
            serverIdentity.certs[0]
        )
    }

    @Test
    fun testCertAuthenticatorWithCallbackError() {
        val serverIdentity = createIdentity()
        val clientIdentity = createIdentity(false)

        val listener = listenTls(serverIdentity, ListenerCertificateAuthenticator { false })
        listeners.add(listener)

        run(
            TLS_HANDSHAKE_FAILED,
            CBLError.Domain.CBLITE,
            listener.endpointUri(),
            true,
            true,
            false,
            ClientCertificateAuthenticator(clientIdentity),
            serverIdentity.certs[0]
        )
    }

    @Test
    fun testCertAuthenticatorWithRootCertsError() {
        val serverIdentity = createIdentity()
        val clientIdentity = createIdentity(false)
        val wrongClientIdentity = createIdentity(false)

        val listener = listenTls(serverIdentity, ListenerCertificateAuthenticator(clientIdentity.certs))
        listeners.add(listener)

        run(
            TLS_HANDSHAKE_FAILED,
            CBLError.Domain.CBLITE,
            listener.endpointUri(),
            true,
            true,
            false,
            ClientCertificateAuthenticator(wrongClientIdentity),
            serverIdentity.certs[0]
        )
    }

    @FlakyTest
    @Test
    fun testConnectionStatus() {
        val latch = CountDownLatch(1)

        val lConfig = URLEndpointListenerConfiguration(otherDB)
        lConfig.setDisableTls(true)

        val listener = URLEndpointListener(lConfig)
        listeners.add(listener)

        assertNull(listener.status?.connectionCount)
        assertNull(listener.status?.activeConnectionCount)

        listener.start()
        assertEquals(0, listener.status?.connectionCount)
        assertEquals(0, listener.status?.activeConnectionCount)

        makeDoc("connectionStatus", otherDB)
        val rConfig = makeConfig(true, true, false, otherDB, listener.endpoint())

        var maxConnectionCount = 0
        var maxActiveCount = 0

        val repl = Replicator(rConfig)
        val token = repl.addChangeListener { change ->
            val status = listener.status
            if (status != null) {
                maxConnectionCount = Math.max(status.connectionCount, maxConnectionCount)
                maxActiveCount = Math.max(status.activeConnectionCount, maxActiveCount)
            }
            if (change.status.activityLevel == AbstractReplicator.ActivityLevel.STOPPED) latch.countDown()
        }

        repl.start(false)
        latch.await(STD_TIMEOUT_SECS, TimeUnit.SECONDS)

        assertEquals(1, maxConnectionCount)
        assertEquals(1, maxActiveCount)
        assertEquals(1, otherDB.count)

        listener.stop()
        repl.removeChangeListener(token)
        repl.stop()

        assertNull(listener.status?.connectionCount)
        assertNull(listener.status?.activeConnectionCount)
    }

    @Test
    fun testReplicatorAndListenerOnSameDatabase() {
        var shouldWait = true
        val barrier = CyclicBarrier(2) { shouldWait = false }
        val stopLatch = CountDownLatch(2)

        // filters can actually hang the replication
        val filter = ReplicationFilter { _, _ ->
            if (shouldWait) {
                try {
                    barrier.await(STD_TIMEOUT_SECS, TimeUnit.SECONDS)
                } catch (_: TimeoutException) {
                }
            }
            true
        }

        val changeListener = ReplicatorChangeListener { change ->
            if (change.status.activityLevel == AbstractReplicator.ActivityLevel.STOPPED) stopLatch.countDown()
        }

        val docId = makeDoc("repl_listener", otherDB)

        val serverIdentity = createIdentity()
        val listener = listenTls(serverIdentity, null)
        listeners.add(listener)

        val db1 = createDb("db1")
        val docId1 = makeDoc("db1", db1)

        // Replicator#1 (DB#1 <-> Listener(otherDB))
        val config1 = makeConfig(true, true, false, db1, listener.endpoint(), serverIdentity.certs[0])
        config1.pullFilter = filter
        val repl1 = Replicator(config1)
        val token1 = repl1.addChangeListener(changeListener)

        val db2 = createDb("db2")
        val docId2 = makeDoc("db2", db2)

        // Replicator#2 (otherDB <-> DB#2)
        val config2 = makeConfig(true, true, false, otherDB, DatabaseEndpoint(db2))
        config2.pushFilter = filter
        val repl2 = Replicator(config2)
        val token2 = repl1.addChangeListener(changeListener)

        assertEquals(1, db1.count)
        assertEquals(1, db2.count)
        assertEquals(1, otherDB.count)

        repl1.start(false)
        repl2.start(false)

        assertTrue(stopLatch.await(STD_TIMEOUT_SECS, TimeUnit.SECONDS))

        repl1.stop()
        repl1.removeChangeListener(token1)

        repl2.stop()
        repl2.removeChangeListener(token2)

        assertFalse(barrier.isBroken)

        assertEquals(2, db1.count)
        assertNotNull(db1.getDocument(docId))
        assertNotNull(db1.getDocument(docId1))

        assertEquals(2, db2.count)
        assertNotNull(db2.getDocument(docId))
        assertNotNull(db2.getDocument(docId2))

        assertEquals(3, otherDB.count)
        assertNotNull(otherDB.getDocument(docId))
        assertNotNull(otherDB.getDocument(docId1))
        assertNotNull(otherDB.getDocument(docId2))

        db1.close()
        db2.close()
    }

    @Test
    fun testMultipleListenersOnSameDatabase() {
        val config = URLEndpointListenerConfiguration(otherDB)

        config.tlsIdentity = createIdentity();
        val listener1 = URLEndpointListener(config)
        listeners.add(listener1)

        config.tlsIdentity = createIdentity();
        val listener2 = URLEndpointListener(config)
        listeners.add(listener2)

        listener1.start()
        listener2.start()

        makeDoc("multi-listener", otherDB)
        run(listener1.endpointUri(), true, true, false, null, listener1.tlsIdentity!!.certs[0])
    }

    @Test
    fun testCloseWithActiveListener() {
        val listener = listenTls(createIdentity(), null)
        listeners.add(listener)

        otherDB.close()

        assertEquals(-1, listener.port)
        assertEquals(0, listener.urls.count())
    }

    @Ignore("https://issues.couchbase.com/browse/CBL-1140")
    @Test
    fun testEmptyNetworkInterface() {
        TODO("Not yet implemented")
    }

    @FlakyTest
    @Test
    fun testMultipleReplicatorsToListener() {
        var shouldWait = true
        val barrier = CyclicBarrier(2) { shouldWait = false }
        val stopLatch = CountDownLatch(2)

        // filters can actually hang the replication
        val filter = ReplicationFilter { _, _ ->
            if (shouldWait) {
                try {
                    barrier.await(STD_TIMEOUT_SECS, TimeUnit.SECONDS)
                } catch (_: TimeoutException) {
                } catch (_: BrokenBarrierException) {
                }
            }
            true
        }

        val changeListener = { change: ReplicatorChange ->
            if (change.status.activityLevel == AbstractReplicator.ActivityLevel.STOPPED) stopLatch.countDown()
        }

        val docId = makeDoc("multi-repl", otherDB)

        val serverIdentity = createIdentity()
        val listener = listenTls(serverIdentity, null)
        listeners.add(listener)

        val db1 = createDb("db1")
        val docId1 = makeDoc("db1", db1)

        // Replicator#1 (DB#1 <-> Listener(otherDB))
        val config1 = makeConfig(true, true, false, db1, listener.endpoint(), serverIdentity.certs[0])
        config1.pullFilter = filter
        val repl1 = Replicator(config1)
        val token1 = repl1.addChangeListener(changeListener)

        val db2 = createDb("db2")
        val docId2 = makeDoc("db2", db2)

        // Replicator#2 (DB#2 <-> Listener(otherDB))
        val config2 = makeConfig(true, true, false, db2, listener.endpoint(), serverIdentity.certs[0])
        config2.pullFilter = filter
        val repl2 = Replicator(config2)
        val token2 = repl2.addChangeListener(changeListener)

        assertEquals(1, db1.count)
        assertEquals(1, db2.count)
        assertEquals(1, otherDB.count)

        repl1.start(false)
        repl2.start(false)

        assertTrue(stopLatch.await(STD_TIMEOUT_SECS, TimeUnit.SECONDS))

        repl1.stop()
        repl1.removeChangeListener(token1)

        repl2.stop()
        repl2.removeChangeListener(token2)

        assertFalse(barrier.isBroken)

        assertEquals(2, db1.count)
        assertNotNull(db1.getDocument(docId))
        assertNotNull(db1.getDocument(docId1))

        assertEquals(2, db2.count)
        assertNotNull(db2.getDocument(docId))
        assertNotNull(db2.getDocument(docId2))

        assertEquals(3, otherDB.count)
        assertNotNull(otherDB.getDocument(docId))
        assertNotNull(otherDB.getDocument(docId1))
        assertNotNull(otherDB.getDocument(docId2))

        repl1.removeChangeListener(token1)
        repl2.removeChangeListener(token2)

        db2.close()
        db1.close()
    }

    @Ignore(" https://issues.couchbase.com/browse/CBL-954")
    @Test
    fun testReadOnlyListener() {
        TODO("Not yet implemented")
    }

    @Test
    fun testReplicatorServerCert() {
        val identity = createIdentity()
        val cert = identity.certs[0]

        val listener = listenTls(identity, null)
        listeners.add(listener)

        val config = makeConfig(true, true, false, listener.endpoint(), cert, false)
        val repl = run(config, 0, null, false, false) { r: Replicator ->
            assertNull(r.serverCertificates)
            r.addChangeListener {
                if (it.status.activityLevel == AbstractReplicator.ActivityLevel.IDLE) {
                    assertTrue(Arrays.equals(cert.encoded, r.serverCertificates!![0].encoded))
                }
            }
        }

        assertTrue(Arrays.equals(cert.encoded, repl.serverCertificates!![0].encoded))
    }

    @Test
    fun testReplicatorServerCertWithError() {
        val identity = createIdentity()
        val cert = identity.certs[0]

        val listener = listenTls(identity, null)
        listeners.add(listener)

        val config = makeConfig(true, true, false, listener.endpoint(), null, false)
        val repl = run(
            config,
            CBLError.Code.TLS_CERT_UNTRUSTED,
            CBLError.Domain.CBLITE,
            false,
            false,
            { r: Replicator -> assertNull(r.serverCertificates) })

        assertTrue(Arrays.equals(cert.encoded, repl.serverCertificates!![0].encoded))
    }

    @Test
    fun testReplicatorServerCertificateWithTLSDisabled() {
        val idleLatch = CountDownLatch(1)
        val stopLatch = CountDownLatch(1)

        val listener = listenHttp(false, null)
        listeners.add(listener)

        val repl = Replicator(makeConfig(true, true, true, baseTestDb, listener.endpoint(), null))
        val token = repl.addChangeListener { change ->
            when (change.status.activityLevel) {
                AbstractReplicator.ActivityLevel.IDLE -> idleLatch.countDown()
                AbstractReplicator.ActivityLevel.STOPPED -> stopLatch.countDown()
                else -> Unit
            }
        }

        assertNull(repl.serverCertificates)

        repl.start(false)

        idleLatch.await(STD_TIMEOUT_SECS, TimeUnit.SECONDS)
        assertNull(repl.serverCertificates)

        repl.stop()

        stopLatch.await(STD_TIMEOUT_SECS, TimeUnit.SECONDS)
        assertNull(repl.serverCertificates)

        repl.removeChangeListener(token)
    }

    @Test
    fun testAcceptOnlySelfSignedServerCert() {
        val identity = createIdentity()

        val listener = listenTls(identity, null)
        listeners.add(listener)

        // Error as acceptOnlySelfSignedServerCert = false and no pinned server certificate:
        val config1 = makeConfig(true, true, false, listener.endpoint(), null, false)
        run(
            config1,
            CBLError.Code.TLS_CERT_UNTRUSTED,
            CBLError.Domain.CBLITE,
            false,
            false,
            null
        )

        // Success with acceptOnlySelfSignedServerCert = true and no pinned server certificate:
        val config2 = makeConfig(true, true, false, listener.endpoint(), null, true)
        run(config2, 0, null, false, false, null)
    }

    @Test
    fun testSetBothPinnedServerCertAndAcceptOnlySelfSignedServerCert() {
        val identity = createIdentity()
        val cert = identity.certs[0]

        val wrongIdentity = createIdentity()
        val wrongCert = wrongIdentity.certs[0]

        val listener = listenTls(identity, null)
        listeners.add(listener)

        // Error as wrong pinned server certificate even though acceptOnlySelfSignedServerCertificate = true:
        var config = makeConfig(true, true, false, listener.endpoint(), wrongCert, true)
        run(
            config,
            CBLError.Code.TLS_CERT_UNTRUSTED,
            CBLError.Domain.CBLITE,
            false,
            false,
            null
        )

        // Success with correct pinned server certificate:
        config = makeConfig(true, true, false, listener.endpoint(), cert, true)
        run(config, 0, null, false, false, null)
    }

    @Test
    fun testPinnedServerCert() {
        val identity = createIdentity()
        val cert = identity.certs[0]

        val wrongIdentity = createIdentity()
        val wrongCert = wrongIdentity.certs[0]

        val listener = listenTls(identity, null)
        listeners.add(listener)

        // Error as no pinned server certificate:
        run(
            makeConfig(true, true, false, listener.endpoint(), null, false),
            CBLError.Code.TLS_CERT_UNTRUSTED,
            CBLError.Domain.CBLITE,
            false,
            false,
            null
        )

        // Error as wrong pinned server certificate:
        run(
            makeConfig(true, true, false, listener.endpoint(), wrongCert, false),
            CBLError.Code.TLS_CERT_UNTRUSTED,
            CBLError.Domain.CBLITE,
            false,
            false,
            null
        )

        // Success with correct pinned server certificate:
        run(makeConfig(true, true, false, listener.endpoint(), cert, false), 0, null, false, false, null)
    }

    private fun listenHttp(tls: Boolean, auth: ListenerPasswordAuthenticator?): URLEndpointListener {
        val config = URLEndpointListenerConfiguration(otherDB)
        config.port = getPort()
        config.setDisableTls(!tls)
        config.setAuthenticator(auth)

        val listener = URLEndpointListener(config)
        listeners.add(listener)

        listener.start()

        return listener
    }

    private fun listenTls(identity: TLSIdentity?, auth: ListenerAuthenticator?): URLEndpointListener {
        // Listener:
        val config = URLEndpointListenerConfiguration(otherDB)
        config.port = getPort()
        config.setAuthenticator(auth)
        identity?.let { config.tlsIdentity = it }

        val listener = URLEndpointListener(config)
        listeners.add(listener)

        listener.start()

        return listener
    }

    private fun createIdentity(isServer: Boolean = true): TLSIdentity {
        val alias = SecurityBaseTest.newKeyAlias()

        val attributes = SecurityBaseTest.X509_ATTRIBUTES

        val expiration = Calendar.getInstance()
        expiration.add(Calendar.YEAR, 3)

        return PlatformSecurityTest.createIdentity(isServer, attributes, expiration.time, alias)
    }

    private fun makeConfig(
        push: Boolean,
        pull: Boolean,
        continuous: Boolean,
        target: Endpoint?,
        pinnedServerCert: Certificate?,
        acceptOnlySelfSignedCert: Boolean /* EE Only */
    ): ReplicatorConfiguration {
        val config = makeConfig(push, pull, continuous, target, pinnedServerCert)
        config.isAcceptOnlySelfSignedServerCertificate = acceptOnlySelfSignedCert
        return config
    }

    private fun makeDoc(id: String, db: Database): String {
        val docId = StringUtils.getUniqueName(id, 8)
        val testDoc = MutableDocument(docId)
        testDoc.setString("species", "Tiger")
        db.save(testDoc)
        return docId
    }
}

fun URLEndpointListener.endpointUri() =
    URI(
        if (config.isTlsDisabled) "ws" else "wss",
        null,
        "localhost",
        port,
        "/${config.database.name}",
        null,
        null
    )

fun URLEndpointListener.endpoint() = URLEndpoint(endpointUri())
