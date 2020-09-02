//
// Copyright (c) 2020, 2017 Couchbase, Inc All rights reserved.
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
package com.couchbase.lite;

import android.support.annotation.NonNull;

import java.security.cert.Certificate;
import java.util.List;

import com.couchbase.lite.internal.core.InternalCertAuthenticator;


/**
 * A Listener Certificate Authenticator
 * Certificate base authentication and authorization.
 */
public class ListenerCertificateAuthenticator extends InternalCertAuthenticator {

    //-------------------------------------------------------------------------
    // Constructor
    //-------------------------------------------------------------------------

    /**
     * Create an authenticator that allows clients whose certificate chains can be verified using (only)
     * on of the certs in the passed list.  OS-bundled certs are ignored.
     *
     * @param rootCerts root certificates used to verify client certificate chains.
     */
    public ListenerCertificateAuthenticator(@NonNull List<Certificate> rootCerts) { super(rootCerts, null); }

    /**
     * Create an authenticator that delegates all responsibility for authentication and authorization
     * to the passed delegate.  See {@link ListenerCertificateAuthenticatorDelegate}.
     *
     * @param delegate an authenticator
     */
    public ListenerCertificateAuthenticator(@NonNull ListenerCertificateAuthenticatorDelegate delegate) {
        super(null, delegate);
    }
}
