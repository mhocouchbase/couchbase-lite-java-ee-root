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
import com.couchbase.lite.internal.utils.PlatformUtils;
import com.couchbase.lite.internal.utils.Preconditions;
import com.couchbase.lite.internal.utils.StringUtils;


public class C4Listener extends C4NativePeer implements Closeable {
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
            boolean deltaSync)
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
            @Nullable byte[] rootClientCerts)
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
        final C4Listener listener = new C4Listener(nativeImpl, token, authenticator);
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
                deltaSync);
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
        @Nullable C4KeyPair keyPair)
        throws CouchbaseLiteException {
        if (keyPair == null) { throw new IllegalArgumentException("keyPair must not be null"); }

        final int token = LISTENER_CONTEXT.reserveKey();
        final C4Listener listener = new C4Listener(nativeImpl, token, authenticator);
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
                null);
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
        final C4Listener listener = new C4Listener(nativeImpl, token, authenticator);
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
                null);
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
    @SuppressWarnings("unused")
    static boolean httpAuthCallback(long token, @Nullable String authHeader) {
        final C4Listener listener = LISTENER_CONTEXT.getObjFromContext(token);
        if (listener == null) {
            Log.i(LogDomain.LISTENER, "No listener for token: " + token);
            return false;
        }
        return listener.authenticateBasic(authHeader);
    }

    // This method is called by reflection.  Don't change its signature.
    @SuppressWarnings("unused")
    static boolean certAuthCallback(long token, @Nullable byte[] clientCertData) {
        final C4Listener listener = LISTENER_CONTEXT.getObjFromContext(token);
        if (listener == null) {
            Log.i(LogDomain.LISTENER, "No listener for token: " + token);
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

    protected C4Listener(@NonNull NativeImpl impl, int token, @Nullable ListenerAuthenticator authenticator) {
        this.token = token;
        this.impl = Preconditions.assertNotNull(impl, "companion");
        this.authenticator = authenticator;
    }

    //-------------------------------------------------------------------------
    // public methods
    //-------------------------------------------------------------------------

    @Override
    public void close() {
        LISTENER_CONTEXT.unbind(token);
        final long peer = getPeerAndClear();
        if (peer == 0) { return; }
        impl.nFree(peer);
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
            final long peer = getPeerUnchecked();
            if (peer == 0) { return; }
            LISTENER_CONTEXT.unbind(token);
            impl.nFree(peer);
            Log.d(LogDomain.LISTENER, "Finalized without closing: " + this);
        }
        finally { super.finalize(); }
    }

    //-------------------------------------------------------------------------
    // package protected methods
    //-------------------------------------------------------------------------

    boolean authenticateBasic(@Nullable String authHeader) {
         // !!! The password is now in a base64 encoded String

        final ListenerPasswordAuthenticator auth = (ListenerPasswordAuthenticator) authenticator;
        if (auth == null) { return true; }

        if (authHeader == null) { return false; }

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
            if (material == null) {
                Log.i(LogDomain.LISTENER, "Unrecognized authentication material");
                return false;
            }

            creds = new String(material, StandardCharsets.UTF_8).split(":");
            // !!! The password is now in plaintext String
        }

        return auth.authenticate(
            StringUtils.getArrayString(creds, 0),
            StringUtils.getArrayString(creds, 1).toCharArray());
    }


    boolean authenticateCert(@Nullable byte[] clientCert) {
        final ListenerCertificateAuthenticator auth = (ListenerCertificateAuthenticator) authenticator;
        if (auth == null) { return true; }

        // ??? Handle null content
        if (clientCert == null) { throw new IllegalArgumentException("cert is null"); }

        // ??? construct cert list
        final List<Certificate> certs = new ArrayList<>();
        try (InputStream in = new ByteArrayInputStream(clientCert)) {
            certs.add(CertificateFactory.getInstance("X.509").generateCertificate(in));
        }
        catch (CertificateException | IOException e) {
            Log.w(LogDomain.LISTENER, "Failed parsing certificate for: " + this);
            return false;
        }

        return auth.authenticate(certs);
    }
}
