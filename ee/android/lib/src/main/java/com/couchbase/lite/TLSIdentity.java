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

import java.io.InputStream;
import java.security.cert.Certificate;
import java.security.interfaces.RSAKey;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
     * Get the TLSIdentity of the given label from the secure storage.
     *
     * @param alias the keystore alias for the identities key.
     * @return the identity
     * @throws CouchbaseLiteException on failure to get identity
     */
    @Nullable
    public static TLSIdentity getIdentity(@NonNull String alias) throws CouchbaseLiteException {
        Preconditions.assertNotNull(alias, "alias");

        final List<Certificate> certs = getManager().getCertificateChain(null, alias);
        if (certs == null) {
            Log.v(LogDomain.LISTENER, "No cert chain for: " + alias);
            return null;
        }

        final RSAKey key = getManager().getKey(null, alias, null);
        if (key == null) {
            Log.v(LogDomain.LISTENER, "No private key for: " + alias);
            return null;
        }

        final C4KeyPair keyPair = C4KeyPair.createKeyPair(
            null,
            alias,
            null,
            KeyStoreManager.KeyAlgorithm.RSA,
            KeyStoreManager.KeySize.getKeySize(key.getModulus().bitLength()),
            null);

        return new TLSIdentity(alias, keyPair, certs);
    }

    /**
     * Create and store a client self-signed identity in a secure storage.
     * The identity will be stored in the secure storage using the given label.
     *
     * @param isServer   true if this is a server certificate
     * @param attributes Certificate attributes
     * @param expiration Expiration date
     * @param alias      Alias to
     * @return the new identity
     * @throws CouchbaseLiteException on failure to get identity
     */
    @NonNull
    public static TLSIdentity createIdentity(
        @NonNull String alias,
        boolean isServer,
        @NonNull Map<String, String> attributes,
        @Nullable Date expiration)
        throws CouchbaseLiteException {
        Preconditions.assertNotNull(alias, "alias");
        Preconditions.assertNotNull(attributes, "attributes");
        KeyStoreManager.checkAlias(alias);

        getManager().createSelfSignedCertEntry(null, alias, null, isServer, attributes, expiration);

        final TLSIdentity identity = getIdentity(alias);
        if (identity == null) {
            throw new CouchbaseLiteException(
                "Could not find new identity in the KeyStore",
                CBLError.Domain.CBLITE,
                CBLError.Code.CRYPTO);
        }

        return identity;
    }

    /**
     * Import a key pair into secure storage and create an TLSIdentity from the import.
     *
     * @param extType      KeyStore type, eg: "PKCS12"
     * @param extStore     An InputStream from the keystore
     * @param extStorePass The keystore password
     * @param extAlias     The alias, in the external keystore, of the entry to be used.
     * @param extKeyPass   The key password
     * @param alias        The alias for the imported key
     * @return a TLSIdentity
     * @throws CouchbaseLiteException on error
     */
    @NonNull
    public static TLSIdentity importIdentity(
        @NonNull String extType,
        @NonNull InputStream extStore,
        @Nullable char[] extStorePass,
        @NonNull String extAlias,
        @Nullable char[] extKeyPass,
        @NonNull String alias)
        throws CouchbaseLiteException {
        Preconditions.assertNotNull(extType, "extType");
        Preconditions.assertNotNull(extStore, "extStore");
        Preconditions.assertNotNull(extAlias, "extAlias");
        Preconditions.assertNotNull(alias, "alias");
        KeyStoreManager.checkAlias(alias);

        getManager().importEntry(extType, extStore, extStorePass, extAlias, extKeyPass, alias);

        final TLSIdentity identity = getIdentity(alias);
        if (identity == null) {
            throw new CouchbaseLiteException(
                "Could not find imported identity in the KeyStore",
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

    static TLSIdentity getAnonymousIdentity(@NonNull String alias) throws CouchbaseLiteException {
        final String fullAlias = KeyStoreManager.ANON_IDENTITY_ALIAS + alias;

        final KeyStoreManager keyStoreManager = KeyStoreManager.getInstance();
        if (keyStoreManager.findAlias(null, fullAlias)) { return getIdentity(fullAlias); }

        final Map<String, String> attributes = new HashMap<>();
        attributes.put(URLEndpointListener.CERT_ATTRIBUTE_COMMON_NAME, KeyStoreManager.ANON_COMMON_NAME);

        keyStoreManager.createSelfSignedCertEntry(null, fullAlias, null, true, attributes, null);

        return getIdentity(fullAlias);
    }


    @VisibleForTesting
    TLSIdentity() { }

    private TLSIdentity(@NonNull String alias, @NonNull C4KeyPair keyPair, @NonNull List<Certificate> certificates) {
        super(alias, keyPair, certificates);
    }
}
