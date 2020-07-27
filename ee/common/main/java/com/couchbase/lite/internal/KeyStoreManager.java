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
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.UnrecoverableEntryException;
import java.security.cert.Certificate;
import java.security.interfaces.RSAKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.couchbase.lite.CouchbaseLiteException;
import com.couchbase.lite.LogDomain;
import com.couchbase.lite.internal.core.C4KeyPair;
import com.couchbase.lite.internal.security.Signature;
import com.couchbase.lite.internal.support.Log;
import com.couchbase.lite.internal.utils.Fn;


public abstract class KeyStoreManager {
    public static final String ANON_IDENTITY_ALIAS = "CBL-ANON-";

    public static final String ANON_COMMON_NAME = "CBLAnonymousCertificate";
    public static final int ANON_EXPIRATION_YEARS = 1;

    protected static final String ERROR_LOADING_KEYSTORE = "Failed loading keystore";

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
    // Native Callbacks
    //-------------------------------------------------------------------------

    /**
     * Provides the _public_ key's raw data, as an ASN.1 DER sequence of [modulus, exponent].
     *
     * @param keyPair The key pair
     * @return the raw key data or null failure.
     */
    @Nullable
    public abstract byte[] getKeyData(@NonNull C4KeyPair keyPair);

    /**
     * Uses the private key to generate a signature of input data.
     *
     * @param keyPair         The key pair
     * @param digestAlgorithm Indicates what type of digest to create the signature from.
     * @param data            The data to be signed.
     * @return the signature (length must be equal to the key size) or null on failure.
     */
    @Nullable
    public abstract byte[] sign(
        @NonNull C4KeyPair keyPair,
        @NonNull Signature.SignatureDigestAlgorithm digestAlgorithm,
        @NonNull byte[] data);

    /**
     * Decrypts data using the private key.
     *
     * @param keyPair The key pair
     * @param data    The data to be encrypted.
     * @return the raw key data or null failure.
     */
    @Nullable
    public abstract byte[] decrypt(@NonNull C4KeyPair keyPair, @NonNull byte[] data);

    /**
     * Called when the C4KeyPair is released and the externalKey is no longer needed
     * and when associated resources may be freed
     *
     * @param keyPair The key pair
     */
    public abstract void free(@NonNull C4KeyPair keyPair);

    //-------------------------------------------------------------------------
    // Keystore management
    //-------------------------------------------------------------------------

    public abstract boolean findAlias(@Nullable KeyStore keyStore, @NonNull String targetAlias)
        throws CouchbaseLiteException;

    @Nullable
    public abstract RSAKey getKey(
        @Nullable KeyStore keyStore,
        @NonNull String keyAlias,
        @Nullable char[] keyPassword);

    @Nullable
    public abstract List<Certificate> getCertificateChain(@Nullable KeyStore keyStore, @NonNull String keyAlias);

    public abstract void createSelfSignedCertEntry(
        @Nullable KeyStore keyStore,
        @NonNull String alias,
        @Nullable char[] keyPassword,
        boolean isServer,
        @NonNull Map<String, String> attributes,
        @Nullable Date expiration)
        throws CouchbaseLiteException;

    public abstract void importEntry(
        @NonNull String type,
        @NonNull InputStream stream,
        @Nullable char[] storePassword,
        @NonNull String alias,
        @Nullable char[] keyPassword,
        @NonNull String targetAlias)
        throws CouchbaseLiteException;

    public abstract int deleteEntries(@Nullable KeyStore keyStore, Fn.Predicate<String> filter)
        throws CouchbaseLiteException;

    protected final void checkAlias(@NonNull String alias) throws CouchbaseLiteException {
        if (alias.startsWith(KeyStoreManager.ANON_IDENTITY_ALIAS)) {
            throw new CouchbaseLiteException(
                "Attempt to use reserved identity prefix " + KeyStoreManager.ANON_IDENTITY_ALIAS);
        }
    }

    protected final byte[] getEncodedKey(KeyStore keyStore, @NonNull C4KeyPair keyPair) {
        final Certificate cert;
        try { cert = keyStore.getCertificate(keyPair.getKeyAlias()); }
        catch (KeyStoreException e) { throw new IllegalStateException("Uninitialized key store", e); }
        if (cert == null) {
            Log.w(LogDomain.LISTENER, "No certificate found for alias: " + keyPair.getKeyAlias());
            return null;
        }

        final PublicKey key = cert.getPublicKey();
        if (key == null) {
            Log.w(LogDomain.LISTENER, "No public key for alias " + keyPair.getKeyAlias());
            return null;
        }

        return key.getEncoded();
    }

    @Nullable
    protected final RSAKey getRSAKey(
        @NonNull String alias,
        KeyStore keyStore,
        KeyStore.ProtectionParameter protectionParam) {
        final KeyStore.Entry entry;
        try { entry = keyStore.getEntry(alias, protectionParam); }
        catch (UnrecoverableEntryException | NoSuchAlgorithmException | KeyStoreException e) {
            Log.w(LogDomain.LISTENER, "Key: no key found for alias: " + alias, e);
            return null;
        }

        if (!(entry instanceof KeyStore.PrivateKeyEntry)) {
            Log.w(LogDomain.LISTENER, "Key: no private key found for alias " + alias);
            return null;
        }

        final PrivateKey key = ((KeyStore.PrivateKeyEntry) entry).getPrivateKey();
        if (!(key instanceof RSAKey)) {
            Log.w(LogDomain.LISTENER, "Key: unsupported algorithm (%s) for %s ", key.getAlgorithm(), alias);
            return null;
        }

        return (RSAKey) key;
    }


    @Nullable
    protected final List<Certificate> getCertificates(KeyStore keyStore, @NonNull String alias) {
        final Certificate[] certs;
        try { certs = keyStore.getCertificateChain(alias); }
        catch (KeyStoreException e) {
            Log.w(LogDomain.LISTENER, "Certs: no cert chain for " + alias, e);
            return null;
        }

        return ((certs == null) || (certs.length <= 0)) ? null : new ArrayList<>(Arrays.asList(certs));
    }

    protected final int deleteStoreEntries(KeyStore keyStore, Fn.Predicate<String> filter)
        throws CouchbaseLiteException {
        final Enumeration<String> aliases;
        try { aliases = keyStore.aliases(); }
        catch (KeyStoreException e) { throw new CouchbaseLiteException("Failed deleting entries", e); }

        int deleted = 0;
        while (aliases.hasMoreElements()) {
            final String alias = aliases.nextElement();
            if (!filter.test(alias)) { continue; }

            try {
                keyStore.deleteEntry(alias);
                deleted++;
            }
            catch (KeyStoreException e) {
                throw new CouchbaseLiteException("Delete: failed with " + alias, e);
            }
        }

        return deleted;
    }
}
