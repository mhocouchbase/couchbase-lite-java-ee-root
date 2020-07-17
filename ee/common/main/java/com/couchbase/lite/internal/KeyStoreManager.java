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
package com.couchbase.lite.internal;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;

import java.io.InputStream;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.couchbase.lite.CouchbaseLiteException;
import com.couchbase.lite.internal.utils.Fn;


public abstract class KeyStoreManager {
    public static final String ANON_IDENTITY_ALIAS = "CBL-ANON";

    public static final String ANON_COMMON_NAME = "CBLAnonymousCertificate";
    public static final int ANON_EXPIRATION_YEARS = 1;

    public static final String CERT_ATTRIBUTE_COMMON_NAME = "CN";

    public enum KeyAlgorithm {RSA}

    public enum KeySize {
        BIT_512(512), BIT_768(768), BIT_1024(1024), BIT_2048(2048), BIT_3072(3072), BIT_4096(4096);

        final int len;

        private static final Map<Integer, KeySize> KEY_SIZES;
        static {
            final Map<Integer, KeySize> m = new HashMap<>();
            for (KeySize keySize: KeySize.values()) { m.put(keySize.len, keySize); }
            KEY_SIZES = Collections.unmodifiableMap(m);
        }
        public static KeySize getKeySize(int bitLen) {
            final KeySize keySize = KEY_SIZES.get(bitLen);
            if (keySize == null) {
                throw new IllegalArgumentException("Unsupported key length: " + bitLen);
            }
            return keySize;
        }


        KeySize(int len) { this.len = len; }

        public int getBitLength() { return len; }
    }

    public enum CertUsage {
        UNSPECIFIED(0x00),        //< No specified usage (not generally useful)
        TLS_CLIENT(0x80),         //< TLS (SSL) client cert
        TLS_SERVER(0x40),         //< TLS (SSL) server cert
        EMAIL(0x20),              //< Email signing and encryption
        OBJECT_SIGNING(0x10),     //< Signing arbitrary data
        TLS_CA(0x04),             //< CA for signing TLS cert requests
        EMAIL_CA(0x02),           //< CA for signing email cert requests
        OBJECT_SIGNING_CA(0x01);  //< CA for signing object-signing cert requests

        final byte code;

        CertUsage(int code) { this.code = (byte) code; }

        public byte getCode() { return code; }
    }

    public enum SignatureDigestAlgorithm {NONE, SHA1, SHA224, SHA256, SHA384, SHA512, RIPEMD160}

    private static final AtomicReference<KeyStoreManager> INSTANCE = new AtomicReference<>();

    // PMD is just not very clever...
    @SuppressWarnings("PMD.SingletonClassReturningNewInstance")
    public static KeyStoreManager getInstance() {
        final KeyStoreManager instance = INSTANCE.get();
        if (instance != null) { return instance; }

        INSTANCE.compareAndSet(null, new KeyStoreManagerDelegate());
        return INSTANCE.get();
    }

    @VisibleForTesting
    public static void setInstance(KeyStoreManager mgr) { INSTANCE.set(mgr); }


    //-------------------------------------------------------------------------
    // Manager Methods
    //-------------------------------------------------------------------------

    /**
     * Provides the _public_ key's raw data, as an ASN.1 DER sequence of [modulus, exponent].
     *
     * @param keyStore    The key store containing the needed key.
     * @param keyAlias    The alias for the needed key.
     * @param keyPassword The password for the needed key.
     * @return the raw key data or null failure.
     */
    @Nullable
    public abstract byte[] getKeyData(
        @Nullable KeyStore keyStore,
        @NonNull String keyAlias,
        @Nullable char[] keyPassword);

    /**
     * Decrypts data using the private key.
     *
     * @param keyStore    The key store containing the needed key.
     * @param keyAlias    The alias for the needed key.
     * @param keyPassword The password for the needed key.
     * @param data        The data to be encrypted.
     * @return the raw key data or null failure.
     */
    @Nullable
    public abstract byte[] decrypt(
        @Nullable KeyStore keyStore,
        @NonNull String keyAlias,
        @Nullable char[] keyPassword,
        @NonNull byte[] data);

    /**
     * Uses the private key to generate a signature of input data.
     *
     * @param keyStore        The key store containing the needed key.
     * @param keyAlias        The alias for the needed key.
     * @param keyPassword     The password for the needed key.
     * @param digestAlgorithm Indicates what type of digest to create the signature from.
     * @param data            The data to be signed.
     * @return the signature (length must be equal to the key size) or null on failure.
     */
    @Nullable
    public abstract byte[] signKey(
        @Nullable KeyStore keyStore,
        @NonNull String keyAlias,
        @Nullable char[] keyPassword,
        @NonNull SignatureDigestAlgorithm digestAlgorithm,
        @NonNull byte[] data);

    /**
     * Called when the C4KeyPair is released and the externalKey is no longer needed
     * and when associated resources may be freed
     *
     * @param keyStore    The key store containing the needed key.
     * @param keyAlias    The alias for the needed key.
     * @param keyPassword The password for the needed key.
     */
    public abstract void free(
        @Nullable KeyStore keyStore,
        @NonNull String keyAlias,
        @Nullable char[] keyPassword);

    public abstract void createCertEntry(
        @NonNull String alias,
        boolean isServer,
        @NonNull Map<String, String> attributes,
        @NonNull Date expiration)
        throws CouchbaseLiteException;

    public abstract void createAnonymousCertEntry(@NonNull String alias, boolean isServer)
        throws CouchbaseLiteException;

    public abstract void importEntry(
        @NonNull String type,
        @NonNull InputStream stream,
        @Nullable char[] storePassword,
        @NonNull String alias,
        @Nullable char[] keyPassword,
        @NonNull String targetAlias)
        throws CouchbaseLiteException;

    public abstract boolean findAlias(@NonNull String keyAlias) throws CouchbaseLiteException;

    @Nullable
    public abstract Certificate getCertificate(
        @Nullable KeyStore keyStore,
        @NonNull String keyAlias,
        @Nullable char[] keyPassword)
        throws CouchbaseLiteException;

    @VisibleForTesting
    public abstract int deleteEntries(Fn.Predicate<String> filter) throws CouchbaseLiteException;

    @VisibleForTesting
    @Nullable
    public abstract KeyPair generateRSAKeyPair(
        @NonNull String alias,
        boolean isServer,
        @NonNull KeySize keySize,
        @NonNull Map<String, String> attributes,
        @NonNull Date expiration)
        throws CouchbaseLiteException;
}
