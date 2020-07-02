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

import java.io.IOException;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import com.couchbase.lite.CouchbaseLiteException;
import com.couchbase.lite.internal.core.C4KeyPair;
import com.couchbase.lite.internal.utils.PlatformUtils;
import com.couchbase.lite.internal.utils.Preconditions;


public class AbstractTLSIdentity {
    public enum CertAttribute {
        COMMON_NAME("CN"),
        PSEUDONYM("pseudonym"),
        GIVEN_NAME("GN"),
        SURNAME("SN"),
        ORGANIZATION("O"),
        ORGANIZATION_UNIT("OU"),
        POSTAL_ADDRESS("postalAddress"),
        LOCALITY("locality"),
        POSTAL_CODE("postalCode"),
        STATE_OR_PROVINCE("ST"),
        COUNTRY("C"),
        EMAIL_ADDRESS("rfc822Name"),
        HOSTNAME("dNSName"),
        URL("uniformResourceIdentifier"),
        IP_ADDRESS("iPAddress"),
        REGISTERED_ID("registeredID");

        final String code;

        CertAttribute(String code) { this.code = code; }

        public String getCode() { return code; }
    }

    //  !!! Temporary hack...
    protected static List<Certificate> readCerts() {
        final List<Certificate> certs = new ArrayList<>();
        try {
            final KeyStore keystore = KeyStore.getInstance("PKCS12");
            keystore.load(PlatformUtils.getAsset("certs.p12"), "123".toCharArray());
            // Android has a funny idea of the alias name...
            certs.add(keystore.getCertificate(keystore.aliases().nextElement()));
        }
        catch (KeyStoreException | CertificateException | NoSuchAlgorithmException | IOException ignore) { }

        return certs;
    }

    // !!! Temporary hack...
    @NonNull
    protected static C4KeyPair getKeys() throws CouchbaseLiteException {
        final Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.YEAR, 1);
        final KeyPair keys = CouchbaseLiteInternal.getKeyManager().generateKeyPair(
            "identity",
            KeyManager.KeyAlgorithm.RSA,
            KeyManager.KeySize.BIT_2048,
            BigInteger.TEN,
            calendar.getTime());
        if (keys == null) { throw new CouchbaseLiteException("Could not create key pair"); }
        return C4KeyPair.createKeyPair(keys, KeyManager.KeyAlgorithm.RSA);
    }


    @NonNull
    private final List<Certificate> certs;

    @NonNull
    private final C4KeyPair keys;

    @NonNull
    private final Date expiration;

    @VisibleForTesting
    protected AbstractTLSIdentity() throws CouchbaseLiteException {
        this(readCerts(), getKeys(), new Date(2020, 12, 30));
    }

    protected AbstractTLSIdentity(@NonNull List<Certificate> certs, @NonNull C4KeyPair keys, @NonNull Date expiration) {
        this.certs = Preconditions.assertNotEmpty(certs, "certificates");
        this.keys = Preconditions.assertNotNull(keys, "key pair");
        this.expiration = Preconditions.assertNotNull(expiration, "expiration");
    }

    @NonNull
    public List<Certificate> getCerts() { return certs; }

    @NonNull
    public Certificate getCert() { return certs.get(0); }

    @NonNull
    public C4KeyPair getKeyPair() { return keys; }

    @NonNull
    public Date getExpiration() { return new Date(expiration.getTime()); }
}
