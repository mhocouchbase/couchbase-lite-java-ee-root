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
import android.support.annotation.VisibleForTesting;

import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAKey;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import com.couchbase.lite.CouchbaseLiteException;
import com.couchbase.lite.LogDomain;
import com.couchbase.lite.internal.core.C4KeyPair;
import com.couchbase.lite.internal.support.Log;
import com.couchbase.lite.internal.utils.Preconditions;
import com.couchbase.lite.internal.utils.StringUtils;


@SuppressWarnings("PMD.AbstractClassWithoutAbstractMethod")
public abstract class AbstractTLSIdentity {
    public static final String ANON_IDENTITY_ALIAS = "CBL-ANON";

    protected static final KeyStoreManager KEY_STORE_MANAGER = new KeyStoreManager();

    @NonNull
    public static String createAnonymousServerCertificate() throws CouchbaseLiteException {
        final String alias = StringUtils.getUniqueName(ANON_IDENTITY_ALIAS, 8);

        final Map<KeyStoreManager.CertAttribute, String> attributes = new HashMap<>();
        attributes.put(KeyStoreManager.CertAttribute.COMMON_NAME, "Couchbase Lite");
        attributes.put(KeyStoreManager.CertAttribute.ORGANIZATION, "Couchbase");
        attributes.put(KeyStoreManager.CertAttribute.ORGANIZATION_UNIT, "Mobile");
        attributes.put(KeyStoreManager.CertAttribute.EMAIL_ADDRESS, "lite@couchbase.com");

        final Calendar expiration = Calendar.getInstance();
        expiration.add(Calendar.YEAR, 3);

        KEY_STORE_MANAGER.createCertEntry(alias, true, attributes, expiration.getTime());

        return alias;
    }

    @NonNull
    private final String keyAlias;
    @NonNull
    private final List<Certificate> certificates;
    @NonNull
    private final C4KeyPair keyPair;

    @VisibleForTesting
    @SuppressWarnings("ConstantConditions")
    @SuppressFBWarnings("NP_STORE_INTO_NONNULL_FIELD")
    protected AbstractTLSIdentity() {
        keyAlias = "test";
        certificates = new ArrayList<>();
        keyPair = null;
    }

    protected AbstractTLSIdentity(@NonNull String keyAlias, @NonNull List<Certificate> certificates)
        throws CouchbaseLiteException {
        this.keyAlias = keyAlias;
        this.certificates = Preconditions.assertNotEmpty(certificates, "certificates");
        final PublicKey key = certificates.get(0).getPublicKey();
        Log.d(LogDomain.LISTENER, "creating identity for key algorithm: " + key.getAlgorithm());
        this.keyPair = C4KeyPair.createKeyPair(
            keyAlias,
            KeyStoreManager.KeyAlgorithm.RSA,
            (KeyStoreManager.KeySize.getKeySize(((RSAKey) key).getModulus().bitLength())));
    }

    @NonNull
    public List<Certificate> getCerts() { return certificates; }

    @NonNull
    public Certificate getCert() { return certificates.get(0); }

    @NonNull
    public C4KeyPair getKeyPair() { return keyPair; }

    @NonNull
    public Date getExpiration() { return ((X509Certificate) certificates.get(0)).getNotAfter(); }

    @NonNull
    public String getAlias() { return keyAlias; }
}
