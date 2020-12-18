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

import android.support.annotation.NonNull;

import java.util.Map;

import com.couchbase.lite.internal.core.C4Replicator;


public final class ReplicatorConfiguration extends AbstractReplicatorConfiguration {
    private boolean acceptOnlySelfSignedServerCertificate;

    public ReplicatorConfiguration(@NonNull ReplicatorConfiguration config) { this(config, false); }

    public ReplicatorConfiguration(@NonNull Database database, @NonNull Endpoint target) { super(database, target); }

    ReplicatorConfiguration(@NonNull ReplicatorConfiguration config, boolean readOnly) {
        super(config, readOnly);
        this.acceptOnlySelfSignedServerCertificate = config.acceptOnlySelfSignedServerCertificate;
    }

    /**
     * Specify whether the replicator will accept any and only self-signed certificates.
     * Any non-self-signed certificates will be rejected to avoid accidentally using
     * this mode with the non-self-signed certs in production. The default value is false.
     *
     * @param acceptOnlySelfSignedServerCertificate Whether the replicator will accept
     *                                              any and only self-signed certificates.
     * @return this.
     */
    @NonNull
    public ReplicatorConfiguration setAcceptOnlySelfSignedServerCertificate(
        boolean acceptOnlySelfSignedServerCertificate) {
        checkReadOnly();
        this.acceptOnlySelfSignedServerCertificate = acceptOnlySelfSignedServerCertificate;
        return getReplicatorConfiguration();
    }

    /**
     * Return whether the replicator will accept any and only self-signed server certificates.
     */
    public boolean isAcceptOnlySelfSignedServerCertificate() { return acceptOnlySelfSignedServerCertificate; }

    @Override
    ReplicatorConfiguration getReplicatorConfiguration() { return this; }

    @Override
    protected Map<String, Object> effectiveOptions() {
        final Map<String, Object> options = super.effectiveOptions();
        options.put(C4Replicator.REPLICATOR_OPTION_SELF_SIGNED_SERVER_CERT, acceptOnlySelfSignedServerCertificate);
        return options;
    }
}
