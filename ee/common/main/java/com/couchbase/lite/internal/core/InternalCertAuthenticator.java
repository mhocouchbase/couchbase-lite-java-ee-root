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

import java.security.cert.Certificate;
import java.util.List;

import com.couchbase.lite.ListenerAuthenticator;
import com.couchbase.lite.ListenerCertificateAuthenticatorDelegate;


/**
 * A Listener Certificate Authenticator Delegate
 */
public class InternalCertAuthenticator implements ListenerAuthenticator {

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
    List<Certificate> getRootCerts() { return rootCerts; }
}
