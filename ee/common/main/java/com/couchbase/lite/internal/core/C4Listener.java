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
package com.couchbase.lite.internal.core;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.List;

import com.couchbase.lite.CBLError;
import com.couchbase.lite.ConnectionStatus;
import com.couchbase.lite.CouchbaseLiteException;
import com.couchbase.lite.ListenerAuthenticator;
import com.couchbase.lite.ListenerCertificateAuthenticator;
import com.couchbase.lite.ListenerPasswordAuthenticator;
import com.couchbase.lite.LiteCoreException;
import com.couchbase.lite.LogDomain;
import com.couchbase.lite.internal.CBLStatus;
import com.couchbase.lite.internal.core.impl.NativeC4Listener;
import com.couchbase.lite.internal.support.Log;
import com.couchbase.lite.internal.utils.ClassUtils;
import com.couchbase.lite.internal.utils.PlatformUtils;
import com.couchbase.lite.internal.utils.Preconditions;
import com.couchbase.lite.internal.utils.StringUtils;


public class C4Listener extends C4NativePeer implements AutoCloseable {
    public static final String AUTH_MODE_BASIC = "Basic";

    /**
     * Native Implementation
     */
    @SuppressWarnings("PMD.ExcessiveParameterList")
    public interface NativeImpl {
        int SYNC_API = 0x02;

        long nStartHttp(
            long token,
            int port,
            @Nullable String iFace,
            int apis,
            @NonNull String dbPath,
            boolean allowCreateDBs,
            boolean allowDeleteDBs,
            boolean push,
            boolean pull,
            boolean deltaSync,
            boolean requirePasswordAuth)
            throws LiteCoreException;

        @SuppressWarnings("PMD.ExcessiveParameterList")
        long nStartTls(
            long token,
            int port,
            @Nullable String iFace,
            int apis,
            @NonNull String dbPath,
            boolean allowCreateDBs,
            boolean allowDeleteDBs,
            boolean allowPush,
            boolean allowPull,
            boolean deltaSync,
            long keyPair,
            @NonNull byte[] serverCert,
            boolean requireClientCerts,
            @Nullable byte[] rootClientCerts,
            boolean requirePasswordAuth)
            throws LiteCoreException;

        void nFree(long handle);

        void nShareDb(long handle, @NonNull String name, long c4Db) throws LiteCoreException;

        void nUnshareDb(long handle, long c4Db) throws LiteCoreException;

        @NonNull
        List<String> nGetUrls(long handle, long c4Db) throws LiteCoreException;

        int nGetPort(long handle);

        @NonNull
        ConnectionStatus nGetConnectionStatus(long handle);

        @NonNull
        String nGetUriFromPath(String path);
    }

    @NonNull
    @VisibleForTesting
    static NativeImpl nativeImpl = new NativeC4Listener();

    @NonNull
    @VisibleForTesting
    static final NativeContext<C4Listener> LISTENER_CONTEXT = new NativeContext<>();

    //-------------------------------------------------------------------------
    // Static Factory Methods
    //-------------------------------------------------------------------------

    @SuppressWarnings("PMD.ExcessiveParameterList")
    @NonNull
    public static C4Listener createHttpListener(
        int port,
        @Nullable String iFace,
        @NonNull String dbPath,
        @Nullable ListenerPasswordAuthenticator authenticator,
        boolean push,
        boolean pull,
        boolean deltaSync)
        throws CouchbaseLiteException {
        final int token = LISTENER_CONTEXT.reserveKey();
        final C4Listener listener = new C4Listener(token, nativeImpl, authenticator);
        LISTENER_CONTEXT.bind(token, listener);

        final long peer;
        try {
            peer = nativeImpl.nStartHttp(
                token,
                port,
                iFace,
                NativeImpl.SYNC_API, // REST API not supported
                dbPath,
                false,               // REST API not supported
                false,               // REST API not supported
                push,
                pull,
                deltaSync,
                (authenticator != null));
        }
        catch (LiteCoreException e) {
            throw CBLStatus.convertException(e);
        }

        listener.setPeer(peer);

        return listener;
    }

    @SuppressWarnings("PMD.ExcessiveParameterList")
    @NonNull
    public static C4Listener createTlsListenerPasswordAuth(
        int port,
        @Nullable String iFace,
        @NonNull String dbPath,
        @Nullable ListenerPasswordAuthenticator authenticator,
        boolean push,
        boolean pull,
        boolean deltaSync,
        @NonNull Certificate serverCert,
        @Nullable C4KeyPair keys)
        throws CouchbaseLiteException {
        Preconditions.assertNotNull(dbPath, "database path");
        Preconditions.assertNotNull(serverCert, "server cert");
        final C4KeyPair keyPair = Preconditions.assertNotNull(keys, "key pair");
        final int token = LISTENER_CONTEXT.reserveKey();
        final C4Listener listener = new C4Listener(token, nativeImpl, authenticator);
        LISTENER_CONTEXT.bind(token, listener);
        final long peer;
        try {
            peer = nativeImpl.nStartTls(
                token,
                port,
                iFace,
                NativeImpl.SYNC_API, // REST API not supported
                dbPath,
                false,               // REST API not supported
                false,               // REST API not supported
                push,
                pull,
                deltaSync,
                keyPair.getPeer(),
                serverCert.getEncoded(),
                false,
                null,
                (authenticator != null));
        }
        catch (LiteCoreException e) {
            throw CBLStatus.convertException(e);
        }
        catch (CertificateEncodingException e) {
            throw new CouchbaseLiteException(
                "Bad cert encoding",
                e,
                C4Constants.LogDomain.LISTENER,
                CBLError.Code.TLS_CLIENT_CERT_REJECTED);
        }

        listener.setPeer(peer);

        return listener;
    }

    @SuppressWarnings("PMD.ExcessiveParameterList")
    @NonNull
    public static C4Listener createTlsListenerCertAuth(
        int port,
        @Nullable String iFace,
        @NonNull String dbPath,
        @Nullable ListenerCertificateAuthenticator authenticator,
        boolean push,
        boolean pull,
        boolean deltaSync,
        @NonNull Certificate serverCert,
        @Nullable C4KeyPair keyPair)
        throws CouchbaseLiteException {
        if (keyPair == null) { throw new IllegalArgumentException("keyPair must not be null"); }

        final int token = LISTENER_CONTEXT.reserveKey();
        final C4Listener listener = new C4Listener(token, nativeImpl, authenticator);
        LISTENER_CONTEXT.bind(token, listener);

        final long peer;
        try {
            peer = nativeImpl.nStartTls(
                token,
                port,
                iFace,
                NativeImpl.SYNC_API, // REST API not supported
                dbPath,
                false,               // REST API not supported
                false,               // REST API not supported
                push,
                pull,
                deltaSync,
                keyPair.getPeer(),
                serverCert.getEncoded(),
                true,
                (authenticator == null)
                    ? null
                    : ((InternalCertAuthenticator) authenticator).getRootCerts(),
                false);
        }
        catch (LiteCoreException e) {
            throw CBLStatus.convertException(e);
        }
        catch (CertificateEncodingException e) {
            throw new CouchbaseLiteException(
                "Bad cert encoding",
                e,
                C4Constants.LogDomain.LISTENER,
                CBLError.Code.TLS_CLIENT_CERT_REJECTED);
        }

        listener.setPeer(peer);

        return listener;
    }

    //-------------------------------------------------------------------------
    // Native callback methods
    //-------------------------------------------------------------------------

    // This method is called by reflection.  Don't change its signature.
    static boolean httpAuthCallback(long token, @Nullable String authHeader) {
        final C4Listener listener = LISTENER_CONTEXT.getObjFromContext(token);
        if (listener == null) {
            Log.w(LogDomain.LISTENER, "No listener for token: " + token);
            return false;
        }
        return listener.authenticateBasic(authHeader);
    }

    // This method is called by reflection.  Don't change its signature.
    static boolean certAuthCallback(long token, @Nullable byte[] clientCertData) {
        final C4Listener listener = LISTENER_CONTEXT.getObjFromContext(token);
        if (listener == null) {
            Log.w(LogDomain.LISTENER, "No listener for token: " + token);
            return false;
        }
        return listener.authenticateCert(clientCertData);
    }


    //-------------------------------------------------------------------------
    // Data members
    //-------------------------------------------------------------------------

    private final int token;
    @NonNull
    private final NativeImpl impl;
    @Nullable
    private final ListenerAuthenticator authenticator;

    //-------------------------------------------------------------------------
    // Constructors
    //-------------------------------------------------------------------------

    protected C4Listener(int token, @NonNull NativeImpl impl, @Nullable ListenerAuthenticator authenticator) {
        Preconditions.assertNotZero(token, "token");
        this.token = token;
        this.impl = Preconditions.assertNotNull(impl, "native impl");
        this.authenticator = authenticator;
    }

    //-------------------------------------------------------------------------
    // public methods
    //-------------------------------------------------------------------------

    @Override
    public void close() { free(); }

    @NonNull
    @Override
    public String toString() {
        return "C4Listener{" + ClassUtils.objId(this) + "/" + getPeerUnchecked() + ": " + token + "}";
    }

    public void shareDb(@NonNull String name, @NonNull C4Database db) throws CouchbaseLiteException {
        try { impl.nShareDb(getPeer(), name, db.getHandle()); }
        catch (LiteCoreException e) { throw CBLStatus.convertException(e); }
    }

    public void unshareDb(@NonNull C4Database db) throws CouchbaseLiteException {
        try { impl.nUnshareDb(getPeer(), db.getHandle()); }
        catch (LiteCoreException e) { throw CBLStatus.convertException(e); }
    }

    @Nullable
    public List<String> getUrls(@NonNull C4Database db) {
        try { return impl.nGetUrls(getPeer(), db.getHandle()); }
        catch (LiteCoreException e) { Log.w(LogDomain.LISTENER, "Failed getting URLs", e); }
        return null;
    }

    public int getPort() { return impl.nGetPort(getPeer()); }

    @NonNull
    public ConnectionStatus getConnectionStatus() { return impl.nGetConnectionStatus(getPeer()); }

    @Nullable
    public String getUriFromPath(String path) { return impl.nGetUriFromPath(path); }

    //-------------------------------------------------------------------------
    // protected methods
    //-------------------------------------------------------------------------

    @SuppressWarnings("NoFinalizer")
    @Override
    protected void finalize() throws Throwable {
        try {
            if (free()) { Log.i(LogDomain.LISTENER, "C4Listener was not closed: " + this); }
        }
        finally { super.finalize(); }
    }

    //-------------------------------------------------------------------------
    // package protected methods
    //-------------------------------------------------------------------------

    boolean authenticateBasic(@Nullable String authHeader) {
        Preconditions.assertThat(
            authenticator,
            "authenticator must be a password authenticator",
            auth -> auth instanceof InternalPwdAuthenticator);

        if (authHeader == null) { return false; }

        // !!! The password is now in a base64 encoded String
        final String[] headers = authHeader.split("\\s+");
        if (!headers[0].equals(AUTH_MODE_BASIC)) {
            Log.i(LogDomain.LISTENER, "Unrecognized authentication mode: %s", headers[0]);
            return false;
        }

        if (headers.length > 2) {
            Log.i(LogDomain.LISTENER, "Unrecognized authentication material");
            return false;
        }

        String[] creds = null;
        if ((headers.length > 1) && (!StringUtils.isEmpty(headers[1]))) {
            final byte[] material = PlatformUtils.getDecoder().decodeString(headers[1]);
            if ((material == null) || (material.length <= 0)) {
                Log.i(LogDomain.LISTENER, "Unrecognized authentication material");
                return false;
            }

            creds = new String(material, StandardCharsets.UTF_8).split(":");
            // !!! The password is now in plaintext String
        }

        return ((InternalPwdAuthenticator) authenticator).authenticate(
            StringUtils.getArrayString(creds, 0),
            StringUtils.getArrayString(creds, 1).toCharArray());
    }

    boolean authenticateCert(@Nullable byte[] clientCert) {
        Preconditions.assertThat(authenticator, "authenticator must be a certificate authenticator",
            auth -> auth instanceof InternalCertAuthenticator); // Not expect to happen

        if ((clientCert == null) || (clientCert.length <= 0)) {
            Log.w(LogDomain.LISTENER, "Null/empty cert in authentication");
            return false;
        }

        final List<Certificate> certs = new ArrayList<>();
        try (InputStream in = new ByteArrayInputStream(clientCert)) {
            certs.add(CertificateFactory.getInstance("X.509").generateCertificate(in));
        }
        catch (CertificateException | IOException e) {
            Log.w(LogDomain.LISTENER, "Failed parsing certificate for: " + this);
            return false;
        }

        return ((InternalCertAuthenticator) authenticator).authenticate(certs);
    }

    //-------------------------------------------------------------------------
    // Private methods
    //-------------------------------------------------------------------------

    private boolean free() {
        LISTENER_CONTEXT.unbind(token);

        final long handle = getPeerAndClear();
        if (handle == 0) { return false; }

        impl.nFree(handle);
        return true;
    }
}
