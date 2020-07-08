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
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.Date;
import java.util.List;
import java.util.Map;

import com.couchbase.lite.internal.AbstractTLSIdentity;
import com.couchbase.lite.internal.core.C4KeyPair;


public final class TLSIdentity extends AbstractTLSIdentity {
    @NonNull
    public static TLSIdentity getIdentity(@NonNull String alias, @Nullable char[] keyPassword)
        throws CouchbaseLiteException {
        return new TLSIdentity();
    }

    @NonNull
    public static TLSIdentity getIdentity(
        @NonNull KeyStore.PrivateKeyEntry privateKey,
        @NonNull List<Certificate> certificate)
        throws CouchbaseLiteException {
        return new TLSIdentity();
    }

    @NonNull
    public static TLSIdentity createIdentity(
        boolean isServer,
        @NonNull Map<AbstractTLSIdentity.CertAttribute, String> attributes,
        @Nullable Date expiration,
        @NonNull String alias,
        @Nullable char[] keyPassword)
        throws CouchbaseLiteException {
        return new TLSIdentity();
    }

    @NonNull
    public static TLSIdentity importIdentity(
        @NonNull InputStream data,
        @NonNull String dataType,
        @Nullable char[] dataPassword,
        @NonNull String alias,
        @Nullable char[] keyPassword)
        throws CouchbaseLiteException {
        return new TLSIdentity();
    }

    public static void deleteIdentity(@NonNull String alias) throws CouchbaseLiteException { }


    @VisibleForTesting
    TLSIdentity() throws CouchbaseLiteException { super(); }

    TLSIdentity(@NonNull List<Certificate> certs, @NonNull C4KeyPair keys, @NonNull Date expiration) {
        super(certs, keys, expiration);
    }
}

