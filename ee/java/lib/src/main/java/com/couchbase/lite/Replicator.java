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
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;

import com.couchbase.lite.internal.core.C4Replicator;
import com.couchbase.lite.internal.core.C4Socket;
import com.couchbase.lite.internal.replicator.NetworkConnectivityManager;


public final class Replicator extends AbstractReplicator {
    /**
     * Initializes a replicator with the given configuration.
     *
     * @param config replicator configuration
     */
    public Replicator(@NonNull ReplicatorConfiguration config) { super(config); }

    @VisibleForTesting
    Replicator(@Nullable NetworkConnectivityManager ignore, @NonNull ReplicatorConfiguration config) { super(config); }

    @Override
    protected C4Replicator createReplicatorForTarget(Endpoint target) throws LiteCoreException {
        if (target instanceof URLEndpoint) { return getRemoteC4Replicator(((URLEndpoint) target).getURL()); }

        if (target instanceof DatabaseEndpoint) {
            return getLocalC4Replicator(((DatabaseEndpoint) target).getDatabase());
        }

        if (target instanceof MessageEndpoint) {
            return getMessageC4Replicator(
                (((MessageEndpoint) target).getProtocolType() != ProtocolType.BYTE_STREAM)
                    ? C4Socket.NO_FRAMING
                    : C4Socket.WEB_SOCKET_CLIENT_FRAMING
            );
        }

        throw new IllegalStateException("unrecognized endpoint type: " + target);
    }

    protected void handleOffline(@NonNull ActivityLevel prevLevel, boolean nowOnline) { }
}

