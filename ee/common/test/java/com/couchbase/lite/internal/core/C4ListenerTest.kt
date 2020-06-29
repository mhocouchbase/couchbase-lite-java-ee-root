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
import com.couchbase.lite.internal.utils.Base64Utils
import com.couchbase.lite.internal.utils.SecurityUtils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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



private const val USER_NAME = "G’Kar"
private const val PASSWORD = "!#*@£ᘺ"

class C4ListenerTest : PlatformBaseTest() {


    private val impl = object : C4Listener.NativeImpl {
        @Throws(LiteCoreException::class)
        override fun nStartHttp(
            context: Long,
            port: Int,
            iFace: String?,
            apis: Int,
            dbPath: String,
            allowCreateDb: Boolean,
            allowDeleteDb: Boolean,
            push: Boolean,
            pull: Boolean,
            deltaSync: Boolean
        ): Long = 666L

        @Throws(LiteCoreException::class)
        override fun nStartTls(
            context: Long,
            port: Int,
            iFace: String?,
            apis: Int,
            dbPath: String,
            allowCreateDb: Boolean,
            allowDeleteDb: Boolean,
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

        override fun nGetUriFromPath(path: String?): String = ""
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
            ListenerPasswordAuthenticator.create { _, _ -> true }
        )
        assertNotNull(listener)

        assertEquals(1, C4Listener.HTTP_LISTENER_CONTEXT.size())
        assertEquals(0, C4Listener.TLS_LISTENER_CONTEXT.size())
    }

    @Test
    fun testHttpListenerAuthenticateNoHeader() {
        val listener = C4Listener.createHttpListener(
            2222,
            "en0",
            "/here/there/everywhere",
            true,
            true,
            true,
            ListenerPasswordAuthenticator.create { _, _ -> true }
        )
        assertNotNull(listener)

        assertEquals(1, C4Listener.HTTP_LISTENER_CONTEXT.size())
        val key = C4Listener.HTTP_LISTENER_CONTEXT.keySet().iterator().next() as Int

        assertFalse(C4Listener.httpAuthCallback(key.toLong(), ""))
    }

    @Test
    fun testHttpListenerAuthenticateUnrecognizedMode() {
        val listener = C4Listener.createHttpListener(
            2222,
            "en0",
            "/here/there/everywhere",
            true,
            true,
            true,
            ListenerPasswordAuthenticator.create { _, _ -> true }
        )
        assertNotNull(listener)

        assertEquals(1, C4Listener.HTTP_LISTENER_CONTEXT.size())
        val key = C4Listener.HTTP_LISTENER_CONTEXT.keySet().iterator().next() as Int

        assertFalse(C4Listener.httpAuthCallback(key.toLong(), "Foo"))
    }

    @Test
    fun testHttpListenerAuthenticateExtraCredentials() {
        val listener = C4Listener.createHttpListener(
            2222,
            "en0",
            "/here/there/everywhere",
            true,
            true,
            true,
            ListenerPasswordAuthenticator.create { _, _ -> true }
        )
        assertNotNull(listener)

        assertEquals(1, C4Listener.HTTP_LISTENER_CONTEXT.size())
        val key = C4Listener.HTTP_LISTENER_CONTEXT.keySet().iterator().next() as Int

        assertFalse(C4Listener.httpAuthCallback(key.toLong(), "${C4Listener.Http.AUTH_MODE_BASIC} usr:pass foo"))
    }

    @Test
    fun testHttpListenerAuthenticateJunkCredentials() {
        val listener = C4Listener.createHttpListener(
            2222,
            "en0",
            "/here/there/everywhere",
            true,
            true,
            true,
            ListenerPasswordAuthenticator.create{ _, _ -> true }
        )
        assertNotNull(listener)

        assertEquals(1, C4Listener.HTTP_LISTENER_CONTEXT.size())
        val key = C4Listener.HTTP_LISTENER_CONTEXT.keySet().iterator().next() as Int

        assertFalse(C4Listener.httpAuthCallback(key.toLong(), "${C4Listener.Http.AUTH_MODE_BASIC} !$@£ᘺ"))
    }

    @Test
    fun testHttpListenerAuthenticateNoCredentials() {
        var user: String? = null
        var pwd: CharArray? = null

        val listener = C4Listener.createHttpListener(
            2222,
            "en0",
            "/here/there/everywhere",
            true,
            true,
            true,
            ListenerPasswordAuthenticator.create { u, p ->
                user = u
                pwd = p
                true
            }
        )
        assertNotNull(listener)

        assertEquals(1, C4Listener.HTTP_LISTENER_CONTEXT.size())
        val key = C4Listener.HTTP_LISTENER_CONTEXT.keySet().iterator().next() as Int

        assertTrue(C4Listener.httpAuthCallback(key.toLong(), C4Listener.Http.AUTH_MODE_BASIC))

        assertEquals(0, user?.length)
        assertEquals(0, pwd?.size)
    }

    @Test
    fun testHttpListenerAuthenticateEmptyCredentials() {
        var user: String? = null
        var pwd: CharArray? = null

        val listener = C4Listener.createHttpListener(
            2222,
            "en0",
            "/here/there/everywhere",
            true,
            true,
            true,
            ListenerPasswordAuthenticator.create { u, p ->
                user = u
                pwd = p
                true
            }
        )
        assertNotNull(listener)

        assertEquals(1, C4Listener.HTTP_LISTENER_CONTEXT.size())
        val key = C4Listener.HTTP_LISTENER_CONTEXT.keySet().iterator().next() as Int

        assertTrue(C4Listener.httpAuthCallback(key.toLong(), "${C4Listener.Http.AUTH_MODE_BASIC}    "))

        assertEquals(0, user?.length)
        assertEquals(0, pwd?.size)
    }

    @Test
    fun testHttpListenerAuthenticate() {
        var user: String? = null
        var pwd: CharArray? = null
        val listener = C4Listener.createHttpListener(
            2222,
            "en0",
            "/here/there/everywhere",
            true,
            true,
            true,
            ListenerPasswordAuthenticator.create { u, p ->
                user = u
                pwd = p
                true
            }
        )
        assertNotNull(listener)

        assertEquals(1, C4Listener.HTTP_LISTENER_CONTEXT.size())
        val key = C4Listener.HTTP_LISTENER_CONTEXT.keySet().iterator().next() as Int

        val creds = "${USER_NAME}:${PASSWORD}".toByteArray(Charsets.UTF_8)
        assertTrue(C4Listener.httpAuthCallback(
            key.toLong(),
            "${C4Listener.Http.AUTH_MODE_BASIC} ${Base64Utils.getEncoder().encodeToString(creds)}"))

        assertEquals(USER_NAME, user)
        assertEquals(PASSWORD, String(pwd!!))
    }

    @Test
    fun testHttpListenerAuthenticateNoUser() {
        var user: String? = null
        var pwd: CharArray? = null
        val listener = C4Listener.createHttpListener(
            2222,
            "en0",
            "/here/there/everywhere",
            true,
            true,
            true,
            ListenerPasswordAuthenticator.create { u, p ->
                user = u
                pwd = p
                true
            }
        )
        assertNotNull(listener)

        assertEquals(1, C4Listener.HTTP_LISTENER_CONTEXT.size())
        val key = C4Listener.HTTP_LISTENER_CONTEXT.keySet().iterator().next() as Int

        val creds = ":${PASSWORD}".toByteArray(Charsets.UTF_8)
        assertTrue(C4Listener.httpAuthCallback(
            key.toLong(),
            "${C4Listener.Http.AUTH_MODE_BASIC} ${Base64Utils.getEncoder().encodeToString(creds)}"))

        assertEquals(0, user?.length)
        assertEquals(PASSWORD, String(pwd!!))
    }

    @Test
    fun testHttpListenerAuthenticateNoPassword() {
        var user: String? = null
        var pwd: CharArray? = null
        val listener = C4Listener.createHttpListener(
            2222,
            "en0",
            "/here/there/everywhere",
            true,
            true,
            true,
            ListenerPasswordAuthenticator.create { u, p ->
                user = u
                pwd = p
                true
            }
        )
        assertNotNull(listener)

        assertEquals(1, C4Listener.HTTP_LISTENER_CONTEXT.size())
        val key = C4Listener.HTTP_LISTENER_CONTEXT.keySet().iterator().next() as Int

        val creds = USER_NAME.toByteArray(Charsets.UTF_8)
        assertTrue(C4Listener.httpAuthCallback(
            key.toLong(),
            "${C4Listener.Http.AUTH_MODE_BASIC} ${Base64Utils.getEncoder().encodeToString(creds)}"))

        assertEquals(USER_NAME, user)
        assertEquals(0, pwd?.size)
    }

    @Test
    fun testHttpListenerAuthenticateEmptyPassword() {
        var user: String? = null
        var pwd: CharArray? = null
        val listener = C4Listener.createHttpListener(
            2222,
            "en0",
            "/here/there/everywhere",
            true,
            true,
            true,
            ListenerPasswordAuthenticator.create { u, p ->
                user = u
                pwd = p
                true
            }
        )
        assertNotNull(listener)

        assertEquals(1, C4Listener.HTTP_LISTENER_CONTEXT.size())
        val key = C4Listener.HTTP_LISTENER_CONTEXT.keySet().iterator().next() as Int

        val creds = "${USER_NAME}:".toByteArray(Charsets.UTF_8)
        assertTrue(C4Listener.httpAuthCallback(
            key.toLong(),
            "${C4Listener.Http.AUTH_MODE_BASIC} ${Base64Utils.getEncoder().encodeToString(creds)}"))

        assertEquals(USER_NAME, user)
        assertEquals(0, pwd?.size)
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
            cert,
            true,
            setOf(cert),
            ListenerCertificateAuthenticator.create { true }
        )
        assertNotNull(listener)

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
            cert,
            true,
            setOf(cert),
            ListenerCertificateAuthenticator.create { certs ->
                clientCert = certs[0]
                true
            }
        )
        assertNotNull(listener)

        assertEquals(1, C4Listener.TLS_LISTENER_CONTEXT.size())
        val key = C4Listener.TLS_LISTENER_CONTEXT.keySet().iterator().next() as Int

        C4Listener.certAuthCallback(key.toLong(), certData)

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
