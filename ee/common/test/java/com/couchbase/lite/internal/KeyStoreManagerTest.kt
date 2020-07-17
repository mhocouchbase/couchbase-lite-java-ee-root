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
import com.couchbase.lite.internal.utils.PlatformUtils

import org.junit.Test
import java.security.KeyStore
import java.util.Calendar

class KeyStoreManagerTest : PlatformBaseTest() {
    @Test
    fun testGetKeyData() {
        val pwd = "password".toCharArray()
        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(PlatformUtils.getAsset("teststore.p12"), pwd)

        val alias = keyStore.aliases().nextElement();
        var keyPair = C4KeyPair.createKeyPair(
            keyStore, alias,
            null,
            KeyStoreManager.KeyAlgorithm.RSA,
            KeyStoreManager.KeySize.BIT_2048);
        KeyStoreManager.getInstance().getKeyData(keyPair);
    }

    @Test
    fun testGenerateSelfSignedCertificate() {
        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(null);

        val attrs = mapOf(KeyStoreManager.CERT_ATTRIBUTE_COMMON_NAME to "Couchbase")
        val exp = Calendar.getInstance()
        exp.add(Calendar.YEAR, 3)

        KeyStoreManager.getInstance().createSelfSignedCertEntry(
            keyStore,
            "MyCert",
            null,
            true,
            attrs,
            exp.time)
    }
}
