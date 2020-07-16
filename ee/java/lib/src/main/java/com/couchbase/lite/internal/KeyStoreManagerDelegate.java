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

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import java.io.InputStream;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.Date;
import java.util.Map;

import com.couchbase.lite.CouchbaseLiteException;
import com.couchbase.lite.internal.utils.Fn;


public class KeyStoreManagerDelegate extends KeyStoreManager {
    @Nullable
    @Override
    public byte[] getKeyData(@Nullable KeyStore keyStore, @NonNull String keyAlias, @Nullable char[] keyPassword) {
        return null;
    }

    @Nullable
    @Override
    public byte[] decrypt(
        @Nullable KeyStore keyStore, @NonNull String keyAlias, @Nullable char[] keyPassword, @NonNull byte[] data) {
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
        return new byte[0];
    }

    @Override
    public void free(@Nullable KeyStore keyStore, @NonNull String keyAlias, @Nullable char[] keyPassword) {
    }

    @Nullable
    @Override
    public String createCertEntry(
        @NonNull String alias,
        boolean isServer,
        @NonNull Map<CertAttribute, String> attributes,
        @NonNull Date expiration) throws CouchbaseLiteException {
        return null;
    }

    @Override
    public void importEntry(
        @NonNull String type,
        @NonNull InputStream stream,
        @Nullable char[] storePassword,
        @NonNull String alias,
        @Nullable char[] keyPassword,
        @NonNull String targetAlias) throws CouchbaseLiteException {
    }

    @Nullable
    @Override
    public Certificate getCertificate(
        @Nullable KeyStore keyStore, @NonNull String keyAlias, @Nullable char[] keyPassword)
        throws CouchbaseLiteException {
        return null;
    }

    @Nullable
    @Override
    public String findAnonymousCertAlias() throws CouchbaseLiteException {
        return null;
    }

    @Override
    public int deleteEntries(Fn.Predicate<String> filter) throws CouchbaseLiteException {
        return 0;
    }

    @Nullable
    @Override
    public KeyPair generateRSAKeyPair(
        @NonNull String alias,
        boolean isServer,
        @NonNull KeySize keySize,
        @NonNull Map<CertAttribute, String> attributes,
        @NonNull Date expiration) throws CouchbaseLiteException {
        return null;
    }
}
