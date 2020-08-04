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
import com.couchbase.lite.internal.core.C4KeyPair
import org.junit.After
import org.junit.AfterClass
import java.security.KeyStore
import java.util.Date


open class PlatformSecurityTest : SecurityBaseTest() {
    companion object {
        @JvmStatic
        @AfterClass
        fun tearDownKeyStoreBaseTest() {
            KeyStoreManager.getInstance().deleteEntries(null) { alias -> alias.startsWith(BASE_KEY_ALIAS) }
        }

        fun createIdentity(
            isServer: Boolean,
            attributes: Map<String, String>,
            expiration: Date?,
            alias: String
        ): TLSIdentity {
            return TLSIdentity.createIdentity(isServer, attributes, expiration, alias)
        }

        fun deleteIdentity(alias: String) = TLSIdentity.deleteIdentity(alias)
    }

    override fun loadPlatformKeyStore(): KeyStore {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        return keyStore
    }

    override fun loadTestKeys(dstKeyStore: KeyStore, dstAlias: String) {
        val extKeyStore = loadTestKeyStore()
        dstKeyStore.setEntry(
            dstAlias,
            extKeyStore.getEntry(EXTERNAL_KEY_ALIAS, KeyStore.PasswordProtection(EXTERNAL_KEY_PASSWORD.toCharArray())),
            null
        )
    }

    override fun getIdentity(alias: String): TLSIdentity? {
        val keyStore = loadPlatformKeyStore()
        loadTestKeys(keyStore, alias)
        return TLSIdentity.getIdentity(alias)
    }

    override fun getC4KeyPair(dstKeyStore: KeyStore, alias: String): C4KeyPair {
        return C4KeyPair.createKeyPair(alias, KeyStoreManager.KeyAlgorithm.RSA, KeyStoreManager.KeySize.BIT_2048)
    }

    override fun createSelfSignedCertEntry(alias: String, isServer: Boolean) {
        KeyStoreManager.getInstance()
            .createSelfSignedCertEntry(null, alias, null, isServer, SecurityBaseTest.X509_ATTRIBUTES, null)
    }

    @After
    fun cleanupPlatformSecurityTest() {
        KeyStoreManager.getInstance().deleteEntries(null) { alias -> alias.startsWith(BASE_KEY_ALIAS) }
    }
}

