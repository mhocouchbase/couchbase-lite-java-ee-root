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
package com.couchbase.lite.internal

import com.couchbase.lite.TLSIdentity
import com.couchbase.lite.URLEndpointListener
import com.couchbase.lite.internal.utils.PlatformUtils
import org.junit.After
import org.junit.Assert
import org.junit.Ignore
import org.junit.Test
import java.io.InputStream
import java.security.KeyStore
import java.util.Calendar
import kotlin.random.Random

class KeyStoreManagerTest : KeyStoreTestAdaptor() {
    @After
    fun tearDownKeyStoreManagerTest() {
        KeyStoreManager.getInstance()
            .deleteEntries(loadPlatformKeyStore()) { alias -> alias.startsWith(BASE_KEY_ALIAS) }
    }

    @Test
    fun testGetKeyData() {
        val alias = newKeyAlias()

        val keyStore = loadPlatformKeyStore()
        loadTestKeys(keyStore, alias)

        val data = KeyStoreManager.getInstance().getKeyData(getC4KeyPair(keyStore, alias))

        Assert.assertNotNull(data)
        Assert.assertEquals(294, data?.size)
    }

    @Ignore("!!! FAILING TEST")
    @Test
    fun testDecrypt() {
        val alias = newKeyAlias()

        val keyStore = loadPlatformKeyStore()
        loadTestKeys(keyStore, alias)

        // FIXME: The data needs to be encrypted properly for testing:
        val data = KeyStoreManager.getInstance().decrypt(getC4KeyPair(keyStore, alias), Random.Default.nextBytes(256))

        Assert.assertNotNull(data)
    }

    @Test
    fun testSignKey() {
        val alias = newKeyAlias()

        val keyStore = loadPlatformKeyStore()
        loadTestKeys(keyStore, alias)

        val data = KeyStoreManager.getInstance().sign(
            getC4KeyPair(keyStore, alias),
            KeyStoreManager.SignatureDigestAlgorithm.SHA256,
            Random.Default.nextBytes(256)
        )

        Assert.assertNotNull(data)
    }

    // ??? Need a test for KeyStoreManager.free?

    @Test
    fun testCreateSelfSignedCertEntry() {
        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(null)

        val attrs = mapOf(URLEndpointListener.CERT_ATTRIBUTE_COMMON_NAME to "Couchbase")
        val exp = Calendar.getInstance()
        exp.add(Calendar.YEAR, 3)

        KeyStoreManager.getInstance().createSelfSignedCertEntry(
            keyStore,
            "MyCert",
            null,
            true,
            attrs,
            exp.time
        )
    }

    @Ignore("!!! FAILING TEST (java)")
    @Test
    fun testImportEntry() {
        val keyStore = loadPlatformKeyStore()

        val alias = newKeyAlias()
        Assert.assertNull(keyStore.getEntry(alias, null))

        PlatformUtils.getAsset(EXTERNAL_KEY_STORE)?.use {
            KeyStoreManager.getInstance().importEntry(
                EXTERNAL_KEY_STORE_TYPE,
                it,
                EXTERNAL_KEY_PASSWORD.toCharArray(),
                EXTERNAL_KEY_ALIAS,
                null,
                alias
            )
        }

        Assert.assertNotNull(keyStore.getEntry(alias, null))
    }

    @Test
    fun testFindAlias() {
        val alias = newKeyAlias()

        val keyStore = loadPlatformKeyStore()

        Assert.assertFalse(KeyStoreManager.getInstance().findAlias(keyStore, alias))

        loadTestKeys(keyStore, alias)

        Assert.assertTrue(KeyStoreManager.getInstance().findAlias(keyStore, alias))
    }

    // ??? How to test getCertificate?  Params are different on the two platforms.

    @Test
    fun testDeleteEntries() {
        val alias1 = newKeyAlias()
        val alias2 = newKeyAlias()

        val keyStore = loadPlatformKeyStore()

        loadTestKeys(keyStore, alias1)
        loadTestKeys(keyStore, alias2)

        Assert.assertTrue(KeyStoreManager.getInstance().findAlias(keyStore, alias1))
        Assert.assertTrue(KeyStoreManager.getInstance().findAlias(keyStore, alias2))

        Assert.assertEquals(1, KeyStoreManager.getInstance().deleteEntries(keyStore) { a -> a == alias1 })

        Assert.assertFalse(KeyStoreManager.getInstance().findAlias(keyStore, alias1))
        Assert.assertTrue(KeyStoreManager.getInstance().findAlias(keyStore, alias2))
    }

    override fun importIdentity(
        extType: String,
        extStore: InputStream,
        extStorePass: CharArray,
        extAlias: String,
        extKeyPass: CharArray,
        alias: String
    ): TLSIdentity? {
        TODO("Not yet implemented")
    }
}
