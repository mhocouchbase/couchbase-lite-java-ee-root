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
import java.security.KeyPair
import java.security.KeyStore
import java.security.PrivateKey
import java.util.Date

/*
 * The following methods provide internal API that uses the internal KeyStore. These APIs are used for
 * 1. Storing the anonymous identity created by the URLEndpointListener. For java, the anonymous identity
 * will be not be persisted.
 * 2. Developing tests that can be shared between CBL Android and Java as these APIs are the same API provided
 * by the CBL Android's TLSIdentity.
 */
open class PlatformSecurityTest : SecurityBaseTest() {
    companion object {
        private var defaultKeyStore: KeyStore? = null

        fun createIdentity(
            isServer: Boolean,
            attributes: Map<String, String>,
            expiration: Date?,
            alias: String
        ): TLSIdentity {
            return TLSIdentity.createIdentity(
                isServer,
                attributes,
                expiration,
                loadDefaultKeyStore(),
                alias,
                EXTERNAL_KEY_PASSWORD.toCharArray()
            )
        }

        fun deleteIdentity(alias: String) = TLSIdentity.deleteIdentity(loadDefaultKeyStore(), alias)

        fun deleteEntries(filter: Fn.Predicate<String>) =
            KeyStoreManager.getInstance().deleteEntries(loadDefaultKeyStore(), filter)

        fun loadDefaultKeyStore(): KeyStore {
            if (defaultKeyStore == null) {
                val ks = KeyStore.getInstance(KeyStore.getDefaultType())
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
        val protection = KeyStore.PasswordProtection(EXTERNAL_KEY_PASSWORD.toCharArray())
        dstKeyStore.setEntry(
            dstAlias,
            extKeyStore.getEntry(EXTERNAL_KEY_ALIAS, protection),
            protection
        )
    }

    override fun loadIdentity(alias: String): TLSIdentity? {
        val keyStore = loadPlatformKeyStore()
        return TLSIdentity.getIdentity(keyStore, alias, EXTERNAL_KEY_PASSWORD.toCharArray())
    }

    override fun createSelfSignedCertEntry(alias: String, isServer: Boolean) {
        KeyStoreManager.getInstance().createSelfSignedCertEntry(
            loadPlatformKeyStore(),
            alias,
            null,
            isServer,
            X509_ATTRIBUTES,
            null
        )
    }

    override fun createC4KeyPair(alias: String): C4KeyPair {
        val keyStore = loadPlatformKeyStore()

        val keyPair = KeyPair(
            keyStore.getCertificate(alias).publicKey,
            keyStore.getKey(alias, EXTERNAL_KEY_PASSWORD.toCharArray()) as PrivateKey
        )

        return C4KeyPair.createKeyPair(
            keyStore,
            alias,
            null,
            KeyStoreManager.KeyAlgorithm.RSA,
            KeyStoreManager.KeySize.BIT_2048,
            keyPair
        )
    }

    override fun getIdentity(alias: String) =
        TLSIdentity.getIdentity(loadPlatformKeyStore(), alias, EXTERNAL_KEY_PASSWORD.toCharArray())
}

