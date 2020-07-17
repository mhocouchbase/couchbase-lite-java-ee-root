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
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import com.couchbase.lite.CBLError;
import com.couchbase.lite.CouchbaseLiteException;
import com.couchbase.lite.LiteCoreException;
import com.couchbase.lite.LogDomain;
import com.couchbase.lite.internal.CBLStatus;
import com.couchbase.lite.internal.KeyStoreManager;
import com.couchbase.lite.internal.core.impl.NativeC4KeyPair;
import com.couchbase.lite.internal.support.Log;
import com.couchbase.lite.internal.utils.ClassUtils;
import com.couchbase.lite.internal.utils.Preconditions;


public class C4KeyPair extends C4NativePeer implements Closeable {
    public interface NativeImpl {
        byte[] nGenerateSelfSignedCertificate(
            long c4KeyPair,
            byte algorithm,
            int keyBits,
            String[][] attributes,
            byte usage,
            long validityInSeconds);

        long nFromExternal(byte algorithm, int keyBits, long token) throws LiteCoreException;

        void nFree(long token);
    }

    @NonNull
    @VisibleForTesting
    static C4KeyPair.NativeImpl nativeImpl = new NativeC4KeyPair();

    @NonNull
    @VisibleForTesting
    static final NativeContext<C4KeyPair> KEY_PAIR_CONTEXT = new NativeContext<>();

    private static final Map<KeyStoreManager.KeyAlgorithm, Byte> KEY_ALGORITHM_TO_C4;
    static {
        final Map<KeyStoreManager.KeyAlgorithm, Byte> m = new HashMap<>();
        m.put(KeyStoreManager.KeyAlgorithm.RSA, (byte) 0x00);
        KEY_ALGORITHM_TO_C4 = Collections.unmodifiableMap(m);
    }

    private static final Map<Integer, KeyStoreManager.SignatureDigestAlgorithm> C4_TO_DIGEST_ALGORITHM;

    static {
        final Map<Integer, KeyStoreManager.SignatureDigestAlgorithm> m = new HashMap<>();
        m.put(0, KeyStoreManager.SignatureDigestAlgorithm.NONE);
        m.put(4, KeyStoreManager.SignatureDigestAlgorithm.SHA1);
        m.put(5, KeyStoreManager.SignatureDigestAlgorithm.SHA224);
        m.put(6, KeyStoreManager.SignatureDigestAlgorithm.SHA256);
        m.put(7, KeyStoreManager.SignatureDigestAlgorithm.SHA384);
        m.put(8, KeyStoreManager.SignatureDigestAlgorithm.SHA512);
        m.put(9, KeyStoreManager.SignatureDigestAlgorithm.RIPEMD160);
        C4_TO_DIGEST_ALGORITHM = Collections.unmodifiableMap(m);
    }

    //-------------------------------------------------------------------------
    // Static Factory Methods
    //-------------------------------------------------------------------------

    /**
     * Create a C4Key pair.
     *
     * @param keyStore    the KeyStore object containing the cert and key pair
     * @param keyAlias    the alias by which the key is known to the keystore
     * @param keyPassword the password protecting the key
     * @param algorithm   key algorithm (must be KeyManager.KeyAlgorithm.RSA)
     * @param keySize     key size
     * @return a new C4KeyPair, representing the cert, public and private keys identified by the alias
     * @throws CouchbaseLiteException on error
     */
    public static C4KeyPair createKeyPair(
        @Nullable KeyStore keyStore,
        @NonNull String keyAlias,
        @Nullable char[] keyPassword,
        @NonNull KeyStoreManager.KeyAlgorithm algorithm,
        KeyStoreManager.KeySize keySize) throws CouchbaseLiteException {
        return createKeyPair(keyStore, keyAlias, keyPassword, algorithm, keySize, null);
    }

    /**
     * Create a C4Key pair.
     *
     * @param keyStore    the KeyStore object containing the cert and key pair
     * @param keyAlias    the alias by which the key is known to the keystore
     * @param keyPassword the password protecting the key
     * @param algorithm   key algorithm (must be KeyManager.KeyAlgorithm.RSA)
     * @param keySize     key size
     * @param keys        keyPair for the case that the KeyPair hasn't been saved into the KeyStore
     * @return a new C4KeyPair, representing the cert, public and private keys identified by the alias
     * @throws CouchbaseLiteException on error
     */
    public static C4KeyPair createKeyPair(
        @Nullable KeyStore keyStore,
        @NonNull String keyAlias,
        @Nullable char[] keyPassword,
        @NonNull KeyStoreManager.KeyAlgorithm algorithm,
        KeyStoreManager.KeySize keySize,
        @Nullable KeyPair keys)
        throws CouchbaseLiteException {
        char[] keyPwd = null;
        if (keyPassword != null) {
            keyPwd = new char[keyPassword.length];
            System.arraycopy(keyPassword, 0, keyPwd, 0, keyPwd.length);
        }

        final int token = KEY_PAIR_CONTEXT.reserveKey();
        final C4KeyPair keyPair = new C4KeyPair(token, keyStore, keyAlias, keyPwd, keys);
        KEY_PAIR_CONTEXT.bind(token, keyPair);

        final long peer;
        try { peer = nativeImpl.nFromExternal(getC4KeyAlgorithm(algorithm), keySize.getBitLength(), token); }
        catch (LiteCoreException e) { throw CBLStatus.convertException(e); }

        keyPair.setPeer(peer);

        return keyPair;
    }

    /**
     * Convenience method for Android, which has only one safe keystore
     * and doesn't use passwords for keys.
     *
     * @param keyAlias  the alias by which the key is known to the keystore
     * @param algorithm key algorithm (must be KeyManager.KeyAlgorithm.RSA)
     * @param keySize   key size
     * @return a new C4KeyPair, representing the cert, public and private keys identified by the alias
     * @throws CouchbaseLiteException on error
     */
    public static C4KeyPair createKeyPair(
        @NonNull String keyAlias,
        @NonNull KeyStoreManager.KeyAlgorithm algorithm,
        KeyStoreManager.KeySize keySize)
        throws CouchbaseLiteException {
        return createKeyPair(null, keyAlias, null, algorithm, keySize, null);
    }

    //-------------------------------------------------------------------------
    // Native callback methods
    //-------------------------------------------------------------------------

    // This method is called by reflection.  Don't change its signature.
    @SuppressWarnings("unused")
    @Nullable
    static byte[] getKeyDataCallback(long token) {
        final C4KeyPair keyPair = getKeyPair(token);
        if (keyPair == null) { return null; }
        return KeyStoreManager.getInstance().getKeyData(keyPair);
    }

    // This method is called by reflection.  Don't change its signature.
    @SuppressWarnings("unused")
    @Nullable
    static byte[] decryptCallback(long token, @NonNull byte[] data) {
        final C4KeyPair keyPair = getKeyPair(token);
        if (keyPair == null) { return null; }
        return KeyStoreManager.getInstance().decrypt(keyPair, data);
    }

    // This method is called by reflection.  Don't change its signature.
    @SuppressWarnings("unused")
    @Nullable
    static byte[] signKeyCallback(long token, int digestAlgorithm, @NonNull byte[] data) {
        final C4KeyPair keyPair = getKeyPair(token);
        if (keyPair == null) { return null; }

        final KeyStoreManager.SignatureDigestAlgorithm algorithm = getDigestAlgorithm(digestAlgorithm);

        return KeyStoreManager.getInstance().signKey(keyPair, algorithm, data);
    }

    // This method is called by reflection.  Don't change its signature.
    @SuppressWarnings("unused")
    static void freeCallback(long token) {
        final C4KeyPair keyPair = getKeyPair(token);
        if (keyPair == null) { return; }
        KeyStoreManager.getInstance().free(keyPair);
    }

    //-------------------------------------------------------------------------
    // Private static methods
    //-------------------------------------------------------------------------

    private static C4KeyPair getKeyPair(long token) {
        Log.d(LogDomain.LISTENER, "get key pair @" + token);

        final C4KeyPair keyPair = KEY_PAIR_CONTEXT.getObjFromContext(token);
        if (keyPair != null) { return keyPair; }

        Log.w(LogDomain.LISTENER, "Could not find the key pair @" + token);
        return null;
    }

    private static byte getC4KeyAlgorithm(KeyStoreManager.KeyAlgorithm algorithm) {
        final Byte c4Algorithm = KEY_ALGORITHM_TO_C4.get(algorithm);
        if (c4Algorithm == null) { throw new IllegalArgumentException("Unrecognized encryption algorithm"); }
        return c4Algorithm;
    }

    private static KeyStoreManager.SignatureDigestAlgorithm getDigestAlgorithm(int digestAlgorithm) {
        final KeyStoreManager.SignatureDigestAlgorithm algorithm = C4_TO_DIGEST_ALGORITHM.get(digestAlgorithm);
        if (algorithm == null) {
            throw new IllegalArgumentException("Unrecognized algorithm algorithm: " + digestAlgorithm);
        }
        return algorithm;
    }

    //-------------------------------------------------------------------------
    // Data members
    //-------------------------------------------------------------------------

    private final int token;
    @SuppressFBWarnings("SE_BAD_FIELD")
    @Nullable
    private final KeyStore keyStore;
    @NonNull
    private final String keyAlias;
    @Nullable
    private final char[] keyPassword;
    @Nullable
    private final KeyPair keys;

    //-------------------------------------------------------------------------
    // Constructors
    //-------------------------------------------------------------------------

    @SuppressWarnings("PMD.ArrayIsStoredDirectly")
    private C4KeyPair(
        int token,
        @Nullable KeyStore keyStore,
        @NonNull String keyAlias,
        @Nullable char[] keyPassword,
        @Nullable KeyPair keyPair) {
        super();
        this.token = token;
        this.keyStore = keyStore;
        this.keyAlias = keyAlias;
        this.keyPassword = keyPassword;
        this.keys = keyPair;
    }

    //-------------------------------------------------------------------------
    // Public Properties
    //-------------------------------------------------------------------------

    @Nullable
    public KeyStore getKeyStore() {
        return keyStore;
    }

    @NonNull
    public String getKeyAlias() {
        return keyAlias;
    }

    @Nullable
    public char[] getKeyPassword() {
        if (keyPassword == null) { return null; }
        return Arrays.copyOf(keyPassword, keyPassword.length);
    }

    @Nullable
    public KeyPair getKeys() {
        return keys;
    }

    //-------------------------------------------------------------------------
    // Public Methods
    //-------------------------------------------------------------------------

    public Certificate generateSelfSignedCertificate(
        @NonNull KeyStoreManager.KeyAlgorithm algorithm,
        KeyStoreManager.KeySize keySize,
        @NonNull Map<String, String> attributes,
        @NonNull KeyStoreManager.CertUsage usage,
        @Nullable Date expiration)
        throws CouchbaseLiteException {
        int i = 0;
        final String[][] attrs = new String[attributes.size()][];
        for (Map.Entry<String, String> attr: attributes.entrySet()) {
            attrs[i++] = new String[] {attr.getKey(), attr.getValue()};
        }

        long validityInSeconds = 0;
        if (expiration != null) {
            validityInSeconds = Preconditions.assertPositive(
                TimeUnit.MILLISECONDS.toSeconds(expiration.getTime() - System.currentTimeMillis()),
                "valid time");
        }


        final byte[] data = nativeImpl.nGenerateSelfSignedCertificate(
            getPeer(),
            getC4KeyAlgorithm(algorithm),
            keySize.getBitLength(),
            attrs,
            usage.getCode(),
            validityInSeconds);

        Preconditions.assertNotNull(data, "data");
        Preconditions.assertPositive(data.length, "data");

        try { return CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(data)); }
        catch (CertificateException e) {
            throw new CouchbaseLiteException(
                "Failed to create certificate",
                e,
                CBLError.Domain.CBLITE,
                CBLError.Code.CRYPTO);
        }

        // return null;
    }

    @Override
    public void close() {
        KEY_PAIR_CONTEXT.unbind(token);
        final long peer = getPeerAndClear();
        if (peer == 0) { return; }
        nativeImpl.nFree(peer);
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
            nativeImpl.nFree(peer);
            Log.d(LogDomain.LISTENER, "Finalized without closing: " + this);
        }
        finally { super.finalize(); }
    }
}
