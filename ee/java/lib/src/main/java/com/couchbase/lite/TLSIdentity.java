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
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.interfaces.RSAKey;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.couchbase.lite.internal.AbstractTLSIdentity;
import com.couchbase.lite.internal.KeyStoreManager;
import com.couchbase.lite.internal.core.C4KeyPair;
import com.couchbase.lite.internal.support.Log;
import com.couchbase.lite.internal.utils.Preconditions;


/**
 * <b>ENTERPRISE EDITION API</b><br><br>
 * <p>
 * TLSIdentity provides the identity information obtained from the given KeyStore,
 * including a private key and X.509 certificate chain. Please note that the private key
 * data will be not extracted out of the KeyStore. The TLSIdentity is used by
 * URLEndpointListener to setup the TLS communication or by the Replicator to setup
 * the client certificate authentication.
 */
public final class TLSIdentity extends AbstractTLSIdentity {
    /**
     * Get a TLSIdentity object from the give KeyStore, key alias, and key password.
     * The KeyStore must contain the private key along with the certificate chain at
     * the given key alias and password, otherwise null will be returned.
     *
     * @param keyStore    KeyStore
     * @param alias       key alias
     * @param keyPassword key password if available
     * @return A TLSIdentity object.
     * @throws CouchbaseLiteException on error
     */
    @Nullable
    public static TLSIdentity getIdentity(
        @NonNull KeyStore keyStore,
        @NonNull String alias,
        @Nullable char[] keyPassword)
        throws CouchbaseLiteException {
        Preconditions.assertNotNull(keyStore, "keyStore");
        Preconditions.assertNotNull(alias, "alias");
        final List<Certificate> certs = getManager().getCertificateChain(keyStore, alias);
        if (certs == null) {
            Log.v(LogDomain.LISTENER, "No cert chain for: " + alias);
            return null;
        }

        // JDK-8236671:
        if (keyPassword == null) { keyPassword = new char[0]; }

        final RSAKey key = getManager().getKey(keyStore, alias, keyPassword);
        if (key == null) {
            Log.v(LogDomain.LISTENER, "No private key for: " + alias);
            return null;
        }

        final C4KeyPair keyPair = C4KeyPair.createKeyPair(
            keyStore,
            alias,
            keyPassword,
            KeyStoreManager.KeyAlgorithm.RSA,
            KeyStoreManager.KeySize.getKeySize(key.getModulus().bitLength()),
            null);

        return new TLSIdentity(alias, keyPair, certs);
    }

    /**
     * Create a self-signed certificate TLSIdentity object. The generated private key
     * will be stored in the KeyStore along with its self-signed certificate.
     *
     * @param isServer    The flag indicating that the certificate is for server or client.
     * @param attributes  The certificate attributes.
     * @param expiration  The certificate expiration date.
     * @param keyStore    The KeyStore object for storing the generated private key and certificate.
     * @param alias       The key alias for storing the generated private key and certificate.
     * @param keyPassword The password to protect the private key entry in the KeyStore.
     * @return A TLSIdentity object.
     * @throws CouchbaseLiteException on failure
     */
    @NonNull
    public static TLSIdentity createIdentity(
        boolean isServer,
        @NonNull Map<String, String> attributes,
        @Nullable Date expiration,
        @NonNull KeyStore keyStore,
        @NonNull String alias,
        @Nullable char[] keyPassword)
        throws CouchbaseLiteException {
        Preconditions.assertNotNull(keyStore, "keyStore");
        Preconditions.assertNotNull(alias, "alias");
        Preconditions.assertNotNull(attributes, "attributes");
        // JDK-8236671:
        if (keyPassword == null) { keyPassword = new char[0]; }

        // Create identity:
        getManager().createSelfSignedCertEntry(keyStore, alias, keyPassword, isServer, attributes, expiration);

        // Get identity from KeyStore to return:
        final TLSIdentity identity = getIdentity(keyStore, alias, keyPassword);
        if (identity == null) {
            throw new CouchbaseLiteException(
                "Could not find new identity in the KeyStore",
                CBLError.Domain.CBLITE,
                CBLError.Code.CRYPTO);
        }

        return identity;
    }

    /**
     * Delete an identity.
     *
     * @param alias the identity to delete
     * @throws CouchbaseLiteException on failure
     */
    public static void deleteIdentity(@NonNull String alias) throws CouchbaseLiteException {
        getManager().deleteEntries(null, alias::equals);
    }

    /**
     * Default KeyStore for storing anonymous identity.
     */
    private static final AtomicReference<KeyStore> DEFAULT_KEY_STORE = new AtomicReference<>();

    private static KeyStore getDefaultKeyStore() throws
        KeyStoreException, CertificateException, NoSuchAlgorithmException, IOException {
        KeyStore keyStore = DEFAULT_KEY_STORE.get();
        if (keyStore != null) { return keyStore; }

        keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null);

        DEFAULT_KEY_STORE.compareAndSet(null, keyStore);
        return DEFAULT_KEY_STORE.get();
    }

    static TLSIdentity getAnonymousIdentity(@NonNull String alias) throws CouchbaseLiteException {
        final KeyStore keyStore;
        try {
            keyStore = getDefaultKeyStore();
        }
        catch (KeyStoreException | CertificateException | NoSuchAlgorithmException | IOException e) {
            throw new IllegalStateException("Cannot get default KeyStore", e);
        }

        final String fullAlias = KeyStoreManager.ANON_IDENTITY_ALIAS + alias;
        if (getManager().findAlias(keyStore, fullAlias)) { getIdentity(keyStore, fullAlias, null); }

        final Map<String, String> attributes = new HashMap<>();
        attributes.put(URLEndpointListener.CERT_ATTRIBUTE_COMMON_NAME, KeyStoreManager.ANON_COMMON_NAME);
        getManager().createSelfSignedCertEntry(keyStore, fullAlias, null, true, attributes, null);

        return createIdentity(true, attributes, null, keyStore, fullAlias, null);
    }

    @VisibleForTesting
    TLSIdentity() { }

    private TLSIdentity(
        @NonNull String keyAlias,
        @NonNull C4KeyPair keyPair,
        @NonNull List<Certificate> certificates) {
        super(keyAlias, keyPair, certificates);
    }
}

