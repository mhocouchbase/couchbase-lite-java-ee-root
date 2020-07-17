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

import com.couchbase.lite.PlatformBaseTest
import com.couchbase.lite.internal.core.C4KeyPair
import com.couchbase.lite.internal.utils.PlatformUtils
import com.couchbase.lite.internal.utils.TestUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Ignore
import org.junit.Test
import java.security.KeyStore
import java.security.interfaces.RSAKey
import java.util.Calendar

class KeyStoreManagerTest : PlatformBaseTest() {

    @Test
    fun testGetKeyData() {
        val pwd = "password".toCharArray()
        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(PlatformUtils.getAsset("teststore.p12"), pwd)

        KeyStoreManager.getInstance().getKeyData(keyStore, "couchbase", pwd)
    }

    @Test
    fun testGenerateRSAKeyPair() {
        val exp = Calendar.getInstance()
        exp.add(Calendar.YEAR, 3)
        val keys = KeyStoreManager.getInstance().generateRSAKeyPair(
            "foo",
            true,
            KeyStoreManager.KeySize.BIT_2048,
            mapOf(KeyStoreManager.CERT_ATTRIBUTE_COMMON_NAME to "couchbase"),
            exp.time
        )
        assertNotNull(keys)
        assertNotNull(keys!!.private as? RSAKey)
        assertEquals(2048, (keys.private as RSAKey).modulus.bitLength())
    }

    @Ignore("BROKEN TEST: LiteCoreException{domain=1, code=22, msg=Can't parse certificate request data (X509 - The name tag or value is invalid : ASN1 - Out of data when parsing an ASN1 data structure)}")
    @Test
    fun testGenerateSelfSignedCertificate() {
        val exp = Calendar.getInstance()
        exp.add(Calendar.YEAR, 3)
        val keys = KeyStoreManager.getInstance().generateRSAKeyPair(
            "foo",
            true,
            KeyStoreManager.KeySize.BIT_2048,
            mapOf(KeyStoreManager.CERT_ATTRIBUTE_COMMON_NAME to "couchbase"),
            exp.time
        )
        assertNotNull(keys)

        val c4Keys = C4KeyPair.createKeyPair(
            "foo",
            KeyStoreManager.KeyAlgorithm.RSA,
            KeyStoreManager.KeySize.BIT_2048
        )
        assertNotNull(c4Keys)

        c4Keys.generateSelfSignedCertificate(
            KeyStoreManager.KeyAlgorithm.RSA,
            KeyStoreManager.KeySize.BIT_2048,
            TestUtils.get509Attributes(),
            KeyStoreManager.CertUsage.TLS_SERVER
        )
    }
}
