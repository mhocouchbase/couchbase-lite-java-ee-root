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

import com.couchbase.lite.BaseTest
import com.couchbase.lite.PlatformSecurityTest
import com.couchbase.lite.internal.KeyStoreManager
import com.couchbase.lite.internal.core.impl.NativeC4KeyPair
import com.couchbase.lite.internal.core.impl.NativeC4Listener
import com.couchbase.lite.internal.security.Signature
import com.couchbase.lite.internal.utils.Fn
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import java.security.KeyStore
import java.security.cert.Certificate
import java.security.interfaces.RSAPrivateKey
import java.util.Date
import kotlin.random.Random

class C4KeyPairTest : PlatformSecurityTest() {
    data class NativeCall(val alg: Byte, val bits: Int, val token: Long, val data: Any? = null)

    data class StoreMgrCall(val store: KeyStore?, val alias: String, val pwd: String?, val data: Any? = null)

    private val c4NativeMock = object : C4KeyPair.NativeImpl {
        val calls = mutableListOf<NativeCall>()

        override fun nGenerateSelfSignedCertificate(
            c4KeyPair: Long,
            algorithm: Byte,
            keyBits: Int,
            attributes: Array<out Array<String>>?,
            usage: Byte,
            validityInSeconds: Long
        ): ByteArray {
            calls.add(NativeCall(algorithm, keyBits, c4KeyPair, attributes))
            return Random.Default.nextBytes(256)
        }

        override fun nFromExternal(algorithm: Byte, keyBits: Int, token: Long): Long {
            calls.add(NativeCall(algorithm, keyBits, token))
            return 1L
        }

        override fun nFree(token: Long) = Unit

        fun reset() = calls.clear()
    }

    private val keyStoreManagerMock = object : KeyStoreManager() {
        val calls = mutableListOf<StoreMgrCall>()

        override fun getKeyData(keyPair: C4KeyPair): ByteArray? {
            calls.add(
                StoreMgrCall(
                    keyPair.keyStore, keyPair.keyAlias,
                    if (keyPair.keyPassword == null) null else String(keyPair.keyPassword!!)
                )
            )
            return null
        }

        override fun decrypt(keyPair: C4KeyPair, data: ByteArray): ByteArray? {
            calls.add(
                StoreMgrCall(
                    keyPair.keyStore, keyPair.keyAlias,
                    if (keyPair.keyPassword == null) null else String(keyPair.keyPassword!!), data
                )
            )
            return null
        }

        override fun sign(keyPair: C4KeyPair, digestAlgorithm: Signature.SignatureDigestAlgorithm, data: ByteArray)
                : ByteArray? {
            calls.add(
                StoreMgrCall(
                    keyPair.keyStore, keyPair.keyAlias,
                    if (keyPair.keyPassword == null) null else String(keyPair.keyPassword!!), data
                )
            )
            return null
        }

        override fun free(keyPair: C4KeyPair) {
            calls.add(
                StoreMgrCall(
                    keyPair.keyStore, keyPair.keyAlias,
                    if (keyPair.keyPassword == null) null else String(keyPair.keyPassword!!)
                )
            )
        }

        override fun getCertificateChain(keyStore: KeyStore?, keyAlias: String): MutableList<Certificate>? = null

        override fun getKey(keyStore: KeyStore?, keyAlias: String, keyPassword: CharArray?): RSAPrivateKey? = null

        override fun createSelfSignedCertEntry(
            keyStore: KeyStore?,
            alias: String,
            keyPassword: CharArray?,
            isServer: Boolean,
            attributes: MutableMap<String, String>,
            expiration: Date?
        ) = Unit

        override fun findAlias(keyStore: KeyStore?, keyAlias: String) = true

        override fun deleteEntries(keyStore: KeyStore?, filter: Fn.Predicate<String>?) = 0

        fun reset() = calls.clear()
    }


    @Before
    fun setUpC4ListenerTest() {
        keyStoreManagerMock.reset()
        KeyStoreManager.setInstance(keyStoreManagerMock)
        c4NativeMock.reset()
        C4KeyPair.nativeImpl = c4NativeMock
        C4KeyPair.KEY_PAIR_CONTEXT.clear()
        BaseTest.logTestInitializationComplete("C4KeyPair")

    }

    @After
    fun tearDownC4ListenerTest() {
        BaseTest.logTestTeardownBegun("C4KeyPair")
        KeyStoreManager.setInstance(null)
        C4Listener.nativeImpl = NativeC4Listener()
        C4KeyPair.nativeImpl = NativeC4KeyPair()
        C4KeyPair.KEY_PAIR_CONTEXT.clear()
    }

    @Test
    fun testCreateC4KeyPair() {
        val c4Keys = C4KeyPair.createKeyPair(
            KeyStore.getInstance("PKCS12"),
            "foo",
            "foo".toCharArray(),
            KeyStoreManager.KeyAlgorithm.RSA,
            KeyStoreManager.KeySize.BIT_2048
        )
        Assert.assertNotNull(c4Keys)
        Assert.assertEquals(1, c4NativeMock.calls.size)
        val call = c4NativeMock.calls[0]
        Assert.assertNotNull(call)
        Assert.assertEquals(0.toByte(), call.alg)
        Assert.assertEquals(2048, call.bits)
        Assert.assertNotEquals(0, call.token)
    }

    @Test
    fun testShortCreateC4KeyPair() {
        val c4Keys = C4KeyPair.createKeyPair(
            "foo",
            KeyStoreManager.KeyAlgorithm.RSA,
            KeyStoreManager.KeySize.BIT_2048
        )
        Assert.assertNotNull(c4Keys)
        Assert.assertEquals(1, c4NativeMock.calls.size)
        val call = c4NativeMock.calls[0]
        Assert.assertNotNull(call)
        Assert.assertEquals(0.toByte(), call.alg)
        Assert.assertEquals(2048, call.bits)
        Assert.assertNotEquals(0, call.token)
    }

    @Test
    fun testGetKeyDataCallback() {
        val store = KeyStore.getInstance("PKCS12")
        val c4Keys = C4KeyPair.createKeyPair(
            store,
            "foo",
            "foo".toCharArray(),
            KeyStoreManager.KeyAlgorithm.RSA,
            KeyStoreManager.KeySize.BIT_2048
        )
        Assert.assertNotNull(c4Keys)

        val tokens = C4KeyPair.KEY_PAIR_CONTEXT.keySet()
        Assert.assertEquals(1, tokens.size)

        C4KeyPair.getKeyDataCallback(tokens.iterator().next().toLong())

        Assert.assertEquals(1, keyStoreManagerMock.calls.size)
        val call = keyStoreManagerMock.calls[0]
        Assert.assertEquals(store, call.store)
        Assert.assertEquals("foo", call.alias)
        Assert.assertEquals("foo", call.pwd)
    }

    @Test
    fun testDecryptCallback() {
        val store = KeyStore.getInstance("PKCS12")
        val data = Random.nextBytes(173)

        val c4Keys = C4KeyPair.createKeyPair(
            store,
            "foo",
            "foo".toCharArray(),
            KeyStoreManager.KeyAlgorithm.RSA,
            KeyStoreManager.KeySize.BIT_2048
        )
        Assert.assertNotNull(c4Keys)

        val tokens = C4KeyPair.KEY_PAIR_CONTEXT.keySet()
        Assert.assertEquals(1, tokens.size)

        C4KeyPair.decryptCallback(tokens.iterator().next().toLong(), data)

        Assert.assertEquals(1, keyStoreManagerMock.calls.size)
        val call = keyStoreManagerMock.calls[0]
        Assert.assertEquals(store, call.store)
        Assert.assertEquals("foo", call.alias)
        Assert.assertEquals("foo", call.pwd)
        Assert.assertEquals(data, call.data)
    }

    @Test
    fun testSignKeyCallback() {
        val store = KeyStore.getInstance("PKCS12")
        val data = Random.nextBytes(173)

        val c4Keys = C4KeyPair.createKeyPair(
            store,
            "foo",
            "foo".toCharArray(),
            KeyStoreManager.KeyAlgorithm.RSA,
            KeyStoreManager.KeySize.BIT_2048
        )
        Assert.assertNotNull(c4Keys)

        val tokens = C4KeyPair.KEY_PAIR_CONTEXT.keySet()
        Assert.assertEquals(1, tokens.size)

        C4KeyPair.signCallback(tokens.iterator().next().toLong(), 0, data)

        Assert.assertEquals(1, keyStoreManagerMock.calls.size)
        val call = keyStoreManagerMock.calls[0]
        Assert.assertEquals(store, call.store)
        Assert.assertEquals("foo", call.alias)
        Assert.assertEquals("foo", call.pwd)
        Assert.assertEquals(data, call.data)
    }

    @Test
    fun freeCallback() {
        val c4Keys = C4KeyPair.createKeyPair(
            "foo",
            KeyStoreManager.KeyAlgorithm.RSA,
            KeyStoreManager.KeySize.BIT_2048
        )
        Assert.assertNotNull(c4Keys)

        val tokens = C4KeyPair.KEY_PAIR_CONTEXT.keySet()
        Assert.assertEquals(1, tokens.size)

        C4KeyPair.freeCallback(tokens.iterator().next().toLong())

        Assert.assertEquals(1, keyStoreManagerMock.calls.size)
        val call = keyStoreManagerMock.calls[0]
        Assert.assertEquals("foo", call.alias)
    }

    @Test
    fun generateSelfSignedCertificate() {
        val c4Keys = C4KeyPair.createKeyPair(
            "foo",
            KeyStoreManager.KeyAlgorithm.RSA,
            KeyStoreManager.KeySize.BIT_2048
        )
        val cert = c4Keys.generateSelfSignedCertificate(
            KeyStoreManager.KeyAlgorithm.RSA,
            KeyStoreManager.KeySize.BIT_2048,
            X509_ATTRIBUTES,
            KeyStoreManager.CertUsage.TLS_SERVER,
            0
        )

        Assert.assertNotNull(cert)
        Assert.assertEquals(2, c4NativeMock.calls.size)
        // the createKeyPair call is first.
        val call = c4NativeMock.calls[1]

        Assert.assertEquals(0.toByte(), call.alg)
        Assert.assertEquals(2048, call.bits)
        Assert.assertNotEquals(0, call.token)
        for (attr in call.data as Array<*>) {
            val att = attr as Array<*>
            Assert.assertEquals(X509_ATTRIBUTES[att[0]], att[1])
        }
    }
}
