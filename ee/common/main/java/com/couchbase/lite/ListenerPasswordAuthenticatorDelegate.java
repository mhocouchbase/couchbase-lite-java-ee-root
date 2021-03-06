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


import androidx.annotation.NonNull;


/**
 * Functional Interface for an Authenticator that uses an authentication strategy based on a user name and password.
 * Pass implementations of this interface to the {@link ListenerPasswordAuthenticator} to realize
 * specific authentication strategies.
 */
@FunctionalInterface
public interface ListenerPasswordAuthenticatorDelegate {
    /**
     * Authenticate a client based on the passed credentials.
     *
     * @param username client supplied username
     * @param password client supplied password
     * @return true when the client is authorized.
     */
    boolean authenticate(@NonNull String username, @NonNull char[] password);
}
