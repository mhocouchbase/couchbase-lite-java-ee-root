//
// Copyright (c) 2020 Couchbase, Inc.  All rights reserved.
//
// Licensed under the Couchbase License Agreement (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
// https://info.couchbase.com/rs/302-GJY-034/images/2017-10-30_License_Agreement.pdf
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
package com.couchbase.lite.internal.replicator;

import android.support.annotation.NonNull;

import java.net.Socket;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.List;

import javax.net.ssl.X509KeyManager;

import com.couchbase.lite.TLSIdentity;
import com.couchbase.lite.internal.KeyStoreManager;
import com.couchbase.lite.internal.core.C4KeyPair;


/**
 * X509KeyManager for client certificate authentication
 * with the given client TLSIdentity.
 */
public class CBLKeyManager implements X509KeyManager {
    @NonNull
    private final TLSIdentity identity;

    public CBLKeyManager(@NonNull TLSIdentity identity) {
        this.identity = identity;
    }

    @NonNull
    @Override
    public String[] getClientAliases(@NonNull String keyType, @NonNull Principal[] issuers) {
        return new String[] {identity.getKeyPair().getKeyAlias()};
    }

    @NonNull
    @Override
    public String chooseClientAlias(@NonNull String[] keyType, @NonNull Principal[] issuers, @NonNull Socket socket) {
        return identity.getKeyPair().getKeyAlias();
    }

    @NonNull
    @Override
    public String[] getServerAliases(@NonNull String keyType, @NonNull Principal[] issuers) {
        throw new UnsupportedOperationException("getServerAliases(String, Principal[]) not supported for client");
    }

    @NonNull
    @Override
    public String chooseServerAlias(@NonNull String keyType, @NonNull Principal[] issuers, @NonNull Socket socket) {
        throw new UnsupportedOperationException("chooseServerAlias(String, Principal[]) not supported for client");
    }

    @NonNull
    @Override
    public X509Certificate[] getCertificateChain(@NonNull String alias) {
        final List<Certificate> certs = identity.getCerts();
        return certs.toArray(new X509Certificate[0]);
    }

    @NonNull
    @Override
    public PrivateKey getPrivateKey(@NonNull String alias) {
        final C4KeyPair keyPair = identity.getKeyPair();
        return KeyStoreManager.getInstance().getKey(
            keyPair.getKeyStore(), keyPair.getKeyAlias(), keyPair.getKeyPassword());
    }
}
