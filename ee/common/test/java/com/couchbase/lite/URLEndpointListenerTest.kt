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

import com.couchbase.lite.internal.KeyStoreBaseTest
import com.couchbase.lite.internal.KeyStoreTestAdaptor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
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

    @Test(expected = IllegalStateException::class)
    fun testBuilderDisallowIdentityWithDisabledTLS() {
        URLEndpointListenerConfiguration.Builder(otherDB)
            .setTlsDisabled(true)
            .setAuthenticator(ListenerCertificateAuthenticator.create { true })
            .build()
    }

    @Test(expected = IllegalStateException::class)
    fun testBuilderDisallowCertAuthWithDisabledTLS() {
        URLEndpointListenerConfiguration.Builder(otherDB)
            .setTlsDisabled(true)
            .setAuthenticator(ListenerPasswordAuthenticator.create { _, _ -> true })
            .setTlsIdentity(TLSIdentity())
            .build()
    }

    @Test
    fun testPasswordAuthenticatorNullServerAuthenticator() {
        val listener = listenHttp(false, null)
        run(listener.endpointUri(), true, true, false, BasicAuthenticator("Bandersnatch", "twas brillig"))
    }

    @Test
    fun testPasswordAuthenticatorBadUser() {
        try {
            val listener = listenHttp(
                false,
                ListenerPasswordAuthenticator.create { username, password ->
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
                ListenerPasswordAuthenticator.create { username, password ->
                    "daniel" == username && ("123" == String(password))
                })

            run(listener.endpointUri(), true, true, false, BasicAuthenticator("daniel", "456"))
        } catch (e: CouchbaseLiteException) {
            assertEquals(10401, e.code)
        }
    }

    @Test
    fun testPasswordAuthenticatorSucceeds() {
        val listener = listenHttp(
            false,
            ListenerPasswordAuthenticator.create { username, password ->
                (username == "daniel") && (String(password) == "123")
            })

        run(listener.endpointUri(), true, true, false, BasicAuthenticator("daniel", "123"))
    }

    @Ignore("!!! FAILING TEST")
    @Test
    fun testBasicAuthWithTls() {
        val alias = KeyStoreBaseTest.newKeyAlias()
        val listener = listenTls(
            TLSIdentity.getAnonymousIdentity(alias),
            ListenerPasswordAuthenticator.create { _, _ -> true })
        run(listener.endpointUri(), true, true, false, BasicAuthenticator("daniel", "123"))
    }

    @Ignore("!!! FAILING TEST")
    @Test
    fun testCertAuthenticator() {
        val identity = createIdentity()
        try {
            val listener = listenTls(
                identity,
                ListenerCertificateAuthenticator.create { true })
            run(listener.endpointUri(), true, true, false, null)
        } finally {
            deleteIdentity(identity)
        }
    }

    @Test
    fun testReplicationWithTLS() {
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

    /*
    @Test
    fun testSimpleReplicationWithImportedIdentity() {
        val doc = MutableDocument("doc1")
        doc.setString("foo", "bar")
        otherDB.save(doc)

        assertEquals(0, baseTestDb.count)

        val alias = KeyStoreBaseTest.newKeyAlias()

        KeyStoreTestAdaptor.deleteIdentity(EXTERNAL_KEY_ALIAS)
        val identity = KeyStoreTestAdaptor.importIdentity(
            EXTERNAL_KEY_STORE_TYPE, PlatformUtils.getAsset(EXTERNAL_KEY_STORE)!!,
            EXTERNAL_KEY_PASSWORD.toCharArray(),
            EXTERNAL_KEY_ALIAS,
            EXTERNAL_KEY_PASSWORD.toCharArray(),
            alias
        )

        val listener = listenTls(identity, null)

        val certs = identity!!.certs
        assertEquals(1, certs.size)
        val cert = certs[0]

        run(listener.endpointUri(), true, true, false, null, cert.encoded)

        assertEquals(1, baseTestDb.count)
        assertNotNull(baseTestDb.getDocument("doc1"))

        KeyStoreTestAdaptor.deleteIdentity(EXTERNAL_KEY_ALIAS)
    }

    @Test
    fun testClientCertAuthenticatorWithClosure() {
        // Listener:
        val listenerAuth = ListenerCertificateAuthenticator { certs ->
            assertEquals(1, certs.count())
            var commonName: String? = null
            val status = SecCertificateCopyCommonName(certs[0], &commonName)
            assertEquals(status, errSecSuccess)
            assertNotNull(commonName)
            assertEquals((commonName as String), "daniel")
            true
        }
        val listener = listen(true, listenerAuth)
        assertNotNull(listener.config.tlsIdentity)
        assertEquals(1, listener.config.tlsIdentity?.certs?.count())

        // Cleanup:
        TLSIdentity.deleteIdentity(CLIENT_CERT_LABEL)

        // Create client identity:
        val attrs = mapOf(certAttrCommonName to "daniel")
        val identity = TLSIdentity.createIdentity(false, attrs, null, CLIENT_CERT_LABEL)

        // Replicator:
        val auth = ClientCertificateAuthenticator(identity)
        val serverCert = listener.config.tlsIdentity?.certs?.get(0)
        run(listener.localURLEndpoint(), getReplicatorType(true, true), false, auth, serverCert)

        // Cleanup:
        TLSIdentity.deleteIdentity(CLIENT_CERT_LABEL)
    }

    @Test
    fun testClientCertAuthenticatorWithRootCerts() {

        // Root Cert:
        val rootCertData = dataFromResource("identity/client-ca", "der")
        val rootCert: Certificate = SecCertificateCreateWithData(kCFAllocatorDefault, rootCertData as CFData)

        // Listener:
        val listenerAuth = ListenerCertificateAuthenticator(listOf(rootCert))
        val listener = listen(true, listenerAuth)

        // Cleanup:
        TLSIdentity.deleteIdentity(CLIENT_CERT_LABEL)

        // Create client identity:
        val clientCertData = dataFromResource("identity/client", "p12")
        val identity = TLSIdentity.importIdentity(clientCertData, "123", CLIENT_CERT_LABEL)

        // Replicator:
        val auth = ClientCertificateAuthenticator(identity)
        val serverCert = listener.config.tlsIdentity?.certs?.get(0)

        run(listener.localURLEndpoint(), getReplicatorType(true, true), false, auth, serverCert)

        // Cleanup:
        TLSIdentity.deleteIdentity(CLIENT_CERT_LABEL)
    }
    */

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
            val repl = run(config, CBLError.Code.TLS_CERT_UNTRUSTED, CBLError.Domain.CBLITE, false, false) { r: Replicator ->
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
            run(config, CBLError.Code.TLS_CERT_UNTRUSTED, CBLError.Domain.CBLITE, false, false, null)

            // Error as wrong pinned server certificate:
            config = makeConfig(true, true, false, listener.endpoint(), wrongCert, false)
            run(config, CBLError.Code.TLS_CERT_UNTRUSTED, CBLError.Domain.CBLITE, false, false, null)

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
        val cert = identity.certs[0]

        try {
            val listener = listenTls(identity, null)

            // Error as acceptOnlySelfSignedServerCert = false and no pinned server certificate:
            var config = makeConfig(true, true, false, listener.endpoint(), null, false)
            run(config, CBLError.Code.TLS_CERT_UNTRUSTED, CBLError.Domain.CBLITE, false, false, null)

            // Success with acceptOnlySelfSignedServerCert = true and no pinned server certificate:
            config = makeConfig(true, true, false, listener.endpoint(), cert, true)
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
            run(config, CBLError.Code.TLS_CERT_UNTRUSTED, CBLError.Domain.CBLITE, false, false, null)

            // Success with correct pinned server certificate:
            config = makeConfig(true, true, false, listener.endpoint(), cert, true)
            run(config, 0, null, false, false, null)
        } finally {
            deleteIdentity(identity)
            deleteIdentity(wrongIdentity)
        }
    }

    private fun listenHttp(tls: Boolean, auth: ListenerPasswordAuthenticator?): URLEndpointListener {
        // Listener:
        val configBuilder = URLEndpointListenerConfiguration.Builder(otherDB)
            .setPort(portFactory.getAndIncrement())
            .setTlsDisabled(!tls)
            .setAuthenticator(auth)

        val listener = URLEndpointListener(configBuilder.build(), false)
        testListener = listener

        listener.start()

        return listener
    }

    private fun listenTls(identity: TLSIdentity?, auth: ListenerAuthenticator?): URLEndpointListener {
        // Listener:
        val configBuilder = URLEndpointListenerConfiguration.Builder(otherDB)
            .setPort(portFactory.getAndIncrement())
            .setAuthenticator(auth)
        identity?.let { configBuilder.setTlsIdentity(it) }

        val listener = URLEndpointListener(configBuilder.build(), false)
        testListener = listener

        listener.start()

        return listener
    }

    private fun createIdentity(): TLSIdentity {
        val alias = KeyStoreBaseTest.newKeyAlias()

        val attributes = KeyStoreBaseTest.get509Attributes()

        val expiration = Calendar.getInstance()
        expiration.add(Calendar.YEAR, 3)

        return KeyStoreTestAdaptor.createIdentity(true, attributes, expiration.time, alias)
    }

    private fun deleteIdentity(identity: TLSIdentity) {
        KeyStoreTestAdaptor.deleteIdentity(identity.keyAlias)
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
    URI(if (config.isTlsDisabled) "ws" else "wss", null, "localhost", port, "/${config.database.name}", null, null)

fun URLEndpointListener.endpoint() = URLEndpoint(endpointUri())
