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

import java.io.IOException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import com.couchbase.lite.internal.AbstractTLSIdentity;
import com.couchbase.lite.internal.KeyStoreManager;
import com.couchbase.lite.internal.support.Log;
import com.couchbase.lite.internal.utils.PlatformUtils;


public final class TLSIdentity extends AbstractTLSIdentity {

    @NonNull
    public static TLSIdentity getIdentity(@NonNull String alias, @Nullable char[] keyPassword)
        throws CouchbaseLiteException {
        return new TLSIdentity();
    }

    @Nullable
    public static TLSIdentity getIdentity(
        @NonNull KeyStore keyStore,
        @NonNull String alias,
        @Nullable char[] keyPassword) {
        return null;
    }

    @NonNull
    public static TLSIdentity createIdentity(
        @NonNull String alias,
        boolean isServer,
        @NonNull Map<KeyStoreManager.CertAttribute, String> attributes,
        @NonNull Date expiration)
        throws CouchbaseLiteException {
        return new TLSIdentity();
    }

    public static void deleteIdentity(@NonNull String alias) throws CouchbaseLiteException { }

    @Nullable
    static TLSIdentity getSavedAnonymousIdentity() { return null; }

    @NonNull
    static TLSIdentity createAnonymousServerIdentity() throws CouchbaseLiteException { return new TLSIdentity(); }


    public TLSIdentity() throws CouchbaseLiteException { super("couchbase", loadCerts()); }

    // !!! DELETE ME
    @NonNull
    private static List<Certificate> loadCerts() {
        final List<Certificate> certs = new ArrayList<>();
        try {
            final KeyStore keystore = KeyStore.getInstance("PKCS12");
            keystore.load(PlatformUtils.getAsset("teststore.jks"), "password".toCharArray());
            certs.add(keystore.getCertificate("couchbase"));
        }
        catch (KeyStoreException | CertificateException | NoSuchAlgorithmException | IOException e) {
            Log.d(LogDomain.LISTENER, "can't load keystore", e);
        }

        return certs;
    }
}

