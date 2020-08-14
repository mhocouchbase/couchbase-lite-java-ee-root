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
package com.couchbase.lite;


import android.support.annotation.NonNull;

import java.util.HashMap;
import java.util.Map;

import com.couchbase.lite.internal.core.C4Replicator;
import com.couchbase.lite.internal.replicator.AbstractCBLWebSocket;
import com.couchbase.lite.internal.replicator.CBLKeyManager;


/**
 * <b>ENTERPRISE EDITION API</b><br><br>
 * <p>
 * An authenticator for client certificate authentication which happens during
 * the TLS handshake when connecting to the server.
 * <p>
 * The client certificate authenticator is currently used for authenticating with
 * the URLEndpointListener only.The URLEndpointListener must have TLS enabled and
 * must be configured with a ListenerCertificateAuthenticator to verify client
 * certificates.
 */
public final class ClientCertificateAuthenticator extends Authenticator {
    @NonNull
    private final TLSIdentity identity;

    /**
     * Creates a ClientCertificateAuthenticator object with the given client identity.
     *
     * @param identity client identity
     */
    public ClientCertificateAuthenticator(@NonNull TLSIdentity identity) {
        this.identity = identity;
    }

    /**
     * Get the client identity.
     *
     * @return client identity
     */
    @NonNull
    public TLSIdentity getIdentity() { return identity; }

    @Override
    void authenticate(Map<String, Object> options) {
        final int token = AbstractCBLWebSocket.CLIENT_CERT_AUTH_KEY_MANAGER.reserveKey();
        AbstractCBLWebSocket.CLIENT_CERT_AUTH_KEY_MANAGER.bind(token, new CBLKeyManager(identity));

        final Map<String, Object> auth = new HashMap<>();
        auth.put(C4Replicator.REPLICATOR_AUTH_TYPE, C4Replicator.AUTH_TYPE_CLIENT_CERT);
        auth.put(C4Replicator.REPLICATOR_AUTH_CLIENT_CERT_KEY, token);
        options.put(C4Replicator.REPLICATOR_OPTION_AUTHENTICATION, auth);
    }
}
