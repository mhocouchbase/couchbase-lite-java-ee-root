package com.couchbase.lite.internal.security

import com.couchbase.lite.BaseTest
import com.couchbase.lite.internal.security.Signature.SignatureDigestAlgorithm
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.MessageDigest

class SignatureTest : BaseTest() {
    data class AlgorithmName(val signatureAlgorithm: String, val digestAlgorithm: String)

    companion object {
        val ALGORITHM_NAME_MAP = mapOf(
            SignatureDigestAlgorithm.NONE to AlgorithmName("NONEwithRSA", ""),
            SignatureDigestAlgorithm.SHA1 to AlgorithmName("SHA1withRSA", "SHA-1"),
            SignatureDigestAlgorithm.SHA224 to AlgorithmName("SHA224withRSA", "SHA-224"),
            SignatureDigestAlgorithm.SHA256 to AlgorithmName("SHA256withRSA", "SHA-256"),
            SignatureDigestAlgorithm.SHA384 to AlgorithmName("SHA384withRSA", "SHA-384"),
            SignatureDigestAlgorithm.SHA512 to AlgorithmName("SHA512withRSA", "SHA-512")
        )
    }

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
            val algName = ALGORITHM_NAME_MAP[algorithm]!!

            // Digest Data:
            val digestData: ByteArray
            if (algorithm == SignatureDigestAlgorithm.NONE) {
                digestData = data
            } else {
                val md = MessageDigest.getInstance(algName.digestAlgorithm)
                md.update(data)
                digestData = md.digest()
            }

            // Create Signature:
            val signatureData = Signature.signHashData(keyPair.private, digestData, algorithm)

            // Verify Signature:
            val signature = java.security.Signature.getInstance(algName.signatureAlgorithm)
            signature.initVerify(keyPair.public)
            signature.update(data)

            assertTrue(signature.verify(signatureData))
        }
    }
}
