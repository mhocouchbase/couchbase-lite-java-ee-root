//
// Copyright (c) 2017 Couchbase, Inc All rights reserved.
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
package com.couchbase.lite.internal.core;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.util.Collection;
import java.util.List;

import com.couchbase.lite.CouchbaseLiteException;
import com.couchbase.lite.ListenerAuthenticator;
import com.couchbase.lite.ListenerCertificateAuthenticatorDelegate;
import com.couchbase.lite.internal.utils.PlatformUtils;


/**
 * A Listener Certificate Authenticator Delegate
 */
public class InternalCertAuthenticator implements ListenerAuthenticator {
    public static final String BEGIN_CERT = "-----BEGIN CERTIFICATE-----";
    public static final String END_CERT = "-----END CERTIFICATE-----";

    //-------------------------------------------------------------------------
    // Fields
    //-------------------------------------------------------------------------

    @Nullable
    private final List<Certificate> rootCerts;

    @Nullable
    private final ListenerCertificateAuthenticatorDelegate delegate;

    //-------------------------------------------------------------------------
    // Constructor
    //-------------------------------------------------------------------------

    public InternalCertAuthenticator(
        @Nullable List<Certificate> rootCerts,
        @Nullable ListenerCertificateAuthenticatorDelegate delegate) {
        this.rootCerts = rootCerts;
        this.delegate = delegate;
    }

    //-------------------------------------------------------------------------
    // Delegate
    //-------------------------------------------------------------------------

    boolean authenticate(@NonNull List<Certificate> certs) {
        if (delegate != null) { return delegate.authenticate(certs); }
        throw new IllegalStateException("No delegate has been set");
    }

    @Nullable
    byte[] getRootCerts() throws CouchbaseLiteException {
        if (rootCerts == null) { return null; }

        try { return encodeCertificateChain(rootCerts); }
        catch (CertificateEncodingException e) {
            throw new CouchbaseLiteException("Failed to encode certificates in PEM format", e);
        }
    }

    @NonNull
    private byte[] encodeCertificateChain(@NonNull Collection<? extends Certificate> certChain)
        throws CertificateEncodingException {
        final PlatformUtils.Base64Encoder encoder = PlatformUtils.getEncoder();
        try (
            ByteArrayOutputStream encodedCerts = new ByteArrayOutputStream();
            PrintStream ps = new PrintStream(encodedCerts, false, "UTF-8")
        ) {
            for (Certificate cert: certChain) { encodeCertificate(encoder, cert, ps); }
            ps.flush();
            return encodedCerts.toByteArray();
        }
        catch (IOException e) {
            throw new CertificateEncodingException("I/O error during encoding", e);
        }
    }

    private void encodeCertificate(
        @NonNull PlatformUtils.Base64Encoder encoder,
        @NonNull Certificate cert,
        @NonNull PrintStream ps)
        throws CertificateEncodingException {
        final String encodedCert = encoder.encodeToString(cert.getEncoded());
        if (encodedCert == null) { return; }

        final int n = encodedCert.length();
        ps.println();
        ps.println(BEGIN_CERT);
        for (int i = 0; i < n; i += 64) { ps.println(encodedCert.substring(i, Math.min(n, i + 64))); }
        ps.println(END_CERT);
    }
}
