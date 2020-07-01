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

import com.couchbase.lite.internal.utils.PlatformUtils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Ignore
import org.junit.Test
import java.io.IOException
import java.net.URI
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.NoSuchAlgorithmException
import java.security.UnrecoverableKeyException
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.util.concurrent.atomic.AtomicInteger


private const val CLIENT_CERT_LABEL = "CBL-Client-Cert"

class URLEndpointListenerTest : BaseReplicatorTest() {
    private var testListener: URLEndpointListener? = null

    @After
    fun cleanupURLEndpointListenerTest() {
        testListener?.stop()
    }

    @Test(expected = IllegalStateException::class)
    fun testBuilderDisallowNullAuthenticator() {
        URLEndpointListenerConfiguration.Builder(otherDB)
            .build()
    }

    @Test(expected = IllegalStateException::class)
    fun testBuilderDisallowIdentityWithTLS() {
        URLEndpointListenerConfiguration.Builder(otherDB)
            .setTlsDisabled(true)
            .setAuthenticator(ListenerCertificateAuthenticator.create { _ -> true })
            .build()
    }

    @Test(expected = IllegalStateException::class)
    fun testBuilderDisallowCertAuthWithTLS() {
        URLEndpointListenerConfiguration.Builder(otherDB)
            .setTlsDisabled(true)
            .setAuthenticator(ListenerPasswordAuthenticator.create { _, _ -> true })
            .setTlsIdentity(TLSIdentity())
            .build()
    }

    @Ignore("CouchbaseLiteException{CouchbaseLite,2,'kC4PrivateKeyFromCert not implemented'}")
    @Test
    fun testBasicAuthWithTls() {
        val listener = listenHttp(true, ListenerPasswordAuthenticator.create { _, _ -> true })
        run(listener.endpointUri(), true, true, false, BasicAuthenticator("daniel", "123"))
    }

    @Test
    fun testPasswordAuthenticator() {
        val listener = listenHttp(
            false,
            ListenerPasswordAuthenticator.create { username, password ->
                (username == "daniel") && (String(password) == "123")
            })

        run(listener.endpointUri(), true, true, false, BasicAuthenticator("daniel", "123"))
    }

    @Test
    fun testPasswordAuthenticatorNoAuthenticator() {
        try {
            val listener = listenHttp(
                false,
                ListenerPasswordAuthenticator.create { username, password ->
                    "daniel" == username && ("123" == String(password))
                })

            run(listener.endpointUri(), true, true, false, null)

            fail("Expected exception {CouchbaseLite,10401,'Unauthorized'}")
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

    @Ignore("CouchbaseLiteException{CouchbaseLite,2,'kC4PrivateKeyFromCert not implemented'}")
    @Test
    fun testCertAuthenticator() {
        val listener = listenTls(
            TLSIdentity.getIdentity("foo", "bar".toCharArray()),
            ListenerCertificateAuthenticator.create { certs -> true })
        run(listener.endpointUri(), true, true, false, null)
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

    private fun listenHttp(tls: Boolean, auth: ListenerPasswordAuthenticator): URLEndpointListener {

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


    private fun listenTls(identity: TLSIdentity?, auth: ListenerCertificateAuthenticator): URLEndpointListener {

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

    @Throws(
        KeyStoreException::class,
        CertificateException::class,
        NoSuchAlgorithmException::class,
        IOException::class,
        UnrecoverableKeyException::class
    )
    private fun getCert(): Certificate {
        val keystore = KeyStore.getInstance("PKCS12")
        keystore.load(PlatformUtils.getAsset("certs.p12"), "123".toCharArray())
        assertEquals(1, keystore.size())

        // Android has a funny idea of the alias name...
        return keystore.getCertificate(keystore.aliases().nextElement())
    }
}

fun URLEndpointListener.endpointUri() =
    URI(if (config.isTlsDisabled) "ws" else "wss", null, "localhost", port, "/${config.database.name}", null, null)


