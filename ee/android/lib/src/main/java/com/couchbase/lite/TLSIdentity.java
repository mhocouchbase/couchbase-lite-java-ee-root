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
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.couchbase.lite.internal.AbstractTLSIdentity;
import com.couchbase.lite.internal.KeyStoreManager;
import com.couchbase.lite.internal.utils.Preconditions;


public final class TLSIdentity extends AbstractTLSIdentity {
    /**
     * Get the TLSIdentity of the given label from the secure storage.
     *
     * @param alias the keystore alias for the identities key.
     * @return the identity
     * @throws CouchbaseLiteException on failure to get identity
     */
    @NonNull
    public static TLSIdentity getIdentity(@NonNull String alias) throws CouchbaseLiteException {
        final List<Certificate> certs = new ArrayList<>();
        certs.add(KeyStoreManager.getInstance().getCertificate(null, alias, null));
        return new TLSIdentity(alias, certs);
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
        final String idAlias = Preconditions.assertNotNull(alias, "alias");
        if (idAlias.startsWith(KeyStoreManager.ANON_IDENTITY_ALIAS)) {
            throw new CouchbaseLiteException(
                "Attempt to use reserved identity prefix " + KeyStoreManager.ANON_IDENTITY_ALIAS);
        }
        KeyStoreManager.getInstance().createSelfSignedCertEntry(null, alias, null, isServer, attributes, expiration);
        return getIdentity(alias);
    }

    /**
     * Import a key pair into secure storage and create an TLSIdentity from the import.
     *
     * @param extType      KeyStore type, eg: "PKCS12"
     * @param extStore     An InputStream from the keystore
     * @param extStorePass The keystore password
     * @param alias        The alias, in the external keystore, of the entry to be used.  This will be the alias for
     *                     the entry in the Android keystore, too
     * @param keyPass      The key password
     * @return a TLSIdentity
     * @throws CouchbaseLiteException on error
     */
    @NonNull
    public static TLSIdentity importIdentity(
        @NonNull String extType,
        @NonNull InputStream extStore,
        @Nullable char[] extStorePass,
        @NonNull String alias,
        @Nullable char[] keyPass)
        throws CouchbaseLiteException {
        KeyStoreManager.getInstance().importEntry(extType, extStore, extStorePass, alias, keyPass, alias);
        return getIdentity(alias);
    }

    /**
     * @throws CouchbaseLiteException on error
     */
    @NonNull
    public static TLSIdentity deleteIdentity(@NonNull String alias)
        throws CouchbaseLiteException {
        KeyStoreManager.getInstance().deleteEntries(null, alias::equals);
        return getIdentity(alias);
    }

    static TLSIdentity getAnonymousIdentity(@NonNull String alias) throws CouchbaseLiteException {
        final String fullAlias = KeyStoreManager.ANON_IDENTITY_ALIAS + alias;
        final KeyStoreManager keyStoreManager = KeyStoreManager.getInstance();
        if (!keyStoreManager.findAlias(null, fullAlias)) {
            final Map<String, String> attributes = new HashMap<>();
            attributes.put(KeyStoreManager.CERT_ATTRIBUTE_COMMON_NAME, KeyStoreManager.ANON_COMMON_NAME);

            keyStoreManager.createSelfSignedCertEntry(null, fullAlias, null, true, attributes, null);
        }
        return getIdentity(fullAlias);
    }


    @VisibleForTesting
    TLSIdentity() { }

    @NonNull
    Certificate getCert() { return getCerts().get(0); }

    @NonNull
    String getAlias() { return getKeyAlias(); }

    private TLSIdentity(@NonNull String alias, @NonNull List<Certificate> certificates) throws CouchbaseLiteException {
        super(alias, certificates);
    }
}
