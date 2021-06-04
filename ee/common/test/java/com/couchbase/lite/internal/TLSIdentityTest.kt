//
//  Copyright (c) 2020 Couchbase, Inc. All rights reserved.
//
//  Licensed under the Couchbase License Agreement (the "License");
//  you may not use this file except in compliance with the License.
//  You may obtain a copy of the License at
//  https://info.couchbase.com/rs/302-GJY-034/images/2017-10-30_License_Agreement.pdf
//
//  Unless required by applicable law or agreed to in writing, software
//  distributed under the License is distributed on an "AS IS" BASIS,
//  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//  See the License for the specific language governing permissions and
//  limitations under the License.
//
package com.couchbase.lite.internal

import com.couchbase.lite.CouchbaseLiteException
import com.couchbase.lite.PlatformSecurityTest
import com.couchbase.lite.TLSIdentity
import org.junit.Assert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.GregorianCalendar
import kotlin.math.abs


class TLSIdentityTest : PlatformSecurityTest() {
    @Test
    fun testGetIdentity() {
        val alias = newKeyAlias()
        loadTestKey(alias)

        val identity = getIdentity(alias)
        assertNotNull(identity)
        validateCertificate(identity!!)
    }

    @Test
    fun testCreateClientIdentity() {
        val alias = newKeyAlias()

        val identity = createIdentity(false, X509_ATTRIBUTES, null, alias)
        assertNotNull(identity)
        validateCertificate(identity)

        val abstractId = identity as BaseTLSIdentity

        assertEquals(abstractId.alias, abstractId.alias)
    }

    @Test
    fun testCreateServerIdentity() {
        val alias = newKeyAlias()

        val identity = createIdentity(true, X509_ATTRIBUTES, null, alias)
        assertNotNull(identity)
        validateCertificate(identity)

        val abstractId = identity as BaseTLSIdentity

        assertEquals(abstractId.alias, abstractId.alias)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testCreateIdentityWithNoAttributes() {
        createIdentity(true, emptyMap(), null, newKeyAlias())
    }

    @Test
    fun testCreateIdentityWithMinimalAttributes() {
        val alias = newKeyAlias()

        val attrs = mapOf(TLSIdentity.CERT_ATTRIBUTE_COMMON_NAME to "CBL Test")

        val identity = createIdentity(true, attrs, null, alias)
        assertNotNull(identity)
        validateCertificate(identity)

        val abstractId = identity as BaseTLSIdentity
        assertEquals(abstractId.alias, abstractId.alias)

        val cert = abstractId.cert as X509Certificate
        assertEquals(
            "${TLSIdentity.CERT_ATTRIBUTE_COMMON_NAME}=${attrs[TLSIdentity.CERT_ATTRIBUTE_COMMON_NAME]}",
            cert.issuerDN.name
        )
    }

    @Test
    fun testCreateIdentityWithReasonableExpiration() {
        val expiration = Calendar.getInstance()
        expiration.add(Calendar.YEAR, 3)

        assertNotNull(createIdentity(true, X509_ATTRIBUTES, expiration.time, newKeyAlias()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun testCreateIdentityWithOldExpiration() {
        createIdentity(true, X509_ATTRIBUTES, GregorianCalendar(1900, 1, 1).time, newKeyAlias())
    }

    @Test(expected = CouchbaseLiteException::class)
    fun testCreateDuplicateClientIdentity() {
        val alias = newKeyAlias()

        val identity: TLSIdentity? = createIdentity(false, X509_ATTRIBUTES, null, alias)
        assertNotNull(identity)
        validateCertificate(identity!!)

        // Create again with the same alias: should fail
        createIdentity(true, X509_ATTRIBUTES, null, alias)
    }

    @Test(expected = CouchbaseLiteException::class)
    fun testCreateDuplicateServerIdentity() {
        val alias = newKeyAlias()

        val identity: TLSIdentity? = createIdentity(true, X509_ATTRIBUTES, null, alias)
        assertNotNull(identity)
        validateCertificate(identity!!)

        // Create again with the same alias: should fail
        createIdentity(true, X509_ATTRIBUTES, null, alias)
    }

    @Test
    fun testCertificateExpiration() {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.YEAR, 1)
        val expiration = calendar.time

        val identity = createIdentity(
            true,
            X509_ATTRIBUTES,
            expiration,
            newKeyAlias()
        )
        assertNotNull(identity)
        assertEquals(1, identity.certs.count())
        validateCertificate(identity)

        println("$expiration == ${identity.expiration}")

        assert(abs(expiration.time - identity.expiration.time) < (2 * KeyStoreManager.CLOCK_DRIFT_MS))
    }

    private fun validateCertificate(identity: TLSIdentity) {
        Assert.assertTrue(identity.certs.size > 0)
        val cert = identity.certs[0] as X509Certificate
        cert.checkValidity()
    }
}
