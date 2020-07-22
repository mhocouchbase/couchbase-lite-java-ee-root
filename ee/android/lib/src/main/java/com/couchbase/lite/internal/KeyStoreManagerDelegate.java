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

import android.annotation.SuppressLint;
import android.os.Build;
import android.security.KeyPairGeneratorSpec;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.RequiresApi;
import android.support.annotation.VisibleForTesting;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.Signature;
import java.security.SignatureException;
import java.security.UnrecoverableEntryException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.interfaces.RSAKey;
import java.security.spec.RSAKeyGenParameterSpec;
import java.util.Calendar;
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
import com.couchbase.lite.URLEndpointListener;
import com.couchbase.lite.internal.core.C4KeyPair;
import com.couchbase.lite.internal.support.Log;
import com.couchbase.lite.internal.utils.Fn;


@SuppressWarnings({"PMD.GodClass"})
public class KeyStoreManagerDelegate extends KeyStoreManager {
    @VisibleForTesting
    static final String ANDROID_KEY_STORE = "AndroidKeyStore";

    protected static final String CIPHER_TYPE = "RSA/ECB/PKCS1Padding";


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
        @NonNull SignatureDigestAlgorithm digestAlgorithm,
        @NonNull byte[] data) {
        final String algorithm = DIGEST_ALGORITHM_TO_JAVA.get(digestAlgorithm);
        android.util.Log.d("###", "digest algorithm: " + algorithm);
        if (algorithm == null) {
            Log.w(LogDomain.LISTENER, "Unsupported digest algorithm: " + digestAlgorithm);
            return null;
        }

        final KeyStore keyStore = loadKeyStore();
        if (keyStore == null) { return null; }

        final String alias = keyPair.getKeyAlias();

        final KeyStore.Entry entry;
        try { entry = keyStore.getEntry(alias, null); }
        catch (UnrecoverableEntryException | NoSuchAlgorithmException | KeyStoreException e) {
            Log.w(LogDomain.LISTENER, "No key found for alias: " + alias, e);
            return null;
        }

        if (!(entry instanceof KeyStore.PrivateKeyEntry)) {
            Log.w(LogDomain.LISTENER, "No private key found for alias: " + alias);
            return null;
        }

        try {
            final Signature sig = Signature.getInstance(DIGEST_ALGORITHM_TO_JAVA.get(digestAlgorithm));
            sig.initSign(((KeyStore.PrivateKeyEntry) entry).getPrivateKey());
            sig.update(data);
            return sig.sign();
        }
        catch (NoSuchAlgorithmException | SignatureException | InvalidKeyException e) {
            Log.w(LogDomain.LISTENER, "Failed creating signature with: " + alias);
            return null;
        }
    }

    @Nullable
    @Override
    public byte[] decrypt(@NonNull C4KeyPair keyPair, @NonNull byte[] data) {
        android.util.Log.d("###", "decrypt @" + keyPair.getKeyAlias());
        final KeyStore keyStore = loadKeyStore();
        if (keyStore == null) { return null; }

        final String alias = keyPair.getKeyAlias();

        final KeyStore.Entry entry;
        try { entry = keyStore.getEntry(alias, null); }
        catch (UnrecoverableEntryException | NoSuchAlgorithmException | KeyStoreException e) {
            Log.w(LogDomain.LISTENER, "No key found for alias: " + alias, e);
            return null;
        }

        if (!(entry instanceof KeyStore.PrivateKeyEntry)) {
            Log.w(LogDomain.LISTENER, "No private key found for alias: " + alias);
            return null;
        }

        try {
            final Cipher cipher = Cipher.getInstance(CIPHER_TYPE);
            cipher.init(Cipher.DECRYPT_MODE, (((KeyStore.PrivateKeyEntry) entry).getPrivateKey()));
            return cipher.doFinal(data);
        }
        catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException |
            BadPaddingException | IllegalBlockSizeException e) {
            Log.w(LogDomain.LISTENER, "Failed decryping data with " + alias, e);
            return null;
        }
    }

    @Override
    public void free(@NonNull C4KeyPair keyPair) {
        android.util.Log.d("###", "free @" + keyPair.getKeyAlias());
    }

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
    public RSAKey getKey(@Nullable KeyStore ignore1, @NonNull String alias, @Nullable char[] ignore2) {
        final KeyStore keyStore = loadKeyStore();
        if (keyStore == null) { throw new IllegalStateException(ERROR_LOADING_KEYSTORE); }
        return getRSAKey(alias, keyStore, null);
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

        checkAlias((alias));
        if (findAlias(null, alias)) {
            throw new CouchbaseLiteException(
                "Key already exits: " + alias,
                CBLError.Domain.CBLITE,
                CBLError.Code.CRYPTO);
        }

        if (expiration == null) {
            final Calendar expDate = Calendar.getInstance();
            expDate.add(Calendar.YEAR, ANON_EXPIRATION_YEARS);
            expiration = expDate.getTime();
        }

        final Map<String, String> localAttributes = new HashMap<>(attributes);
        final String dn = localAttributes.remove(URLEndpointListener.CERT_ATTRIBUTE_COMMON_NAME);
        if (dn == null) {
            throw new CouchbaseLiteException(
                "The Common Name (CN) attribute is required",
                CBLError.Domain.CBLITE,
                CBLError.Code.CRYPTO);
        }
        final X500Principal subject = new X500Principal("CN=" + dn, localAttributes);

        // Generate KeyPair (and Cert) in the store
        try {
            final KeyPairGenerator keyFactory = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                ? initKeyFactoryM(alias, expiration, subject)
                : initKeyFactoryPreM(alias, expiration, subject);

            keyFactory.generateKeyPair();
        }
        catch (NoSuchAlgorithmException | NoSuchProviderException | InvalidAlgorithmParameterException e) {
            throw new CouchbaseLiteException("Failed to create entry: " + alias, e);
        }
    }

    @Override
    public void importEntry(
        @NonNull String type,
        @NonNull InputStream storeStream,
        @Nullable char[] storePassword,
        @NonNull String alias,
        @Nullable char[] keyPassword,
        @NonNull String newAlias)
        throws CouchbaseLiteException {
        final KeyStore androidStore = loadKeyStore();
        if (androidStore == null) { throw new IllegalStateException(ERROR_LOADING_KEYSTORE); }

        final KeyStore.ProtectionParameter protectionParameter = (keyPassword == null)
            ? null
            : new KeyStore.PasswordProtection(keyPassword);

        try {
            final KeyStore externalStore = KeyStore.getInstance(type);
            externalStore.load(storeStream, storePassword);
            androidStore.setEntry(newAlias, externalStore.getEntry(alias, protectionParameter), null);
        }
        catch (IOException | CertificateException | NoSuchAlgorithmException
            | UnrecoverableEntryException | KeyStoreException e) {
            throw new CouchbaseLiteException("Failed importing identity: " + alias + "(" + type + ")", e);
        }
    }

    //-------------------------------------------------------------------------
    // Internal methods
    //-------------------------------------------------------------------------

    @VisibleForTesting
    @Override
    public int deleteEntries(@Nullable KeyStore ignore, Fn.Predicate<String> filter) throws CouchbaseLiteException {
        final KeyStore keyStore = loadKeyStore();
        if (keyStore == null) { throw new IllegalStateException(ERROR_LOADING_KEYSTORE); }
        return deleteStoreEntries(keyStore, filter);
    }

    @SuppressLint("WrongConstant")
    @NonNull
    private KeyPairGenerator initKeyFactoryPreM(
        @NonNull String alias,
        @NonNull Date expiration,
        X500Principal subject)
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
        X500Principal subject)
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
            Log.e(LogDomain.LISTENER, "Failed to load key store", e);
        }
        return null;
    }
}
