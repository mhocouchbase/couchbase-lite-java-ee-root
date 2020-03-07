//
// ReplicatorConnection.java
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


/**
 * <b>ENTERPRISE EDITION API</b><br><br>
 * <p>
 * The replicator connection used by the application to tell the replicator to
 * consume the data received from the other peer or to close the connection.
 */
public interface ReplicatorConnection {
    /**
     * Tells the replicator to close the current replicator connection. In return,
     * the replicator will call the MessageEndpointConnection's close(error, completion)
     * to acknowledge the closed connection.
     *
     * @param error the error if any
     */
    void close(MessagingError error);

    /**
     * Tells the replicator to consume the data received from the other peer.
     *
     * @param message the message
     */
    void receive(@NonNull Message message);
}
