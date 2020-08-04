package com.couchbase.lite.internal

import com.couchbase.lite.LogLevel
import com.couchbase.lite.TLSIdentity
import com.couchbase.lite.internal.utils.PlatformUtils
import com.couchbase.lite.internal.utils.Report
import org.junit.After
import org.junit.Assert
import org.junit.Test

class PlatformTLSIdentityTest : PlatformSecurityTest() {
    @Test
    fun testImportEntry() {
        val keyStore = loadPlatformKeyStore()

        val alias = newKeyAlias()
        Assert.assertNull(keyStore.getEntry(alias, null))

        PlatformUtils.getAsset(EXTERNAL_KEY_STORE)?.use {
            KeyStoreManager.getInstance().importEntry(
                EXTERNAL_KEY_STORE_TYPE,
                it,
                EXTERNAL_KEY_PASSWORD.toCharArray(),
                EXTERNAL_KEY_ALIAS,
                null,
                alias
            )
        }

        Assert.assertNotNull(keyStore.getEntry(alias, null))
    }

    @Test
    fun testImportIdentity() {
        val alias = newKeyAlias()
        val pwd = EXTERNAL_KEY_PASSWORD.toCharArray()
        PlatformUtils.getAsset(EXTERNAL_KEY_STORE)?.use {
            Assert.assertNotNull(
                TLSIdentity.importIdentity(EXTERNAL_KEY_STORE_TYPE, it, pwd, EXTERNAL_KEY_ALIAS, pwd, alias)
            )
        }
    }
}