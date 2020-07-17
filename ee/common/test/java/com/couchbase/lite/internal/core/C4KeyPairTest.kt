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

import com.couchbase.lite.PlatformBaseTest
import com.couchbase.lite.internal.KeyStoreManager
import com.couchbase.lite.internal.core.impl.NativeC4Listener
import com.couchbase.lite.internal.utils.Fn
import com.couchbase.lite.internal.utils.TestUtils
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import java.io.InputStream
import java.security.KeyPair
import java.security.KeyStore
import java.security.cert.Certificate
import java.util.Date
import kotlin.random.Random

class C4KeyPairTest : PlatformBaseTest() {
    data class NativeCall(val alg: Byte, val bits: Int, val token: Long, val data: Any? = null)

    private val c4NativeImpl = object : C4KeyPair.NativeImpl {
        val calls = mutableListOf<NativeCall>()

        override fun nGenerateSelfSignedCertificate(
            c4KeyPair: Long,
            algorithm: Byte,
            keyBits: Int,
            attributes: Array<out Array<String>>?,
            usage: Byte
        ): ByteArray {
            calls.add(NativeCall(algorithm, keyBits, 0L, attributes))
            return Random.Default.nextBytes(256)
        }

        override fun nFromExternal(algorithm: Byte, keyBits: Int, token: Long): Long {
            calls.add(NativeCall(algorithm, keyBits, token))
            return 1L
        }

        override fun nFree(token: Long) = Unit

        fun reset() = calls.clear()
    }

    data class StoreMgrCall(val store: KeyStore?, val alias: String, val pwd: String?, val data: Any? = null)

    private val keyStoreManagerImpl = object : KeyStoreManager() {
        val calls = mutableListOf<StoreMgrCall>()

        override fun getKeyData(keyStore: KeyStore?, keyAlias: String, keyPassword: CharArray?): ByteArray? {
            calls.add(StoreMgrCall(keyStore, keyAlias, if (keyPassword == null) null else String(keyPassword)))
            return null
        }

        override fun decrypt(
            keyStore: KeyStore?,
            keyAlias: String,
            keyPassword: CharArray?,
            data: ByteArray
        ): ByteArray? {
            calls.add(StoreMgrCall(keyStore, keyAlias, if (keyPassword == null) null else String(keyPassword), data))
            return null
        }

        override fun signKey(
            keyStore: KeyStore?,
            keyAlias: String,
            keyPassword: CharArray?,
            digestAlgorithm: SignatureDigestAlgorithm,
            data: ByteArray
        ): ByteArray? {
            calls.add(StoreMgrCall(keyStore, keyAlias, if (keyPassword == null) null else String(keyPassword), data))
            return null
        }

        override fun free(keyStore: KeyStore?, keyAlias: String, keyPassword: CharArray?) {
            calls.add(StoreMgrCall(keyStore, keyAlias, if (keyPassword == null) null else String(keyPassword)))
        }

        override fun createCertEntry(
            alias: String,
            isServer: Boolean,
            attributes: MutableMap<String, String>,
            expiration: Date
        ) = Unit

        override fun createAnonymousCertEntry(alias: String, isServer: Boolean) = Unit

        override fun findAlias(keyAlias: String) = true

        override fun getCertificate(keyStore: KeyStore?, keyAlias: String, keyPassword: CharArray?): Certificate? = null

        override fun deleteEntries(filter: Fn.Predicate<String>?) = 0

        override fun importEntry(
            type: String,
            stream: InputStream,
            storePassword: CharArray?,
            alias: String,
            keyPassword: CharArray?,
            targetAlias: String
        ) = Unit

        override fun generateRSAKeyPair(
            alias: String,
            isServer: Boolean,
            keySize: KeySize,
            attributes: MutableMap<String, String>,
            expiration: Date
        ): KeyPair? = null

        fun reset() = calls.clear()
    }


    @Before
    fun setUpC4ListenerTest() {
        keyStoreManagerImpl.reset()
        KeyStoreManager.setInstance(keyStoreManagerImpl)
        c4NativeImpl.reset()
        C4KeyPair.nativeImpl = c4NativeImpl
        C4KeyPair.KEY_PAIR_CONTEXT.clear()
    }

    @After
    fun tearDownC4ListenerTest() {
        KeyStoreManager.setInstance(null)
        C4KeyPair.KEY_PAIR_CONTEXT.clear()
        C4Listener.nativeImpl = NativeC4Listener()
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
        Assert.assertEquals(1, c4NativeImpl.calls.size)
        val call = c4NativeImpl.calls[0]
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
        Assert.assertEquals(1, c4NativeImpl.calls.size)
        val call = c4NativeImpl.calls[0]
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

        Assert.assertEquals(1, keyStoreManagerImpl.calls.size)
        val call = keyStoreManagerImpl.calls[0]
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

        Assert.assertEquals(1, keyStoreManagerImpl.calls.size)
        val call = keyStoreManagerImpl.calls[0]
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

        C4KeyPair.signKeyCallback(tokens.iterator().next().toLong(), 0, data)

        Assert.assertEquals(1, keyStoreManagerImpl.calls.size)
        val call = keyStoreManagerImpl.calls[0]
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

        Assert.assertEquals(1, keyStoreManagerImpl.calls.size)
        val call = keyStoreManagerImpl.calls[0]
        Assert.assertEquals("foo", call.alias)
    }

    @Ignore("BROKEN TEST")
    @Test
    fun generateSelfSignedCertificate() {
        val c4Keys = C4KeyPair.createKeyPair(
            "foo",
            KeyStoreManager.KeyAlgorithm.RSA,
            KeyStoreManager.KeySize.BIT_2048
        )

        val attributes = TestUtils.get509Attributes();

        val cert = c4Keys.generateSelfSignedCertificate(
            KeyStoreManager.KeyAlgorithm.RSA,
            KeyStoreManager.KeySize.BIT_2048,
            attributes,
            KeyStoreManager.CertUsage.TLS_SERVER
        )

        Assert.assertNotNull(cert)
        Assert.assertEquals(1, c4NativeImpl.calls.size)
        val call = c4NativeImpl.calls[0]
        Assert.assertEquals(0, call.alg)
        Assert.assertEquals(2048, call.bits)
        Assert.assertNotEquals(0, call.token)
        Assert.assertEquals(attributes, call.data)
    }
}
