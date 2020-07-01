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

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.couchbase.lite.internal.utils.Preconditions;


/**
 * Configuration information for a URL endpoint listener.
 * There are two varieties: Http and Tls.
 */
public class URLEndpointListenerConfiguration {
    public static final int MIN_PORT = 0;
    public static final int MAX_PORT = 65535;

    /**
     * Builder
     * Builder for and endpoint listener configurations.
     */
    public static final class Builder {
        @NonNull
        private final Database database;

        @Nullable
        private String networkInterface;

        @Nullable
        private TLSIdentity identity;

        @Nullable
        private ListenerAuthenticator authenticator;

        int port;

        private boolean disableTls;

        private boolean enableDeltaSync;

        /**
         * Create a TLS Endpoint Listener Configuration Builder constructor.
         */
        public Builder(@NonNull Database database) {
            this.database = Preconditions.assertNotNull(database, "database");
        }

        /**
         * Create a TLS Endpoint configuration.
         *
         * @return the TLS Endpoint configuration.
         */
        public URLEndpointListenerConfiguration build() {
            if (authenticator == null) {
                throw new IllegalStateException("A listener must have an authenticator");
            }

            if (disableTls) {
                if (identity != null) {
                    throw new IllegalStateException("Identity specified for connection with TLS disabled");
                }
                if (authenticator instanceof ListenerCertificateAuthenticator) {
                    throw new IllegalStateException(
                        "Certificate authenticator specified for connection with TLS disabled");
                }
            }

            return new URLEndpointListenerConfiguration(
                database,
                networkInterface,
                port,
                disableTls,
                identity,
                authenticator,
                enableDeltaSync);
        }

        /**
         * Set the network interface on which to listen.
         * The default value, null, means that the Listener will listen on all interfaces
         *
         * @param networkInterface the name of a connected network interface
         * @return this
         */
        public Builder setNetworkInterface(@Nullable String networkInterface) {
            this.networkInterface = networkInterface;
            return this;
        }

        /**
         * Set the listener port.
         * If specified, must be a number 0 &lt;= port &lt;= 65535.
         * The default is 0: the OS will choose an available port.
         *
         * @param port a number 0 &lt;= port &lt;= 65535
         * @return this
         */
        public Builder setPort(int port) {
            this.port = checkPort(port);
            return this;
        }

        /**
         * Disable TLS.
         * The default is false: TLS is enabled.
         *
         * @param disableTls true to disable TLS.
         * @return this
         */
        public Builder setTlsDisabled(boolean disableTls) {
            this.disableTls = disableTls;
            return this;
        }

        /**
         * Set the TLS identity.
         * The default is null.  If TLS is enabled a new, anonymous identity will be created for the listener
         *
         * @param identity a TLS Certificate to be used to authenticate this listener.
         * @return this
         */
        public Builder setTlsIdentity(@Nullable TLSIdentity identity) {
            this.identity = Preconditions.assertNotNull(identity, "identity");
            return this;
        }

        /**
         * Set the authenticator.
         * A Listener must have an authenticator to authenticate client connections.
         *
         * @param authenticator An authenticator to be used to validate connections.
         * @return this
         */
        public Builder setAuthenticator(@NonNull ListenerAuthenticator authenticator) {
            this.authenticator = Preconditions.assertNotNull(authenticator, "authenticator");
            return this;
        }

        /**
         * Enable delta-sync.
         * The default is false, delta-sync is disabled.
         *
         * @param enableDeltaSync true to enable delta-sync.
         * @return this
         */
        public Builder setEnableDeltaSync(boolean enableDeltaSync) {
            this.enableDeltaSync = enableDeltaSync;
            return this;
        }

        private int checkPort(int port) {
            if ((port < MIN_PORT) || (port > MAX_PORT)) {
                throw new IllegalArgumentException(
                    "port " + port + "is not between " + MIN_PORT + " and " + MAX_PORT + " inclusive");
            }
            return port;
        }
    }


    //-------------------------------------------------------------------------
    // Data Members
    //-------------------------------------------------------------------------

    @NonNull
    final Database database;

    @Nullable
    final String networkInterface;

    @Nullable
    final TLSIdentity identity;

    @NonNull
    final ListenerAuthenticator authenticator;

    final int port;

    final boolean disableTls;

    final boolean enableDeltaSync;

    //-------------------------------------------------------------------------
    // Constructors
    //-------------------------------------------------------------------------

    URLEndpointListenerConfiguration(
        @NonNull Database database,
        @Nullable String networkInterface,
        int port,
        boolean disableTls,
        @Nullable TLSIdentity identity,
        @NonNull ListenerAuthenticator authenticator,
        boolean enableDeltaSync) {
        this.database = database;
        this.networkInterface = networkInterface;
        this.port = port;
        this.disableTls = disableTls;
        this.identity = identity;
        this.authenticator = authenticator;
        this.enableDeltaSync = enableDeltaSync;
    }

    //-------------------------------------------------------------------------
    // Public API
    //-------------------------------------------------------------------------

    /**
     * Get the configured database.
     *
     * @return the TLS identity for the associated listener.
     */
    @NonNull
    public Database getDatabase() { return database; }


    /**
     * Get the configured network interface.
     *
     * @return the configured network interface.
     */
    @Nullable
    public String getNetworkInterface() { return networkInterface; }

    public int getPort() { return port; }

    /**
     * Get configured connection type.
     *
     * @return true if this configuration will disable TLS in its associated listener.
     */
    public boolean isTlsDisabled() { return disableTls; }

    /**
     * Get the configured TLS identity.
     *
     * @return the TLS identity for the associated listener.
     */
    @Nullable
    public TLSIdentity getTlsIdentity() { return identity; }

    /**
     * Get the configured authenticator.
     *
     * @return the authenticator for the associated listener.
     */
    @NonNull
    public ListenerAuthenticator getAuthenticator() { return authenticator; }

    /**
     * Is delta sync enabled.
     *
     * @return true if delta sync is enabled.
     */
    public boolean isDeltaSyncEnabled() { return enableDeltaSync; }
}
