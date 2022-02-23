//
// Copyright (c) 2020, 2018 Couchbase, Inc.  All rights reserved.
//
// Licensed under the Couchbase License Agreement (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
// https://info.couchbase.com/rs/302-GJY-034/images/2017-10-30_License_Agreement.pdf
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
package com.couchbase.lite;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;

import com.couchbase.lite.internal.ImmutableReplicatorConfiguration;
import com.couchbase.lite.internal.utils.Preconditions;


/**
 * Configuration for a Replicator
 */
public final class ReplicatorConfiguration extends AbstractReplicatorConfiguration {

    //---------------------------------------------
    // Data Members
    //---------------------------------------------
    private boolean selfSignedOnly;

    //---------------------------------------------
    // Constructors
    //---------------------------------------------
    public ReplicatorConfiguration(@NonNull Database database, @NonNull Endpoint target) {
        super(Preconditions.assertNotNull(database, "database"), Preconditions.assertNotNull(target, "target"));
    }

    public ReplicatorConfiguration(@NonNull ReplicatorConfiguration config) {
        super(config);
        this.selfSignedOnly = config.isAcceptOnlySelfSignedServerCertificate();
    }

    ReplicatorConfiguration(@NonNull ImmutableReplicatorConfiguration config) {
        super(config);
        this.selfSignedOnly = config.isAcceptOnlySelfSignedServerCertificate();
    }

    // for Kotlin
    @SuppressWarnings({"PMD.ExcessiveParameterList", "PMD.UnnecessaryFullyQualifiedName"})
    ReplicatorConfiguration(
        @NonNull Database database,
        @NonNull com.couchbase.lite.ReplicatorType type,
        boolean continuous,
        @Nullable Authenticator authenticator,
        @Nullable Map<String, String> headers,
        @Nullable X509Certificate pinnedServerCertificate,
        @Nullable List<String> channels,
        @Nullable List<String> documentIDs,
        @Nullable ReplicationFilter pushFilter,
        @Nullable ReplicationFilter pullFilter,
        @Nullable ConflictResolver conflictResolver,
        int maxAttempts,
        int maxAttemptWaitTime,
        int heartbeat,
        boolean enableAutoPurge,
        @NonNull Endpoint target,
        boolean acceptOnlySelfSignedServerCertificate) {
        super(
            Preconditions.assertNotNull(database, "database"),
            Preconditions.assertNotNull(type, "type"),
            continuous,
            authenticator,
            headers,
            pinnedServerCertificate,
            channels,
            documentIDs,
            pushFilter,
            pullFilter,
            conflictResolver,
            maxAttempts,
            maxAttemptWaitTime,
            verifyHeartbeat(heartbeat),
            enableAutoPurge,
            Preconditions.assertNotNull(target, "target"));
        this.selfSignedOnly = acceptOnlySelfSignedServerCertificate;
    }

    /**
     * Specify whether the replicator will accept only self-signed certificates.
     * If set true, the replicator will accept any self-signed but <b>NO</b> not self-signed certificates
     * This guards against using this mode accidentally, in production.
     * The default value is false.
     *
     * @param selfSignedOnly Whether the replicator will accept any and only self-signed certificates.
     * @return this.
     */
    @NonNull
    public ReplicatorConfiguration setAcceptOnlySelfSignedServerCertificate(boolean selfSignedOnly) {
        this.selfSignedOnly = selfSignedOnly;
        return getReplicatorConfiguration();
    }

    /**
     * Return whether the replicator will accept any and only self-signed server certificates.
     */
    public boolean isAcceptOnlySelfSignedServerCertificate() { return selfSignedOnly; }

    @NonNull
    @Override
    ReplicatorConfiguration getReplicatorConfiguration() { return this; }
}
