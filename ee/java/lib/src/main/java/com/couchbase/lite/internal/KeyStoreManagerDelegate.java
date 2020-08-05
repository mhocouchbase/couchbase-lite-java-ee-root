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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SignatureException;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;

import com.couchbase.lite.CBLError;
import com.couchbase.lite.CouchbaseLiteException;
import com.couchbase.lite.LogDomain;
import com.couchbase.lite.TLSIdentity;
import com.couchbase.lite.internal.core.C4KeyPair;
import com.couchbase.lite.internal.security.Signature;
import com.couchbase.lite.internal.support.Log;
import com.couchbase.lite.internal.utils.Fn;
import com.couchbase.lite.internal.utils.Preconditions;


@SuppressWarnings({"PMD.GodClass"})
public class KeyStoreManagerDelegate extends KeyStoreManager {

    //-------------------------------------------------------------------------
    // Constructor
    //-------------------------------------------------------------------------

    // should be called only from KeyStoreManager.getInstance()
    KeyStoreManagerDelegate() { }

    //-------------------------------------------------------------------------
    // Native Callbacks
    //-------------------------------------------------------------------------

    @Nullable
    @Override
    public byte[] getKeyData(@NonNull C4KeyPair keyPair) {
        final KeyPair keys = keyPair.getKeys();
        if (keys != null) { return keys.getPublic().getEncoded(); }

        final KeyStore keyStore = keyPair.getKeyStore();
        if (keyStore == null) {
            Log.e(LogDomain.LISTENER, "Keystore is null");
            return null;
        }

        return getEncodedKey(keyStore, keyPair);
    }

    @Nullable
    @Override
    public byte[] sign(
        @NonNull C4KeyPair keyPair,
        @NonNull Signature.SignatureDigestAlgorithm digestAlgorithm,
        @NonNull byte[] data) {
        final String alias = keyPair.getKeyAlias();

        final Key key;
        final KeyPair keys = keyPair.getKeys();
        if (keys != null) {
            key = keys.getPrivate();
        }
        else {
            final KeyStore keyStore = keyPair.getKeyStore();
            if (keyStore == null) {
                Log.e(LogDomain.LISTENER, "Sign: keystore is null");
                return null;
            }

            try { key = keyStore.getKey(alias, keyPair.getKeyPassword()); }
            catch (UnrecoverableKeyException | NoSuchAlgorithmException | KeyStoreException e) {
                Log.w(LogDomain.LISTENER, "Sign: no key found for alias " + alias, e);
                return null;
            }

            if (!(key instanceof PrivateKey)) {
                Log.w(LogDomain.LISTENER, "Sign: no private key found for alias " + alias);
                return null;
            }
        }

        try { return Signature.signHashData((PrivateKey) key, data, digestAlgorithm); }
        catch (SignatureException | NoSuchAlgorithmException | InvalidKeyException | IOException e) {
            Log.w(LogDomain.LISTENER, "Sign: failed with " + alias, e);
            return null;
        }
    }

    @Nullable
    @Override
    public byte[] decrypt(@NonNull C4KeyPair keyPair, @NonNull byte[] data) {
        final String alias = keyPair.getKeyAlias();

        final KeyPair keys = keyPair.getKeys();

        final Key key;
        if (keys != null) {
            key = keys.getPrivate();
        }
        // Get private key from the KeyStore:
        else {
            final KeyStore keyStore = keyPair.getKeyStore();
            if (keyStore == null) {
                Log.e(LogDomain.LISTENER, "Decrypt: keystore is null");
                return null;
            }

            try { key = keyStore.getKey(alias, keyPair.getKeyPassword()); }
            catch (UnrecoverableKeyException | NoSuchAlgorithmException | KeyStoreException e) {
                Log.w(LogDomain.LISTENER, "Decrypt: no key found for alias " + alias, e);
                return null;
            }

            if (!(key instanceof PrivateKey)) {
                Log.w(LogDomain.LISTENER, "Decrypt: no private key found for alias: " + alias);
                return null;
            }
        }
        // Decrypt the data:
        try {
            final Cipher cipher = Cipher.getInstance(CIPHER_TYPE);
            cipher.init(Cipher.DECRYPT_MODE, key);
            return cipher.doFinal(data);
        }
        catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException |
            BadPaddingException | IllegalBlockSizeException e) {
            Log.w(LogDomain.LISTENER, "Decrypt: failed with " + alias, e);
            return null;
        }
    }

    @Override
    public void free(@NonNull C4KeyPair keyPair) { }

    //-------------------------------------------------------------------------
    // Keystore management
    //-------------------------------------------------------------------------

    @Override
    public boolean findAlias(@Nullable KeyStore store, @NonNull String targetAlias) {
        final KeyStore keyStore = Preconditions.assertNotNull(store, "keystore");
        try { return keyStore.containsAlias(targetAlias); }
        catch (KeyStoreException e) { throw new IllegalStateException(ERROR_LOADING_KEYSTORE, e); }
    }

    @Nullable
    @Override
    public PrivateKey getKey(@Nullable KeyStore store, @NonNull String alias, @Nullable char[] pwd) {
        final KeyStore keyStore = Preconditions.assertNotNull(store, "keystore");

        final KeyStore.ProtectionParameter protectionParam = (pwd == null)
            ? null
            : new KeyStore.PasswordProtection(pwd);

        return getPrivateKey(alias, keyStore, protectionParam);
    }

    @Nullable
    @Override
    public List<Certificate> getCertificateChain(@Nullable KeyStore store, @NonNull String alias) {
        final KeyStore keyStore = Preconditions.assertNotNull(store, "keystore");
        return getCertificates(keyStore, alias);
    }

    @Override
    public void createSelfSignedCertEntry(
        @Nullable KeyStore keyStore,
        @NonNull String alias,
        @Nullable char[] keyPassword,
        boolean isServer,
        @NonNull Map<String, String> attributes,
        @Nullable Date expiration)
        throws CouchbaseLiteException {
        Preconditions.assertNotNull(keyStore, "keystore");

        if (findAlias(keyStore, alias)) {
            throw new CouchbaseLiteException(
                "Key already exits: " + alias,
                CBLError.Domain.CBLITE,
                CBLError.Code.CRYPTO);
        }

        if (!attributes.containsKey(TLSIdentity.CERT_ATTRIBUTE_COMMON_NAME)) {
            throw new IllegalArgumentException("The Common Name (CN) attribute is required");
        }

        final long validSecs
            = TimeUnit.MILLISECONDS.toSeconds(getExpirationMs(expiration) - System.currentTimeMillis());

        final KeyPair keyPair = generateKeyPair();
        final C4KeyPair c4KeyPair = C4KeyPair.createKeyPair(
            keyStore,
            alias,
            keyPassword,
            KeyAlgorithm.RSA,
            KeySize.BIT_2048,
            keyPair);

        final Certificate cert = generateCertificate(c4KeyPair, isServer, attributes, validSecs);

        // JDK-8236671:
        if (keyPassword == null) { keyPassword = new char[0]; }

        // Store Private Key and Cert into the KeyStore
        try { keyStore.setKeyEntry(alias, keyPair.getPrivate(), keyPassword, new Certificate[] {cert}); }
        catch (KeyStoreException e) {
            throw new CouchbaseLiteException(
                "Failed setting keys and certificate in the KeyStore",
                e,
                CBLError.Domain.CBLITE,
                CBLError.Code.CRYPTO);
        }
    }

    @Override
    public void importEntry(
        @NonNull String type,
        @NonNull InputStream stream,
        @Nullable char[] storePassword,
        @NonNull String alias,
        @Nullable char[] keyPassword,
        @NonNull String targetAlias) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int deleteEntries(@Nullable KeyStore store, Fn.Predicate<String> filter) throws CouchbaseLiteException {
        final KeyStore keyStore = Preconditions.assertNotNull(store, "keystore");
        return deleteStoreEntries(keyStore, filter);
    }

    @NonNull
    private KeyPair generateKeyPair() throws CouchbaseLiteException {
        try {
            final KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(KeySize.BIT_2048.getBitLength());
            return gen.generateKeyPair();
        }
        catch (NoSuchAlgorithmException e) {
            throw new CouchbaseLiteException(
                "Failed generating RSA KeyPair",
                e,
                CBLError.Domain.CBLITE,
                CBLError.Code.CRYPTO);
        }
    }

    // Generate Self-Signed Cert
    @NonNull
    private Certificate generateCertificate(
        C4KeyPair c4KeyPair,
        boolean isServer,
        @NonNull Map<String, String> attributes,
        long expiration)
        throws CouchbaseLiteException {
        final byte[] certData = c4KeyPair.generateSelfSignedCertificate(
            KeyAlgorithm.RSA,
            KeySize.BIT_2048,
            attributes,
            isServer ? CertUsage.TLS_SERVER : CertUsage.TLS_CLIENT,
            expiration);

        if (certData.length <= 0) {
            throw new CouchbaseLiteException("Empty certificate data", CBLError.Domain.CBLITE, CBLError.Code.CRYPTO);
        }

        try { return CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(certData)); }
        catch (CertificateException e) {
            throw new CouchbaseLiteException(
                "Failed to create certificate",
                e,
                CBLError.Domain.CBLITE,
                CBLError.Code.CRYPTO);
        }
    }
}
