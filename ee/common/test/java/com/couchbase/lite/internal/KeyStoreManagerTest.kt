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
package com.couchbase.lite.internal

import com.couchbase.lite.PlatformSecurityTest
import com.couchbase.lite.TLSIdentity
import com.couchbase.lite.internal.security.Signature
import com.couchbase.lite.internal.utils.SlowTest
import org.junit.After
import org.junit.Assert
import org.junit.Test
import java.util.Calendar
import javax.crypto.Cipher


class KeyStoreManagerTest : PlatformSecurityTest() {
    @After
    fun tearDownKeyStoreManagerTest() {
        KeyStoreManager.getInstance()
            .deleteEntries(loadPlatformKeyStore()) { alias -> alias.startsWith(BASE_KEY_ALIAS) }
    }

    @SlowTest
    @Test
    fun testGetKeyData() {
        val key = loadTestKeyStore().getCertificate(EXTERNAL_KEY_ALIAS).publicKey.encoded

        val alias = newKeyAlias()

        loadTestKey(alias)

        val data = KeyStoreManager.getInstance().getKeyData(createC4KeyPair(alias))

        Assert.assertNotNull(data)
        Assert.assertArrayEquals(key, data)
    }

    @SlowTest
    @Test
    fun testSign() {
        val testStore = loadTestKeyStore()
        val key = testStore.getCertificate(EXTERNAL_KEY_ALIAS).publicKey

        val alias = newKeyAlias()

        val data = "Ridin' shotgun down the avalanche".toByteArray(Charsets.UTF_8)

        loadTestKey(alias)

        val algorithms = Signature.SignatureDigestAlgorithm.values()
        for (algorithm in algorithms) {
            val digest = createDigest(algorithm, data)

            val signedData = KeyStoreManager.getInstance().sign(createC4KeyPair(alias), algorithm, digest)

            val sig = java.security.Signature.getInstance(ALGORITHMS[algorithm]?.signatureAlgorithm)
            sig.initVerify(key)
            sig.update(data)

            Assert.assertTrue("Failed using algorithm: ${algorithm}", sig.verify(signedData))
        }
    }

    @SlowTest
    @Test
    fun testDecrypt() {
        val testStore = loadTestKeyStore()
        val key = testStore.getCertificate(EXTERNAL_KEY_ALIAS).publicKey

        val alias = newKeyAlias()

        val cleartext = "Ridin' shotgun down the avalanche"

        loadTestKey(alias)

        val cipher = Cipher.getInstance(KeyStoreManager.CIPHER_TYPE)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encrypted = cipher.doFinal(cleartext.toByteArray(Charsets.UTF_8))

        val data = KeyStoreManager.getInstance().decrypt(createC4KeyPair(alias), encrypted)

        Assert.assertEquals(cleartext, String(data!!))
    }

    // ??? Need a test for KeyStoreManager.free?


    @Test
    fun testFindAlias() {
        val mgr = KeyStoreManager.getInstance()

        val alias = newKeyAlias()

        val keyStore = loadPlatformKeyStore()

        Assert.assertFalse(mgr.findAlias(keyStore, alias))
        Assert.assertFalse(mgr.findAlias(keyStore, EXTERNAL_KEY_ALIAS))

        loadTestKey(alias)

        Assert.assertTrue(mgr.findAlias(keyStore, alias))
        Assert.assertFalse(mgr.findAlias(keyStore, EXTERNAL_KEY_ALIAS))
    }

    @Test
    fun testGetKey() {
        val mgr = KeyStoreManager.getInstance()

        val alias = newKeyAlias()

        val keyStore = loadPlatformKeyStore()

        Assert.assertNull(mgr.getKey(keyStore, EXTERNAL_KEY_ALIAS, null))
        Assert.assertNull(mgr.getKey(keyStore, alias, null))

        loadTestKey(alias)

        // ??? there really must be more that we can test...
        Assert.assertNotNull(mgr.getKey(keyStore, alias, EXTERNAL_KEY_PASSWORD.toCharArray()))
        Assert.assertNull(mgr.getKey(keyStore, EXTERNAL_KEY_ALIAS, EXTERNAL_KEY_PASSWORD.toCharArray()))
    }

    @Test
    fun testGetCerts() {
        val mgr = KeyStoreManager.getInstance()

        val alias = newKeyAlias()

        val keyStore = loadPlatformKeyStore()

        Assert.assertNull(mgr.getCertificates(keyStore, EXTERNAL_KEY_ALIAS))
        Assert.assertNull(mgr.getCertificates(keyStore, alias))

        loadTestKey(alias)

        // ??? there really must be more that we can test...
        Assert.assertNotNull(mgr.getCertificates(keyStore, alias))
        Assert.assertNull(mgr.getCertificates(keyStore, EXTERNAL_KEY_ALIAS))
    }

    @Test
    fun testCreateSelfSignedCertEntry() {
        val keyStore = loadPlatformKeyStore()

        val alias = newKeyAlias()

        val attrs = mapOf(TLSIdentity.CERT_ATTRIBUTE_COMMON_NAME to "Couchbase")
        val exp = Calendar.getInstance()
        exp.add(Calendar.YEAR, 3)

        KeyStoreManager.getInstance().createSelfSignedCertEntry(
            keyStore,
            alias,
            null,
            true,
            attrs,
            exp.time
        )
    }

    @SlowTest
    @Test
    fun testDeleteEntries() {
        val alias1 = newKeyAlias()
        val alias2 = newKeyAlias()

        val keyStore = loadPlatformKeyStore()

        loadTestKey(alias1)
        loadTestKey(alias2)

        Assert.assertTrue(KeyStoreManager.getInstance().findAlias(keyStore, alias1))
        Assert.assertTrue(KeyStoreManager.getInstance().findAlias(keyStore, alias2))

        Assert.assertEquals(1, KeyStoreManager.getInstance().deleteEntries(keyStore) { a -> a == alias1 })

        Assert.assertFalse(KeyStoreManager.getInstance().findAlias(keyStore, alias1))
        Assert.assertTrue(KeyStoreManager.getInstance().findAlias(keyStore, alias2))
    }
}
