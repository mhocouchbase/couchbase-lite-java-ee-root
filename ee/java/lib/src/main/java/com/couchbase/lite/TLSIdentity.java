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
package com.couchbase.lite;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;

import java.io.IOException;
import java.security.Key;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.interfaces.RSAPrivateKey;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.couchbase.lite.internal.AbstractTLSIdentity;
import com.couchbase.lite.internal.KeyStoreManager;
import com.couchbase.lite.internal.KeyStoreManager.KeyAlgorithm;
import com.couchbase.lite.internal.KeyStoreManager.KeySize;
import com.couchbase.lite.internal.core.C4KeyPair;


public final class TLSIdentity extends AbstractTLSIdentity {
    private static final AtomicReference<KeyStore> INTERNAL_KEY_STORE = new AtomicReference<>();

    @Nullable
    public static TLSIdentity getIdentity(
        @NonNull KeyStore keyStore,
        @NonNull String alias,
        @Nullable char[] keyPassword)
        throws CouchbaseLiteException {
        try {
            if (!KeyStoreManager.getInstance().findAlias(keyStore, alias)) { return null; }

            final Certificate[] certs = keyStore.getCertificateChain(alias);
            if (certs == null) { return null; }

            final Key key = keyStore.getKey(alias, keyPassword);
            if (key == null) { return null; }

            if (!(key instanceof RSAPrivateKey)) {
                throw new IllegalArgumentException(
                    "Unsupported key type : " + key.getAlgorithm());
            }

            final RSAPrivateKey privateKey = (RSAPrivateKey) key;
            final KeySize keySize = KeySize.getKeySize(privateKey.getModulus().bitLength());
            final C4KeyPair keyPair = C4KeyPair.createKeyPair(
                keyStore,
                alias,
                keyPassword,
                KeyAlgorithm.RSA,
                keySize,
                null);

            return new TLSIdentity(keyStore, alias, keyPair, Arrays.asList(certs));
        }
        catch (KeyStoreException | NoSuchAlgorithmException | UnrecoverableKeyException e) {
            throw new CouchbaseLiteException(
                "Could not get key from the KeyStore",
                e, CBLError.Domain.CBLITE,
                CBLError.Code.CRYPTO, null);
        }
    }

    @NonNull
    public static TLSIdentity createIdentity(
        boolean isServer,
        @NonNull Map<String, String> attributes,
        @Nullable Date expiration,
        @NonNull KeyStore keyStore,
        @NonNull String alias,
        @Nullable char[] keyPassword)
        throws CouchbaseLiteException {
        // Create identity:
        KeyStoreManager.getInstance().createSelfSignedCertEntry(
            keyStore, alias, keyPassword, isServer, attributes, expiration);
        // Get identity from KeyStore to return:
        final TLSIdentity identity = getIdentity(keyStore, alias, keyPassword);
        if (identity == null) {
            throw new CouchbaseLiteException(
                "Could not get the created identity from the KeyStore",
                CBLError.Domain.CBLITE,
                CBLError.Code.CRYPTO);
        }
        return identity;
    }

    static TLSIdentity getAnonymousIdentity(@NonNull String alias) throws CouchbaseLiteException {
        final String fullAlias = KeyStoreManager.ANON_IDENTITY_ALIAS + "-" + alias;
        final KeyStoreManager keyStoreManager = KeyStoreManager.getInstance();
        final KeyStore keyStore = getInternalKeyStore();
        if (!keyStoreManager.findAlias(null, fullAlias)) {
            final Map<String, String> attributes = new HashMap<>();
            attributes.put(KeyStoreManager.CERT_ATTRIBUTE_COMMON_NAME, KeyStoreManager.ANON_COMMON_NAME);

            keyStoreManager.createSelfSignedCertEntry(null, fullAlias, null, true, attributes, null);
        }
        return getIdentity(keyStore, fullAlias, null);
    }

    private static KeyStore getInternalKeyStore() {
        KeyStore keyStore = INTERNAL_KEY_STORE.get();
        if (keyStore != null) { return keyStore; }

        try {
            keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            if (INTERNAL_KEY_STORE.compareAndSet(null, keyStore)) { keyStore.load(null); }
        }
        catch (IOException | CertificateException | NoSuchAlgorithmException | KeyStoreException e) {
            throw new IllegalStateException("Cannot create an internal KeyStore", e);
        }

        return keyStore;
    }


    @VisibleForTesting
    TLSIdentity() { }

    private TLSIdentity(
        @Nullable KeyStore keyStore,
        @NonNull String keyAlias,
        @NonNull C4KeyPair keyPair,
        @NonNull List<Certificate> certificates) {
        super(keyStore, keyAlias, keyPair, certificates);
    }

    @NonNull
    Certificate getCert() { return getCerts().get(0); }

    @NonNull
    String getAlias() { return getKeyAlias(); }
}

