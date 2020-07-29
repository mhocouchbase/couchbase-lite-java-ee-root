package com.couchbase.lite.internal.security

import com.couchbase.lite.BaseTest
import com.couchbase.lite.internal.SecurityBaseTest
import com.couchbase.lite.internal.security.Signature.SignatureDigestAlgorithm
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.util.Arrays

class SignatureTest : BaseTest() {
    @Test
    fun testSignHashData() {
        // Data:
        val data = "Test signing hash data".toByteArray(Charsets.UTF_8)

        // Generate KeyPair:
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        val keyPair = gen.generateKeyPair()

        // Create signature and verify signature for each SignatureDigestAlgorithm:
        val algorithms = SignatureDigestAlgorithm.values()
        for (algorithm in algorithms) {
            val digest = SecurityBaseTest.createDigest(algorithm, data);

            // Create Signature:
            val signatureData = Signature.signHashData(keyPair.private, digest, algorithm)

            // Verify Signature:
            val signature = java.security.Signature
                .getInstance(SecurityBaseTest.ALGORITHMS[algorithm]!!.signatureAlgorithm)
            signature.initVerify(keyPair.public)
            signature.update(data)

            assertTrue(signature.verify(signatureData))
        }
    }
}
