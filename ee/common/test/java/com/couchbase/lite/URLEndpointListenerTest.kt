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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.security.cert.Certificate
import java.util.Arrays
import java.util.Calendar
import java.util.concurrent.atomic.AtomicInteger


class URLEndpointListenerTest : BaseReplicatorTest() {
    private var testListener: URLEndpointListener? = null

    companion object {
        val portFactory = AtomicInteger(30000)
    }

    @After
    fun cleanupURLEndpointListenerTest() {
        testListener?.stop()
    }

    @Test
    fun testPasswordAuthenticatorNullServerAuthenticator() {
        val listener = listenHttp(false, null)
        run(
            listener.endpointUri(),
            true,
            true,
            false,
            BasicAuthenticator("Bandersnatch", "twas brillig")
        )
    }

    @Test
    fun testPasswordAuthenticatorBadUser() {
        try {
            val listener = listenHttp(false, ListenerPasswordAuthenticator { username, password ->
                "daniel" == username && ("123" == String(password))
            })

            run(listener.endpointUri(), true, true, false, BasicAuthenticator("daneil", "123"))
        } catch (e: CouchbaseLiteException) {
            assertEquals(10401, e.code)
        }
    }

    @Test
    fun testPasswordAuthenticatorBadPassword() {
        try {
            val listener = listenHttp(
                false,
                ListenerPasswordAuthenticator { username, password ->
                    "daniel" == username && ("123" == String(password))
                }
            )

            run(listener.endpointUri(), true, true, false, BasicAuthenticator("daniel", "456"))
        } catch (e: CouchbaseLiteException) {
            assertEquals(10401, e.code)
        }
    }

    @Test
    fun testPasswordAuthenticatorSucceeds() {
        val listener = listenHttp(
            false,
            ListenerPasswordAuthenticator { username, password ->
                (username == "daniel") && (String(password) == "123")
            }
        )

        run(listener.endpointUri(), true, true, false, BasicAuthenticator("daniel", "123"))
    }

    @Test
    fun testPasswordAuthenticatorWithTls() {
        val identity = createIdentity()
        try {
            val listener = listenTls(identity, ListenerPasswordAuthenticator { _, _ -> true })
            run(
                listener.endpointUri(), true, true, false,
                BasicAuthenticator("daniel", "123"), identity.certs[0]
            )
        } finally {
            deleteIdentity(identity)
        }
    }

    @FlakyTest
    @Test
    fun testCertAuthenticatorWithCallbackSucceeds() {
        val serverIdentity = createIdentity()
        val clientIdentity = createIdentity(false)
        try {
            val listener = listenTls(
                serverIdentity,
                ListenerCertificateAuthenticator { true }
            )
            run(
                listener.endpointUri(), true, true, false,
                ClientCertificateAuthenticator(clientIdentity), serverIdentity.certs[0]
            )
        } finally {
            deleteIdentity(serverIdentity)
            deleteIdentity(clientIdentity)
        }
    }

    @Test
    fun testCertAuthenticatorWithCallbackError() {
        val serverIdentity = createIdentity()
        val clientIdentity = createIdentity(false)
        try {
            val listener = listenTls(
                serverIdentity,
                ListenerCertificateAuthenticator { false }
            )
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
        } finally {
            deleteIdentity(serverIdentity)
            deleteIdentity(clientIdentity)
        }
    }

    @Test
    fun testCertAuthenticatorWithRootCerts() {
        val serverIdentity = createIdentity()
        val clientIdentity = createIdentity(false)
        try {
            val listener = listenTls(
                serverIdentity,
                ListenerCertificateAuthenticator(clientIdentity.certs)
            )
            run(
                listener.endpointUri(), true, true, false,
                ClientCertificateAuthenticator(clientIdentity), serverIdentity.certs[0]
            )
        } finally {
            deleteIdentity(serverIdentity)
            deleteIdentity(clientIdentity)
        }
    }

    @Test
    fun testCertAuthenticatorWithRootCertsError() {
        val serverIdentity = createIdentity()
        val clientIdentity = createIdentity(false)
        val wrongClientIdentity = createIdentity(false)
        try {
            val listener = listenTls(
                serverIdentity,
                ListenerCertificateAuthenticator(clientIdentity.certs)
            )
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
        } finally {
            deleteIdentity(serverIdentity)
            deleteIdentity(clientIdentity)
            deleteIdentity(wrongClientIdentity)
        }
    }

    @Test
    fun testReplicationWithTLSIdentity() {
        val identity = createIdentity()
        try {
            val doc = MutableDocument("doc1")
            doc.setString("foo", "bar")
            otherDB.save(doc)

            assertEquals(0, baseTestDb.count)

            val listener = listenTls(identity, null)

            val certs = identity.certs
            assertEquals(1, certs.size)

            val cert = certs[0]
            run(listener.endpointUri(), true, true, false, null, cert)

            assertEquals(1, baseTestDb.count)
            assertNotNull(baseTestDb.getDocument("doc1"))
        } finally {
            deleteIdentity(identity)
        }
    }

    @Test
    fun testReplicationWithAnonymousTLSIdentity() {
        var identity: TLSIdentity? = null
        try {
            val doc = MutableDocument("doc1")
            doc.setString("foo", "bar")
            otherDB.save(doc)

            assertEquals(0, baseTestDb.count)

            val listener = listenTls(null, null)
            assertNotNull(listener.tlsIdentity)
            identity = listener.tlsIdentity

            val config = makeConfig(
                true, true, false,
                listener.endpoint(), null, true
            )
            run(config, 0, null, false, false, null)

            assertEquals(1, baseTestDb.count)
            assertNotNull(baseTestDb.getDocument("doc1"))
        } finally {
            if (identity != null) {
                deleteIdentity(identity)
            }
        }
    }

    @Test
    fun testReplicatorServerCert() {
        val identity = createIdentity()
        val cert = identity.certs[0]

        try {
            val listener = listenTls(identity, null)

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
        } finally {
            deleteIdentity(identity)
        }
    }

    @Test
    fun testReplicatorServerCertWithError() {
        val identity = createIdentity()
        val cert = identity.certs[0]

        try {
            val listener = listenTls(identity, null)

            val config = makeConfig(true, true, false, listener.endpoint(), null, false)
            val repl =
                run(
                    config,
                    CBLError.Code.TLS_CERT_UNTRUSTED,
                    CBLError.Domain.CBLITE,
                    false,
                    false
                ) { r: Replicator ->
                    assertNull(r.serverCertificates)
                }
            assertTrue(Arrays.equals(cert.encoded, repl.serverCertificates!![0].encoded))
        } finally {
            deleteIdentity(identity)
        }
    }

    @Test
    fun testReplicatorServerCertWithTLSDisabled() {
        val listener = listenHttp(false, null)

        val config = makeConfig(true, true, false, listener.endpoint(), null, false)
        val repl = run(config, 0, null, false, false) { r: Replicator ->
            assertNull(r.serverCertificates)
        }
        assertNull(repl.serverCertificates)
    }

    @Test
    fun testPinnedServerCert() {
        val identity = createIdentity()
        val cert = identity.certs[0]

        val wrongIdentity = createIdentity()
        val wrongCert = wrongIdentity.certs[0]

        try {
            val listener = listenTls(identity, null)

            // Error as no pinned server certificate:
            var config = makeConfig(true, true, false, listener.endpoint(), null, false)
            run(
                config,
                CBLError.Code.TLS_CERT_UNTRUSTED,
                CBLError.Domain.CBLITE,
                false,
                false,
                null
            )

            // Error as wrong pinned server certificate:
            config = makeConfig(true, true, false, listener.endpoint(), wrongCert, false)
            run(
                config,
                CBLError.Code.TLS_CERT_UNTRUSTED,
                CBLError.Domain.CBLITE,
                false,
                false,
                null
            )

            // Success with correct pinned server certificate:
            config = makeConfig(true, true, false, listener.endpoint(), cert, false)
            run(config, 0, null, false, false, null)
        } finally {
            deleteIdentity(identity)
            deleteIdentity(wrongIdentity)
        }
    }

    @Test
    fun testAcceptOnlySelfSignedServerCert() {
        val identity = createIdentity()
        try {
            val listener = listenTls(identity, null)

            // Error as acceptOnlySelfSignedServerCert = false and no pinned server certificate:
            var config = makeConfig(true, true, false, listener.endpoint(), null, false)
            run(
                config,
                CBLError.Code.TLS_CERT_UNTRUSTED,
                CBLError.Domain.CBLITE,
                false,
                false,
                null
            )

            // Success with acceptOnlySelfSignedServerCert = true and no pinned server certificate:
            config = makeConfig(true, true, false, listener.endpoint(), null, true)
            run(config, 0, null, false, false, null)
        } finally {
            deleteIdentity(identity)
        }
    }

    @Test
    fun testSetBothPinnedServerCertAndAcceptOnlySelfSignedServerCert() {
        val identity = createIdentity()
        val cert = identity.certs[0]

        val wrongIdentity = createIdentity()
        val wrongCert = wrongIdentity.certs[0]

        try {
            val listener = listenTls(identity, null)
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
        } finally {
            deleteIdentity(identity)
            deleteIdentity(wrongIdentity)
        }
    }

    private fun listenHttp(
        tls: Boolean,
        auth: ListenerPasswordAuthenticator?
    ): URLEndpointListener {
        // Listener:
        val config = URLEndpointListenerConfiguration(otherDB)
        config.port = portFactory.getAndIncrement()
        config.setDisableTls(!tls)
        config.setAuthenticator(auth)

        val listener = URLEndpointListener(config)
        testListener = listener

        listener.start()

        return listener
    }

    private fun listenTls(
        identity: TLSIdentity?,
        auth: ListenerAuthenticator?
    ): URLEndpointListener {
        // Listener:
        val config = URLEndpointListenerConfiguration(otherDB)
        config.port = portFactory.getAndIncrement()
        config.setAuthenticator(auth)
        identity?.let { config.tlsIdentity = it }

        val listener = URLEndpointListener(config)
        testListener = listener

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

    private fun deleteIdentity(identity: TLSIdentity) {
        val id = AbstractTLSIdentity.getKeyAlias(identity) ?: return
        PlatformSecurityTest.deleteIdentity(id)
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
