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
package com.couchbase.lite.internal;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.List;

import com.couchbase.lite.internal.core.C4KeyPair;
import com.couchbase.lite.internal.utils.Preconditions;


@SuppressWarnings("PMD.AbstractClassWithoutAbstractMethod")
public abstract class AbstractTLSIdentity {
    public static final String CERT_ATTRIBUTE_COMMON_NAME = "CN";
    public static final String CERT_ATTRIBUTE_PSEUDONYM = "pseudonym";
    public static final String CERT_ATTRIBUTE_GIVEN_NAME = "GN";
    public static final String CERT_ATTRIBUTE_SURNAME = "SN";
    public static final String CERT_ATTRIBUTE_ORGANIZATION = "O";
    public static final String CERT_ATTRIBUTE_ORGANIZATION_UNIT = "OU";
    public static final String CERT_ATTRIBUTE_POSTAL_ADDRESS = "postalAddress";
    public static final String CERT_ATTRIBUTE_LOCALITY = "locality";
    public static final String CERT_ATTRIBUTE_POSTAL_CODE = "postalCode";
    public static final String CERT_ATTRIBUTE_STATE_OR_PROVINCE = "ST";
    public static final String CERT_ATTRIBUTE_COUNTRY = "C";
    public static final String CERT_ATTRIBUTE_EMAIL_ADDRESS = "rfc822Name";
    public static final String CERT_ATTRIBUTE_HOSTNAME = "dNSName";
    public static final String CERT_ATTRIBUTE_URL = "uniformResourceIdentifier";
    public static final String CERT_ATTRIBUTE_IP_ADDRESS = "iPAddress";
    public static final String CERT_ATTRIBUTE_REGISTERED_ID = "registeredID";

    @Nullable
    public static String getAlias(@Nullable AbstractTLSIdentity identity) {
        return (identity == null) ? null : identity.getAlias();
    }

    @Nullable
    public static Certificate getCert(@Nullable AbstractTLSIdentity identity) {
        return (identity == null) ? null : identity.getCert();
    }

    protected static KeyStoreManager getManager() { return KeyStoreManager.getInstance(); }


    @NonNull
    private final String keyAlias;
    @NonNull
    private final List<Certificate> certificates;
    @NonNull
    private final C4KeyPair keyPair;

    protected AbstractTLSIdentity(
        @NonNull String keyAlias,
        @NonNull C4KeyPair keyPair,
        @NonNull List<Certificate> certificates) {
        this.keyAlias = Preconditions.assertNotNull(keyAlias, "key alias");
        this.keyPair = Preconditions.assertNotNull(keyPair, "key pair");
        this.certificates = Preconditions.assertNotEmpty(certificates, "cert chain");
    }

    @NonNull
    String getAlias() { return keyAlias; }

    @NonNull
    public C4KeyPair getKeyPair() { return keyPair; }

    @NonNull
    Certificate getCert() { return certificates.get(0); }

    @NonNull
    public List<Certificate> getCerts() { return certificates; }

    @NonNull
    public Date getExpiration() { return ((X509Certificate) getCert()).getNotAfter(); }
}
