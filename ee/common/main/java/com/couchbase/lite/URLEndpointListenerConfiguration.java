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
public abstract class URLEndpointListenerConfiguration {
    public static final int MIN_PORT = 0;
    public static final int MAX_PORT = 65535;

    // ---------------------------------------------------------------------------------------------
    // Builders
    // ---------------------------------------------------------------------------------------------

    /**
     * Http Builder
     * Builder for Http endpoint listener configurations.
     */
    public static final class HttpBuilder {
        @NonNull
        private final Database database;

        @Nullable
        private String networkInterface;

        @Nullable
        private ListenerPasswordAuthenticator authenticator;

        int port;

        private boolean disableTls;

        private boolean enableDeltaSync;

        /**
         * Create a Http Endpoint Listener Configuration Builder constructor.
         * Prefer: {@link com.couchbase.lite.URLEndpointListenerConfiguration#buildHttpConfig(Database)}
         */
        public HttpBuilder(@NonNull Database database) {
            this.database = Preconditions.assertNotNull(database, "database");
        }

        /**
         * Create a Http Endpoint Listener Configuration.
         * @return the Http Endpoint Listener Configuration.
         */
        @NonNull
        public URLEndpointListenerConfiguration.Http build() {
            return new Http(
                database,
                Preconditions.assertNotNull(networkInterface, "network interface"),
                port,
                disableTls,
                enableDeltaSync,
                Preconditions.assertNotNull(authenticator, "authenticator"));
        }

        /**
         * Set the network interface on which to listen.
         * Must be set before calling <code>build()</code>.
         *
         * @param networkInterface the name of a connected network interface
         * @return this
         */
        public HttpBuilder setNetworkInterface(@NonNull String networkInterface) {
            this.networkInterface = Preconditions.assertNotNull(networkInterface, "network interface");
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
        public HttpBuilder setPort(int port) {
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
        public HttpBuilder setTlsDisabled(boolean disableTls) {
            this.disableTls = disableTls;
            return this;
        }

        /**
         * Enable delta-sync.
         * The default is false: delta-sync is disabled.
         *
         * @param enableDeltaSync true to enable delta-sync.
         * @return this
         */
        public HttpBuilder setEnableDeltaSync(boolean enableDeltaSync) {
            this.enableDeltaSync = enableDeltaSync;
            return this;
        }

        /**
         * Set the Authenticator.
         * See: {@link com.couchbase.lite.ListenerPasswordAuthenticator}
         *
         * @param authenticator Required: an authentication mechanism.
         * @return this
         */
        public HttpBuilder setAuthenticator(@NonNull ListenerPasswordAuthenticator authenticator) {
            this.authenticator = Preconditions.assertNotNull(authenticator, "authenticator");
            return this;
        }
    }

    /**
     * TLS Builder
     * Builder for TLS endpoint listener configurations.
     */
    public static final class TlsBuilder {
        @NonNull
        private final Database database;

        @Nullable
        private String networkInterface;

        @Nullable
        private TLSIdentity identity;

        @Nullable
        private ListenerCertificateAuthenticator authenticator;

        int port;

        private boolean enableDeltaSync;

        /**
         * Create a TLS Endpoint Listener Configuration Builder constructor.
         * Prefer: {@link com.couchbase.lite.URLEndpointListenerConfiguration#buildTlsConfig(Database)}
         */
        public TlsBuilder(@NonNull Database database) {
            this.database = Preconditions.assertNotNull(database, "database");
        }

        /**
         * Create a TLS Endpoint configuration.
         * @return the TLS Endpoint configuration.
         */
        public URLEndpointListenerConfiguration.Tls build() {
            return new Tls(
                database,
                Preconditions.assertNotNull(networkInterface, "network interface"),
                port,
                enableDeltaSync,
                Preconditions.assertNotNull(identity, "identity"),
                Preconditions.assertNotNull(authenticator, "authenticator"));
        }

        /**
         * Set the network interface on which to listen.
         * Must be set before calling <code>build()</code>.
         *
         * @param networkInterface the name of a connected network interface
         * @return this
         */
        public TlsBuilder setNetworkInterface(@NonNull String networkInterface) {
            this.networkInterface = Preconditions.assertNotNull(networkInterface, "network interface");
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
        public TlsBuilder setPort(int port) {
            this.port = checkPort(port);
            return this;
        }

        /**
         * Enable delta-sync.
         * The default is false, delta-sync is disabled.
         *
         * @param enableDeltaSync true to enable delta-sync.
         * @return this
         */
        public TlsBuilder setEnableDeltaSync(boolean enableDeltaSync) {
            this.enableDeltaSync = enableDeltaSync;
            return this;
        }

        public TlsBuilder setTlsIdentity(@NonNull TLSIdentity identity) {
            this.identity = Preconditions.assertNotNull(identity, "identity");
            return this;
        }

        public TlsBuilder setAuthenticator(@NonNull ListenerCertificateAuthenticator authenticator) {
            this.authenticator = Preconditions.assertNotNull(authenticator, "authenticator");
            return this;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Protocol specific listeners
    // ---------------------------------------------------------------------------------------------

    /**
     * URLEndpointListenerConfiguration.Http
     * Configuration information for an HTTP Url Endpoint listener
     */
    public static final class Http extends URLEndpointListenerConfiguration {
        @NonNull
        private final ListenerPasswordAuthenticator authenticator;

        private final boolean disableTls;

        // Accessible only from the builder
        Http(
            @NonNull Database database,
            @NonNull String networkInterface,
            int port,
            boolean enableDeltaSync,
            boolean disableTls,
            @NonNull ListenerPasswordAuthenticator authenticator) {
            super(database, networkInterface, port, enableDeltaSync);
            this.disableTls = disableTls;
            this.authenticator = authenticator;
        }


        //-------------------------------------------------------------------------
        // Public API
        //-------------------------------------------------------------------------

        /**
         * Get the configuration's authenticator.
         * Signature common to all authenticators.
         *
         * @return the authenticator for the associated listener.
         */
        @Override
        @NonNull
        public ListenerAuthenticator getAuthenticator() { return authenticator; }

        /**
         * Get the configuration's authenticator.
         *
         * @return the authenticator for the associated listener.
         */
        @NonNull
        public ListenerPasswordAuthenticator getHttpAuthenticator() { return authenticator; }

        /**
         * Get TLS configuration state.
         *
         * @return true if this configuration will disable TLS in its associated listener.
         */
        public boolean isTlsDisabled() { return disableTls; }
    }

    /**
     * URLEndpointListenerConfiguration.Tls
     * Configuration information for a TLS Url Endpoint listener
     */
    public static final class Tls extends URLEndpointListenerConfiguration {
        @NonNull
        private final TLSIdentity identity;

        @NonNull
        private final ListenerCertificateAuthenticator authenticator;

        // Accessible only from the builder
        Tls(
            @NonNull Database database,
            @NonNull String networkInterface,
            int port,
            boolean enableDeltaSync,
            @NonNull TLSIdentity identity,
            @NonNull ListenerCertificateAuthenticator authenticator) {
            super(database, networkInterface, port, enableDeltaSync);
            this.identity = identity;
            this.authenticator = authenticator;
        }

        //-------------------------------------------------------------------------
        // Public API
        //-------------------------------------------------------------------------

        /**
         * Get the configuration's authenticator.
         * Signature common to all authenticators.
         *
         * @return the authenticator for the associated listener.
         */
        @Override
        @NonNull
        public ListenerAuthenticator getAuthenticator() { return authenticator; }

        /**
         * Get the configuration's authenticator.
         *
         * @return the authenticator for the associated listener.
         */
        @NonNull
        public ListenerCertificateAuthenticator getCertAuthenticator() { return authenticator; }

        /**
         * Get TLS identity.
         *
         * @return the TLS identity for the associated listener.
         */
        @NonNull
        public TLSIdentity getTlsIdentity() { return identity; }
    }

    //-------------------------------------------------------------------------
    // Public static methods
    //-------------------------------------------------------------------------

    /**
     * Convenience method to create a config builder for an HTTP Listener
     *
     * @param database the target database
     * @return the config builder
     */
    public static HttpBuilder buildHttpConfig(@NonNull Database database) { return new HttpBuilder(database); }

    /**
     * Convenience method to create a config builder for an TLS Listener
     *
     * @param database the target database
     * @return the config builder
     */
    public static TlsBuilder buildTlsConfig(@NonNull Database database) { return new TlsBuilder(database); }

    /**
     * Convenience method to create a config builder for an HTTP Listener from an existing config.
     *
     * @param config an existing HTTP listener config
     * @return the config builder
     */
    public static HttpBuilder buildHttpConfig(@NonNull Http config) {
        return new HttpBuilder(config.database)
            .setNetworkInterface(config.networkInterface)
            .setPort(config.port)
            .setEnableDeltaSync(config.enableDeltaSync)
            .setTlsDisabled(config.disableTls)
            .setAuthenticator(config.authenticator);
    }

    /**
     * Convenience method to create a config builder for an HTTP Listener from an existing config.
     * The new builder will be initialized from the one passed as a parameter.
     *
     * @param config an existing TLS listener config
     * @return the config builder
     */
    public static TlsBuilder buildTlsConfig(@NonNull Tls config) {
        return new TlsBuilder(config.database)
            .setNetworkInterface(config.networkInterface)
            .setPort(config.port)
            .setEnableDeltaSync(config.enableDeltaSync)
            .setTlsIdentity(config.identity)
            .setAuthenticator(config.authenticator);
    }

    //-------------------------------------------------------------------------
    // Private static methods
    //-------------------------------------------------------------------------

    private static int checkPort(int port) {
        if ((port < MIN_PORT) || (port > MAX_PORT)) {
            throw new IllegalArgumentException(
                "port " + port + "is not between " + MIN_PORT + " and " + MAX_PORT + " inclusive");
        }
        return port;
    }


    //-------------------------------------------------------------------------
    // Data Members
    //-------------------------------------------------------------------------

    @NonNull
    final Database database;

    @NonNull
    final String networkInterface;

    final int port;

    final boolean enableDeltaSync;

    //-------------------------------------------------------------------------
    // Constructors
    //-------------------------------------------------------------------------

    URLEndpointListenerConfiguration(
        @NonNull Database database,
        @NonNull String networkInterface,
        int port,
        boolean enableDeltaSync) {
        this.database = database;
        this.networkInterface = networkInterface;
        this.port = port;
        this.enableDeltaSync = enableDeltaSync;
    }

    //-------------------------------------------------------------------------
    // Public API
    //-------------------------------------------------------------------------

    /**
     * Get the configuration's database.
     *
     * @return the TLS identity for the associated listener.
     */

    @NonNull
    public Database getDatabase() { return database; }

    @NonNull
    public String getNetworkInterface() { return networkInterface; }

    public int getPort() { return port; }

    public boolean isEnableDeltaSync() { return enableDeltaSync; }

    public abstract ListenerAuthenticator getAuthenticator();
}
