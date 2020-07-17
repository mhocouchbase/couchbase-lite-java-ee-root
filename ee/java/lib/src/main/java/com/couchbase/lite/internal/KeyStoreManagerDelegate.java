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

import java.io.InputStream;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.UnrecoverableEntryException;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;

import com.couchbase.lite.CBLError;
import com.couchbase.lite.CouchbaseLiteException;
import com.couchbase.lite.LogDomain;
import com.couchbase.lite.internal.core.C4KeyPair;
import com.couchbase.lite.internal.support.Log;
import com.couchbase.lite.internal.utils.Fn;


public class KeyStoreManagerDelegate extends KeyStoreManager {
    private static final Map<KeyStoreManager.SignatureDigestAlgorithm, String> DIGEST_ALGORITHM_TO_JAVA;
    static {
        final Map<KeyStoreManager.SignatureDigestAlgorithm, String> m = new HashMap<>();
        m.put(KeyStoreManager.SignatureDigestAlgorithm.NONE, "NONEwithRSA");
        m.put(KeyStoreManager.SignatureDigestAlgorithm.SHA1, "SHA1withRSA");
        m.put(KeyStoreManager.SignatureDigestAlgorithm.SHA224, "SHA224withRSA");
        m.put(KeyStoreManager.SignatureDigestAlgorithm.SHA256, "SHA256withRSA");
        m.put(KeyStoreManager.SignatureDigestAlgorithm.SHA384, "SHA384withRSA");
        m.put(KeyStoreManager.SignatureDigestAlgorithm.SHA512, "SHA384withRSA");
        DIGEST_ALGORITHM_TO_JAVA = Collections.unmodifiableMap(m);
    }

    @Nullable
    @Override
    public byte[] getKeyData(@NonNull C4KeyPair keyPair) {
        try {
            final KeyPair keys = keyPair.getKeys();
            if (keys != null) { return keys.getPublic().getEncoded(); }

            final KeyStore keyStore = keyPair.getKeyStore();
            assert keyStore != null;
            final Certificate cert  = keyStore.getCertificate(keyPair.getKeyAlias());
            return cert.getPublicKey().getEncoded();
        }
        catch (KeyStoreException e) {
            Log.e(LogDomain.LISTENER, "Failed obtaining public key data", e);
        }
        return null;
    }

    @Nullable
    @Override
    public byte[] decrypt(@NonNull C4KeyPair keyPair, @NonNull byte[] data) {
        // Get private key from the KeyStore:
        final Key key;
        try {
            final KeyPair keys = keyPair.getKeys();
            if (keys != null) {
                key = keys.getPrivate();
            } else {
                final KeyStore keyStore = keyPair.getKeyStore();
                assert keyStore != null;
                key = keyStore.getKey(keyPair.getKeyAlias(), keyPair.getKeyPassword());
                if (!(key instanceof PrivateKey)) {
                    Log.e(LogDomain.LISTENER, "Failed obtaining private key for decryping");
                    return null;
                }
            }
        }
        catch (KeyStoreException | NoSuchAlgorithmException | UnrecoverableKeyException  e) {
            Log.e(LogDomain.LISTENER, "Failed obtaining private key", e);
            return null;
        }
        // Decrypt the data:
        try {
            final Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.DECRYPT_MODE, key);
            return cipher.doFinal(data);
        }
        catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException |
               BadPaddingException | IllegalBlockSizeException e) {
            Log.e(LogDomain.LISTENER, "Failed decryping data", e);
            return null;
        }
    }

    @Nullable
    @Override
    public byte[] signKey(
        @NonNull C4KeyPair keyPair,
        @NonNull SignatureDigestAlgorithm digestAlgorithm,
        @NonNull byte[] data) {
        try {
            final Key key;
            final KeyPair keys = keyPair.getKeys();
            if (keys != null) {
                key = keys.getPrivate();
            } else {
                final KeyStore keyStore = keyPair.getKeyStore();
                assert keyStore != null;
                key = keyStore.getKey(keyPair.getKeyAlias(), keyPair.getKeyPassword());
                if (!(key instanceof PrivateKey)) {
                    Log.e(LogDomain.LISTENER, "Failed obtaining private key for decryping");
                    return null;
                }
            }

            final String algorithm = DIGEST_ALGORITHM_TO_JAVA.get(digestAlgorithm);
            if (algorithm == null) {
                Log.e(LogDomain.LISTENER, "Unsupported digest algorithm for signing: " + digestAlgorithm);
                return null;
            }

            final Signature sig = Signature.getInstance(algorithm);
            sig.initSign((PrivateKey) key);
            sig.update(data);
            return sig.sign();
        }
        catch (SignatureException | UnrecoverableEntryException | NoSuchAlgorithmException |
               InvalidKeyException | KeyStoreException e) {
            Log.e(LogDomain.LISTENER, "Failed signing key", e);
        }

        return null;
    }

    @Override
    public void free(@NonNull C4KeyPair keyPair) { }

    @Override
    public void createSelfSignedCertEntry(
        @Nullable KeyStore keyStore,
        @NonNull String alias,
        @Nullable char[] keyPassword,
        boolean isServer,
        @NonNull Map<String, String> attributes,
        @Nullable Date expiration)
        throws CouchbaseLiteException {
        try {
            assert keyStore != null;
            if (findAlias(keyStore, alias)) {
                throw new CouchbaseLiteException("Key already exits @" + alias,
                    CBLError.Domain.CBLITE, CBLError.Code.CRYPTO);
            }

            // Generate KeyPair:
            final KeyPair keyPair = generateRSAKeyPair(alias, isServer, KeySize.BIT_2048, attributes, expiration);

            // Generate C4KeyPair:
            final C4KeyPair c4KeyPair = C4KeyPair.createKeyPair(
                keyStore, alias,
                keyPassword,
                KeyAlgorithm.RSA,
                KeySize.BIT_2048,
                keyPair);

            // Generate Self-Signed Cert:
            final CertUsage usage = isServer ? CertUsage.TLS_SERVER : CertUsage.TLS_CLIENT;
            final Certificate cert = c4KeyPair.generateSelfSignedCertificate(
                KeyAlgorithm.RSA, KeySize.BIT_2048, attributes, usage, expiration);

            // Store Private Key and Cert into the KeyStore
            keyStore.setKeyEntry(alias, keyPair.getPrivate(), keyPassword, new Certificate[] {cert});
        }
        catch (KeyStoreException e) {
            throw new CouchbaseLiteException("Failed setting keys and certificate in the KeyStore", e,
                CBLError.Domain.CBLITE, CBLError.Code.CRYPTO, null);
        }
    }

    @Override
    public void createAnonymousCertEntry(@NonNull String alias, boolean isServer) throws CouchbaseLiteException { }

    @Override
    public void importEntry(
        @NonNull String type,
        @NonNull InputStream stream,
        @Nullable char[] storePassword,
        @NonNull String alias,
        @Nullable char[] keyPassword,
        @NonNull String targetAlias) throws CouchbaseLiteException {
        throw new UnsupportedOperationException();
    }

    @Nullable
    @Override
    public Certificate getCertificate(
        @Nullable KeyStore keyStore, @NonNull String keyAlias, @Nullable char[] keyPassword)
        throws CouchbaseLiteException {
        try {
            assert keyStore != null;
            return keyStore.getCertificate(keyAlias);
        }
        catch (KeyStoreException e) {
            throw new CouchbaseLiteException("Failed finding certificate @ " + keyAlias, e,
                CBLError.Domain.CBLITE, CBLError.Code.CRYPTO, null);
        }
    }

    @Override
    public boolean findAlias(
        @Nullable KeyStore keyStore,
        @NonNull String keyAlias)
        throws CouchbaseLiteException {
        try {
            assert keyStore != null;
            return keyStore.containsAlias(keyAlias);
        }
        catch (KeyStoreException e) {
            throw new CouchbaseLiteException("Failed finding alias @" + keyAlias, e,
                CBLError.Domain.CBLITE, CBLError.Code.CRYPTO, null);
        }
    }

    @Override
    public int deleteEntries(@Nullable KeyStore keyStore, Fn.Predicate<String> filter)
        throws CouchbaseLiteException {
        int deleted = 0;
        try {
            assert keyStore != null;
            final Enumeration<String> aliases = keyStore.aliases();
            while (aliases.hasMoreElements()) {
                final String alias = aliases.nextElement();
                if (filter.test(alias)) {
                    keyStore.deleteEntry(alias);
                    deleted++;
                }
            }
        }
        catch (KeyStoreException e) {
            throw new CouchbaseLiteException("Failed deleting KeyStore entries", e,
                CBLError.Domain.CBLITE, CBLError.Code.CRYPTO, null);
        }
        return deleted;
    }

    @NonNull
    private KeyPair generateRSAKeyPair(
        @NonNull String alias,
        boolean isServer,
        @NonNull KeySize keySize,
        @NonNull Map<String, String> attributes,
        @Nullable Date expiration) throws CouchbaseLiteException {
        try {
            final KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(keySize.getBitLength());
            return gen.generateKeyPair();
        }
        catch (NoSuchAlgorithmException e) {
            throw new CouchbaseLiteException("Failed generating RSA KeyPair", e,
                CBLError.Domain.CBLITE, CBLError.Code.CRYPTO, null);
        }
    }
}
