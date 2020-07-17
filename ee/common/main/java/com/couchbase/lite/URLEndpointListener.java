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
package com.couchbase.lite;

import android.support.annotation.GuardedBy;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import com.couchbase.lite.internal.core.C4Listener;
import com.couchbase.lite.internal.support.Log;


public class URLEndpointListener {
    @NonNull
    private final Object lock = new Object();

    @NonNull
    private final URLEndpointListenerConfiguration config;
    @Nullable
    private final TLSIdentity identity;

    private final boolean readOnly;


    @Nullable
    @GuardedBy("lock")
    private C4Listener c4Listener;

    private int port;

    //-------------------------------------------------------------------------
    // Constructors
    //-------------------------------------------------------------------------

    public URLEndpointListener(@NonNull URLEndpointListenerConfiguration config, boolean readOnly)
        throws CouchbaseLiteException {
        this.config = config;
        this.readOnly = readOnly;

        if (config.disableTls) { identity = null; }
        else {
            final TLSIdentity id = config.identity;
            if (id != null) { identity = id; }
            else {
                final String uuid = config.database.getUuid();
                if (uuid == null) { throw new IllegalArgumentException("Configured database is not open"); }
                identity = TLSIdentity.getAnonymousIdentity(uuid + "@" + config.getPort());
            }
        }
    }

    //-------------------------------------------------------------------------
    // Public API
    //-------------------------------------------------------------------------

    /**
     * Get the listener's configuration.
     *
     * @return the listener's configuration.
     */
    @NonNull
    public URLEndpointListenerConfiguration getConfig() { return config; }

    public int getPort() {
        synchronized (lock) { return (c4Listener == null) ? -1 : getCachedPort(c4Listener); }
    }

    /**
     * Get the list of URLs for the listener
     *
     * @return a list of listener urls.
     */
    @NonNull
    public List<URL> getUrls() {
        final List<URL> urls = new ArrayList<>();

        final List<String> urlStrs;
        synchronized (lock) {
            if (c4Listener == null) { return urls; }

            urlStrs = c4Listener.getUrls(getConfig().getDatabase().getC4Database());
            if (urlStrs == null) { return urls; }
        }

        for (String url: urlStrs) {
            try { urls.add(new URL(url)); }
            catch (MalformedURLException e) {
                Log.w(LogDomain.NETWORK, "Failed to encode url (ignored): " + url, e);
            }
        }

        return urls;
    }

    /**
     * Get the listener status
     *
     * @return listener status.
     */
    @Nullable
    public ConnectionStatus getStatus() {
        synchronized (lock) { return (c4Listener == null) ? null : c4Listener.getConnectionStatus(); }
    }

    /**
     * Start the listener
     */
    public void start() throws CouchbaseLiteException {
        final C4Listener listener;
        synchronized (lock) {
            if (c4Listener != null) { return; }
            listener = startLocked();
            c4Listener = listener;
        }

        final Database db = getConfig().getDatabase();
        listener.shareDb(db.getName(), db.getC4Database());
    }

    /**
     * Stop the listener
     */
    public void stop() {
        final C4Listener listener;
        synchronized (lock) {
            listener = c4Listener;
            c4Listener = null;
        }

        if (listener == null) { return; }

        listener.close();
    }

    //-------------------------------------------------------------------------
    // Protected methods
    //-------------------------------------------------------------------------

    @NonNull
    private C4Listener startLocked() throws CouchbaseLiteException {
        final ListenerAuthenticator auth = config.getAuthenticator();

        if (identity == null) {
            return C4Listener.createHttpListener(
                config.port,
                config.networkInterface,
                config.database.getPath(),
                (ListenerPasswordAuthenticator) auth,
                true,
                !readOnly,
                config.enableDeltaSync
            );
        }

        if ((auth == null) || (auth instanceof ListenerPasswordAuthenticator)) {
            return C4Listener.createTlsListenerPasswordAuth(
                config.port,
                config.networkInterface,
                config.database.getPath(),
                (ListenerPasswordAuthenticator) auth,
                true,
                !readOnly,
                config.enableDeltaSync,
                identity.getCert(),
                identity.getKeyPair()
            );
        }

        return C4Listener.createTlsListenerCertAuth(
            config.port,
            config.networkInterface,
            config.database.getPath(),
            (ListenerCertificateAuthenticator) auth,
            true,
            !readOnly,
            config.enableDeltaSync,
            identity.getCert(),
            identity.getKeyPair()
        );
    }

    //-------------------------------------------------------------------------
    // Private methods
    //-------------------------------------------------------------------------

    private int getCachedPort(@NonNull C4Listener listener) {
        if (port == 0) { port = listener.getPort(); }
        return port;
    }
}

