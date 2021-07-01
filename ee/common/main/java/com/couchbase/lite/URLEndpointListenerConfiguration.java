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

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.couchbase.lite.internal.ImmutableURLEndpointListenerConfiguration;
import com.couchbase.lite.internal.utils.Preconditions;


/**
 * Configuration information for a URL endpoint listener.
 * There are two varieties: Http and Tls.
 */
public class URLEndpointListenerConfiguration {
    public static final int MIN_PORT = 0;
    public static final int MAX_PORT = 65535;

    //-------------------------------------------------------------------------
    // Data Members
    //-------------------------------------------------------------------------
    @NonNull
    private final Database database;

    @Nullable
    private String networkInterface;

    @Nullable
    private TLSIdentity identity;

    @Nullable
    ListenerAuthenticator authenticator;

    private int port;

    private boolean disableTls;
    private boolean enableDeltaSync;
    private boolean readOnly;

    //-------------------------------------------------------------------------
    // Constructors
    //-------------------------------------------------------------------------
    /**
     * Create a listener configuration, for the specified database, with default values.
     *
     * @param database the database to which the listener is attached
     */
    public URLEndpointListenerConfiguration(@NonNull Database database) {
        this(database, null, 0, false, null, null, false, false);
    }

    /**
     * Clone the passed listener configuration.
     *
     * @param config the configuration to duplicate
     */
    public URLEndpointListenerConfiguration(@NonNull URLEndpointListenerConfiguration config) {
        this(
            config.getDatabase(),
            config.getNetworkInterface(),
            config.getPort(),
            config.isTlsDisabled(),
            config.getTlsIdentity(),
            config.getAuthenticator(),
            config.isReadOnly(),
            config.isDeltaSyncEnabled());
    }

    URLEndpointListenerConfiguration(@NonNull ImmutableURLEndpointListenerConfiguration config) {
        this(
            config.getDatabase(),
            config.getNetworkInterface(),
            config.getPort(),
            config.isTlsDisabled(),
            config.getTlsIdentity(),
            config.getAuthenticator(),
            config.isReadOnly(),
            config.isDeltaSyncEnabled());
    }

    public URLEndpointListenerConfiguration(
        @NonNull Database database,
        @Nullable String networkInterface,
        int port,
        boolean disableTls,
        @Nullable TLSIdentity identity,
        @Nullable ListenerAuthenticator authenticator,
        boolean readOnly,
        boolean enableDeltaSync) {
        this.database = Preconditions.assertNotNull(database, "database");
        this.networkInterface = networkInterface;
        this.identity = identity;
        this.authenticator = authenticator;
        this.port = port;
        this.disableTls = disableTls;
        this.enableDeltaSync = enableDeltaSync;
        this.readOnly = readOnly;
    }

    //-------------------------------------------------------------------------
    // Public API
    //-------------------------------------------------------------------------

    /**
     * Get the configured database.
     *
     * @return the configured database.
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

    /**
     * Set the configured network interface.
     *
     * @param networkInterface the name of the interface on which to configure the listener (e.g. "en0")
     */
    public void setNetworkInterface(@Nullable String networkInterface) { this.networkInterface = networkInterface; }

    /**
     * Get the configured port.
     *
     * @return the configured port.
     */
    public int getPort() { return port; }

    /**
     * Set the configured port.
     * A port number of 0 (the default) tells the OS to choose some available port.
     *
     * @param port the port number on which to configure the listener (between 0 and 65535, inclusive)
     */
    public void setPort(int port) {
        if ((port < MIN_PORT) || (port > MAX_PORT)) {
            throw new IllegalArgumentException(
                "port " + port + "is not between " + MIN_PORT + " and " + MAX_PORT + " inclusive");
        }

        this.port = port;
    }

    /**
     * Get configured connection type.
     *
     * @return true if this configuration will disable TLS in its associated listener.
     */
    public boolean isTlsDisabled() { return disableTls; }

    /**
     * Set the configured security protocol.
     * TLS is enabled by default. disabling it is not recommended for production.
     *
     * @param disableTls true to disable TLS security.
     */
    public void setDisableTls(boolean disableTls) { this.disableTls = disableTls; }

    /**
     * Get the configured TLS identity.
     *
     * @return the TLS identity for the associated listener.
     */
    @Nullable
    public TLSIdentity getTlsIdentity() { return identity; }

    /**
     * Set the certificates and keys for the associated listener.
     *
     * @param identity a TLSIdentity that the listener will supply to authenticate itself.
     */
    public void setTlsIdentity(@Nullable TLSIdentity identity) { this.identity = identity; }

    /**
     * Get the configured authenticator.
     *
     * @return the authenticator for the associated listener.
     */
    @Nullable
    public ListenerAuthenticator getAuthenticator() { return authenticator; }

    /**
     * Set the authenticator.
     * When TLS is enabled, a null authenticator (the default) will allow clients whose
     * certificate chains can be verified by one of the OS-bundled root certificates.
     * There are two types of TLS authenticators.  See {@link ListenerCertificateAuthenticator}
     * <p>
     * When TLS is disabled, a null authenticator (the default) will allow all clients.
     * A non-null authenticator will be passed the client's credentials
     * and is completely responsible for authenticating them.
     * See {@link ListenerPasswordAuthenticator}
     *
     * @param authenticator the client authenticator
     */
    public void setAuthenticator(@Nullable ListenerAuthenticator authenticator) { this.authenticator = authenticator; }

    /**
     * Is connection read-only.
     *
     * @return true if the connections is read-only.
     */
    public boolean isReadOnly() { return readOnly; }

    /**
     * Set the connection read-only.
     *
     * @param readOnly set true to make the configured listener read-only
     */
    public void setReadOnly(boolean readOnly) { this.readOnly = readOnly; }

    /**
     * Is delta sync enabled.
     *
     * @return true if delta sync is enabled.
     */
    public boolean isDeltaSyncEnabled() { return enableDeltaSync; }

    /**
     * Set delta sync enabled.
     *
     * @param enableDeltaSync set true to enable delta sync
     */
    public void setEnableDeltaSync(boolean enableDeltaSync) { this.enableDeltaSync = enableDeltaSync; }
}
