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
package com.couchbase.lite;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;

import java.security.PrivateKey;
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
 * including a private key and X.509 certificate chain.  TLSIdentities are backed
 * by the canonical AndroidKeyStore and do not extract private key materials.
 * The TLSIdentity is used by URLEndpointListeners and by Replicator, to set up
 * certificate authenticated TLS communication.
 */
public final class TLSIdentity extends AbstractTLSIdentity {
    /**
     * Get a TLSIdentity backed by the information for the passed alias.
     *
     * @param alias the keystore alias for the identities entry.
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

        final PrivateKey key = getManager().getKey(null, alias, null);
        if (key == null) {
            Log.v(LogDomain.LISTENER, "No private key for: " + alias);
            return null;
        }

        final C4KeyPair keyPair = C4KeyPair.createKeyPair(
            null,
            alias,
            null,
            KeyStoreManager.KeyAlgorithm.RSA,
            KeyStoreManager.KeySize.getKeySize(((RSAKey) key).getModulus().bitLength()),
            null);

        return new TLSIdentity(alias, keyPair, certs);
    }

    /**
     * Create self-signed certificate and private key, store them in the canonical keystore,
     * and return a identity backed by the new entry.
     * The identity will be stored in the secure storage using the specified alias
     * and can be recovered using that alias, after this method returns.
     *
     * @param isServer   true if this is a server certificate
     * @param attributes certificate attributes
     * @param expiration expiration date
     * @param alias      alias used to identify the key/certificate entry, in the keystore
     * @return the new identity
     * @throws CouchbaseLiteException on failure to get identity
     */
    @NonNull
    public static TLSIdentity createIdentity(
        boolean isServer,
        @NonNull Map<String, String> attributes,
        @Nullable Date expiration,
        @NonNull String alias)
        throws CouchbaseLiteException {
        Preconditions.assertNotNull(attributes, "attributes");
        Preconditions.assertNotNull(alias, "alias");
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

    static TLSIdentity getAnonymousIdentity(@NonNull String alias) throws CouchbaseLiteException {
        final String fullAlias = KeyStoreManager.ANON_IDENTITY_ALIAS + alias;

        final KeyStoreManager keyStoreManager = KeyStoreManager.getInstance();
        if (keyStoreManager.findAlias(null, fullAlias)) { return getIdentity(fullAlias); }

        final Map<String, String> attributes = new HashMap<>();
        attributes.put(CERT_ATTRIBUTE_COMMON_NAME, KeyStoreManager.ANON_COMMON_NAME);

        keyStoreManager.createSelfSignedCertEntry(null, fullAlias, null, true, attributes, null);

        return getIdentity(fullAlias);
    }

    @VisibleForTesting
    static void deleteIdentity(@NonNull String alias) throws CouchbaseLiteException {
        getManager().deleteEntries(null, alias::equals);
    }


    private TLSIdentity(@NonNull String alias, @NonNull C4KeyPair keyPair, @NonNull List<Certificate> certificates) {
        super(alias, keyPair, certificates);
    }
}
