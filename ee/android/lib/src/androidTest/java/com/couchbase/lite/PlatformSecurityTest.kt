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
package com.couchbase.lite

import com.couchbase.lite.internal.KeyStoreManager
import com.couchbase.lite.internal.SecurityBaseTest
import com.couchbase.lite.internal.core.C4KeyPair
import com.couchbase.lite.internal.utils.Fn
import java.security.KeyStore
import java.util.Date


open class PlatformSecurityTest : SecurityBaseTest() {
    companion object {
        private var defaultKeyStore: KeyStore? = null

        fun createIdentity(
            isServer: Boolean,
            attributes: Map<String, String>,
            expiration: Date?,
            alias: String
        ): TLSIdentity {
            return TLSIdentity.createIdentity(isServer, attributes, expiration, alias)
        }

        fun importTestEntry(srcAlias: String, dstAlias: String) {
            val externalStore = loadTestKeyStore()
            val internalStore = loadDefaultKeyStore()
            internalStore.setEntry(
                dstAlias,
                externalStore.getEntry(srcAlias, KeyStore.PasswordProtection(EXTERNAL_KEY_PASSWORD.toCharArray())),
                null
            )
        }

        fun getIdentity(alias: String) = TLSIdentity.getIdentity(alias)

        fun deleteIdentity(alias: String) = TLSIdentity.deleteIdentity(alias)

        fun deleteEntries(filter: Fn.Predicate<String>) =
            KeyStoreManager.getInstance().deleteEntries(null, filter);

        fun loadDefaultKeyStore(): KeyStore {
            if (defaultKeyStore == null) {
                val ks = KeyStore.getInstance("AndroidKeyStore")
                ks.load(null)
                defaultKeyStore = ks
            }
            return defaultKeyStore!!
        }
    }

    override fun loadPlatformKeyStore(): KeyStore = loadDefaultKeyStore()

    override fun loadTestKey(dstAlias: String) {
        val extKeyStore = loadTestKeyStore()
        val dstKeyStore = loadPlatformKeyStore()
        dstKeyStore.setEntry(
            dstAlias,
            extKeyStore.getEntry(
                EXTERNAL_KEY_ALIAS_TEST,
                KeyStore.PasswordProtection(EXTERNAL_KEY_PASSWORD.toCharArray())
            ),
            null
        )
    }

    override fun loadIdentity(alias: String): TLSIdentity? {
        loadTestKey(alias)
        return TLSIdentity.getIdentity(alias)
    }

    override fun createSelfSignedCertEntry(alias: String, isServer: Boolean) {
        KeyStoreManager.getInstance()
            .createSelfSignedCertEntry(
                null, alias, null, isServer,
                X509_ATTRIBUTES, null
            )
    }

    override fun createC4KeyPair(alias: String): C4KeyPair {
        return C4KeyPair.createKeyPair(
            alias,
            KeyStoreManager.KeyAlgorithm.RSA,
            KeyStoreManager.KeySize.BIT_2048
        )
    }

    override fun getIdentity(alias: String) = PlatformSecurityTest.getIdentity(alias)
}

