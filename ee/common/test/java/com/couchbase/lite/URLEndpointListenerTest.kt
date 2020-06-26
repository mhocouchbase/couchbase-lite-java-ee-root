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

import org.junit.After
import org.junit.Test
import java.net.URL


private const val WS_PORT = 4984
private const val WSS_PORT = 4985
private const val CLIENT_CERT_LABEL = "CBL-Client-Cert"

fun URLEndpointListener.Http.localURLEndpoint(): URL {
    return URL(
        if (httpConfig.isTlsDisabled) "ws" else "wss",
        "localhost",
        port,
        config.database.name
    )
}

class URLEndpointListenerTest : BaseReplicatorTest() {
    private var listener: URLEndpointListener? = null

    @After
    fun tearDown() {
        val localListener = listener
        localListener?.stop()
        //localListener?.config?.tlsIdentity?.deleteFromKeyChain()
    }

    @Test
    fun testPasswordAuthenticatorNoAutheticator() {
        val listenerAuth = ListenerPasswordAuthenticator.create { username, password ->
            "daniel" == username && ("123" == String(password))
        }

        val listener = listenHttp(false, listenerAuth)

        run(listener.localURLEndpoint(), true, true, false, null)

        listener.stop()
    }

    @Test
    fun testPasswordAuthenticatorBadPassword() {
        val listenerAuth = ListenerPasswordAuthenticator.create { username, password ->
            "daniel" == username && ("123" == String(password))
        }

        val listener = listenHttp(false, listenerAuth)

        run(listener.localURLEndpoint(), true, true, false, BasicAuthenticator("daniel", "456"))

        listener.stop()
    }

    @Test
    fun testPasswordAuthenticator() {
        val listenerAuth = ListenerPasswordAuthenticator.create { username, password ->
            "daniel" == username && ("123" == String(password))
        }

        val listener = listenHttp(false, listenerAuth)

        run(listener.localURLEndpoint(), true, true, false, BasicAuthenticator("daniel", "123"))

        listener.stop()
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

    private fun listenHttp(): URLEndpointListener = listenHttp(true)

    private fun listenHttp(tls: Boolean): URLEndpointListener = listenHttp(tls, null)

    private fun listenHttp(tls: Boolean, auth: ListenerPasswordAuthenticator?): URLEndpointListener.Http {
        // Listener:
        val configBuilder = URLEndpointListenerConfiguration.buildHttpConfig(otherDB)
            .setPort(if (tls) WSS_PORT else WS_PORT)
            .setTlsDisabled(!tls)
        if (auth != null) {
            configBuilder.setAuthenticator(auth);
        }

        val localListener = URLEndpointListener.createListener(configBuilder.build(), false)

        // Start:
        localListener.start()

        return localListener
    }

    private fun run(url: URL, push: Boolean, pull: Boolean, continuous: Boolean, auth: Authenticator?) {
        // !!! Write me!!!
    }
}
