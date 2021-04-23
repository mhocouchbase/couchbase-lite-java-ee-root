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

import java.util.Map;

import com.couchbase.lite.ReplicatorConfiguration;
import com.couchbase.lite.internal.core.C4Replicator;


public class ImmutableReplicatorConfiguration extends BaseImmutableReplicatorConfiguration {
    //-------------------------------------------------------------------------
    // Data members
    //-------------------------------------------------------------------------
    private final boolean acceptOnlySelfSignedServerCertificate;

    //-------------------------------------------------------------------------
    // Constructors
    //-------------------------------------------------------------------------
    public ImmutableReplicatorConfiguration(@NonNull ReplicatorConfiguration config) {
        super(config);
        this.acceptOnlySelfSignedServerCertificate = config.isAcceptOnlySelfSignedServerCertificate();
    }

    //-------------------------------------------------------------------------
    // Properties
    //-------------------------------------------------------------------------
    public final boolean isAcceptOnlySelfSignedServerCertificate() { return acceptOnlySelfSignedServerCertificate; }

    //-------------------------------------------------------------------------
    // Public methods
    //-------------------------------------------------------------------------
    @Override
    public void addEffectiveOptions(@NonNull Map<String, Object> options) {
        super.addEffectiveOptions(options);
        options.put(C4Replicator.REPLICATOR_OPTION_SELF_SIGNED_SERVER_CERT, acceptOnlySelfSignedServerCertificate);
    }
}
