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
 * The interface implemented by the application using a custom transportation
 * method to exchange replication data between peers.
 */
public interface MessageEndpointConnection {
    /**
     * Called to open a remote connection to the other peer when the replicator
     * starts or when the MessageEndpointListener accepts the connection.
     * When the remote connection is established, call the completion block to
     * acknowledge the completion.
     *
     * @param connection the replicator connection
     * @param completion the completion callback
     */
    void open(@NonNull ReplicatorConnection connection, @NonNull MessagingCompletion completion);

    /**
     * Called to close the remote connection with the other peer when the
     * replicator stops or when the MessageEndpointListener closes the connection.
     * When the remote connection is closed, call the completion block to acknowledge
     * the completion.
     *
     * @param completion the completion callback
     */
    void close(Exception error, @NonNull MessagingCloseCompletion completion);

    /**
     * Called to send the replication data to the other peer. When the replication
     * data is sent, call the completion block to acknowledge the completion.
     *
     * @param message    the message
     * @param completion the completion callback
     */
    void send(@NonNull Message message, @NonNull MessagingCompletion completion);
}
