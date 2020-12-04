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

import android.support.annotation.GuardedBy;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.List;

import com.couchbase.lite.internal.AbstractTLSIdentity;
import com.couchbase.lite.internal.core.C4Listener;
import com.couchbase.lite.internal.support.Log;


public class URLEndpointListener {

    @NonNull
    private final Object lock = new Object();

    @NonNull
    private final URLEndpointListenerConfiguration config;

    @Nullable
    @GuardedBy("lock")
    private TLSIdentity identity;

    @Nullable
    @GuardedBy("lock")
    private C4Listener c4Listener;

    private int port;

    //-------------------------------------------------------------------------
    // Constructors
    //-------------------------------------------------------------------------

    /**
     * Create a URLEndpointListener with the passed configuration.
     *
     * @param config the listener configuration.
     */
    public URLEndpointListener(@NonNull URLEndpointListenerConfiguration config) {
        if (config.getDatabase().getUuid() == null) {
            throw new IllegalArgumentException("Configured database is not open");
        }
        this.config = new URLEndpointListenerConfiguration(config, true);
        identity = (config.isTlsDisabled()) ? null : config.getTlsIdentity();
    }

    //-------------------------------------------------------------------------
    // Public API
    //-------------------------------------------------------------------------

    /**
     * Get the listener's configuration.
     *
     * @return the listener's configuration (read only).
     */
    @NonNull
    public URLEndpointListenerConfiguration getConfig() { return config; }

    /**
     * Get the listener's port.
     * This method will return a value of -1 except between the time
     * the listener is started and the time it is stopped.
     *
     * When a listener is configured with the port number 0, the return value from this function will
     * give the port at which the listener is actually listening.
     *
     * @return the listener's port, or -1.
     */
    public int getPort() {
        synchronized (lock) { return (c4Listener == null) ? -1 : getCachedPort(c4Listener); }
    }

    /**
     * Get the list of URIs for the listener.
     *
     * @return a list of listener URIs.
     */
    @NonNull
    public List<URI> getUrls() {
        final List<URI> uris = new ArrayList<>();

        final List<String> uriStrs;
        synchronized (lock) {
            if (c4Listener == null) { return uris; }

            uriStrs = c4Listener.getUrls(getConfig().getDatabase().getC4Database());
            if (uriStrs == null) { return uris; }
        }

        for (String uri: uriStrs) {
            try { uris.add(new URI(uri)); }
            catch (URISyntaxException e) { Log.w(LogDomain.LISTENER, "Failed creating URI for: " + uri); }
        }

        return uris;
    }

    /**
     * Get the listener status.
     *
     * @return listener status.
     */
    @Nullable
    public ConnectionStatus getStatus() {
        synchronized (lock) { return (c4Listener == null) ? null : c4Listener.getConnectionStatus(); }
    }

    /**
     * Get the TLS identity used by the listener.
     *
     * @return TLS identity.
     */
    @Nullable
    public TLSIdentity getTlsIdentity() {
        synchronized (lock) { return identity; }
    }

    /**
     * Start the listener.
     */
    public void start() throws CouchbaseLiteException {
        final Database db = getConfig().getDatabase();
        db.registerUrlListener(this);

        final C4Listener listener;
        synchronized (lock) {
            if (c4Listener != null) { return; }
            listener = startLocked();
            c4Listener = listener;
        }

        listener.shareDb(db.getName(), db.getC4Database());
    }

    /**
     * Stop the listener.
     */
    public void stop() {
        final C4Listener listener;
        synchronized (lock) {
            listener = c4Listener;
            c4Listener = null;
        }

        if (listener == null) { return; }

        getConfig().getDatabase().unregisterUrlListener(this);

        listener.close();
    }

    boolean isRunning() {
        synchronized (lock) { return c4Listener != null; }
    }

    //-------------------------------------------------------------------------
    // Private methods
    //-------------------------------------------------------------------------

    @NonNull
    private C4Listener startLocked() throws CouchbaseLiteException {
        final ListenerAuthenticator auth = config.getAuthenticator();
        Log.i(LogDomain.LISTENER, "Starting with auth: " + auth);

        if (config.isTlsDisabled()) {
            return C4Listener.createHttpListener(
                config.getPort(),
                config.getNetworkInterface(),
                config.getDatabase().getPath(),
                (ListenerPasswordAuthenticator) auth,
                true,
                !config.isReadOnly(),
                config.isDeltaSyncEnabled()
            );
        }

        final TLSIdentity id;
        synchronized (lock) {
            if (identity == null) {
                final String uuid = getDbUuid();
                identity = TLSIdentity.getAnonymousIdentity(uuid + "@" + config.getPort());
            }
            id = identity;
        }

        final Certificate serverCert = AbstractTLSIdentity.getCert(id);
        if (serverCert == null) { throw new IllegalStateException("Server cert is null"); }

        if ((auth == null) || (auth instanceof ListenerPasswordAuthenticator)) {
            return C4Listener.createTlsListenerPasswordAuth(
                config.getPort(),
                config.getNetworkInterface(),
                config.getDatabase().getPath(),
                (ListenerPasswordAuthenticator) auth,
                true,
                !config.isReadOnly(),
                config.isDeltaSyncEnabled(),
                serverCert,
                id.getKeyPair()
            );
        }

        return C4Listener.createTlsListenerCertAuth(
            config.getPort(),
            config.getNetworkInterface(),
            config.getDatabase().getPath(),
            (ListenerCertificateAuthenticator) auth,
            true,
            !config.isReadOnly(),
            config.isDeltaSyncEnabled(),
            serverCert,
            id.getKeyPair()
        );
    }

    //-------------------------------------------------------------------------
    // Private methods
    //-------------------------------------------------------------------------

    private int getCachedPort(@NonNull C4Listener listener) {
        if (port == 0) { port = listener.getPort(); }
        return port;
    }

    private String getDbUuid() {
        final String uuid = config.getDatabase().getUuid();
        if (uuid == null) { throw new IllegalArgumentException("Configured database is not open"); }
        return uuid;
    }
}

