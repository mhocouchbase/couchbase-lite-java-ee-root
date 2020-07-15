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

import android.os.Build;
import android.security.KeyPairGeneratorSpec;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyPair;
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


public class KeyStoreManager extends AbstractKeyStoreManager {
    public static final String ANON_IDENTITY_ALIAS = "CBL-ANON";
    private static final String ANDROID_KEY_STORE = "AndroidKeyStore";

    private static final Map<AbstractKeyStoreManager.SignatureDigestAlgorithm, String> DIGEST_ALGORITHM_TO_JAVA;
    static {
        final Map<AbstractKeyStoreManager.SignatureDigestAlgorithm, String> m = new HashMap<>();
        m.put(AbstractKeyStoreManager.SignatureDigestAlgorithm.NONE, "NONEwithRSA");
        m.put(AbstractKeyStoreManager.SignatureDigestAlgorithm.SHA1, "SHA1withRSA");
        m.put(AbstractKeyStoreManager.SignatureDigestAlgorithm.SHA224, "SHA224withRSA");
        m.put(AbstractKeyStoreManager.SignatureDigestAlgorithm.SHA256, "SHA256withRSA");
        m.put(AbstractKeyStoreManager.SignatureDigestAlgorithm.SHA384, "SHA384withRSA");
        m.put(AbstractKeyStoreManager.SignatureDigestAlgorithm.SHA512, "SHA384withRSA");
        DIGEST_ALGORITHM_TO_JAVA = Collections.unmodifiableMap(m);
    }
    @Nullable
    @Override
    public byte[] getKeyData(
        @Nullable KeyStore ignore1,
        @NonNull String keyAlias,
        @Nullable char[] ignore2) {
        android.util.Log.d("###", "getKeyData alias: " + keyAlias);
        try {
            final KeyStore keystore = KeyStore.getInstance(ANDROID_KEY_STORE);
            keystore.load(null);

            final Certificate cert = keystore.getCertificate(keyAlias);
            if (cert == null) {
                Log.e(LogDomain.LISTENER, "Could not find a certificate for alias: " + keyAlias);
                return null;
            }

            final PublicKey key = cert.getPublicKey();
            if (key == null) {
                Log.e(LogDomain.LISTENER, "Could not find a public key for alias: " + keyAlias);
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
    public byte[] decrypt(
        @Nullable KeyStore keyStore,
        @NonNull String keyAlias,
        @Nullable char[] keyPassword, @NonNull byte[] data) {
        android.util.Log.d("###", "decrypt @" + keyAlias);
        return new byte[0];
    }

    @Nullable
    @Override
    public byte[] signKey(
        @Nullable KeyStore keyStore,
        @NonNull String keyAlias,
        @Nullable char[] keyPassword,
        @NonNull SignatureDigestAlgorithm digestAlgorithm,
        @NonNull byte[] data) {
        final String algorithm = DIGEST_ALGORITHM_TO_JAVA.get(digestAlgorithm);

        try {
            final KeyStore keystore = KeyStore.getInstance(ANDROID_KEY_STORE);
            keystore.load(null);

            final KeyStore.Entry entry = keystore.getEntry(keyAlias, null);
            if (!(entry instanceof KeyStore.PrivateKeyEntry)) {
                Log.e(LogDomain.LISTENER, "Could not find a private key for alias: " + keyAlias);
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
    public void free(
        @Nullable KeyStore keyStore,
        @NonNull String keyAlias,
        @Nullable char[] keyPassword) {
        android.util.Log.d("###", "free @" + keyAlias);
    }

    @Nullable
    @Override
    public String createCertEntry(
        @NonNull String alias,
        boolean isServer,
        @NonNull Map<KeyStoreManager.CertAttribute, String> attributes,
        @NonNull Date expiration)
        throws CouchbaseLiteException {
        generateRSAKeyPair(alias, isServer, KeyStoreManager.KeySize.BIT_2048, attributes, expiration);

        final C4KeyPair c4keys
            = C4KeyPair.createKeyPair(alias, KeyStoreManager.KeyAlgorithm.RSA, KeyStoreManager.KeySize.BIT_2048);

        final Certificate cert = c4keys.generateSelfSignedCertificate(
            KeyStoreManager.KeyAlgorithm.RSA,
            KeyStoreManager.KeySize.BIT_2048,
            attributes,
            (isServer) ? KeyStoreManager.CertUsage.TLS_SERVER : KeyStoreManager.CertUsage.TLS_CLIENT);

        try {
            final KeyStore keystore = KeyStore.getInstance(KeyStoreManager.ANDROID_KEY_STORE);
            keystore.load(null);
            keystore.setCertificateEntry(alias, cert);
        }
        catch (IOException | CertificateException | NoSuchAlgorithmException | KeyStoreException e) {
            throw new CouchbaseLiteException("Failed creating identity", e);
        }

        return alias;
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

            final KeyStore androidStore = KeyStore.getInstance(ANDROID_KEY_STORE);
            androidStore.load(null);

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
            final KeyStore keystore = KeyStore.getInstance(KeyStoreManager.ANDROID_KEY_STORE);
            keystore.load(null);

            return keystore.getCertificate(keyAlias);
        }
        catch (IOException | CertificateException | NoSuchAlgorithmException | KeyStoreException e) {
            throw new CouchbaseLiteException("Failed fetching identity: " + keyAlias, e);
        }
    }

    @Nullable
    @Override
    public String findAnonymousCertAlias() throws CouchbaseLiteException {
        try {
            final KeyStore keystore = KeyStore.getInstance(KeyStoreManager.ANDROID_KEY_STORE);

            final Enumeration<String> aliases = keystore.aliases();
            while (aliases.hasMoreElements()) {
                final String alias = aliases.nextElement();
                if (alias.startsWith(ANON_IDENTITY_ALIAS)) { return alias; }
            }
        }
        catch (KeyStoreException e) {
            throw new CouchbaseLiteException("Failed searching for a saved anonymous identity", e);
        }

        return null;
    }

    @VisibleForTesting
    @Override
    public int deleteEntries(Fn.Predicate<String> filter) throws CouchbaseLiteException {
        int deleted = 0;
        try {
            final KeyStore keystore = KeyStore.getInstance(KeyStoreManager.ANDROID_KEY_STORE);

            final Enumeration<String> aliases = keystore.aliases();
            while (aliases.hasMoreElements()) {
                final String alias = aliases.nextElement();
                if (filter.test(alias)) {
                    keystore.deleteEntry(alias);
                    deleted++;
                }
            }
        }
        catch (KeyStoreException e) {
            throw new CouchbaseLiteException("Failed searching for a saved anonymous identity", e);
        }
        return deleted;
    }

    @VisibleForTesting
    @Nullable
    public KeyPair generateRSAKeyPair(
        @NonNull String alias,
        boolean isServer,
        @NonNull KeySize keySize,
        @NonNull Map<CertAttribute, String> attributes,
        @NonNull Date expiration)
        throws CouchbaseLiteException {
        final String dn = attributes.get(CertAttribute.COMMON_NAME);
        if (dn == null) { throw new CouchbaseLiteException("Certificate must have a distinguished name (CN)"); }

        final HashMap<String, String> localAttributes = new HashMap<>();
        for (Map.Entry<CertAttribute, String> entry: attributes.entrySet()) {
            final CertAttribute key = entry.getKey();
            if (CertAttribute.COMMON_NAME.equals(key)) { continue; }
            localAttributes.put(key.getCode(), entry.getValue());
        }

        final Date now = new Date();
        final X500Principal subject = new X500Principal("CN=" + dn, localAttributes);
        final RSAKeyGenParameterSpec algorithmSpec
            = new RSAKeyGenParameterSpec(keySize.getBitLength(), RSAKeyGenParameterSpec.F4);

        try {
            final KeyPairGenerator keyFactory;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                keyFactory = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEY_STORE);
                keyFactory.initialize(new KeyPairGeneratorSpec.Builder(CouchbaseLiteInternal.getContext())
                    .setAlias(alias)
                    .setAlgorithmParameterSpec(algorithmSpec)
                    .setSerialNumber(BigInteger.ONE)
                    .setSubject(subject)
                    .setStartDate(now)
                    .setEndDate(expiration)
                    .build());
            }
            else {
                keyFactory = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEY_STORE);
                keyFactory.initialize(new KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                    //.setAlgorithmParameterSpec(algorithmSpec)
                    .setCertificateSerialNumber(BigInteger.ONE)
                    .setCertificateSubject(subject)
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setCertificateNotBefore(now)
                    .setCertificateNotAfter(expiration)
                    .setUserAuthenticationRequired(false)
                    .build());
            }

            return keyFactory.generateKeyPair();
        }
        catch (NoSuchAlgorithmException | NoSuchProviderException | InvalidAlgorithmParameterException ignore) { }

        return null;
    }
}
