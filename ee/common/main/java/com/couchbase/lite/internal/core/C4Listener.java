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
import java.security.KeyPair;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import com.couchbase.lite.ConnectionStatus;
import com.couchbase.lite.ListenerCertificateAuthenticator;
import com.couchbase.lite.ListenerPasswordAuthenticator;
import com.couchbase.lite.LiteCoreException;
import com.couchbase.lite.LogDomain;
import com.couchbase.lite.internal.core.impl.NativeC4Listener;
import com.couchbase.lite.internal.support.Log;
import com.couchbase.lite.internal.utils.Preconditions;


public abstract class C4Listener extends C4NativePeer implements Closeable {
    /**
     * Synchronization modes
     */
    public enum Option {REST, SYNC}

    /**
     * Auth modes
     */
    public enum KeyMode {CERT, KEY}

    /**
     * Native companion
     */
    @SuppressWarnings("PMD.ExcessiveParameterList")
    public interface Companion {
        long nStartHttp(
            long context,
            int port,
            String networkInterface,
            int opts,
            String dbPath,
            boolean allowCreateDBs,
            boolean allowDeleteDBs,
            boolean allowPush,
            boolean allowPull,
            boolean enableDeltaSync) throws LiteCoreException;

        @SuppressWarnings("PMD.ExcessiveParameterList")
        long nStartTls(
            long context,
            int port,
            String networkInterface,
            int opts,
            String dbPath,
            boolean allowCreateDBs,
            boolean allowDeleteDBs,
            boolean allowPush,
            boolean allowPull,
            boolean enableDeltaSync,
            long keyMode,
            byte[] keyPair,
            byte[] cert,
            boolean requireClientCerts,
            byte[][] rootClientCerts) throws LiteCoreException;

        void nFree(long handle);

        void nShareDb(long handle, String name, long c4Db) throws LiteCoreException;

        void nUnshareDb(long handle, long c4Db) throws LiteCoreException;

        List<String> nGetUrls(long handle, long c4Db, int api) throws LiteCoreException;

        int nGetPort(long handle);

        ConnectionStatus nGetConnectionStatus(long handle);

        String nGetUriFromPath(long handle, String path);
    }

    //-------------------------------------------------------------------------
    // Implementation classes
    //-------------------------------------------------------------------------

    @SuppressFBWarnings("SE_BAD_FIELD")
    static class HttpListener extends C4Listener {
        @NonNull
        private final ListenerPasswordAuthenticator authenticator;

        @VisibleForTesting
        public HttpListener(Companion companion, long handle, @NonNull ListenerPasswordAuthenticator authenticator) {
            super(companion, handle);
            this.authenticator = Preconditions.assertNotNull(authenticator, "authenticator");
        }

        boolean authenticate(@Nullable String authHeader) {
            // ??? Handle null header
            if (authHeader == null) { throw new IllegalArgumentException("header is null"); }

            // ??? parse headers
            final char[] password = authHeader.toCharArray();

            final boolean auth = authenticator.authenticate(authHeader, password);
            Arrays.fill(password, ' ');

            return auth;
        }
    }

    @SuppressFBWarnings("SE_BAD_FIELD")
    static class TlsListener extends C4Listener {
        @NonNull
        private final ListenerCertificateAuthenticator authenticator;

        @VisibleForTesting
        public TlsListener(Companion companion, long handle, @NonNull ListenerCertificateAuthenticator authenticator) {
            super(companion, handle);
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
                Log.w(LogDomain.NETWORK, "Failed parsing certificate for: " + this);
                return false;
            }

            return authenticator.authenticate(certs);
        }
    }

    private static final Map<Option, Integer> OPTION_TO_C4;

    static {
        final Map<Option, Integer> m = new HashMap<>();
        m.put(Option.REST, 0x01);
        m.put(Option.SYNC, 0x02);
        OPTION_TO_C4 = Collections.unmodifiableMap(m);
    }

    private static final Map<KeyMode, Integer> KEY_MODE_TO_C4;

    static {
        final Map<KeyMode, Integer> m = new HashMap<>();
        m.put(KeyMode.CERT, 1);
        m.put(KeyMode.KEY, 2);
        KEY_MODE_TO_C4 = Collections.unmodifiableMap(m);
    }

    private static final NativeC4Listener IMPL = new NativeC4Listener();

    private static final NativeContext<HttpListener> HTTP_LISTENER_CONTEXT = new NativeContext<>();

    private static final NativeContext<TlsListener> TLS_LISTENER_CONTEXT = new NativeContext<>();

    //-------------------------------------------------------------------------
    // Native callback methods
    //-------------------------------------------------------------------------

    // This method is called by reflection.  Don't change its signature.
    @SuppressWarnings("unused")
    static boolean httpAuthCallback(long context, String authHeader) {
        final HttpListener listener = HTTP_LISTENER_CONTEXT.getListenerFromContext(context);
        if (listener == null) {
            Log.i(LogDomain.NETWORK, "No listener for context: " + context);
            return false;
        }
        return listener.authenticate(authHeader);
    }

    // This method is called by reflection.  Don't change its signature.
    @SuppressWarnings("unused")
    static boolean certAuthCallback(long context, byte[] clientCertData) {
        final TlsListener listener = TLS_LISTENER_CONTEXT.getListenerFromContext(context);
        if (listener == null) {
            Log.i(LogDomain.NETWORK, "No listener for context: " + context);
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
        String iFace,
        String dbPath,
        boolean allowCreateDBs,
        boolean allowDeleteDBs,
        boolean allowPush,
        boolean allowPull,
        boolean enableDeltaSync,
        ListenerPasswordAuthenticator authenticator)
        throws LiteCoreException {
        final long context = HTTP_LISTENER_CONTEXT.reserveKey();

        final long hdl = IMPL.nStartHttp(
            context,
            port,
            iFace,
            OPTION_TO_C4.get(Option.SYNC),
            dbPath,
            allowCreateDBs,
            allowDeleteDBs,
            allowPush,
            allowPull,
            enableDeltaSync);

        final HttpListener listener = new HttpListener(IMPL, hdl, authenticator);
        HTTP_LISTENER_CONTEXT.bind(context, listener);

        return listener;
    }

    @SuppressWarnings("PMD.ExcessiveParameterList")
    @NonNull
    public static C4Listener createTlsListener(
        int port,
        String iFace,
        String dbPath,
        boolean allowCreateDBs,
        boolean allowDeleteDBs,
        boolean allowPush,
        boolean allowPull,
        boolean enableDeltaSync,
        KeyMode keyMode,
        KeyPair privateKey,
        Certificate cert,
        boolean requireClientCerts,
        Set<Certificate> rootClientCerts,
        ListenerCertificateAuthenticator authenticator)
        throws LiteCoreException {
        final long context = TLS_LISTENER_CONTEXT.reserveKey();

        final long hdl = IMPL.nStartTls(
            context,
            port,
            iFace,
            OPTION_TO_C4.get(Option.SYNC),
            dbPath,
            allowCreateDBs,
            allowDeleteDBs,
            allowPush,
            allowPull,
            enableDeltaSync,
            keyModeToC4(keyMode),
            null,
            null,
            requireClientCerts,
            null);

        final TlsListener listener = new TlsListener(IMPL, hdl, authenticator);
        TLS_LISTENER_CONTEXT.bind(context, listener);

        return listener;
    }

    //-------------------------------------------------------------------------
    // Private Static Methods
    //-------------------------------------------------------------------------

    private static int optsToC4(@Nullable EnumSet<Option> opts) {
        if (opts == null) { return 0; }

        int c4Opts = 0;
        for (Option opt: opts) {
            final Integer c4Opt = OPTION_TO_C4.get(opt);
            if (c4Opt == null) { continue; }
            c4Opts |= c4Opt;
        }

        return c4Opts;
    }

    private static int keyModeToC4(@NonNull KeyMode keyMode) {
        return Preconditions.assertNotNull(
            KEY_MODE_TO_C4.get(Preconditions.assertNotNull(keyMode, "key mode")),
            "unrecognized mode " + keyMode);
    }


    //-------------------------------------------------------------------------
    // Data members
    //-------------------------------------------------------------------------

    @NonNull
    private final Companion companion;

    //-------------------------------------------------------------------------
    // Constructors
    //-------------------------------------------------------------------------

    @VisibleForTesting
    C4Listener(Companion companion, long handle) {
        super(handle);
        this.companion = Preconditions.assertNotNull(companion, "companion");
    }

    //-------------------------------------------------------------------------
    // public methods
    //-------------------------------------------------------------------------

    @Override
    public void close() {
        final long hdl = getPeerAndClear();
        companion.nFree(hdl);
    }

    public void shareDb(@NonNull String name, @NonNull C4Database db) throws LiteCoreException {
        companion.nShareDb(getPeer(), name, db.getHandle());
    }

    public void unshareDb(@NonNull C4Database db) throws LiteCoreException {
        companion.nUnshareDb(getPeer(), db.getHandle());
    }

    @NonNull
    public List<String> getUrls(@NonNull C4Database db, @Nullable EnumSet<Option> options) throws LiteCoreException {
        return companion.nGetUrls(getPeer(), db.getHandle(), optsToC4(options));
    }

    public int getPort() { return companion.nGetPort(getPeer()); }

    public ConnectionStatus getConnectionStatus() { return companion.nGetConnectionStatus(getPeer()); }

    public String getUriFromPath(String path) { return companion.nGetUriFromPath(getPeer(), path); }

    //-------------------------------------------------------------------------
    // protected methods
    //-------------------------------------------------------------------------

    @SuppressWarnings("NoFinalizer")
    @Override
    protected void finalize() throws Throwable {
        try {
            companion.nFree(getPeer());
            Log.d(LogDomain.DATABASE, "Finalized without closing: " + this);
        }
        finally { super.finalize(); }
    }
}
