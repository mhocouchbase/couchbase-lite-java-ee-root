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
import com.couchbase.lite.internal.AbstractTLSIdentity
import com.couchbase.lite.internal.CouchbaseLiteInternal
import com.couchbase.lite.internal.KeyManager
import com.couchbase.lite.internal.core.C4KeyPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import java.math.BigInteger
import java.security.interfaces.RSAKey
import java.util.Calendar

class KeyManagerTest : PlatformBaseTest() {
    private lateinit var keyManager: KeyManager

    @Before
    fun setUpExecutionServiceTest() {
        keyManager = CouchbaseLiteInternal.getKeyManager()
    }

    @Test
    fun testCreateKeyPair() {
        val exp = Calendar.getInstance()
        exp.add(Calendar.YEAR, 3)
        val keys = keyManager.generateKeyPair(
            "foo",
            KeyManager.KeyAlgorithm.RSA,
            KeyManager.KeySize.BIT_2048,
            BigInteger.TEN,
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
        val keys = keyManager.generateKeyPair(
            "foo",
            KeyManager.KeyAlgorithm.RSA,
            KeyManager.KeySize.BIT_2048,
            BigInteger.TEN,
            exp.time
        )

        assertNotNull(keys)
        val c4Keys = C4KeyPair.createKeyPair(keys!!, KeyManager.KeyAlgorithm.RSA)
        assertNotNull(c4Keys)
    }

    @Ignore("LiteCoreException{domain=1, code=22, msg=Can't parse certificate request data (PK - The pubkey tag or value is invalid (only RSA and EC are supported) : ASN1 - Data is invalid. ())")
    @Test
    fun testCreateCertificate() {
        val exp = Calendar.getInstance()
        exp.add(Calendar.YEAR, 3)
        val keys = keyManager.generateKeyPair(
            "foo",
            KeyManager.KeyAlgorithm.RSA,
            KeyManager.KeySize.BIT_2048,
            BigInteger.TEN,
            exp.time
        )
        assertNotNull(keys)

        val c4Keys = C4KeyPair.createKeyPair(keys!!, KeyManager.KeyAlgorithm.RSA)
        assertNotNull(c4Keys)

        val subjectName: Map<AbstractTLSIdentity.CertAttribute, String> = mapOf(
            AbstractTLSIdentity.CertAttribute.COMMON_NAME to "CouchbaseLite",
            AbstractTLSIdentity.CertAttribute.ORGANIZATION to "Couchbase",
            AbstractTLSIdentity.CertAttribute.ORGANIZATION_UNIT to "Mobile",
            AbstractTLSIdentity.CertAttribute.EMAIL_ADDRESS to "lite@couchbase.com"
        )

        c4Keys.generateSelfSignedCertificate(KeyManager.KeyAlgorithm.RSA, subjectName, KeyManager.CertUsage.TLS_SERVER)
    }
}
