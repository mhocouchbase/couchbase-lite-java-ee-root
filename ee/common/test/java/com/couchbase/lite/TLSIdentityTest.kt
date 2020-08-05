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
package com.couchbase.lite

import org.junit.Assert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.GregorianCalendar
import kotlin.math.abs


class TLSIdentityTest : PlatformSecurityTest() {
    // 2 x KeyStoreManager.CLOCK_DRIFT_MS
    private val clockSlop = 2 * 60 * 1000

    @Test
    fun testGetIdentity() {
        val alias = newKeyAlias()
        loadTestKey(alias)

        val identity = getIdentity(alias)
        assertNotNull(identity)
        validateCertificate(identity!!)
    }

    @Test
    fun testCreateIdentity() {
        val identity = createIdentity(true, X509_ATTRIBUTES, null, newKeyAlias())
        assertNotNull(identity)
        validateCertificate(identity)
    }

    @Test
    fun testCreateIdentityWithMinimalAttributes() {
        val identity = createIdentity(
            true,
            mapOf(TLSIdentity.CERT_ATTRIBUTE_COMMON_NAME to "CBL Test"),
            null,
            newKeyAlias()
        )
        assertNotNull(identity)
        validateCertificate(identity)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testCreateIdentityWithEmptyAttributes() {
        assertNotNull(
            createIdentity(
                true,
                mapOf(),
                null,
                newKeyAlias()
            )
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
        assertNotNull(createIdentity(true, X509_ATTRIBUTES, GregorianCalendar(1900, 1, 1).time, newKeyAlias()))
    }

    @Test(expected = CouchbaseLiteException::class)
    fun testCreateDuplicateIdentity() {
        val alias = newKeyAlias()

        val identity: TLSIdentity? = createIdentity(true, X509_ATTRIBUTES, null, alias)
        assertNotNull(identity)
        validateCertificate(identity!!)

        // Create again with the same alias:
        createIdentity(true, X509_ATTRIBUTES, null, alias)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testCreateIdentityWithNoAttributes() {
        createIdentity(true, emptyMap(), null, newKeyAlias())
    }

    @Test
    fun testCertificateExpiration() {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.YEAR, 1)
        val expiration = calendar.time

        val identity = createIdentity(true, X509_ATTRIBUTES, expiration, newKeyAlias())
        assertNotNull(identity)
        assertEquals(1, identity.certs.count())
        validateCertificate(identity)

        println("$expiration == ${identity.expiration}")

        assert(abs(expiration.time - identity.expiration.time) < clockSlop)
    }

    private fun validateCertificate(identity: TLSIdentity) {
        Assert.assertTrue(identity.certs.size > 0)
        val cert = identity.certs[0] as X509Certificate
        cert.checkValidity()
    }
}
