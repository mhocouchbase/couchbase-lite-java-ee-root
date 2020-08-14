//
// Copyright (c) 2020, 2018 Couchbase, Inc.  All rights reserved.
//
// Licensed under the Couchbase License Agreement (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
// https://info.couchbase.com/rs/302-GJY-034/images/2017-10-30_License_Agreement.pdf
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
package com.couchbase.lite.internal.security;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SignatureException;


/**
 * Signature for signing hash data.
 */
public final class Signature {
    private static final byte DER_TAG_OCTET_STRING = 0x04;
    private static final byte DER_TAG_SEQUENCE = 0x30;
    private static final String SIGNING_ALGORITHM = "NONEwithRSA";

    public enum SignatureDigestAlgorithm {
        NONE(new byte[0]),
        SHA1(new byte[] {0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00}),
        SHA224(new byte[] {
            0x30, 0x0d, 0x06, 0x09, 0x60, (byte) 0x86, 0x48, 0x01, 0x65, 0x03, 0x04, 0x02, 0x04, 0x05, 0x00}),
        SHA256(new byte[] {
            0x30, 0x0d, 0x06, 0x09, 0x60, (byte) 0x86, 0x48, 0x01, 0x65, 0x03, 0x04, 0x02, 0x01, 0x05, 0x00}),
        SHA384(new byte[] {
            0x30, 0x0d, 0x06, 0x09, 0x60, (byte) 0x86, 0x48, 0x01, 0x65, 0x03, 0x04, 0x02, 0x02, 0x05, 0x00}),
        SHA512(new byte[] {
            0x30, 0x0d, 0x06, 0x09, 0x60, (byte) 0x86, 0x48, 0x01, 0x65, 0x03, 0x04, 0x02, 0x03, 0x05, 0x00});

        @NonNull
        private final byte[] encodedDigestAlgorithmOid;

        @SuppressWarnings("PMD.ArrayIsStoredDirectly")
        SignatureDigestAlgorithm(@NonNull byte[] encodedDigestAlgorithmOid) {
            this.encodedDigestAlgorithmOid = encodedDigestAlgorithmOid;
        }

        @NonNull
        private byte[] getEncodedDigestAlgorithmOid() { return encodedDigestAlgorithmOid; }
    }

    /**
     * Private Constructor
     */
    private Signature() { }


    /**
     * Signs hash data. The method will doing as follows:
     * 1. Encode the object identifier of the digest algorithm and the hash data
     * in DER format if the SignatureDigestAlgorithm is not NONE.
     * 2. Signed the encoded data with "NONEwithRSA" algorithm.
     */
    @NonNull
    public static byte[] signHashData(
        @NonNull PrivateKey key,
        @NonNull byte[] hashData,
        @NonNull SignatureDigestAlgorithm algorithm)
        throws IOException, SignatureException, NoSuchAlgorithmException, InvalidKeyException {
        // Encode digest algorithm OID and hash data in DER format as needed:
        final byte[] data = encode(hashData, algorithm);

        // Sign without hashing by using NONEwithRSA algorithm:
        final java.security.Signature sig = java.security.Signature.getInstance(SIGNING_ALGORITHM);
        sig.initSign(key);
        sig.update(data);

        return sig.sign();
    }

    /**
     * Format:
     * DigestInfo ::= SEQUENCE {
     * digestAlgorithm AlgorithmIdentifier,
     * digest OCTET STRING
     * }
     * <p>
     * Code Reference using private API:
     * DerOutputStream out = new DerOutputStream();
     * new AlgorithmId(SHA256_oid).encode(out);
     * out.putOctetString(data);
     * byte[] derValue = out.toByteArray();
     * DerValue der = new DerValue(tag_Sequence, derValue);
     * data = der.toByteArray();
     */
    @NonNull
    private static byte[] encode(
        @NonNull byte[] hashData,
        @NonNull SignatureDigestAlgorithm algorithm) throws IOException {
        // NONE - no Hash, no encode :
        if (algorithm == SignatureDigestAlgorithm.NONE) { return hashData; }

        // Encode oid and hash data:
        final ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        encoded.write(algorithm.getEncodedDigestAlgorithmOid());
        encodeDerValue(encoded, DER_TAG_OCTET_STRING, hashData);

        // Put the encoded data under the sequence tag:
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        encodeSequence(out, encoded.toByteArray());

        return out.toByteArray();
    }

    private static void encodeSequence(@NonNull ByteArrayOutputStream out, @NonNull byte[] data) throws IOException {
        encodeDerValue(out, DER_TAG_SEQUENCE, data);
    }

    private static void encodeDerValue(@NonNull ByteArrayOutputStream out, byte tag, @Nullable byte[] bytes)
        throws IOException {
        out.write(tag);

        if (bytes == null) {
            out.write(0x00);
            return;
        }

        encodeLength(out, bytes.length);
        out.write(bytes);
    }


    /**
     * Encode byte length as follows:
     * 1. Short form (< 128): One byte - Bit 8 has value = 0 and bits 7-1 are the length.
     * 2. Long form (>= 128): Two to 127 bytes - Bit 8 of first byte has value = 1 and
     * bits 7-1 are the number of required bytes for keeping the length in base 256.
     * The following bytes are the length in base 256, most significant digit first.
     */
    private static void encodeLength(@NonNull ByteArrayOutputStream out, int length) {
        if (length < 128) {
            out.write((byte) length);
            return;
        }

        int size = 1;
        int val = length;
        while ((val >>>= 8) != 0) { size++; }

        out.write((byte) (size | 0x80));

        for (int i = ((size - 1) * 8); i >= 0; i -= 8) { out.write((byte) (length >> i)); }
    }
}
