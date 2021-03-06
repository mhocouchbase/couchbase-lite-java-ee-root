//
// Copyright (c) 2020 Couchbase, Inc All rights reserved.
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
package com.couchbase.lite.internal.core

import com.couchbase.lite.ConnectionStatus
import com.couchbase.lite.ListenerCertificateAuthenticator
import com.couchbase.lite.ListenerPasswordAuthenticator
import com.couchbase.lite.LiteCoreException
import com.couchbase.lite.PlatformSecurityTest
import com.couchbase.lite.internal.core.impl.NativeC4Listener
import com.couchbase.lite.internal.utils.PlatformUtils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.cert.Certificate


private const val USER_NAME = "G’Kar"
private const val PASSWORD = "!#*@£ᘺ"

class C4ListenerTest : PlatformSecurityTest() {
    private val c4ListenerMock = object : C4Listener.NativeImpl {
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
            deltaSync: Boolean,
            requirePasswordAuth: Boolean
        ): Long = 0xdadL

        @Throws(LiteCoreException::class)
        override fun nStartTls(
            token: Long,
            port: Int,
            iFace: String?,
            apis: Int,
            dbPath: String,
            allowCreateDb: Boolean,
            allowDeleteDb: Boolean,
            allowPush: Boolean,
            allowPull: Boolean,
            deltaSync: Boolean,
            keyPair: Long,
            serverCert: ByteArray,
            requireClientCerts: Boolean,
            rootClientCerts: ByteArray?,
            requirePasswordAuth: Boolean
        ): Long = 0xdadL

        override fun nFree(handle: Long) = Unit

        @Throws(LiteCoreException::class)
        override fun nShareDb(handle: Long, name: String, c4Db: Long) = Unit

        @Throws(LiteCoreException::class)
        override fun nUnshareDb(handle: Long, c4Db: Long) = Unit

        @Throws(LiteCoreException::class)
        override fun nGetUrls(handle: Long, c4Db: Long): List<String?> = listOf("")

        override fun nGetPort(handle: Long): Int = 1

        override fun nGetConnectionStatus(handle: Long): ConnectionStatus = ConnectionStatus(0, 0)

        override fun nGetUriFromPath(path: String): String = ""
    }

    @Before
    fun setUpC4ListenerTest() {
        C4Listener.nativeImpl = c4ListenerMock
        C4Listener.LISTENER_CONTEXT.clear()
    }

    @After
    fun tearDownC4ListenerTest() {
        C4Listener.nativeImpl = NativeC4Listener()
        C4Listener.LISTENER_CONTEXT.clear()
    }

    @Test
    fun testCreateHttpListener() {
        assertEquals(0, C4Listener.LISTENER_CONTEXT.size())

        val listener = C4Listener.createHttpListener(
            2222,
            "en0",
            "/here/there/everywhere",
            ListenerPasswordAuthenticator { _, _ -> true },
            true,
            true,
            true
        )
        assertNotNull(listener)

        assertEquals(1, C4Listener.LISTENER_CONTEXT.size())
    }

    @Test
    fun testHttpListenerAuthenticateNoHeader() {
        val listener = C4Listener.createHttpListener(
            2222,
            "en0",
            "/here/there/everywhere",
            ListenerPasswordAuthenticator { _, _ -> true },
            true,
            true,
            true
        )
        assertNotNull(listener)

        assertEquals(1, C4Listener.LISTENER_CONTEXT.size())
        val key = C4Listener.LISTENER_CONTEXT.keySet().iterator().next()

        assertFalse(C4Listener.httpAuthCallback(key.toLong(), ""))
    }

    @Test
    fun testHttpListenerAuthenticateUnrecognizedMode() {
        val listener = C4Listener.createHttpListener(
            2222,
            "en0",
            "/here/there/everywhere",
            ListenerPasswordAuthenticator { _, _ -> true },
            true,
            true,
            true
        )
        assertNotNull(listener)

        assertEquals(1, C4Listener.LISTENER_CONTEXT.size())
        val key = C4Listener.LISTENER_CONTEXT.keySet().iterator().next()

        assertFalse(C4Listener.httpAuthCallback(key.toLong(), "Foo"))
    }

    @Test
    fun testHttpListenerAuthenticateExtraCredentials() {
        val listener = C4Listener.createHttpListener(
            2222,
            "en0",
            "/here/there/everywhere",
            ListenerPasswordAuthenticator { _, _ -> true },
            true,
            true,
            true
        )
        assertNotNull(listener)

        assertEquals(1, C4Listener.LISTENER_CONTEXT.size())
        val key = C4Listener.LISTENER_CONTEXT.keySet().iterator().next()

        assertFalse(
            C4Listener.httpAuthCallback(
                key.toLong(),
                "${C4Listener.AUTH_MODE_BASIC} usr:pass foo"
            )
        )
    }

    @Test
    fun testHttpListenerAuthenticateJunkCredentials() {
        val listener = C4Listener.createHttpListener(
            2222,
            "en0",
            "/here/there/everywhere",
            ListenerPasswordAuthenticator { _, _ -> true },
            true,
            true,
            true
        )
        assertNotNull(listener)

        assertEquals(1, C4Listener.LISTENER_CONTEXT.size())
        val key = C4Listener.LISTENER_CONTEXT.keySet().iterator().next()

        assertFalse(
            C4Listener.httpAuthCallback(
                key.toLong(),
                "${C4Listener.AUTH_MODE_BASIC} !$@£ᘺ"
            )
        )
    }

    @Test
    fun testHttpListenerAuthenticateNoCredentials() {
        var user: String? = null
        var pwd: CharArray? = null

        val listener = C4Listener.createHttpListener(
            2222,
            "en0",
            "/here/there/everywhere",
            ListenerPasswordAuthenticator { u, p ->
                user = u
                pwd = p
                true
            },
            true,
            true,
            true
        )
        assertNotNull(listener)

        assertEquals(1, C4Listener.LISTENER_CONTEXT.size())
        val key = C4Listener.LISTENER_CONTEXT.keySet().iterator().next()

        assertTrue(C4Listener.httpAuthCallback(key.toLong(), C4Listener.AUTH_MODE_BASIC))

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
            ListenerPasswordAuthenticator { u, p ->
                user = u
                pwd = p
                true
            },
            true,
            true,
            true
        )
        assertNotNull(listener)

        assertEquals(1, C4Listener.LISTENER_CONTEXT.size())
        val key = C4Listener.LISTENER_CONTEXT.keySet().iterator().next()

        assertTrue(C4Listener.httpAuthCallback(key.toLong(), "${C4Listener.AUTH_MODE_BASIC}    "))

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
            ListenerPasswordAuthenticator { u, p ->
                user = u
                pwd = p
                true
            },
            true,
            true,
            true
        )
        assertNotNull(listener)

        assertEquals(1, C4Listener.LISTENER_CONTEXT.size())
        val key = C4Listener.LISTENER_CONTEXT.keySet().iterator().next()

        val creds = "${USER_NAME}:${PASSWORD}".toByteArray(Charsets.UTF_8)
        assertTrue(
            C4Listener.httpAuthCallback(
                key.toLong(),
                "${C4Listener.AUTH_MODE_BASIC} ${PlatformUtils.getEncoder().encodeToString(creds)}"
            )
        )

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
            ListenerPasswordAuthenticator { u, p ->
                user = u
                pwd = p
                true
            },
            true,
            true,
            true
        )
        assertNotNull(listener)

        assertEquals(1, C4Listener.LISTENER_CONTEXT.size())
        val key = C4Listener.LISTENER_CONTEXT.keySet().iterator().next()

        val creds = ":${PASSWORD}".toByteArray(Charsets.UTF_8)
        assertTrue(
            C4Listener.httpAuthCallback(
                key.toLong(),
                "${C4Listener.AUTH_MODE_BASIC} ${PlatformUtils.getEncoder().encodeToString(creds)}"
            )
        )

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
            ListenerPasswordAuthenticator { u, p ->
                user = u
                pwd = p
                true
            },
            true,
            true,
            true
        )
        assertNotNull(listener)

        assertEquals(1, C4Listener.LISTENER_CONTEXT.size())
        val key = C4Listener.LISTENER_CONTEXT.keySet().iterator().next()

        val creds = USER_NAME.toByteArray(Charsets.UTF_8)
        assertTrue(
            C4Listener.httpAuthCallback(
                key.toLong(),
                "${C4Listener.AUTH_MODE_BASIC} ${PlatformUtils.getEncoder().encodeToString(creds)}"
            )
        )

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
            ListenerPasswordAuthenticator { u, p ->
                user = u
                pwd = p
                true
            },
            true,
            true,
            true
        )
        assertNotNull(listener)

        assertEquals(1, C4Listener.LISTENER_CONTEXT.size())
        val key = C4Listener.LISTENER_CONTEXT.keySet().iterator().next()

        val creds = "${USER_NAME}:".toByteArray(Charsets.UTF_8)
        assertTrue(
            C4Listener.httpAuthCallback(
                key.toLong(),
                "${C4Listener.AUTH_MODE_BASIC} ${PlatformUtils.getEncoder().encodeToString(creds)}"
            )
        )

        assertEquals(USER_NAME, user)
        assertEquals(0, pwd?.size)
    }

    @Test
    fun testCreateTlsPasswordListener() {
        assertEquals(0, C4Listener.LISTENER_CONTEXT.size())

        val alias = newKeyAlias()
        loadTestKey(alias)
        val ks = loadPlatformKeyStore()
        val cert = ks.getCertificate(alias)
        val keyPair = createC4KeyPair(alias)

        val listener = C4Listener.createTlsListenerPasswordAuth(
            2222,
            "en0",
            "/here/there/everywhere",
            ListenerPasswordAuthenticator { _, _ -> true },
            true,
            true,
            true,
            cert,
            keyPair
        )
        assertNotNull(listener)
        assertEquals(1, C4Listener.LISTENER_CONTEXT.size())
    }

    @Test
    fun testCreateTlsCertificateListener() {
        assertEquals(0, C4Listener.LISTENER_CONTEXT.size())

        val alias = newKeyAlias()
        loadTestKey(alias)
        val ks = loadPlatformKeyStore()
        val cert = ks.getCertificate(alias)
        val keyPair = createC4KeyPair(alias)

        val listener = C4Listener.createTlsListenerCertAuth(
            2222,
            "en0",
            "/here/there/everywhere",
            ListenerCertificateAuthenticator { true },
            true,
            true,
            true,
            cert,
            keyPair
        )
        assertNotNull(listener)

        assertEquals(1, C4Listener.LISTENER_CONTEXT.size())
    }

    @Test
    fun testTlsCertificateListenerAuthenticate() {
        val alias = newKeyAlias()
        loadTestKey(alias)
        val ks = loadPlatformKeyStore()
        val cert = ks.getCertificate(alias)
        val keyPair = createC4KeyPair(alias)

        var clientCerts: List<Certificate>? = null
        val listener = C4Listener.createTlsListenerCertAuth(
            2222,
            "en0",
            "/here/there/everywhere",
            ListenerCertificateAuthenticator { certs ->
                clientCerts = certs
                true
            },
            true,
            true,
            true,
            cert,
            keyPair
        )
        assertNotNull(listener)

        assertEquals(1, C4Listener.LISTENER_CONTEXT.size())
        val key = C4Listener.LISTENER_CONTEXT.keySet().iterator().next()

        C4Listener.certAuthCallback(key.toLong(), cert.encoded)

        val certs = clientCerts
        assertNotNull(certs)
        assertFalse(certs!!.isEmpty())
        assertEquals(cert, certs[0])
    }
}
