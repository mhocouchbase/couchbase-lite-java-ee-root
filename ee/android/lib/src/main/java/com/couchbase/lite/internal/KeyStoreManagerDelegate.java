//
// Copyright (c) 2020 Couchbase, Inc All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// https://info.couchbase.com/rs/302-GJY-034/images/2017-10-30_License_Agreement.pdf
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
package com.couchbase.lite.internal;

import android.annotation.SuppressLint;
import android.os.Build;
import android.security.KeyPairGeneratorSpec;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.VisibleForTesting;

import java.io.IOException;
import java.math.BigInteger;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.SignatureException;
import java.security.UnrecoverableEntryException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.spec.RSAKeyGenParameterSpec;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.security.auth.x500.X500Principal;

import com.couchbase.lite.CBLError;
import com.couchbase.lite.CouchbaseLiteException;
import com.couchbase.lite.LogDomain;
import com.couchbase.lite.TLSIdentity;
import com.couchbase.lite.internal.core.C4KeyPair;
import com.couchbase.lite.internal.security.Signature;
import com.couchbase.lite.internal.support.Log;
import com.couchbase.lite.internal.utils.Fn;


public class KeyStoreManagerDelegate extends KeyStoreManager {
    @VisibleForTesting
    static final String ANDROID_KEY_STORE = "AndroidKeyStore";

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
        final KeyStore keyStore = loadKeyStore();
        if (keyStore == null) { return null; }
        return getEncodedKey(keyStore, keyPair);
    }

    @Nullable
    @Override
    public byte[] sign(
        @NonNull C4KeyPair keyPair,
        @NonNull Signature.SignatureDigestAlgorithm digestAlgorithm,
        @NonNull byte[] data) {
        final KeyStore keyStore = loadKeyStore();
        if (keyStore == null) { return null; }

        final String alias = keyPair.getKeyAlias();
        final PrivateKey key = getPrivateKey(keyStore, alias);
        if (key == null) { return null; }

        try { return Signature.signHashData(key, data, digestAlgorithm); }
        catch (NoSuchAlgorithmException | SignatureException | InvalidKeyException | IOException e) {
            Log.w(LogDomain.LISTENER, "Sign: failed with " + alias, e);
            return null;
        }
    }

    @Nullable
    @Override
    public byte[] decrypt(@NonNull C4KeyPair keyPair, @NonNull byte[] data) {
        final KeyStore keyStore = loadKeyStore();
        if (keyStore == null) { return null; }

        final String alias = keyPair.getKeyAlias();
        final PrivateKey key = getPrivateKey(keyStore, alias);
        if (key == null) { return null; }

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
    public boolean findAlias(@Nullable KeyStore ignore, @NonNull String targetAlias) {
        final KeyStore keyStore = loadKeyStore();
        if (keyStore == null) { throw new IllegalStateException(ERROR_LOADING_KEYSTORE); }

        try { return keyStore.containsAlias(targetAlias); }
        catch (KeyStoreException e) { throw new IllegalStateException(ERROR_LOADING_KEYSTORE, e); }
    }

    @Nullable
    @Override
    public PrivateKey getKey(@Nullable KeyStore ignore1, @NonNull String alias, @Nullable char[] ignore2) {
        final KeyStore keyStore = loadKeyStore();
        if (keyStore == null) { throw new IllegalStateException(ERROR_LOADING_KEYSTORE); }
        return getPrivateKey(alias, keyStore, null);
    }

    @Nullable
    @Override
    public List<Certificate> getCertificateChain(@Nullable KeyStore ignore, @NonNull String alias) {
        final KeyStore keyStore = loadKeyStore();
        if (keyStore == null) { throw new IllegalStateException(ERROR_LOADING_KEYSTORE); }
        return getCertificates(keyStore, alias);
    }

    @SuppressLint("NewApi")
    @Override
    public void createSelfSignedCertEntry(
        @Nullable KeyStore ignore1,
        @NonNull String alias,
        @Nullable char[] ignore2,
        boolean isServer,
        @NonNull Map<String, String> attributes,
        @Nullable Date expiration)
        throws CouchbaseLiteException {
        if (findAlias(null, alias)) {
            throw new CouchbaseLiteException(
                "Key already exits: " + alias,
                CBLError.Domain.CBLITE,
                CBLError.Code.CRYPTO);
        }

        final Map<String, String> localAttributes = new HashMap<>(attributes);
        final String dn = localAttributes.remove(TLSIdentity.CERT_ATTRIBUTE_COMMON_NAME);
        if (dn == null) { throw new IllegalArgumentException("The Common Name (CN) attribute is required"); }
        final X500Principal subject = new X500Principal("CN=" + dn, localAttributes);

        final Date exp = new Date(getExpirationMs(expiration));

        // Generate KeyPair (and Cert) in the store
        try {
            final KeyPairGenerator keyFactory = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                ? initKeyFactoryM(alias, exp, subject)
                : initKeyFactoryPreM(alias, exp, subject);
            keyFactory.generateKeyPair();
        }
        catch (NoSuchAlgorithmException | NoSuchProviderException | InvalidAlgorithmParameterException e) {
            throw new CouchbaseLiteException("Failed to create entry: " + alias, e);
        }
    }

    //-------------------------------------------------------------------------
    // Internal methods
    //-------------------------------------------------------------------------

    @VisibleForTesting
    @Override
    public int deleteEntries(
        @Nullable KeyStore ignore,
        @NonNull Fn.Predicate<String> filter)
        throws CouchbaseLiteException {
        final KeyStore keyStore = loadKeyStore();
        if (keyStore == null) { throw new IllegalStateException(ERROR_LOADING_KEYSTORE); }
        return deleteStoreEntries(keyStore, filter);
    }

    @SuppressLint("WrongConstant")
    @NonNull
    private KeyPairGenerator initKeyFactoryPreM(
        @NonNull String alias,
        @NonNull Date expiration,
        @NonNull X500Principal subject)
        throws NoSuchAlgorithmException, InvalidAlgorithmParameterException, NoSuchProviderException {
        final KeyPairGenerator keyFactory = KeyPairGenerator.getInstance("RSA", ANDROID_KEY_STORE);
        keyFactory.initialize(new KeyPairGeneratorSpec.Builder(CouchbaseLiteInternal.getContext())
            .setAlias(alias)
            .setKeyType("RSA")
            .setKeySize(KeySize.BIT_2048.getBitLength())
            .setSubject(subject)
            .setSerialNumber(BigInteger.ONE)
            .setStartDate(new Date())
            .setEndDate(expiration)
            .build());
        return keyFactory;
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    @NonNull
    private KeyPairGenerator initKeyFactoryM(
        @NonNull String alias,
        @NonNull Date expiration,
        @NonNull X500Principal subject)
        throws InvalidAlgorithmParameterException, NoSuchProviderException, NoSuchAlgorithmException {
        final KeyPairGenerator keyFactory
            = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEY_STORE);
        keyFactory.initialize(new KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN
                | KeyProperties.PURPOSE_VERIFY
                | KeyProperties.PURPOSE_ENCRYPT
                | KeyProperties.PURPOSE_DECRYPT)
            .setAlgorithmParameterSpec(
                new RSAKeyGenParameterSpec(KeySize.BIT_2048.getBitLength(), RSAKeyGenParameterSpec.F0))
            .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256)
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
            .setBlockModes(KeyProperties.BLOCK_MODE_ECB)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
            .setCertificateSubject(subject)
            .setCertificateSerialNumber(BigInteger.ONE)
            .setCertificateNotBefore(new Date())
            .setCertificateNotAfter(expiration)
            .setUserAuthenticationRequired(false)
            .build());
        return keyFactory;
    }

    // ??? should cache the store?
    @Nullable
    private KeyStore loadKeyStore() {
        try {
            final KeyStore store = KeyStore.getInstance(ANDROID_KEY_STORE);
            store.load(null);
            return store;
        }
        catch (IOException | CertificateException | NoSuchAlgorithmException | KeyStoreException e) {
            Log.w(LogDomain.LISTENER, "Failed to load key store", e);
        }
        return null;
    }

    @Nullable
    private PrivateKey getPrivateKey(@NonNull KeyStore keyStore, @NonNull String alias) {
        final Key key;
        try { key = keyStore.getKey(alias, null); }
        // Conscript may throw a RuntimeException.
        catch (Exception e) {
            Log.w(LogDomain.LISTENER, "Failed retrieving key for alias " + alias, e);
            return null;
        }

        if (!(key instanceof PrivateKey)) {
            Log.w(LogDomain.LISTENER, "No private key found for alias " + alias);
            return null;
        }

        return (PrivateKey) key;
    }
}
