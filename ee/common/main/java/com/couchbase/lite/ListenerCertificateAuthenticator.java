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
package com.couchbase.lite;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import java.security.cert.Certificate;
import java.util.List;


/**
 * A Listener Certificate Authenticator Delegate
 */
public class ListenerCertificateAuthenticator
    implements ListenerAuthenticator, ListenerCertificateAuthenticatorDelegate {

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

    public ListenerCertificateAuthenticator(@NonNull List<Certificate> rootCerts) {
        this.rootCerts = rootCerts;
        this.delegate = null;
    }

    public ListenerCertificateAuthenticator(@NonNull ListenerCertificateAuthenticatorDelegate delegate) {
        this.rootCerts = null;
        this.delegate = delegate;
    }

    //-------------------------------------------------------------------------
    // Delegate
    //-------------------------------------------------------------------------

    @Override
    public boolean authenticate(@NonNull List<Certificate> certs) {
        if (delegate != null) { return delegate.authenticate(certs); }

        throw new IllegalStateException("No delegate has been set");
    }

    //-------------------------------------------------------------------------
    // Internal
    //-------------------------------------------------------------------------

    /**
     * FIXME: CBL-1182
     * Used by C4Listener to get the root certs. It needs public accessor as C4Listener is
     * in a different package.
     */
    @Nullable
    public List<Certificate> getRootCerts() {
        return rootCerts;
    }
}
