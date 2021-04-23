//
// Copyright (c) 2021 Couchbase, Inc All rights reserved.
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
package com.couchbase.lite.internal;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.couchbase.lite.Database;
import com.couchbase.lite.ListenerAuthenticator;
import com.couchbase.lite.TLSIdentity;
import com.couchbase.lite.URLEndpointListenerConfiguration;


/**
 * A bit odd.  Why are these properties not simply properties on the AbstractDatabase object?
 * Because they are mandated by a spec:
 * https://docs.google.com/document/d/16XmIOw7aZ_NcFc6Dy6fc1jV7sc994r6iv5qm9_J7qKo/edit#heading=h.kt1n12mtpzx4
 */
public class ImmutableURLEndpointListenerConfiguration {

    //-------------------------------------------------------------------------
    // Data Members
    //-------------------------------------------------------------------------
    @NonNull
    private final Database database;

    @Nullable
    private final String networkInterface;

    @Nullable
    private final TLSIdentity identity;

    @Nullable
    private final ListenerAuthenticator authenticator;

    private final  int port;

    private final boolean disableTls;
    private final boolean enableDeltaSync;
    private final boolean readOnly;

    //-------------------------------------------------------------------------
    // Constructors
    //-------------------------------------------------------------------------
    public ImmutableURLEndpointListenerConfiguration(URLEndpointListenerConfiguration config) {
        this.database = config.getDatabase();
        this.networkInterface = config.getNetworkInterface();
        this.port = config.getPort();
        this.disableTls = config.isTlsDisabled();
        this.identity = config.getTlsIdentity();
        this.authenticator = config.getAuthenticator();
        this.readOnly = config.isReadOnly();
        this.enableDeltaSync = config.isDeltaSyncEnabled();
    }

    //-------------------------------------------------------------------------
    // Properties
    //-------------------------------------------------------------------------
    @NonNull
    public Database getDatabase() { return database; }

    @Nullable
    public String getNetworkInterface() { return networkInterface; }

    @Nullable
    public TLSIdentity getTlsIdentity() { return identity; }

    @Nullable
    public ListenerAuthenticator getAuthenticator() { return authenticator; }

    public int getPort() { return port; }

    public boolean isTlsDisabled() { return disableTls; }

    public boolean isDeltaSyncEnabled() { return enableDeltaSync; }

    public boolean isReadOnly() { return readOnly; }
}
