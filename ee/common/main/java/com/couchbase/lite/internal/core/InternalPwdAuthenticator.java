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
package com.couchbase.lite.internal.core;

import android.support.annotation.NonNull;

import com.couchbase.lite.ListenerAuthenticator;
import com.couchbase.lite.ListenerPasswordAuthenticatorDelegate;


/**
 * Authenticator for HTTP Listener password authentication
 */
public class InternalPwdAuthenticator implements ListenerAuthenticator {

    //-------------------------------------------------------------------------
    // Fields
    //-------------------------------------------------------------------------

    @NonNull
    private final ListenerPasswordAuthenticatorDelegate delegate;

    //-------------------------------------------------------------------------
    // Constructor
    //-------------------------------------------------------------------------

    public InternalPwdAuthenticator(@NonNull ListenerPasswordAuthenticatorDelegate delegate) {
        this.delegate = delegate;
    }

    //-------------------------------------------------------------------------
    // Delegate Methods
    //-------------------------------------------------------------------------

    boolean authenticate(@NonNull String username, @NonNull char[] password) {
        return delegate.authenticate(username, password);
    }
}
