package com.couchbase.lite

import com.couchbase.lite.internal.utils.PlatformUtils
import org.junit.Assert
import org.junit.Test

class PlatformTLSIdentityTest : PlatformSecurityTest() {
    @Test
    fun testImportEntry() {
        val keyStore = loadPlatformKeyStore()

        val alias = newKeyAlias()
        Assert.assertNull(keyStore.getEntry(alias, null))

        val pwd = EXTERNAL_KEY_PASSWORD.toCharArray()

        PlatformUtils.getAsset(EXTERNAL_KEY_STORE)?.use {
            TLSIdentity.importIdentity(
                EXTERNAL_KEY_STORE_TYPE,
                it,
                pwd,
                EXTERNAL_KEY_ALIAS,
                null,
                alias
            )
        }

        Assert.assertNotNull(keyStore.getEntry(alias, null))
    }
}