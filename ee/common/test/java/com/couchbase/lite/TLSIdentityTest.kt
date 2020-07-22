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

import com.couchbase.lite.internal.EXTERNAL_KEY_ALIAS
import com.couchbase.lite.internal.EXTERNAL_KEY_PASSWORD
import com.couchbase.lite.internal.EXTERNAL_KEY_STORE
import com.couchbase.lite.internal.EXTERNAL_KEY_STORE_TYPE
import com.couchbase.lite.internal.KeyStoreTestAdaptor
import com.couchbase.lite.internal.utils.PlatformUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Ignore
import org.junit.Test
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.Date


class TLSIdentityTest : KeyStoreTestAdaptor() {
    @Ignore("Cant't work on Java")
    @Test
    fun testImportIdentity() {
        val pwd = EXTERNAL_KEY_PASSWORD.toCharArray()
        PlatformUtils.getAsset(EXTERNAL_KEY_STORE)?.use {
            assertNotNull(importIdentity(EXTERNAL_KEY_STORE_TYPE, it, pwd, EXTERNAL_KEY_ALIAS, pwd))
        }
    }

    @Test
    fun testCreateIdentity() {
        val alias = newKeyAlias()

        val attributes = get509Attributes()

        val expiration = Calendar.getInstance()
        expiration.add(Calendar.YEAR, 3)

        assertNotNull(createIdentity(true, attributes, expiration.time, alias))
    }

    @Ignore("Failed on Android")
    @Test
    fun testCreateAndGetServerIdentity() {
        val alias = newKeyAlias()

        // Get:
        var identity = getIdentity(alias)
        assertNull(identity)

        // Create:
        val attrs = get509Attributes()
        identity = createIdentity(true, attrs, null, alias)
        assertNotNull(identity)
        validateCertificate(identity!!, true)

        // Get:
        identity = getIdentity(alias)
        assertNotNull(identity)
        validateCertificate(identity!!, true)

        // Delete:
        deleteIdentity(alias)

        // Get:
        identity = getIdentity(alias)
        assertNull(identity)
    }

    @Ignore("Failed on Android")
    @Test
    fun testCreateAndGetClientIdentity() {
        val alias = newKeyAlias()

        // Get:
        var identity = getIdentity(alias)
        assertNull(identity)

        // Create:
        val attrs = get509Attributes()
        identity = createIdentity(false, attrs, null, alias)
        assertNotNull(identity)
        validateCertificate(identity!!, false)

        // Get:
        identity = getIdentity(alias)
        assertNotNull(identity)
        validateCertificate(identity!!, false)

        // Delete:
        deleteIdentity(alias)

        // Get:
        identity = getIdentity(alias)
        assertNull(identity)
    }

    @Ignore("Failed on Android")
    @Test(expected = CouchbaseLiteException::class)
    fun testCreateDuplicateIdentity() {
        val alias = newKeyAlias()

        var identity: TLSIdentity?

        // Create:
        val attrs = get509Attributes()
        identity = createIdentity(true, attrs, null, alias)
        assertNotNull(identity)
        validateCertificate(identity!!, true)

        // Get:
        identity = getIdentity(alias)
        assertNotNull(identity)
        validateCertificate(identity!!, true)

        // Create again with the same alias:
        createIdentity(true, attrs, null, alias)
    }

    @Test(expected = CouchbaseLiteException::class)
    fun testCreateIdentityWithNoAttributes() {
        createIdentity(true, emptyMap(), null, newKeyAlias())
    }

    @Ignore("Failed on Android")
    @Test
    fun testCertificateExpiration() {
        val alias = newKeyAlias()

        // Get:
        var identity = getIdentity(alias)
        assertNull(identity)

        val attrs = get509Attributes()
        val expiration = Date(System.currentTimeMillis() + 60000)

        identity = createIdentity(true, attrs, expiration, alias)
        assertNotNull(identity)
        assertEquals(1, identity!!.certs.count())
        validateCertificate(identity, true)

        // The actual expiration will be slightly less than the set expiration time due to expiration date
        // to time conversion and time spent in key generation.
        assert(Math.abs(expiration.time - identity.expiration.time) < 2000)
    }

    fun validateCertificate(identity: TLSIdentity, isServer: Boolean) {
        // Check validity:
        assert(identity.certs.size > 0)
        val cert = identity.certs[0] as X509Certificate
        cert.checkValidity()

        // Check if the certificate is used as digital signature (client cert):
        var keyUsage = cert.keyUsage
        assert(keyUsage.size > 0)
        if (isServer) {
            assert(!keyUsage[0])
        } else {
            assert(keyUsage[0])
        }
    }
}
