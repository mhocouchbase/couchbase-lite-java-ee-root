//
// Copyright (c) 2020, 2017 Couchbase, Inc All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
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
 * Connection Status
 */
public class ConnectionStatus {
    private final int connections;
    private final int activeConnections;

    public ConnectionStatus(int connectionCount, int activeConnectionCount) {
        this.connections = connectionCount;
        this.activeConnections = activeConnectionCount;
    }

    /**
     * Get the count of clients currently connected to this listener.
     *
     * @return number of clients currently connected to this listener.
     */
    public int getConnectionCount() { return connections; }

    /**
     * Get the count of clients that are currently actively transferring data.
     * Note: this number is highly volatile.  The actual number of active connections
     * may have changed by the time the call returns.
     *
     * @return number of connections that are currently active.
     */
    public int getActiveConnectionCount() { return activeConnections; }

    @NonNull
    @Override
    public String toString() { return "ConnectionStatus{" + connections + ", " + activeConnections + "}"; }
}
