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


/**
 * Authenticator for HTTP Listener password authentication
 */
public final class ListenerPasswordAuthenticator
    implements ListenerAuthenticator, ListenerPasswordAuthenticatorDelegate {

    //-------------------------------------------------------------------------
    // Fields
    //-------------------------------------------------------------------------

    @NonNull
    private final ListenerPasswordAuthenticatorDelegate delegate;

    //-------------------------------------------------------------------------
    // Constructor
    //-------------------------------------------------------------------------

    public ListenerPasswordAuthenticator(@NonNull ListenerPasswordAuthenticatorDelegate delegate) {
        this.delegate = delegate;
    }

    //-------------------------------------------------------------------------
    // Delegate Methods
    //-------------------------------------------------------------------------

    public boolean authenticate(@NonNull String username, @NonNull char[] password) {
        return delegate.authenticate(username, password);
    }
}
