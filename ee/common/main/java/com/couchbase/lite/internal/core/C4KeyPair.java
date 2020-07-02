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
package com.couchbase.lite.internal.core;

import android.support.annotation.NonNull;
import android.support.annotation.VisibleForTesting;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.security.KeyPair;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.interfaces.RSAKey;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import com.couchbase.lite.CouchbaseLiteException;
import com.couchbase.lite.LiteCoreException;
import com.couchbase.lite.LogDomain;
import com.couchbase.lite.internal.AbstractTLSIdentity;
import com.couchbase.lite.internal.CBLStatus;
import com.couchbase.lite.internal.KeyManager;
import com.couchbase.lite.internal.core.impl.NativeC4KeyPair;
import com.couchbase.lite.internal.support.Log;
import com.couchbase.lite.internal.utils.ClassUtils;


public class C4KeyPair extends C4NativePeer implements Closeable {
    public interface NativeImpl {
        byte[] nGenerateSelfSignedCertificate(
            long c4KeyPair,
            byte algorithm,
            int keyBits,
            String[][] subjectName,
            byte usage);

        long nFromExternal(byte algorithm, int keyBits, long token) throws LiteCoreException;

        void nFree(long token);
    }

    @NonNull
    @VisibleForTesting
    static C4KeyPair.NativeImpl nativeImpl = new NativeC4KeyPair();

    @NonNull
    @VisibleForTesting
    static final NativeContext<C4KeyPair> KEY_PAIR_CONTEXT = new NativeContext<>();

    private static final Map<KeyManager.KeyAlgorithm, Byte> ALGORITHM_TO_C4;

    static {
        final Map<KeyManager.KeyAlgorithm, Byte> m = new HashMap<>();
        m.put(KeyManager.KeyAlgorithm.RSA, (byte) 0x00);
        ALGORITHM_TO_C4 = Collections.unmodifiableMap(m);
    }

    private static byte getC4Algorithm(KeyManager.KeyAlgorithm algorithm) {
        final Byte c4Algorithm = ALGORITHM_TO_C4.get(algorithm);
        if (c4Algorithm == null) { throw new IllegalArgumentException("Unrecognized encryption algorithm"); }
        return c4Algorithm;
    }

    public static C4KeyPair createKeyPair(
        @NonNull KeyPair keys,
        @NonNull KeyManager.KeyAlgorithm algorithm)
        throws CouchbaseLiteException {
        final int token = KEY_PAIR_CONTEXT.reserveKey();
        final C4KeyPair keyPair = new C4KeyPair(nativeImpl, token, keys);
        KEY_PAIR_CONTEXT.bind(token, keyPair);

        final int keyBits = ((RSAKey) keys.getPublic()).getModulus().bitLength();

        final long peer;
        try { peer = nativeImpl.nFromExternal(getC4Algorithm(algorithm), keyBits, token); }
        catch (LiteCoreException e) { throw CBLStatus.convertException(e); }

        keyPair.setPeer(peer);

        return keyPair;
    }


    //-------------------------------------------------------------------------
    // Data members
    //-------------------------------------------------------------------------

    private final int token;
    @NonNull
    private final C4KeyPair.NativeImpl impl;
    @NonNull
    private final KeyPair keys;

    //-------------------------------------------------------------------------
    // Constructors
    //-------------------------------------------------------------------------

    private C4KeyPair(@NonNull C4KeyPair.NativeImpl impl, int token, @NonNull KeyPair keys) {
        super();
        this.impl = impl;
        this.token = token;
        this.keys = keys;
    }

    //-------------------------------------------------------------------------
    // Public Methods
    //-------------------------------------------------------------------------

    public Certificate generateSelfSignedCertificate(
        @NonNull KeyManager.KeyAlgorithm algorithm,
        @NonNull Map<AbstractTLSIdentity.CertAttribute, String> nameComponents,
        @NonNull KeyManager.CertUsage usage) {
        int i = 0;
        final String[][] components = new String[nameComponents.size()][];
        for (Map.Entry<AbstractTLSIdentity.CertAttribute, String> component: nameComponents.entrySet()) {
            components[i++] = new String[] { component.getKey().getCode(), component.getValue() };
        }

        final byte[] data = impl.nGenerateSelfSignedCertificate(
            getPeer(),
            getC4Algorithm(algorithm),
            keySize(),
            components,
            usage.getCode());

        try { return CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(data)); }
        catch (CertificateException e) { Log.w(LogDomain.LISTENER, "Failed to create certificate", e); }

        return null;
    }

    @Override
    public void close() {
        KEY_PAIR_CONTEXT.unbind(token);
        final long peer = getPeerAndClear();
        if (peer == 0) { return; }
        impl.nFree(peer);
    }

    @NonNull
    @Override
    public String toString() {
        return "C4KeyPair{" + ClassUtils.objId(this) + "/" + getPeer() + ": " + token + "}";
    }

    //-------------------------------------------------------------------------
    // protected methods
    //-------------------------------------------------------------------------

    @SuppressWarnings("NoFinalizer")
    @Override
    protected void finalize() throws Throwable {
        try {
            final long peer = getPeerUnchecked();
            if (peer == 0) { return; }
            KEY_PAIR_CONTEXT.unbind(token);
            impl.nFree(peer);
            Log.d(LogDomain.LISTENER, "Finalized without closing: " + this);
        }
        finally { super.finalize(); }
    }

    private int keySize() { return ((RSAKey) keys.getPublic()).getModulus().bitLength(); }
}
