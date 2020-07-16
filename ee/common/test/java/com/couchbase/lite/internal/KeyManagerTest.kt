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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Ignore
import org.junit.Test
import java.security.interfaces.RSAKey
import java.util.Calendar

class KeyManagerTest : PlatformBaseTest() {
    @Test
    fun testCreateKeyPair() {
        val exp = Calendar.getInstance()
        exp.add(Calendar.YEAR, 3)
        val keys = KeyStoreManager.getInstance().generateRSAKeyPair(
            "foo",
            true,
            KeyStoreManager.KeySize.BIT_2048,
            mapOf(KeyStoreManager.CertAttribute.COMMON_NAME to "couchbase"),
            exp.time
        )
        assertNotNull(keys)
        assertNotNull(keys!!.private as? RSAKey)
        assertEquals(2048, (keys.private as RSAKey).modulus.bitLength())
    }

    @Test
    fun testCreateC4KeyPair() {
        val exp = Calendar.getInstance()
        exp.add(Calendar.YEAR, 3)
        val keys = KeyStoreManager.getInstance().generateRSAKeyPair(
            "foo",
            true,
            KeyStoreManager.KeySize.BIT_2048,
            mapOf(KeyStoreManager.CertAttribute.COMMON_NAME to "couchbase"),
            exp.time
        )
        assertNotNull(keys)

        val c4Keys = C4KeyPair.createKeyPair(
            "foo",
            KeyStoreManager.KeyAlgorithm.RSA,
            KeyStoreManager.KeySize.BIT_2048
        )
        assertNotNull(c4Keys)
    }

    @Ignore("java.security.InvalidKeyException: Unsupported key algorithm: RSA. OnlyEC supported")
    @Test
    fun testCreateCertificate() {
        val exp = Calendar.getInstance()
        exp.add(Calendar.YEAR, 3)
        val keys = KeyStoreManager.getInstance().generateRSAKeyPair(
            "foo",
            true,
            KeyStoreManager.KeySize.BIT_2048,
            mapOf(KeyStoreManager.CertAttribute.COMMON_NAME to "couchbase"),
            exp.time
        )
        assertNotNull(keys)

        val c4Keys = C4KeyPair.createKeyPair(
            "foo",
            KeyStoreManager.KeyAlgorithm.RSA,
            KeyStoreManager.KeySize.BIT_2048
        )
        assertNotNull(c4Keys)

        val subjectName: Map<KeyStoreManager.CertAttribute, String> = mapOf(
            KeyStoreManager.CertAttribute.COMMON_NAME to "CouchbaseLite",
            KeyStoreManager.CertAttribute.ORGANIZATION to "Couchbase",
            KeyStoreManager.CertAttribute.ORGANIZATION_UNIT to "Mobile",
            KeyStoreManager.CertAttribute.EMAIL_ADDRESS to "lite@couchbase.com"
        )

        c4Keys.generateSelfSignedCertificate(
            KeyStoreManager.KeyAlgorithm.RSA,
            KeyStoreManager.KeySize.BIT_2048,
            subjectName,
            KeyStoreManager.CertUsage.TLS_SERVER
        )
    }
}
