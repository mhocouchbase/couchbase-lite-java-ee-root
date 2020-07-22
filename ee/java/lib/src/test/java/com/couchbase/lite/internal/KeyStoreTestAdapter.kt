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
import org.junit.AfterClass
import java.io.InputStream
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
open class KeyStoreTestAdaptor : KeyStoreBaseTest() {
    companion object {
        var keyStore: KeyStore? = null

        @JvmStatic
        @AfterClass
        fun tearDownKeyStoreBaseTest() {
            KeyStoreManager.getInstance().deleteEntries(loadDefaultKeyStore()) { alias ->
                alias.startsWith(BASE_KEY_ALIAS)
            }
        }

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
                EXTERNAL_KEY_PASSWORD.toCharArray())
        }

        fun importIdentity(
            extType: String,
            extStore: InputStream,
            extStorePass: CharArray,
            alias: String,
            keyPass: CharArray
        ): TLSIdentity {
            val exKeyStore = KeyStore.getInstance(extType)
            exKeyStore.load(extStore, extStorePass)

            val protection = KeyStore.PasswordProtection(keyPass)
            val entry = exKeyStore.getEntry(alias, protection)

            val keyStore = loadDefaultKeyStore()
            keyStore.setEntry(alias, entry, protection)
            return TLSIdentity.getIdentity(keyStore, alias, keyPass)!!
        }

        fun deleteIdentity(alias: String) = loadDefaultKeyStore().deleteEntry(alias)

        fun loadDefaultKeyStore(): KeyStore {
            if (keyStore == null) {
                keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
                keyStore!!.load(null)
            }
            return keyStore!!
        }
    }

    override fun loadPlatformKeyStore(): KeyStore = loadDefaultKeyStore()

    override fun loadTestKeys(dstKeyStore: KeyStore, dstAlias: String) {
        loadTestKeys(dstKeyStore, dstAlias, EXTERNAL_KEY_PASSWORD)
    }

    override fun getIdentity(alias: String): TLSIdentity? {
        val keyStore = loadPlatformKeyStore()
        return TLSIdentity.getIdentity(keyStore, alias, EXTERNAL_KEY_PASSWORD.toCharArray())
    }

    override fun importIdentity(
        extType: String,
        extStore: InputStream,
        extStorePass: CharArray,
        extAlias: String,
        extKeyPass: CharArray,
        alias: String
    ): TLSIdentity? {
        TODO("Not yet implemented")
    }

    override fun createSelfSignedCertEntry(alias: String, isServer: Boolean) {
        KeyStoreManager.getInstance().createSelfSignedCertEntry(
            loadPlatformKeyStore(),
            alias,
            null,
            isServer,
            get509Attributes(),
            null
        )
    }

    override fun getC4KeyPair(dstKeyStore: KeyStore, alias: String): C4KeyPair {
        val keyPair = KeyPair(
            keyStore!!.getCertificate(alias).publicKey,
            keyStore!!.getKey(alias, EXTERNAL_KEY_PASSWORD.toCharArray()) as PrivateKey
        );

        return C4KeyPair.createKeyPair(
            keyStore,
            alias,
            null,
            KeyStoreManager.KeyAlgorithm.RSA,
            KeyStoreManager.KeySize.BIT_2048,
            keyPair
        )
    }
}

