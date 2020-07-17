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

import com.couchbase.lite.LogLevel
import com.couchbase.lite.PlatformBaseTest
import com.couchbase.lite.TLSIdentity
import com.couchbase.lite.internal.core.C4KeyPair
import com.couchbase.lite.internal.utils.PlatformUtils
import com.couchbase.lite.internal.utils.Report
import com.couchbase.lite.internal.utils.StringUtils
import java.io.InputStream
import java.security.KeyStore


const val EXTERNAL_KEY_STORE = "teststore.p12"
const val EXTERNAL_KEY_STORE_TYPE = "PKCS12"
const val EXTERNAL_KEY_ALIAS = "couchbase"
const val EXTERNAL_KEY_PASSWORD = "password"

const val BASE_KEY_ALIAS = "test-alias"

abstract class KeyStoreBaseTest : PlatformBaseTest() {
    companion object {
        fun newKeyAlias() = StringUtils.getUniqueName(BASE_KEY_ALIAS, 8).toLowerCase()

        fun get509Attributes() = mapOf(
            KeyStoreManager.CERT_ATTRIBUTE_COMMON_NAME to "CBL Test",
            KeyStoreManager.CERT_ATTRIBUTE_ORGANIZATION to "Couchbase",
            KeyStoreManager.CERT_ATTRIBUTE_ORGANIZATION_UNIT to "Mobile",
            KeyStoreManager.CERT_ATTRIBUTE_EMAIL_ADDRESS to "lite@couchbase.com"
        )
    }

    abstract fun loadPlatformKeyStore(): KeyStore
    abstract fun loadTestKeys(dstKeyStore: KeyStore, dstAlias: String)
    abstract fun getC4KeyPair(dstKeyStore: KeyStore, alias: String): C4KeyPair
    abstract fun getIdentity(alias: String): TLSIdentity?
    abstract fun importIdentity(
        extType: String,
        extStore: InputStream,
        extStorePass: CharArray,
        alias: String,
        keyPass: CharArray
    ): TLSIdentity?

    abstract fun createSelfSignedCertEntry(alias: String, isServer: Boolean)

    fun loadTestKeyStore(): KeyStore {
        val externalStore = KeyStore.getInstance(EXTERNAL_KEY_STORE_TYPE)
        PlatformUtils.getAsset(EXTERNAL_KEY_STORE).use { `in` ->
            externalStore.load(`in`, EXTERNAL_KEY_PASSWORD.toCharArray())
        }
        return externalStore
    }

    fun loadTestKeys(dstKeyStore: KeyStore, dstAlias: String, dstPwd: String?) {
        val extKeyStore = loadTestKeyStore()
        dstKeyStore.setEntry(
            dstAlias,
            extKeyStore.getEntry(EXTERNAL_KEY_ALIAS, KeyStore.PasswordProtection(EXTERNAL_KEY_PASSWORD.toCharArray())),
            if (dstPwd == null) null else KeyStore.PasswordProtection(dstPwd.toCharArray())
        )
    }

    fun dumpKeystore() {
        val keystore = loadPlatformKeyStore()
        val aliases = keystore.aliases()
        while (aliases.hasMoreElements()) {
            val alias = aliases.nextElement()
            val entry = keystore.getEntry(alias, null)
            Report.log(LogLevel.INFO, "============\n@${alias}:\n${entry}")
        }
    }

    fun dumpKeystoreEntry(alias: String) {
        val keystore = loadPlatformKeyStore()
        Report.log(LogLevel.INFO, "PRIVATE: ${keystore.getKey(alias, null)}")
        val cert = keystore.getCertificate(alias)
        Report.log(LogLevel.INFO, "CERTIFICATE: ${cert}")
        Report.log(LogLevel.INFO, "PUBLIC: ${cert.publicKey}")
        val certChain = keystore.getCertificateChain(alias)
        Report.log(LogLevel.INFO, "CHAIN:")
        val n = 0
        for (c in certChain) {
            Report.log(LogLevel.INFO, "   @${n}: ${c}")
        }
    }
}