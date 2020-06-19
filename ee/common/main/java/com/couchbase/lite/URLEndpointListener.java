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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import com.couchbase.lite.internal.core.C4Listener;
import com.couchbase.lite.internal.support.Log;


public abstract class URLEndpointListener {

    /**
     * HTTP Endpoint listener
     */
    public static final class Http extends URLEndpointListener {
        @NonNull
        private final URLEndpointListenerConfiguration.Http config;

        private final boolean readOnly;

        public Http(@NonNull URLEndpointListenerConfiguration.Http config, boolean readOnly) {
            super(config.port);
            this.config = config;
            this.readOnly = readOnly;
        }

        @Override
        @NonNull
        public URLEndpointListenerConfiguration.Http getConfig() { return config; }

        @NonNull
        public URLEndpointListenerConfiguration.Http getHttpConfig() { return config; }

        @NonNull
        @Override
        protected C4Listener startLocked() throws CouchbaseLiteException {
            return C4Listener.createHttpListener(
                config.port,
                config.networkInterface,
                config.database.getPath(),
                !readOnly,
                true,
                config.enableDeltaSync,
                config.getHttpAuthenticator());
        }
    }

    public static final class Tls extends URLEndpointListener {
        @NonNull
        private final URLEndpointListenerConfiguration.Tls config;

        private final boolean readOnly;

        public Tls(@NonNull URLEndpointListenerConfiguration.Tls config, boolean readOnly) {
            super(config.port);
            this.config = config;
            this.readOnly = readOnly;
        }

        @Override
        @NonNull
        public URLEndpointListenerConfiguration.Tls getConfig() { return config; }

        @NonNull
        public URLEndpointListenerConfiguration.Tls getHttpConfig() { return config; }

        // !!! Implement TLS
        @SuppressWarnings("ConstantConditions")
        @SuppressFBWarnings("NP_NONNULL_PARAM_VIOLATION")
        @NonNull
        @Override
        protected C4Listener startLocked() throws CouchbaseLiteException {
            return C4Listener.createTlsListener(
                config.port,
                config.networkInterface,
                config.database.getPath(),
                !readOnly,
                true,
                config.enableDeltaSync,
                null,
                true,
                null,
                config.getCertAuthenticator());
        }
    }

    public static Http createListener(
        @NonNull URLEndpointListenerConfiguration.Http config,
        boolean readOnly) {
        return new URLEndpointListener.Http(config, readOnly);
    }


    public static URLEndpointListener.Tls createListener(
        @NonNull URLEndpointListenerConfiguration.Tls config,
        boolean readOnly) {
        return new URLEndpointListener.Tls(config, readOnly);
    }

    //-------------------------------------------------------------------------
    // Data members
    //-------------------------------------------------------------------------

    @NonNull
    private final Object lock = new Object();

    @Nullable
    @GuardedBy("lock")
    private C4Listener c4Listener;

    private int port;

    //-------------------------------------------------------------------------
    // Constructors
    //-------------------------------------------------------------------------

    public URLEndpointListener(int port) { this.port = port; }

    //-------------------------------------------------------------------------
    // Public API
    //-------------------------------------------------------------------------

    @NonNull
    public abstract URLEndpointListenerConfiguration getConfig();

    @NonNull
    protected abstract C4Listener startLocked() throws CouchbaseLiteException;

    public int getPort() {
        synchronized (lock) {
            return (c4Listener == null) ? 0 : getCachedPort(c4Listener);
        }
    }

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

    @Nullable
    public ConnectionStatus getStatus() {
        synchronized (lock) {
            return (c4Listener == null) ? null : c4Listener.getConnectionStatus();
        }
    }

    public void start() throws CouchbaseLiteException {
        synchronized (lock) {
            if (c4Listener != null) { return; }

            c4Listener = startLocked();
        }
    }

    public void stop() throws CouchbaseLiteException {
        final C4Listener listener;
        synchronized (lock) {
            listener = c4Listener;
            c4Listener = null;
        }

        if (listener == null) { return; }

        try { listener.unshareDb(getConfig().getDatabase().getC4Database()); }
        finally { listener.close(); }
    }

    //-------------------------------------------------------------------------
    // Private methods
    //-------------------------------------------------------------------------

    private int getCachedPort(@NonNull C4Listener listener) {
        if (port == 0) { port = listener.getPort(); }
        return port;
    }
}

