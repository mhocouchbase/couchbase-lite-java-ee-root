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

import com.couchbase.lite.internal.KeyStoreManager
import com.couchbase.lite.internal.KeyStoreManager.CERT_ATTRIBUTE_COMMON_NAME
import com.couchbase.lite.internal.utils.StringUtils
import com.couchbase.lite.internal.utils.TestUtils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Ignore
import org.junit.Test
import java.net.URI
import java.util.Calendar
import java.util.concurrent.atomic.AtomicInteger


private const val KEY_ALIAS = "test-alias"

class URLEndpointListenerTest : BaseReplicatorTest() {
    private var testListener: URLEndpointListener? = null

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

    @Ignore("unimplemented")
    @Test
    fun testBasicAuthWithTls() {
        val alias = StringUtils.getUniqueName(KEY_ALIAS, 8)
        val listener = listenTls(
            TLSIdentity.getAnonymousIdentity(alias),
            ListenerPasswordAuthenticator.create { _, _ -> true })
        run(listener.endpointUri(), true, true, false, BasicAuthenticator("daniel", "123"))
    }

    @Ignore("unimplemented")
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
    fun testSimpleReplicationWithTLS() {
        val doc = MutableDocument("doc1")
        doc.setString("foo", "bar")
        otherDB.save(doc)

        assertEquals(0, baseTestDb.count)

        val attributes: MutableMap<String, String> = HashMap()
        attributes[CERT_ATTRIBUTE_COMMON_NAME] = "Couchbase Lite Test"

        TLSIdentity.deleteIdentity("server-cert");
        val identity = TLSIdentity.createIdentity(true, attributes, null, "server-cert");

        val listener = listenTls(identity, null);

        val certs = identity.certs
        assertEquals(1, certs.size)

        var cert = certs[0]
        run(listener.endpointUri(), true, true, false, null, cert.encoded)

        assertEquals(1, baseTestDb.count)
        assertNotNull(baseTestDb.getDocument("doc1"))

        TLSIdentity.deleteIdentity("server-cert");
    }


/*
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

    companion object {
        val portFactory = AtomicInteger(30000)
    }

    private fun listenHttp(tls: Boolean, auth: ListenerPasswordAuthenticator?): URLEndpointListener {

        // Listener:
        val configBuilder = URLEndpointListenerConfiguration.Builder(otherDB)
            .setPort(portFactory.getAndIncrement())
            .setTlsDisabled(!tls)
            .setAuthenticator(auth)

        val listener = URLEndpointListener(configBuilder.build(), false)
        testListener = listener

        // Start:
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

        // Start:
        listener.start()

        return listener
    }

    private fun createIdentity(): TLSIdentity {
        val alias = StringUtils.getUniqueName(KEY_ALIAS, 8)

        val attributes = TestUtils.get509Attributes()

        val expiration = Calendar.getInstance()
        expiration.add(Calendar.YEAR, 3)

        return TLSIdentity.createIdentity(true, attributes, expiration.time, alias)
    }

    private fun deleteIdentity(identity: TLSIdentity) {
        KeyStoreManager.getInstance().deleteEntries(null) { alias: String -> alias == identity.alias }
    }
}

fun URLEndpointListener.endpointUri() =
    URI(if (config.isTlsDisabled) "ws" else "wss", null, "localhost", port, "/${config.database.name}", null, null)


