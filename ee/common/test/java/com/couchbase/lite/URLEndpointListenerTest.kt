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

import com.couchbase.lite.internal.BaseTLSIdentity
import com.couchbase.lite.internal.SecurityBaseTest
import com.couchbase.lite.internal.utils.FlakyTest
import com.couchbase.lite.internal.utils.Fn
import com.couchbase.lite.internal.utils.PlatformUtils
import com.couchbase.lite.internal.utils.Report
import com.couchbase.lite.internal.utils.SlowTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.net.NetworkInterface
import java.net.URI
import java.security.KeyStore
import java.security.cert.Certificate
import java.util.Arrays
import java.util.Calendar
import java.util.concurrent.BrokenBarrierException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern
import kotlin.math.max

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
            val alias = BaseTLSIdentity.getAlias(it.tlsIdentity)
            if (alias != null) {
                PlatformSecurityTest.deleteEntries { a -> alias == a }
            }
        }
        listeners.clear()
        SecurityBaseTest.deleteTestAliases()
    }


    ////////////////  B A S I C   F U N C T I O N A L I T Y ////////////////

    // A listener should report running on the configured port
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

        listener.stop(/**/)
        assertEquals(-1, listener.port)
    }

    // A listener with no port configured should report running on a legal port
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

    // An attempt to run two listeners on the same port should fail
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
    fun testLegalNetworkInterface() {
        val listener = URLEndpointListener(makeLocalConfig())
        listeners.add(listener)
        listener.start()
    }

    @Test(expected = CouchbaseLiteException::class)
    fun testIllegalNetworkInterface() {
        val config = URLEndpointListenerConfiguration(otherDB)
        config.networkInterface = "1.1.1.256"
        val listener = URLEndpointListener(config)
        listeners.add(listener)
        listener.start()
    }

    @Test(expected = CouchbaseLiteException::class)
    fun testRidiculousNetworkInterface() {
        val config = URLEndpointListenerConfiguration(otherDB)
        config.networkInterface = "blah"
        val listener = URLEndpointListener(config)
        listeners.add(listener)
        listener.start()
    }

    // This test fails if no network is available
    @Test
    fun testURLs() {
        val listener = URLEndpointListener(makeLocalConfig())
        listeners.add(listener)
        assertEquals(0, listener.urls.count())

        listener.start()
        assertTrue(0 < listener.urls.count())

        listener.stop()
        assertEquals(0, listener.urls.count())
    }


    ////////////////  H T T P   A U T H E N T I C A T I O N   ////////////////


    // A listener with TLS disabled and no client authenticator
    // should accept a client that presents no credentials
    @Test
    fun testHTTPNullListenerAuthenticatorWithNullClientCredentials() {
        val docId = makeOneDoc("auth", otherDB)

        val listener = listenHttp()

        assertEquals(0, baseTestDb.count)
        runRepl(listener.endpoint())

        assertOneDoc(docId, baseTestDb)
    }

    // A listener with TLS disabled and no client authenticator
    // should accept a client that presents password credentials
    @Test
    fun testHTTPNullListenerAuthenticatorWithPasswordClientCredentials() {
        val docId = makeOneDoc("auth", otherDB)

        val listener = listenHttp()

        assertEquals(0, baseTestDb.count)
        runRepl(listener.endpoint(), BasicAuthenticator("Bandersnatch", "twas brillig".toCharArray()))

        assertOneDoc(docId, baseTestDb)
    }


    // A listener with TLS disabled and no client authenticator
    // should accept a client that presents certificate credentials
    @Test
    fun testHTTPNullListenerAuthenticatorWithClientCertCredentials() {
        val docId = makeOneDoc("auth", otherDB)

        val listener = listenHttp()

        assertEquals(0, baseTestDb.count)
        runRepl(listener.endpoint(), ClientCertificateAuthenticator(createIdentity(false)))

        assertOneDoc(docId, baseTestDb)
    }

    // A listener with TLS disabled and a password client authenticator
    // should accept a client that presents matching password credentials
    @Test
    fun testHTTPPasswordListenerAuthenticatorWithMatchingPasswordClientCredentials() {
        val docId = makeOneDoc("auth", otherDB)

        val listener = listenHttp(
            ListenerPasswordAuthenticator { user, pwd -> (user == "daniel") && (String(pwd) == "123") }
        )

        assertEquals(0, baseTestDb.count)
        runRepl(listener.endpoint(), BasicAuthenticator("daniel", "123".toCharArray()))

        assertOneDoc(docId, baseTestDb)
    }

    // A listener with TLS disabled and a password client authenticator
    // should refuse a client that presents no credentials
    @Test
    fun testHTTPPasswordListenerAuthenticatorWithNullClientCredentials() {
        val listener = listenHttp(
            ListenerPasswordAuthenticator { user, pwd -> ("daniel" == user) && ("123" == String(pwd)) }
        )
        runReplWithError(CBLError.Code.HTTP_AUTH_REQUIRED, CBLError.Domain.CBLITE, listener.endpoint())
    }

    // A listener with TLS disabled and a password client authenticator
    // should refuse a client that presents non-matching credentials (user)
    @Test
    fun testHTTPPasswordListenerAuthenticatorWithNonMatchingUserClientCredentials() {
        val listener = listenHttp(
            ListenerPasswordAuthenticator { user, pwd -> "daniel" == user && ("123" == String(pwd)) }
        )
        runReplWithError(
            CBLError.Code.HTTP_AUTH_REQUIRED,
            CBLError.Domain.CBLITE,
            listener.endpoint(),
            BasicAuthenticator("daneil", "123".toCharArray())
        )
    }

    // A listener with TLS disabled and a password client authenticator
    // should refuse a client that presents non-matching credentials (password)
    @Test
    fun testHTTPPasswordListenerAuthenticatorWithNonMatchingClientCredentials() {
        val listener = listenHttp(
            ListenerPasswordAuthenticator { user, pwd -> "daniel" == user && ("123" == String(pwd)) }
        )
        runReplWithError(
            CBLError.Code.HTTP_AUTH_REQUIRED,
            CBLError.Domain.CBLITE,
            listener.endpoint(),
            BasicAuthenticator("daniel", "456".toCharArray())
        )
    }

    // A listener with TLS disabled and a password client authenticator
    // should refuse a client that presents only certificate credentials
    @Test
    fun testHTTPPasswordListenerAuthenticatorCertificateClientCredentials() {
        val listener = listenHttp(
            ListenerPasswordAuthenticator { user, pwd -> "daniel" == user && ("123" == String(pwd)) }
        )
        runReplWithError(
            CBLError.Code.HTTP_AUTH_REQUIRED,
            CBLError.Domain.CBLITE,
            listener.endpoint(),
            ClientCertificateAuthenticator(createIdentity(false))
        )
    }


    ////////////////  T L S   A U T H E N T I C A T I O N   ////////////////

    // TLS CLIENT AUTHENTICATES SERVER: PINNED CERT

    // A client with a pinned certificate
    // should accept a server that presents that certificate
    @Test
    fun testTLSPinnedCertClientAuthenticatorWithMatchingServerCredentials() {
        val serverIdentity = createIdentity()

        val docId = makeOneDoc("auth", otherDB)

        val listener = listenTls(serverIdentity)

        assertEquals(0, baseTestDb.count)
        runRepl(listener.endpoint(), null, serverIdentity.certs[0])

        assertOneDoc(docId, baseTestDb)
    }

    // A client with a pinned certificate
    // should refuse a server that presents a non-matching certificate (anonymous)
    @Test
    fun testTLSPinnedCertClientAuthenticatorWithAnonymousServerCredentials() {
        val serverIdentity = createIdentity()

        val listener = listenTls()

        runReplWithError(
            CBLError.Code.TLS_CERT_UNTRUSTED,
            CBLError.Domain.CBLITE,
            listener.endpoint(),
            null,
            serverIdentity.certs[0]
        )
    }

    // A client with a pinned certificate
    // should refuse a server that presents a non-matching certificate (explicit, self signed)
    // this is a redundant test...
    @Test
    fun testTLSPinnedCertClientAuthenticatorWithNonMatchingSelfSignedServerCredentials() {
        val serverIdentity = createIdentity()
        val bogusIdentity = createIdentity()

        val listener = listenTls(bogusIdentity)

        runReplWithError(
            CBLError.Code.TLS_CERT_UNTRUSTED,
            CBLError.Domain.CBLITE,
            listener.endpoint(),
            null,
            serverIdentity.certs[0]
        )
    }

    // A client with a pinned certificate
    // should refuse a server that presents certificate chain (even if the root is the pinned cert)
    @SlowTest
    @Test
    fun testTLSPinnedCertClientAuthenticatorWithChainedServerCredentials() {
        val caCert = getTestCACertificate()
        val serverIdentity = getTestChainIdentity()

        val certs = serverIdentity?.certs!!
        assertEquals(2, certs.size)
        assertEquals(caCert, certs[1])

        val listener = listenTls(serverIdentity)

        runReplWithError(
            CBLError.Code.TLS_CERT_UNTRUSTED,
            CBLError.Domain.CBLITE,
            listener.endpoint(),
            null,
            getTestCACertificate()
        )
    }

    // A client with a pinned certificate, also configured to accept self-signed certs
    // should accept a server that presents a matching self-signed certificate
    // Pinning takes precedence
    @Test
    fun testTLSPinnedAndSelfSignedCertClientAuthenticatorWithMatchingServerCredentials() {
        val serverIdentity = createIdentity()

        val docId = makeOneDoc("auth", otherDB)

        val listener = listenTls(serverIdentity)

        assertEquals(0, baseTestDb.count)
        runRepl(listener.endpoint(), null, serverIdentity.certs[0])

        assertOneDoc(docId, baseTestDb)
    }

    // A client with a pinned certificate, also configured to accept self-signed certs
    // should refuse a server that presents a non-matching self-signed certificate
    // Pinning takes precedence
    @Test
    fun testTLSPinnedAndSelfSignedCertClientAuthenticatorWithNonMatchingSelfSignedServerCredentials() {
        val serverIdentity = createIdentity()

        val listener = listenTls(serverIdentity)

        // pin the wrong cert.
        runReplWithError(
            CBLError.Code.TLS_CERT_UNTRUSTED,
            CBLError.Domain.CBLITE,
            listener.endpoint(),
            null,
            createIdentity().certs[0]
        )

    }

    // CLIENT AUTHENTICATES SERVER: SELF_SIGNED CERT

    // A client with configured to accept self-signed certs
    // should accept a server that presents a self-signed cert (anonymous)
    @Test
    fun testTLSSelfSignedClientAuthenticatorWithAnonymousServerCredentials() {
        val docId = makeOneDoc("auth", otherDB)

        val listener = listenTls()

        val identity = listener.tlsIdentity
        assertNotNull(identity)
        val certs = identity!!.certs
        assertEquals(1, certs.size)

        assertEquals(0, baseTestDb.count)
        runRepl(listener.endpoint())

        assertOneDoc(docId, baseTestDb)
    }

    // A client with configured to accept self-signed certs
    // should accept a server that presents a self-signed cert (explicit)
    @Test
    fun testTLSSelfSignedClientAuthenticatorWithExplicitSelfSignedServerCredentials() {
        val serverIdentity = createIdentity()
        val certs = serverIdentity.certs
        assertEquals(1, certs.size)

        val docId = makeOneDoc("auth", otherDB)

        val listener = listenTls(serverIdentity)

        assertEquals(0, baseTestDb.count)
        runRepl(listener.endpoint())

        assertOneDoc(docId, baseTestDb)
    }

    // A client with configured to accept self-signed certs
    // should refuse a server that presents a cert that is not self-signed
    @Test
    fun testTLSSelfSignedClientAuthenticatorWithChainedCredentials() {
        val serverIdentity = getTestChainIdentity()
        val certs = serverIdentity!!.certs
        assertEquals(2, certs.size)

        val listener = listenTls(serverIdentity)

        runReplWithError(
            CBLError.Code.TLS_CERT_UNTRUSTED,
            CBLError.Domain.CBLITE,
            listener.endpoint()
        )
    }

    // CLIENT AUTHENTICATES SERVER: DEFAULT

    // we do not have an actual Trusted CA signed cert, with which to test.

    // TLS SERVER AUTHENTICATES CLIENT: PASSWORD
    // For all of these tests, the client authenticates the server with a self-signed cert

    // A listener with TLS enabled and a password client authenticator
    // should accept a client that presents matching credentials
    @Test
    fun testTLSPasswordListenerAuthenticatorWithMatchingClientCredentials() {
        val docId = makeOneDoc("auth", otherDB)

        val listener = listenTls(
            null,
            ListenerPasswordAuthenticator { user, pwd -> (user == "daniel") && (String(pwd) == "123") })

        assertEquals(0, baseTestDb.count)
        runRepl(listener.endpoint(), BasicAuthenticator("daniel", "123".toCharArray()))

        assertOneDoc(docId, baseTestDb)
    }

    // A listener with TLS enabled and a password client authenticator
    // should refuse a client that presents no credentials
    @Test
    fun testTLSPasswordListenerAuthenticatorWithNullClientCredentials() {
        val listener = listenTls(
            null,
            ListenerPasswordAuthenticator { user, pwd -> (user == "daniel") && (String(pwd) == "123") })
        runReplWithError(CBLError.Code.HTTP_AUTH_REQUIRED, CBLError.Domain.CBLITE, listener.endpoint())
    }

    // A listener with TLS enabled and a password client authenticator
    // should refuse a client that presents non-matching credentials
    @Test
    fun testTLSPasswordListenerAuthenticatorWithNonMatchingClientCredentials() {
        val listener = listenTls(
            null,
            ListenerPasswordAuthenticator { user, pwd -> (user == "daniel") && (String(pwd) == "123") })
        runReplWithError(
            CBLError.Code.HTTP_AUTH_REQUIRED,
            CBLError.Domain.CBLITE,
            listener.endpoint(),
            BasicAuthenticator("daniel", "12".toCharArray())
        )
    }

    // TLS SERVER AUTHENTICATES CLIENT: TRUSTED CERTS

    // we do not have an actual Trusted CA signed cert, with which to test.

    // TLS SERVER AUTHENTICATES CLIENT: ROOT CERTS

    // A listener with TLS enabled and a client authenticator pinning certificates
    // should accept a client that presents pinned credentials
    @FlakyTest(log = ["Linux: 21/06/18"])
    @Test
    fun testTLSPinnedCertificateListenerAuthenticatorWithMatchingClientCredentials() {
        val clientIdentity = createIdentity()

        val docId = makeOneDoc("auth", otherDB)

        val listener = listenTls(
            null,
            ListenerCertificateAuthenticator(clientIdentity.certs)
        )

        assertEquals(0, baseTestDb.count)
        runRepl(listener.endpoint(), ClientCertificateAuthenticator(clientIdentity))

        assertOneDoc(docId, baseTestDb)
    }

    // A listener with TLS enabled and a client authenticator pinning certificates
    // should accept a client that presents a cert chain whose root is pinned
    @FlakyTest(log = ["Linux: 21/06/11"])
    @Test
    fun testTLSPinnedCertificateListenerAuthenticatorWithMatchingChainClientCredentials() {
        val clientIdentity = getTestChainIdentity()
        val certs = clientIdentity!!.certs
        assertEquals(2, certs.size)

        val docId = makeOneDoc("auth", otherDB)

        val listener = listenTls(null, ListenerCertificateAuthenticator(listOf(certs[1])))

        assertEquals(0, baseTestDb.count)
        runRepl(listener.endpoint(), ClientCertificateAuthenticator(clientIdentity))

        assertOneDoc(docId, baseTestDb)
    }

    // A listener with TLS enabled and a client authenticator pinning certificates
    // should refuse a client that presents non-matching credentials.
    @Test
    fun testTLSPinnedCertificateListenerAuthenticatorWithNonMatchingClientCredentials() {
        val listener = listenTls(
            null,
            ListenerCertificateAuthenticator(createIdentity().certs)
        )

        runReplWithError(
            CBLError.Code.TLS_HANDSHAKE_FAILED,
            CBLError.Domain.CBLITE,
            listener.endpoint(),
            ClientCertificateAuthenticator(createIdentity())
        )
    }

    // TLS SERVER AUTHENTICATES CLIENT: CALLBACK

    // A listener with TLS enabled and a callback client authenticator
    // should accept a client that presents credentials that cause the authenticator to return true.
    @Test
    fun testCertAuthenticatorWithCallbackSucceeds() {
        val clientIdentity = createIdentity(false)

        val docId = makeOneDoc("auth", otherDB)

        val listener = listenTls(
            null,
            ListenerCertificateAuthenticator { certs -> certs[0] == clientIdentity.certs[0] })

        assertEquals(0, baseTestDb.count)
        runRepl(listener.endpoint(), ClientCertificateAuthenticator(clientIdentity))

        assertOneDoc(docId, baseTestDb)
    }

    // A listener with TLS enabled and a callback client authenticator
    // should refuse a client that presents credentials that cause the authenticator to return true.
    @Test
    fun testCertAuthenticatorWithCallbackError() {
        val clientIdentity = createIdentity(false)

        assertEquals(0, baseTestDb.count)

        val listener = listenTls(null, ListenerCertificateAuthenticator { certs -> certs[0] == null })

        runReplWithError(
            CBLError.Code.TLS_HANDSHAKE_FAILED,
            CBLError.Domain.CBLITE,
            listener.endpoint(),
            ClientCertificateAuthenticator(clientIdentity)
        )
    }


    ////////////////  C O N N E C T E D   F U N C T I O N A L I T Y   ////////////////

    @Test(expected = CouchbaseLiteException::class)
    fun testReadOnlyListener() {
        val config = URLEndpointListenerConfiguration(otherDB)
        config.isReadOnly = true

        val listener = URLEndpointListener(config)
        listeners.add(listener)
        listener.start()

        makeOneDoc("read-only", baseTestDb)

        runRepl(listener.endpoint())
    }

    // This test fails when no network is available
    @Test
    fun testNetworkInterfaces() {
        val ipV6Pattern = Pattern.compile(".local|(([a-f0-9]{1,4}:{1,2})+)")
        val urlKey = "URL"

        val listener = listenTls()
        val localUrls = listener.urls.filter { !ipV6Pattern.matcher(it.host).find() }

        localUrls.forEach {
            val url = it.toString()
            Report.log("Testing interface: ${url}")

            val db = createDb(it.host)

            val doc = MutableDocument()
            doc.setString(urlKey, url)
            db.save(doc)

            run(makeReplConfig(URLEndpoint(it), db, null, false))

            db.delete()
        }

        val dbUrls = QueryBuilder.select(SelectResult.property(urlKey))
            .from(DataSource.database(otherDB))
            .execute()
            .allResults()
            .map { it.getString(urlKey) }

        Report.log("Local URLs: ${localUrls}, db urls: ${dbUrls}")
        assertEquals(localUrls.size, dbUrls.size)
        localUrls.forEach { assertTrue(dbUrls.contains(it.toString())) }
    }

    @SlowTest
    @Test
    fun testStatus() {
        makeOneDoc("connectionStatus", otherDB)

        val config = URLEndpointListenerConfiguration(otherDB)
        config.setDisableTls(true)
        val listener = URLEndpointListener(config)
        listeners.add(listener)

        assertNull(listener.status?.connectionCount)
        assertNull(listener.status?.activeConnectionCount)

        listener.start()

        assertEquals(0, listener.status?.connectionCount)
        assertEquals(0, listener.status?.activeConnectionCount)

        val maxVals = mutableListOf(0, 0)

        var token: ListenerToken? = null
        val repl = run(makeReplConfig(listener.endpoint(), otherDB)) { repl: Replicator ->
            token = repl.addChangeListener {
                listener.status?.run {
                    // on Android < 24, we cannot use AtomicInteger.getAndAccumulate
                    synchronized(maxVals) {
                        maxVals[0] = max(maxVals[0], activeConnectionCount)
                        maxVals[1] = max(maxVals[1], connectionCount)
                    }
                }
            }
        }
        repl.removeChangeListener(token!!)

        listener.stop()

        assertEquals(1, otherDB.count)

        // The count of active connections is, apparently, so incredibly fleeting as to be pretty much useless.
        // assertEquals(1, maxVals[0])
        assertEquals(1, maxVals[1])

        assertNull(listener.status?.connectionCount)
        assertNull(listener.status?.activeConnectionCount)
    }

    // A listener with TLS disabled should not create an anonymous identity
    @SlowTest
    @Test
    fun testHTTPListenerIdentity() {
        val listener = listenHttp()
        listeners.add(listener)

        assertNull(listener.tlsIdentity)

        var busyIdentity: TLSIdentity? = null
        var token: ListenerToken? = null
        val repl = run(makeReplConfig(listener.endpoint())) { repl: Replicator ->
            token = repl.addChangeListener { change ->
                when (change.status.activityLevel) {
                    ReplicatorActivityLevel.BUSY ->
                        if (busyIdentity == null) {
                            busyIdentity = listener.tlsIdentity
                        }
                    else -> Unit
                }
            }
        }
        repl.removeChangeListener(token!!)

        listener.stop()

        assertNull(busyIdentity)
        assertNull(listener.tlsIdentity)
    }

    // A replicator should report that no certificate was supplied by a server with TLS disabled
    @SlowTest
    @Test
    fun testHTTPListenerReplicatorGetCertificate() {
        val listener = listenHttp()

        var busyCerts: List<Certificate>? = null

        var token: ListenerToken? = null
        val repl = run(makeReplConfig(listener.endpoint())) { repl: Replicator ->
            assertNull(repl.serverCertificates)
            token = repl.addChangeListener { change ->
                when (change.status.activityLevel) {
                    ReplicatorActivityLevel.BUSY ->
                        if (busyCerts == null) {
                            busyCerts = repl.serverCertificates
                        }
                    else -> Unit
                }
            }
        }
        repl.removeChangeListener(token!!)

        listener.stop()

        assertNull(busyCerts)
        assertNull(listener.tlsIdentity)
    }

    // A replicator should report the server's certificate on connection success
    @Test
    fun testTLSListenerReplicatorGetCertificate() {
        val serverIdentity = createIdentity()
        val cert = serverIdentity.certs[0]

        val listener = listenTls(serverIdentity)

        var busyCerts: List<Certificate>? = null

        var token: ListenerToken? = null
        val repl = run(makeReplConfig(listener.endpoint(), baseTestDb, cert, false)) { repl: Replicator ->
            assertNull(repl.serverCertificates)
            token = repl.addChangeListener { change ->
                when (change.status.activityLevel) {
                    ReplicatorActivityLevel.BUSY ->
                        if (busyCerts == null) {
                            busyCerts = repl.serverCertificates
                        }
                    else -> Unit
                }
            }
        }
        repl.removeChangeListener(token!!)

        val encodedCert = cert.encoded
        assertTrue(Arrays.equals(encodedCert, busyCerts!![0].encoded))
        assertTrue(Arrays.equals(encodedCert, repl.serverCertificates!![0].encoded))
    }

    // A replicator should report the server's certificate on connection failure
    @Test
    fun testTLSListenerReplicatorGetCertificateOnError() {
        val serverIdentity = createIdentity()

        val listener = listenTls(serverIdentity)

        val repl = run(
            makeReplConfig(listener.endpoint(), baseTestDb, createIdentity().certs[0], false),
            CBLError.Code.TLS_CERT_UNTRUSTED,
            CBLError.Domain.CBLITE,
            false
        ) { repl: Replicator ->
            assertNull(repl.serverCertificates)
        }

        assertTrue(Arrays.equals(serverIdentity.certs[0].encoded, repl.serverCertificates!![0].encoded))
    }

    // Closing a database should shutdown all listeners
    @Test
    fun testCloseDbWithActiveListeners() {
        createDocsInDb(1000, 7, otherDB)

        createDocsInDb(2000, 11, baseTestDb)

        val serverId = createIdentity()
        val listener = listenTls(serverId)
        listener.start()

        val idleLatch1 = CountDownLatch(1)
        val stopLatch1 = CountDownLatch(1)
        val repl1 = testReplicator(makeReplConfig(listener.endpoint(), baseTestDb, serverId.certs[0]))
        repl1.addChangeListener { change ->
            when (change.status.activityLevel) {
                ReplicatorActivityLevel.IDLE -> idleLatch1.countDown()
                ReplicatorActivityLevel.STOPPED -> stopLatch1.countDown()
                else -> Unit
            }
        }
        repl1.start(false)

        val db2 = createDb("other-other-db")
        val idleLatch2 = CountDownLatch(1)
        val stopLatch2 = CountDownLatch(1)
        val repl2 = testReplicator(makeReplConfig(listener.endpoint(), db2, serverId.certs[0]))
        repl1.addChangeListener { change ->
            when (change.status.activityLevel) {
                ReplicatorActivityLevel.IDLE -> idleLatch2.countDown()
                ReplicatorActivityLevel.STOPPED -> stopLatch2.countDown()
                else -> Unit
            }
        }
        repl2.start(false)

        assertTrue(idleLatch1.await(LONG_TIMEOUT_SEC, TimeUnit.SECONDS))
        assertTrue(idleLatch2.await(LONG_TIMEOUT_SEC, TimeUnit.SECONDS))

        assertEquals(2, listener.status?.connectionCount)

        db2.close()
        baseTestDb.close()
        otherDB.close()

        assertTrue(stopLatch1.await(LONG_TIMEOUT_SEC, TimeUnit.SECONDS))
        assertTrue(stopLatch2.await(LONG_TIMEOUT_SEC, TimeUnit.SECONDS))
        assertFalse(listener.isRunning)
    }

    // Deleting a database should shutdown all listeners
    @Test
    fun testDeleteDbWithActiveListeners() {
        createDocsInDb(1000, 7, otherDB)

        createDocsInDb(2000, 11, baseTestDb)

        val serverId = createIdentity()
        val listener = listenTls(serverId)
        listener.start()

        val idleLatch1 = CountDownLatch(1)
        val stopLatch1 = CountDownLatch(1)
        val repl1 = testReplicator(makeReplConfig(listener.endpoint(), baseTestDb, serverId.certs[0]))
        repl1.addChangeListener { change ->
            when (change.status.activityLevel) {
                ReplicatorActivityLevel.IDLE -> idleLatch1.countDown()
                ReplicatorActivityLevel.STOPPED -> stopLatch1.countDown()
                else -> Unit
            }
        }
        repl1.start(false)

        val db2 = createDb("other-other-db")
        val idleLatch2 = CountDownLatch(1)
        val stopLatch2 = CountDownLatch(1)
        val repl2 = Replicator(makeReplConfig(listener.endpoint(), db2, serverId.certs[0]))
        repl1.addChangeListener { change ->
            when (change.status.activityLevel) {
                ReplicatorActivityLevel.IDLE -> idleLatch2.countDown()
                ReplicatorActivityLevel.STOPPED -> stopLatch2.countDown()
                else -> Unit
            }
        }
        repl2.start(false)

        assertTrue(idleLatch1.await(LONG_TIMEOUT_SEC, TimeUnit.SECONDS))
        assertTrue(idleLatch2.await(LONG_TIMEOUT_SEC, TimeUnit.SECONDS))

        assertEquals(2, listener.status?.connectionCount)

        db2.delete()
        baseTestDb.delete()
        otherDB.delete()

        assertTrue(stopLatch1.await(LONG_TIMEOUT_SEC, TimeUnit.SECONDS))
        assertTrue(stopLatch2.await(LONG_TIMEOUT_SEC, TimeUnit.SECONDS))
        assertFalse(listener.isRunning)
    }

    // https://issues.couchbase.com/browse/CBL-1116
    // A document listener on a replicator connected to a URLEndpointListener should observe deletions
    @Test
    fun testDeleteEvent() {
        createDocsInDb(200, 7, baseTestDb)
        createDocsInDb(300, 11, otherDB)

        assertEquals(7, baseTestDb.count)
        assertEquals(11, otherDB.count)

        val repl = run(
            makeConfig(
                listenHttp().endpoint(),
                ReplicatorType.PUSH_AND_PULL,
                false
            )
        )

        assertEquals(18, baseTestDb.count)
        assertEquals(18, otherDB.count)

        var deleted = 0
        repl.addDocumentReplicationListener { replication ->
            for (doc in replication.documents) {
                if (doc.flags().contains(DocumentFlag.DocumentFlagsDeleted)) {
                    deleted++
                }
            }
        }

        otherDB.delete(otherDB.getNonNullDoc("doc-303"))
        otherDB.delete(otherDB.getNonNullDoc("doc-307"))

        val latch = CountDownLatch(1)
        val token = repl.addChangeListener { change ->
            if (change.status.activityLevel == ReplicatorActivityLevel.STOPPED) {
                latch.countDown()
            }
        }
        repl.start(false)
        assertTrue(latch.await(STD_TIMEOUT_SEC, TimeUnit.SECONDS))

        repl.removeChangeListener(token)

        assertEquals(16, baseTestDb.count)
        assertEquals(16, otherDB.count)

        assertEquals(2, deleted)
    }


    ////////////////////   L O A D   T E S T S   ////////////////////

    @Test
    fun testMultipleListenersOnSameDatabase() {
        val config = URLEndpointListenerConfiguration(otherDB)

        assertEquals(0, baseTestDb.count)

        config.tlsIdentity = createIdentity(false)
        val listener1 = URLEndpointListener(config)
        listeners.add(listener1)
        listener1.start()

        config.tlsIdentity = createIdentity(false)
        val listener2 = URLEndpointListener(config)
        listeners.add(listener2)
        listener2.start()

        val docId = makeOneDoc("endpoint-test", otherDB)

        runRepl(listener1.endpoint())
        runRepl(listener2.endpoint())

        assertOneDoc(docId, baseTestDb)
    }

    @Test
    fun testMultipleReplicatorsToListener() {
        var shouldWait = true
        val barrier = CyclicBarrier(2) { shouldWait = false }
        val stopLatch = CountDownLatch(2)

        // filters can actually hang the replication
        val filter = ReplicationFilter { _, _ ->
            if (shouldWait) {
                try {
                    barrier.await(STD_TIMEOUT_SEC, TimeUnit.SECONDS)
                } catch (_: TimeoutException) {
                } catch (_: BrokenBarrierException) {
                }
            }
            true
        }

        val changeListener = { change: ReplicatorChange ->
            if (change.status.activityLevel == ReplicatorActivityLevel.STOPPED) stopLatch.countDown()
        }

        val docId = makeOneDoc("multi-repl", otherDB)

        val serverIdentity = createIdentity()
        val listener = listenTls(serverIdentity)

        val db1 = createDb("db1")
        val docId1 = makeOneDoc("db1", db1)

        // Replicator#1 (DB#1 <-> Listener(otherDB))
        val config1 = makeReplConfig(listener.endpoint(), db1, serverIdentity.certs[0], false)
        config1.pullFilter = filter
        val repl1 = testReplicator(config1)
        val token1 = repl1.addChangeListener(changeListener)

        val db2 = createDb("db2")
        val docId2 = makeOneDoc("db2", db2)

        // Replicator#2 (DB#2 <-> Listener(otherDB))
        val config2 = makeReplConfig(listener.endpoint(), db2, serverIdentity.certs[0], false)
        config2.pullFilter = filter
        val repl2 = testReplicator(config2)
        val token2 = repl2.addChangeListener(changeListener)

        assertEquals(1, db1.count)
        assertEquals(1, db2.count)
        assertEquals(1, otherDB.count)

        repl1.start(false)
        repl2.start(false)

        assertTrue(stopLatch.await(STD_TIMEOUT_SEC, TimeUnit.SECONDS))

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

    @FlakyTest(log = ["Windows: 21/06/11"])
    @Test
    fun testReplicatorAndListenerOnSameDatabase() {
        var shouldWait = true
        val barrier = CyclicBarrier(2) { shouldWait = false }
        val stopLatch = CountDownLatch(2)

        // filters can actually hang the replication
        val filter = ReplicationFilter { _, _ ->
            if (shouldWait) {
                try {
                    barrier.await(STD_TIMEOUT_SEC, TimeUnit.SECONDS)
                } catch (_: TimeoutException) {
                }
            }
            true
        }

        val changeListener = ReplicatorChangeListener { change ->
            if (change.status.activityLevel == ReplicatorActivityLevel.STOPPED) stopLatch.countDown()
        }

        val docId = makeOneDoc("repl_listener", otherDB)

        val serverIdentity = createIdentity()
        val listener = listenTls(serverIdentity)

        val db1 = createDb("db1")
        val docId1 = makeOneDoc("db1", db1)

        // Replicator#1 (DB#1 <-> Listener(otherDB))
        val config1 = makeReplConfig(listener.endpoint(), db1, serverIdentity.certs[0], false)
        config1.pullFilter = filter
        val repl1 = testReplicator(config1)
        val token1 = repl1.addChangeListener(changeListener)

        val db2 = createDb("db2")
        val docId2 = makeOneDoc("db2", db2)

        // Replicator#2 (otherDB <-> DB#2)
        val config2 = makeReplConfig(DatabaseEndpoint(db2), otherDB, null, false)
        config2.pushFilter = filter
        val repl2 = testReplicator(config2)
        val token2 = repl1.addChangeListener(changeListener)

        assertEquals(1, db1.count)
        assertEquals(1, db2.count)
        assertEquals(1, otherDB.count)

        repl1.start(false)
        repl2.start(false)

        assertTrue(stopLatch.await(STD_TIMEOUT_SEC, TimeUnit.SECONDS))

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


    ////////////////  H E L P E R   F U N C T I O N S   ////////////////

    private fun listenHttp(auth: ListenerPasswordAuthenticator? = null): URLEndpointListener {
        val config = URLEndpointListenerConfiguration(otherDB)
        config.port = getPort()
        config.setDisableTls(true)
        config.setAuthenticator(auth)

        val listener = URLEndpointListener(config)
        listeners.add(listener)

        listener.start()

        return listener
    }

    private fun listenTls(
        identity: TLSIdentity? = null,
        auth: ListenerAuthenticator? = null
    ): URLEndpointListener {
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

    // This creates an identity for an imported keystore alias with a cert chain of length two
    // and whose root is the certificate obtained from getTestCACertificate below.
    private fun getTestChainIdentity(): TLSIdentity? {
        val alias = SecurityBaseTest.newKeyAlias()
        PlatformSecurityTest.importTestEntry(SecurityBaseTest.EXTERNAL_KEY_ALIAS_TEST_CHAIN, alias)
        return PlatformSecurityTest.getIdentity(alias)
    }

    private fun getTestCACertificate(): Certificate? {
        val externalStore = KeyStore.getInstance(SecurityBaseTest.EXTERNAL_KEY_STORE_TYPE)
        PlatformUtils.getAsset(SecurityBaseTest.EXTERNAL_KEY_STORE).use { ks ->
            externalStore.load(ks, SecurityBaseTest.EXTERNAL_KEY_PASSWORD.toCharArray())
        }
        return externalStore.getCertificate(SecurityBaseTest.EXTERNAL_KEY_ALIAS_TEST_CA)
    }

    private fun createIdentity(isServer: Boolean = true): TLSIdentity {
        val alias = SecurityBaseTest.newKeyAlias()

        val attributes = SecurityBaseTest.X509_ATTRIBUTES

        val expiration = Calendar.getInstance()
        expiration.add(Calendar.YEAR, 3)

        return PlatformSecurityTest.createIdentity(isServer, attributes, expiration.time, alias)
    }

    private fun makeReplConfig(
        target: Endpoint,
        source: Database = baseTestDb,
        pinnedServerCert: Certificate? = null,
        continuous: Boolean = true
    ): ReplicatorConfiguration {
        return makeReplConfig(target, source, null, pinnedServerCert, continuous)
    }

    private fun makeReplConfig(
        target: Endpoint,
        source: Database,
        auth: Authenticator?,
        pinnedServerCert: Certificate?,
        continuous: Boolean
    ): ReplicatorConfiguration {
        val config = makeConfig(
            source,
            target,
            ReplicatorType.PUSH_AND_PULL,
            continuous,
            pinnedServerCert
        )
        config.isAcceptOnlySelfSignedServerCertificate = true
        if (auth != null) {
            config.setAuthenticator(auth)
        }
        return config
    }

    private fun makeLocalConfig(): URLEndpointListenerConfiguration {
        val ifaces = NetworkInterface.getNetworkInterfaces().asSequence()
            .flatMap { it.inetAddresses.asSequence() }
            .filter { it.isSiteLocalAddress }
            .map { it.hostAddress }

        val iface = ifaces.firstOrNull()
        assumeTrue("Cannot find a local interface", iface != null)

        val config = URLEndpointListenerConfiguration(otherDB)
        config.networkInterface = iface

        return config
    }

    private fun runRepl(
        target: Endpoint,
        auth: Authenticator? = null,
        pinnedServerCert: Certificate? = null
    ): Replicator {
        return run(makeReplConfig(target, baseTestDb, auth, pinnedServerCert, false))
    }

    private fun runReplWithError(
        code: Int,
        domain: String,
        target: Endpoint,
        auth: Authenticator? = null,
        pinnedServerCert: Certificate? = null,
        continuous: Boolean = false,
        onReady: Fn.Consumer<Replicator>? = null
    ): Replicator {
        return run(
            makeReplConfig(target, baseTestDb, auth, pinnedServerCert, continuous),
            code,
            domain,
            false,
            onReady
        )
    }

    private fun makeOneDoc(id: String, db: Database): String {
        assertEquals(0, db.count)

        val docId = getUniqueName(id)

        val testDoc = MutableDocument(docId)
        testDoc.setString("species", "Tiger")
        db.save(testDoc)

        return docId
    }

    private fun assertOneDoc(id: String, db: Database) {
        assertEquals(1, db.count)
        assertNotNull(db.getDocument(id))
    }
}

private fun URLEndpointListener.endpointUri() =
    URI(
        if (config.isTlsDisabled) "ws" else "wss",
        null,
        "localhost",
        port,
        "/${config.database.name}",
        null,
        null
    )

private fun URLEndpointListener.endpoint() = URLEndpoint(endpointUri())

private fun Database.getNonNullDoc(id: String) =
    this.getDocument(id) ?: throw IllegalStateException("document ${id} is null")

