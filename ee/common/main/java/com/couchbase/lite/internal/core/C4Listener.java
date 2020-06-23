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
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import com.couchbase.lite.CBLError;
import com.couchbase.lite.ConnectionStatus;
import com.couchbase.lite.CouchbaseLiteException;
import com.couchbase.lite.ListenerCertificateAuthenticator;
import com.couchbase.lite.ListenerPasswordAuthenticator;
import com.couchbase.lite.LiteCoreException;
import com.couchbase.lite.LogDomain;
import com.couchbase.lite.internal.CBLStatus;
import com.couchbase.lite.internal.core.impl.NativeC4Listener;
import com.couchbase.lite.internal.support.Log;
import com.couchbase.lite.internal.utils.Preconditions;
import com.couchbase.lite.internal.utils.SecurityUtils;


public abstract class C4Listener extends C4NativePeer implements Closeable {
    /**
     * Native Implementation
     */
    @SuppressWarnings("PMD.ExcessiveParameterList")
    public interface NativeImpl {
        int SYNC_API = 0x02;

        long nStartHttp(
            long context,
            int port,
            @NonNull String iFace,
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
            long context,
            int port,
            @NonNull String iFace,
            int apis,
            @NonNull String dbPath,
            boolean allowCreateDBs,
            boolean allowDeleteDBs,
            boolean allowPush,
            boolean allowPull,
            boolean deltaSync,
            @NonNull byte[] cert,
            boolean requireClientCerts,
            @NonNull byte[] rootClientCerts)
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

    //-------------------------------------------------------------------------
    // Implementation classes
    //-------------------------------------------------------------------------

    @SuppressFBWarnings("SE_BAD_FIELD") // base class AtomicLong is Serializable
    static class Http extends C4Listener {
        @NonNull
        private final ListenerPasswordAuthenticator authenticator;

        @Override
        @NonNull
        public String toString() { return "HttpListener{" + getPeer() + "}"; }

        @VisibleForTesting
        Http(@NonNull NativeImpl impl, long handle, @NonNull ListenerPasswordAuthenticator authenticator) {
            super(impl, handle);
            this.authenticator = Preconditions.assertNotNull(authenticator, "authenticator");
        }

        boolean authenticate(@Nullable String authHeader) {
            // ??? Handle null header
            if (authHeader == null) { throw new IllegalArgumentException("header is null"); }

            // ??? parse headers
            // ??? That password is in a String, here, anyway...
            final char[] password = authHeader.toCharArray();

            final boolean auth = authenticator.authenticate(authHeader, password);
            Arrays.fill(password, ' ');

            return auth;
        }
    }

    @SuppressFBWarnings("SE_BAD_FIELD") // base class AtomicLong is Serializable
    static class Tls extends C4Listener {
        @NonNull
        private final ListenerCertificateAuthenticator authenticator;

        @Override
        @NonNull
        public String toString() { return "TlsListener{" + getPeer() + "}"; }

        @VisibleForTesting
        Tls(@NonNull NativeImpl impl, long handle, @NonNull ListenerCertificateAuthenticator authenticator) {
            super(impl, handle);
            this.authenticator = Preconditions.assertNotNull(authenticator, "authenticator");
        }

        boolean authenticate(@Nullable byte[] clientCert) {
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

            return authenticator.authenticate(certs);
        }
    }

    @NonNull
    @VisibleForTesting
    static NativeImpl nativeImpl = new NativeC4Listener();

    @NonNull
    @VisibleForTesting
    static final NativeContext<Http> HTTP_LISTENER_CONTEXT = new NativeContext<>();

    @NonNull
    @VisibleForTesting
    static final NativeContext<Tls> TLS_LISTENER_CONTEXT = new NativeContext<>();

    //-------------------------------------------------------------------------
    // Native callback methods
    //-------------------------------------------------------------------------

    // This method is called by reflection.  Don't change its signature.
    @SuppressWarnings("unused")
    static boolean httpAuthCallback(long context, @Nullable String authHeader) {
        final Http listener = HTTP_LISTENER_CONTEXT.getObjFromContext(context);
        if (listener == null) {
            Log.i(LogDomain.LISTENER, "No listener for context: " + context);
            return false;
        }
        return listener.authenticate(authHeader);
    }

    // This method is called by reflection.  Don't change its signature.
    @SuppressWarnings("unused")
    static boolean certAuthCallback(long context, @Nullable byte[] clientCertData) {
        final Tls listener = TLS_LISTENER_CONTEXT.getObjFromContext(context);
        if (listener == null) {
            Log.i(LogDomain.LISTENER, "No listener for context: " + context);
            return false;
        }
        return listener.authenticate(clientCertData);
    }

    //-------------------------------------------------------------------------
    // Static Factory Methods
    //-------------------------------------------------------------------------




    @SuppressWarnings("PMD.ExcessiveParameterList")
    @NonNull
    public static C4Listener createHttpListener(
        int port,
        @NonNull String iFace,
        @NonNull String dbPath,
        boolean push,
        boolean pull,
        boolean deltaSync,
        @NonNull ListenerPasswordAuthenticator authenticator)
        throws CouchbaseLiteException {
        final long context = HTTP_LISTENER_CONTEXT.reserveKey();

        final long hdl;
        try {
            hdl = nativeImpl.nStartHttp(
                context,
                port,
                iFace,
                NativeImpl.SYNC_API, // REST API not supported
                dbPath,
                false, // REST API not supported
                false, // REST API not supported
                push,
                pull,
                deltaSync);
        }
        catch (LiteCoreException e) { throw CBLStatus.convertException(e); }

        final Http listener = new Http(nativeImpl, hdl, authenticator);
        HTTP_LISTENER_CONTEXT.bind(context, listener);

        return listener;
    }

    @SuppressWarnings("PMD.ExcessiveParameterList")
    @NonNull
    public static C4Listener createTlsListener(
        int port,
        @NonNull String iFace,
        @NonNull String dbPath,
        boolean push,
        boolean pull,
        boolean deltaSync,
        @NonNull Certificate cert,
        boolean requireClientCerts,
        @NonNull Set<Certificate> rootClientCerts,
        @NonNull ListenerCertificateAuthenticator authenticator)
        throws CouchbaseLiteException {
        final long context = TLS_LISTENER_CONTEXT.reserveKey();

        final long hdl;
        try {
            hdl = nativeImpl.nStartTls(
                context,
                port,
                iFace,
                NativeImpl.SYNC_API, // REST API not supported
                dbPath,
                false, // REST API not supported
                false, // REST API not supported
                push,
                pull,
                deltaSync,
                cert.getEncoded(),
                requireClientCerts,
                SecurityUtils.encodeCertificateChain(rootClientCerts));
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

        final Tls listener = new Tls(nativeImpl, hdl, authenticator);
        TLS_LISTENER_CONTEXT.bind(context, listener);

        return listener;
    }


    //-------------------------------------------------------------------------
    // Data members
    //-------------------------------------------------------------------------

    @NonNull
    private final NativeImpl impl;

    //-------------------------------------------------------------------------
    // Constructors
    //-------------------------------------------------------------------------

    protected C4Listener(NativeImpl impl, long handle) {
        super(handle);
        this.impl = Preconditions.assertNotNull(impl, "companion");
    }

    //-------------------------------------------------------------------------
    // public methods
    //-------------------------------------------------------------------------

    @Override
    public void close() {
        final long hdl = getPeerAndClear();
        impl.nFree(hdl);
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
            impl.nFree(getPeer());
            Log.d(LogDomain.LISTENER, "Finalized without closing: " + this);
        }
        finally { super.finalize(); }
    }
}
