//
// MessageEndpointListener.java
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import com.couchbase.lite.internal.core.C4DocumentEnded;
import com.couchbase.lite.internal.core.C4Replicator;
import com.couchbase.lite.internal.core.C4ReplicatorListener;
import com.couchbase.lite.internal.core.C4ReplicatorMode;
import com.couchbase.lite.internal.core.C4ReplicatorStatus;
import com.couchbase.lite.internal.core.C4Socket;
import com.couchbase.lite.internal.fleece.FLEncoder;
import com.couchbase.lite.internal.replicator.MessageSocket;
import com.couchbase.lite.internal.support.Log;
import com.couchbase.lite.internal.utils.Preconditions;


/**
 * MessageEndpointListener to serve incoming message endpoint connection.
 */
public class MessageEndpointListener {
    private static final LogDomain DOMAIN = LogDomain.NETWORK;

    private class ReplicatorListener implements C4ReplicatorListener {
        ReplicatorListener() {}

        @Override
        public void statusChanged(C4Replicator repl, C4ReplicatorStatus status, Object context) {
            try { dispatcher.execute(() -> ((MessageEndpointListener) context).statusChanged(repl, status)); }
            catch (RejectedExecutionException ignored) { }
        }

        @Override
        public void documentEnded(C4Replicator u1, boolean u2, C4DocumentEnded[] u3, Object u4) { /* Not used */ }
    }

    //---------------------------------------------
    // member variables
    //---------------------------------------------

    private final Object lock = new Object();

    private final Executor dispatcher = CouchbaseLite.getExecutionService().getSerialExecutor();

    // protected by lock.
    private final Map<C4Replicator, MessageEndpointConnection> replicators = new HashMap<>();

    private final ChangeNotifier<MessageEndpointListenerChange> changeNotifier = new ChangeNotifier<>();

    private final MessageEndpointListenerConfiguration config;

    public MessageEndpointListener(@NonNull MessageEndpointListenerConfiguration config) {
        Preconditions.checkArgNotNull(config, "config");
        this.config = config;
    }

    /**
     * The active connections from peers.
     *
     * @return a list of active connections
     */
    List<MessageEndpointConnection> getConnections() {
        synchronized (lock) { return new ArrayList<>(replicators.values()); }
    }

    /**
     * Accept a new connection.
     *
     * @param connection new incoming connection
     */
    public void accept(@NonNull MessageEndpointConnection connection) {
        Preconditions.checkArgNotNull(connection, "connection");

        final MessageSocket socket = new MessageSocket(connection, config.getProtocolType());

        final FLEncoder encoder = new FLEncoder();
        encoder.beginDict(1);
        encoder.writeKey(C4Socket.REPLICATOR_OPTION_NO_INCOMING_CONFLICTS);
        encoder.writeValue(true);
        encoder.endDict();

        byte[] options = null;
        try { options = encoder.finish(); }
        catch (LiteCoreException e) { Log.e(DOMAIN, "Failed to encode", e); }
        finally { encoder.free(); }

        final C4ReplicatorListener statusListener = new ReplicatorListener();

        C4Replicator replicator = null;
        C4ReplicatorStatus status;
        synchronized (config.getDatabase().getLock()) {
            try {
                replicator = config.getDatabase().getC4Database().createReplicator(
                    socket,
                    C4ReplicatorMode.C4_PASSIVE,
                    C4ReplicatorMode.C4_PASSIVE,
                    options,
                    statusListener,
                    this);
                replicator.start();
                status = new C4ReplicatorStatus(C4ReplicatorStatus.ActivityLevel.CONNECTING);
            }
            catch (LiteCoreException e) {
                status = new C4ReplicatorStatus(C4ReplicatorStatus.ActivityLevel.STOPPED, e.domain, e.code);
            }
        }

        if (replicator != null) {
            synchronized (lock) { replicators.put(replicator, connection); }
        }

        changeNotifier.postChange(new MessageEndpointListenerChange(connection, status));
    }

    /**
     * Close the given connection.
     *
     * @param connection the connection to be closed
     */
    public void close(@NonNull MessageEndpointConnection connection) {
        Preconditions.checkArgNotNull(connection, "connection");

        synchronized (lock) {
            for (Map.Entry<C4Replicator, MessageEndpointConnection> entry : replicators.entrySet()) {
                if (connection.equals(entry.getValue())) {
                    entry.getKey().stop();
                    break;
                }
            }
        }
    }

    /**
     * Close all active connections.
     */
    public void closeAll() {
        synchronized (lock) {
            for (C4Replicator replicator : replicators.keySet()) { replicator.stop(); }
        }
    }

    /**
     * Add a change listener.
     *
     * @param listener the listener
     * @return listener identifier
     */
    @NonNull
    public ListenerToken addChangeListener(@NonNull MessageEndpointListenerChangeListener listener) {
        return addChangeListener(null, listener);
    }

    /**
     * Add a change listener with the given dispatch queue.
     *
     * @param queue the executor on which the listener will run
     * @param listener the listener
     * @return listener identifier
     */
    @NonNull
    public ListenerToken addChangeListener(Executor queue, @NonNull MessageEndpointListenerChangeListener listener) {
        Preconditions.checkArgNotNull(listener, "listener");
        return changeNotifier.addChangeListener(queue, listener);
    }

    /**
     * Remove a change listener.
     *
     * @param token identifier for the listener to be removed
     */
    public void removeChangeListener(@NonNull ListenerToken token) {
        Preconditions.checkArgNotNull(token, "token");
        changeNotifier.removeChangeListener(token);
    }

    //---------------------------------------------
    // Protected visibility
    //---------------------------------------------

    void statusChanged(C4Replicator replicator, C4ReplicatorStatus status) {
        final boolean stopped;
        final MessageEndpointConnection connection;
        synchronized (lock) {
            stopped = status.getActivityLevel() == C4ReplicatorStatus.ActivityLevel.STOPPED;
            connection = (stopped) ? replicators.remove(replicator) : replicators.get(replicator);
        }

        if (connection != null) { changeNotifier.postChange(new MessageEndpointListenerChange(connection, status)); }

        if (stopped) { replicator.free(); }
    }
}

