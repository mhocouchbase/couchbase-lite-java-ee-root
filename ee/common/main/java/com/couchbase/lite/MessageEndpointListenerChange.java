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

import com.couchbase.lite.internal.core.C4ReplicatorStatus;


/**
 * A change event posted by MessageEndpointListener.
 */
public class MessageEndpointListenerChange {
    private final MessageEndpointConnection connection;
    private final ReplicatorStatus status;

    MessageEndpointListenerChange(MessageEndpointConnection connection, C4ReplicatorStatus c4Status) {
        this.connection = connection;
        this.status = new ReplicatorStatus(c4Status);
    }

    /**
     * Return connection
     *
     * @return the connection
     */
    @NonNull
    public MessageEndpointConnection getConnection() { return connection; }

    /**
     * Return replicator status
     *
     * @return status
     */
    @NonNull
    public ReplicatorStatus getStatus() { return status; }
}
