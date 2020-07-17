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
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.UnrecoverableEntryException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.spec.RSAKeyGenParameterSpec;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import javax.security.auth.x500.X500Principal;

import com.couchbase.lite.CouchbaseLiteException;
import com.couchbase.lite.LogDomain;
import com.couchbase.lite.internal.core.C4KeyPair;
import com.couchbase.lite.internal.support.Log;
import com.couchbase.lite.internal.utils.Fn;


public class KeyStoreManagerDelegate extends KeyStoreManager {
    @VisibleForTesting
    static final String ANDROID_KEY_STORE = "AndroidKeyStore";

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


    //-------------------------------------------------------------------------
    // Implementation
    //-------------------------------------------------------------------------

    // should be called only from KeyStoreManager.getInstance()
    KeyStoreManagerDelegate() { }

    @Nullable
    @Override
    public byte[] getKeyData(@NonNull C4KeyPair keyPair) {
        android.util.Log.d("###", "getKeyData alias: " + keyPair.getKeyAlias());
        try {
            final KeyStore keystore = loadKeyStore();

            final Certificate cert = keystore.getCertificate(keyPair.getKeyAlias());
            if (cert == null) {
                Log.e(LogDomain.LISTENER, "Could not find a certificate for alias: " + keyPair.getKeyAlias());
                return null;
            }

            final PublicKey key = cert.getPublicKey();
            if (key == null) {
                Log.e(LogDomain.LISTENER, "Could not find a public key for alias: " + keyPair.getKeyAlias());
                return null;
            }

            final byte[] ret = key.getEncoded();
            android.util.Log.d("###", "getKeyData return: " + ret.length);
            return ret;
        }
        catch (NoSuchAlgorithmException | CertificateException | KeyStoreException | IOException e) {
            Log.e(LogDomain.LISTENER, "Failed obtaining public key data", e);
        }

        return null;
    }

    @Nullable
    @Override
    public byte[] decrypt(@NonNull C4KeyPair keyPair, @NonNull byte[] data) {
        android.util.Log.d("###", "decrypt @" + keyPair.getKeyAlias());
        return new byte[0];
    }

    @Nullable
    @Override
    public byte[] signKey(
        @NonNull C4KeyPair keyPair,
        @NonNull SignatureDigestAlgorithm digestAlgorithm,
        @NonNull byte[] data) {
        final String algorithm = DIGEST_ALGORITHM_TO_JAVA.get(digestAlgorithm);

        try {
            final KeyStore keystore = loadKeyStore();

            final KeyStore.Entry entry = keystore.getEntry(keyPair.getKeyAlias(), null);
            if (!(entry instanceof KeyStore.PrivateKeyEntry)) {
                Log.e(LogDomain.LISTENER, "Could not find a private key for alias: " + keyPair.getKeyAlias());
                return null;
            }

            final Signature sig = Signature.getInstance(algorithm);
            sig.initSign(((KeyStore.PrivateKeyEntry) entry).getPrivateKey());
            sig.update(data);

            return sig.sign();
        }
        catch (SignatureException | UnrecoverableEntryException | NoSuchAlgorithmException
            | CertificateException | InvalidKeyException | KeyStoreException | IOException e) {
            Log.e(LogDomain.LISTENER, "Failed signing key", e);
        }

        return null;
    }

    @Override
    public void free(@NonNull C4KeyPair keyPair) {
        android.util.Log.d("###", "free @" + keyPair.getKeyAlias());
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
        final Map<String, String> localAttributes = new HashMap<>(attributes);
        final String dn = localAttributes.remove(CERT_ATTRIBUTE_COMMON_NAME);
        if (dn == null) { throw new CouchbaseLiteException("Certificate must have a distinguished name (CN)"); }

        if (expiration == null) {
            final Calendar expDate = Calendar.getInstance();
            expDate.add(Calendar.YEAR, ANON_EXPIRATION_YEARS);
            expiration = expDate.getTime();
        }

        final X500Principal subject = new X500Principal("CN=" + dn, localAttributes);
        try {
            final KeyPairGenerator keyFactory = (Build.VERSION.SDK_INT < Build.VERSION_CODES.M)
                ? getGeneratorPreM(alias, expiration, subject)
                : getKeyFactoryM(alias, expiration, subject);

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

        try {
            final KeyStore externalStore = KeyStore.getInstance(type);
            externalStore.load(storeStream, storePassword);

            final KeyStore androidStore = loadKeyStore();

            final KeyStore.ProtectionParameter protectionParameter = (keyPassword == null)
                ? null
                : new KeyStore.PasswordProtection(keyPassword);

            androidStore.setEntry(newAlias, externalStore.getEntry(alias, protectionParameter), null);
        }
        catch (IOException | CertificateException | NoSuchAlgorithmException
            | UnrecoverableEntryException | KeyStoreException e) {
            throw new CouchbaseLiteException("Failed importing identity: " + alias + "(" + type + ")", e);
        }
    }

    @Nullable
    @Override
    public Certificate getCertificate(
        @Nullable KeyStore keyStore,
        @NonNull String keyAlias,
        @Nullable char[] keyPassword)
        throws CouchbaseLiteException {
        try {
            final KeyStore keystore = loadKeyStore();

            return keystore.getCertificate(keyAlias);
        }
        catch (IOException | CertificateException | NoSuchAlgorithmException | KeyStoreException e) {
            throw new CouchbaseLiteException("Failed fetching identity: " + keyAlias, e);
        }
    }

    @Override
    public boolean findAlias(@Nullable KeyStore keyStore, @NonNull String targetAlias) throws CouchbaseLiteException {
        try {
            final KeyStore keystore = loadKeyStore();

            final Enumeration<String> aliases = keystore.aliases();
            while (aliases.hasMoreElements()) {
                final String alias = aliases.nextElement();
                if (targetAlias.equals(alias)) { return true; }
            }
        }
        catch (KeyStoreException | CertificateException | NoSuchAlgorithmException | IOException e) {
            throw new CouchbaseLiteException("Failed searching for a saved anonymous identity", e);
        }

        return false;
    }

    @VisibleForTesting
    @Override
    public int deleteEntries(@Nullable KeyStore ignore, Fn.Predicate<String> filter) throws CouchbaseLiteException {
        int deleted = 0;
        try {
            final KeyStore keystore = loadKeyStore();

            final Enumeration<String> aliases = keystore.aliases();
            while (aliases.hasMoreElements()) {
                final String alias = aliases.nextElement();
                if (filter.test(alias)) {
                    keystore.deleteEntry(alias);
                    deleted++;
                }
            }
        }
        catch (KeyStoreException | CertificateException | NoSuchAlgorithmException | IOException e) {
            throw new CouchbaseLiteException("Failed searching for a saved anonymous identity", e);
        }

        return deleted;
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private KeyPairGenerator getKeyFactoryM(@NonNull String alias, @NonNull Date expiration, X500Principal subject)
        throws NoSuchAlgorithmException, NoSuchProviderException, InvalidAlgorithmParameterException {
        android.util.Log.d("###", "Using modern key generator: " + alias);

        final KeyPairGenerator keyFactory
            = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEY_STORE);

        keyFactory.initialize(new KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
            //.setAlgorithmParameterSpec(algorithmSpec)
            .setCertificateSerialNumber(BigInteger.ONE)
            .setCertificateSubject(subject)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
            .setCertificateNotBefore(new Date())
            .setCertificateNotAfter(expiration)
            .setUserAuthenticationRequired(false)
            .build());

        return keyFactory;
    }

    private KeyPairGenerator getGeneratorPreM(@NonNull String alias, @NonNull Date expiration, X500Principal subject)
        throws NoSuchAlgorithmException, NoSuchProviderException, InvalidAlgorithmParameterException {
        android.util.Log.d("###", "Using legacy key creation: " + alias);

        final RSAKeyGenParameterSpec algorithmSpec
            = new RSAKeyGenParameterSpec(KeyStoreManager.KeySize.BIT_2048.getBitLength(), RSAKeyGenParameterSpec.F4);

        final KeyPairGenerator keyFactory
            = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEY_STORE);

        keyFactory.initialize(new KeyPairGeneratorSpec.Builder(CouchbaseLiteInternal.getContext())
            .setAlias(alias)
            .setAlgorithmParameterSpec(algorithmSpec)
            .setSerialNumber(BigInteger.ONE)
            .setSubject(subject)
            .setStartDate(new Date())
            .setEndDate(expiration)
            .build());

        return keyFactory;
    }

    private KeyStore loadKeyStore()
        throws CertificateException, NoSuchAlgorithmException, IOException, KeyStoreException {
        final KeyStore keystore = KeyStore.getInstance(ANDROID_KEY_STORE);
        keystore.load(null);
        return keystore;
    }
}
