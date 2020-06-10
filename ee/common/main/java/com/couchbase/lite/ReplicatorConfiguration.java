//
// Copyright (c) 2018 Couchbase, Inc.  All rights reserved.
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

import com.couchbase.lite.internal.utils.Preconditions;


public final class ReplicatorConfiguration extends AbstractReplicatorConfiguration {
    @NonNull
    private ServerCertificateVerificationMode certificateVerificationMode
        = ServerCertificateVerificationMode.CA_CERT;


    public ReplicatorConfiguration(@NonNull ReplicatorConfiguration config) {
        super(config);
        this.certificateVerificationMode = config.certificateVerificationMode;
    }

    public ReplicatorConfiguration(@NonNull Database database, @NonNull Endpoint target) {
        super(database, target);
    }

    /**
     * Sets the replicator verification mode.
     * The default value is ServerCertificateVerificationMode.CA_CERT.
     *
     * @param mode Specifies the way the replicator verifies the server identity when using TLS communication
     * @return this.
     */
    @NonNull
    public ReplicatorConfiguration setServerCertificateVerificationMode(
        @NonNull ServerCertificateVerificationMode mode) {
        checkReadOnly();
        this.certificateVerificationMode = Preconditions.assertNotNull(mode, "certificate verification mode");
        return getReplicatorConfiguration();
    }

    /**
     * Return the replicator verification mode.
     */
    @NonNull
    public ServerCertificateVerificationMode getServerCertificateVerificationMode() {
        return certificateVerificationMode;
    }

    @Override
    ReplicatorConfiguration getReplicatorConfiguration() { return this; }
}
