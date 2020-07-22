//
// Copyright (c) 2020 Couchbase, Inc All rights reserved.
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
package com.couchbase.lite.internal;

import android.support.annotation.NonNull;
import android.support.annotation.VisibleForTesting;

import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import com.couchbase.lite.internal.core.C4KeyPair;
import com.couchbase.lite.internal.utils.Preconditions;


@SuppressWarnings("PMD.AbstractClassWithoutAbstractMethod")
public abstract class AbstractTLSIdentity {
    protected static KeyStoreManager getManager() { return KeyStoreManager.getInstance(); }


    @NonNull
    private final String keyAlias;
    @NonNull
    private final List<Certificate> certificates;
    @NonNull
    private final C4KeyPair keyPair;

    @VisibleForTesting
    @SuppressWarnings("ConstantConditions")
    @SuppressFBWarnings("NP_STORE_INTO_NONNULL_FIELD")
    protected AbstractTLSIdentity() {
        keyAlias = "test";
        certificates = new ArrayList<>();
        keyPair = null;
    }

    protected AbstractTLSIdentity(
        @NonNull String keyAlias,
        @NonNull C4KeyPair keyPair,
        @NonNull List<Certificate> certificates) {
        this.keyAlias = Preconditions.assertNotNull(keyAlias, "key alias");
        this.keyPair = Preconditions.assertNotNull(keyPair, "key pair");
        this.certificates = Preconditions.assertNotEmpty(certificates, "cert chain");
    }

    @NonNull
    public Certificate getCert() { return certificates.get(0); }

    @NonNull
    public List<Certificate> getCerts() { return certificates; }

    @NonNull
    public C4KeyPair getKeyPair() { return keyPair; }

    @NonNull
    public Date getExpiration() { return ((X509Certificate) getCert()).getNotAfter(); }

    @NonNull
    public String getKeyAlias() { return keyAlias; }
}
