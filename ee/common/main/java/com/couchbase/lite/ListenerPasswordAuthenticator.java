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

import com.couchbase.lite.internal.core.InternalPwdAuthenticator;


/**
 * Authenticator for HTTP Listener password authentication
 */
public final class ListenerPasswordAuthenticator extends InternalPwdAuthenticator {
    //-------------------------------------------------------------------------
    // Constructor
    //-------------------------------------------------------------------------

    /**
     * Create an Authenticator using the passed delegate.
     * See {@link ListenerPasswordAuthenticatorDelegate}
     *
     * @param delegate where the action is.
     */
    public ListenerPasswordAuthenticator(@NonNull ListenerPasswordAuthenticatorDelegate delegate) { super(delegate); }
}
