//
// Copyright (c) 2020 Couchbase, Inc All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
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
package com.couchbase.lite.internal.core

import com.couchbase.lite.ConnectionStatus
import com.couchbase.lite.ListenerCertificateAuthenticator
import com.couchbase.lite.ListenerPasswordAuthenticator
import com.couchbase.lite.LiteCoreException
import com.couchbase.lite.PlatformBaseTest
import com.couchbase.lite.internal.core.impl.NativeC4Listener
import com.couchbase.lite.internal.utils.SecurityUtils
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import java.io.IOException
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.NoSuchAlgorithmException
import java.security.UnrecoverableKeyException
import java.security.cert.Certificate
import java.security.cert.CertificateException

class C4ListenerTest : PlatformBaseTest() {
    private val impl = object : C4Listener.NativeImpl {
        @Throws(LiteCoreException::class)
        override fun nStartHttp(
            context: Long,
            port: Int,
            networkInterface: String,
            dbPath: String,
            allowCreateDBs: Boolean,
            allowDeleteDBs: Boolean,
            allowPush: Boolean,
            allowPull: Boolean,
            enableDeltaSync: Boolean
        ): Long = 666L

        @Throws(LiteCoreException::class)
        override fun nStartTls(
            context: Long,
            port: Int,
            networkInterface: String,
            dbPath: String,
            allowCreateDBs: Boolean,
            allowDeleteDBs: Boolean,
            allowPush: Boolean,
            allowPull: Boolean,
            enableDeltaSync: Boolean,
            cert: ByteArray,
            requireClientCerts: Boolean,
            rootClientCerts: ByteArray
        ): Long = 666L

        override fun nFree(handle: Long) = Unit

        @Throws(LiteCoreException::class)
        override fun nShareDb(handle: Long, name: String, c4Db: Long) = Unit

        @Throws(LiteCoreException::class)
        override fun nUnshareDb(handle: Long, c4Db: Long) = Unit

        @Throws(LiteCoreException::class)
        override fun nGetUrls(handle: Long, c4Db: Long): List<String?> = listOf("")

        override fun nGetPort(handle: Long): Int = 1

        override fun nGetConnectionStatus(handle: Long): ConnectionStatus = ConnectionStatus(0, 0)

        override fun nGetUriFromPath(handle: Long, path: String?): String = ""
    }

    private lateinit var cert: Certificate

    @Before
    fun setUpC4ListenerTest() {
        C4Listener.nativeImpl = impl
        C4Listener.HTTP_LISTENER_CONTEXT.clear()
        C4Listener.TLS_LISTENER_CONTEXT.clear()
        cert = getCert()
    }

    @After
    fun tearDownC4ListenerTest() {
        C4Listener.nativeImpl = NativeC4Listener()
    }

    @Test
    fun testHttpListenerCreate() {
        assertEquals(0, C4Listener.HTTP_LISTENER_CONTEXT.size())
        assertEquals(0, C4Listener.TLS_LISTENER_CONTEXT.size())

        val listener = C4Listener.createHttpListener(
            2222,
            "en0",
            "/here/there/everywhere",
            true,
            true,
            true,
            true,
            true,
            ListenerPasswordAuthenticator.create { u, p -> true }
        )

        assertEquals(1, C4Listener.HTTP_LISTENER_CONTEXT.size())
        assertEquals(0, C4Listener.TLS_LISTENER_CONTEXT.size())
    }

    @Test
    fun testHttpListenerAuthenticate() {
        val header = "Twas brillig and the slythy toves"

        var opwd: CharArray? = null;
        var pwd: CharArray? = null;
        var user: String? = null
        val listener = C4Listener.createHttpListener(
            2222,
            "en0",
            "/here/there/everywhere",
            true,
            true,
            true,
            true,
            true,
            ListenerPasswordAuthenticator.create { u, p ->
                opwd = p;
                pwd = p.copyOf()
                user = u
                true
            }
        )

        assertEquals(1, C4Listener.HTTP_LISTENER_CONTEXT.size())
        val key = C4Listener.HTTP_LISTENER_CONTEXT.keySet().iterator().next() as Long

        C4Listener.httpAuthCallback(key, header)

        assertEquals(header.length, opwd?.size)
        assertEquals(' ', opwd?.get(0))
        assertArrayEquals(header.toCharArray(), pwd)
        assertEquals(header, user)
    }

    @Test
    fun testTlsListenerCreate() {
        assertEquals(0, C4Listener.HTTP_LISTENER_CONTEXT.size())
        assertEquals(0, C4Listener.TLS_LISTENER_CONTEXT.size())

        val listener = C4Listener.createTlsListener(
            2222,
            "en0",
            "/here/there/everywhere",
            true,
            true,
            true,
            true,
            true,
            cert,
            true,
            setOf(cert),
            ListenerCertificateAuthenticator.create { certs -> true }
        )

        assertEquals(0, C4Listener.HTTP_LISTENER_CONTEXT.size())
        assertEquals(1, C4Listener.TLS_LISTENER_CONTEXT.size())
    }

    @Ignore("Incomplete")
    @Test
    fun testTlsListenerAuthenticate() {
        val certData = SecurityUtils.encodeCertificate(cert)

        var clientCert: Certificate? = null
        val listener = C4Listener.createTlsListener(
            2222,
            "en0",
            "/here/there/everywhere",
            true,
            true,
            true,
            true,
            true,
            cert,
            true,
            setOf(cert),
            ListenerCertificateAuthenticator.create { certs ->
                clientCert = cert
                true
            }
        )

        assertEquals(1, C4Listener.TLS_LISTENER_CONTEXT.size())
        val key = C4Listener.TLS_LISTENER_CONTEXT.keySet().iterator().next() as Long

        C4Listener.certAuthCallback(key, certData)

        assertEquals(cert, clientCert)
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
        keystore.load(getAsset("certs.p12"), "123".toCharArray())
        assertEquals(1, keystore.size())

        // Android has a funny idea of the alias name...
         return keystore.getCertificate(keystore.aliases().nextElement())
    }
}
