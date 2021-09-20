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
package com.couchbase.lite.mock;

import androidx.annotation.NonNull;

import java.util.concurrent.atomic.AtomicInteger;

import com.couchbase.lite.MessageEndpoint;
import com.couchbase.lite.MessageEndpointConnection;
import com.couchbase.lite.MessageEndpointDelegate;
import com.couchbase.lite.internal.utils.Fn;


public class MockConnectionFactory implements MessageEndpointDelegate {
    private final AtomicInteger connections = new AtomicInteger(0);
    private final MockClientConnection.ErrorLogic errorLogic;
    private final Fn.Consumer<MockConnection> onConnectionCreated;
    private final int maxConnections;

    public MockConnectionFactory(
        int maxConnections,
        Fn.Consumer<MockConnection> onConnectionCreated,
        MockClientConnection.ErrorLogic errorLogic) {
        this.errorLogic = errorLogic;
        this.maxConnections = maxConnections;
        this.onConnectionCreated = onConnectionCreated;
    }

    @NonNull
    @Override
    public MessageEndpointConnection createConnection(@NonNull MessageEndpoint endpoint) {
        final int connects = connections.incrementAndGet();
        if (connects > maxConnections) { throw new IllegalStateException("Too many connections: " + connects); }
        final MockClientConnection client = new MockClientConnection(endpoint, errorLogic);
        if (onConnectionCreated != null) { onConnectionCreated.accept(client); }
        return client;
    }
}
