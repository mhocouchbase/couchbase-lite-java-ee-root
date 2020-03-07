//
// Replicator.java
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

import com.couchbase.lite.internal.core.C4Replicator;
import com.couchbase.lite.internal.core.C4Socket;


public final class Replicator extends AbstractReplicator {
    /**
     * Initializes a replicator with the given configuration.
     *
     * @param config the configuration
     */
    public Replicator(ReplicatorConfiguration config) { super(config); }


    @Override
    protected C4Replicator getC4ReplicatorLocked() throws LiteCoreException {
        final Endpoint target = config.getTarget();

        if (target instanceof URLEndpoint) { return getRemoteC4ReplicatorLocked(((URLEndpoint) target).getURL()); }

        if (target instanceof DatabaseEndpoint) {
            return getLocalC4ReplicatorLocked(((DatabaseEndpoint) target).getDatabase());
        }

        if (target instanceof MessageEndpoint) {
            return getMessageC4ReplicatorLocked(
                (((MessageEndpoint) target).getProtocolType() != ProtocolType.BYTE_STREAM)
                    ? C4Socket.NO_FRAMING
                    : C4Socket.WEB_SOCKET_CLIENT_FRAMING
            );
        }

        throw new IllegalStateException("unrecognized endpoint type: " + target);
    }
}
