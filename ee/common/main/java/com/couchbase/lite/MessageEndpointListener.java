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

import android.support.annotation.GuardedBy;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import com.couchbase.lite.internal.CouchbaseLiteInternal;
import com.couchbase.lite.internal.core.C4DocumentEnded;
import com.couchbase.lite.internal.core.C4Replicator;
import com.couchbase.lite.internal.core.C4ReplicatorListener;
import com.couchbase.lite.internal.core.C4ReplicatorMode;
import com.couchbase.lite.internal.core.C4ReplicatorStatus;
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
        public void statusChanged(
            @Nullable C4Replicator repl,
            @Nullable C4ReplicatorStatus status,
            @Nullable Object context) {
            if ((!(context instanceof MessageEndpointListener)) || (status == null)) { return; }
            dispatcher.execute(() -> ((MessageEndpointListener) context).statusChanged(repl, status));
        }

        @Override
        public void documentEnded(
            @NonNull C4Replicator ign1,
            boolean ign2,
            @Nullable C4DocumentEnded[] ign3,
            @Nullable Object ign4) {
            /* Not used */
        }
    }

    //---------------------------------------------
    // member variables
    //---------------------------------------------

    private final Object lock = new Object();

    private final Executor dispatcher = CouchbaseLiteInternal.getExecutionService().getSerialExecutor();

    private final ChangeNotifier<MessageEndpointListenerChange> changeNotifier = new ChangeNotifier<>();

    private final AtomicBoolean stopped = new AtomicBoolean(false);

    private final MessageEndpointListenerConfiguration config;

    @GuardedBy("lock")
    private final Map<C4Replicator, MessageEndpointConnection> replicators = new HashMap<>();

    //---------------------------------------------
    // Constructor
    //---------------------------------------------

    public MessageEndpointListener(@NonNull MessageEndpointListenerConfiguration config) {
        Preconditions.assertNotNull(config, "config");
        this.config = config;
    }

    //---------------------------------------------
    // Public methods
    //---------------------------------------------

    /**
     * Accept a new connection.
     *
     * @param connection new incoming connection
     */
    public void accept(@NonNull MessageEndpointConnection connection) {
        Log.d(LogDomain.LISTENER, "Accepting connection: " + connection);
        Preconditions.assertNotNull(connection, "connection");

        if (stopped.get()) { return; }

        final byte[] options;
        try { options = getOptions(); }
        catch (LiteCoreException e) {
            // ??? shouldn't this just throw?
            Log.e(DOMAIN, "Failed getting encoding options", e);
            return;
        }

        final int passiveMode = C4ReplicatorMode.C4_PASSIVE.getVal();
        final Database db = config.getDatabase();
        final C4Replicator replicator;

        C4ReplicatorStatus status;
        synchronized (db.getLock()) {
            try {
                replicator = db.createTargetReplicator(
                    new MessageSocket(connection, config.getProtocolType()),
                    passiveMode,
                    passiveMode,
                    options,
                    new ReplicatorListener(),
                    this);

                if (addConnection(replicator, connection)) { db.registerMessageListener(this); }

                replicator.start(false);

                status = new C4ReplicatorStatus(C4ReplicatorStatus.ActivityLevel.CONNECTING);
            }
            catch (LiteCoreException e) {
                status = new C4ReplicatorStatus(C4ReplicatorStatus.ActivityLevel.STOPPED, e.domain, e.code);
            }
        }

        changeNotifier.postChange(new MessageEndpointListenerChange(connection, status));
    }

    /**
     * Close the given connection.
     *
     * @param connection the connection to be closed
     */
    public void close(@NonNull MessageEndpointConnection connection) {
        Log.d(LogDomain.LISTENER, "Closing connection: " + connection);
        Preconditions.assertNotNull(connection, "connection");

        C4Replicator replicator = null;
        synchronized (lock) {
            for (Map.Entry<C4Replicator, MessageEndpointConnection> entry: replicators.entrySet()) {
                if (connection.equals(entry.getValue())) {
                    replicator = entry.getKey();
                    break;
                }
            }
        }

        if (replicator != null) { replicator.stop(); }
    }

    /**
     * Close all connections active at the time of the call.
     */
    public void closeAll() {
        final List<C4Replicator> repls;
        synchronized (lock) { repls = new ArrayList<>(replicators.keySet()); }
        for (C4Replicator replicator: repls) { replicator.stop(); }
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
     * @param queue    the executor on which the listener will run
     * @param listener the listener
     * @return listener identifier
     */
    @NonNull
    public ListenerToken addChangeListener(Executor queue, @NonNull MessageEndpointListenerChangeListener listener) {
        Preconditions.assertNotNull(listener, "listener");
        return changeNotifier.addChangeListener(queue, listener);
    }

    /**
     * Remove a change listener.
     *
     * @param token identifier for the listener to be removed
     */
    public void removeChangeListener(@NonNull ListenerToken token) {
        Preconditions.assertNotNull(token, "token");
        changeNotifier.removeChangeListener(token);
    }

    //---------------------------------------------
    // Package visibility
    //---------------------------------------------

    void statusChanged(C4Replicator replicator, C4ReplicatorStatus status) {
        final MessageEndpointConnection connection
            = (status.getActivityLevel() != C4ReplicatorStatus.ActivityLevel.STOPPED)
            ? getConnection(replicator)
            : removeConnection(replicator);

        if (connection != null) { changeNotifier.postChange(new MessageEndpointListenerChange(connection, status)); }
    }

    boolean isStopped() {
        synchronized (lock) { return replicators.isEmpty(); }
    }

    void stop() {
        stopped.set(true);
        closeAll();
    }

    @VisibleForTesting
    MessageEndpointListenerConfiguration getConfig() { return config; }

    //---------------------------------------------
    // Private
    //---------------------------------------------

    private MessageEndpointConnection getConnection(@NonNull C4Replicator replicator) {
        synchronized (lock) { return replicators.get(replicator); }
    }

    private boolean addConnection(
        @NonNull C4Replicator replicator,
        @NonNull MessageEndpointConnection connection) {
        synchronized (lock) {
            replicators.put(replicator, connection);
            return replicators.size() == 1;
        }
    }

    private MessageEndpointConnection removeConnection(@NonNull C4Replicator replicator) {
        final boolean mustUnregister;
        final MessageEndpointConnection connection;
        synchronized (lock) {
            mustUnregister = replicators.size() == 1;
            connection = replicators.remove(replicator);
        }

        if (mustUnregister) { config.getDatabase().unregisterMessageListener(this); }

        return connection;
    }

    private byte[] getOptions() throws LiteCoreException {
        final FLEncoder encoder = new FLEncoder();
        try {
            encoder.beginDict(1);
            encoder.writeKey(C4Replicator.REPLICATOR_OPTION_NO_INCOMING_CONFLICTS);
            encoder.writeValue(true);
            encoder.endDict();
            return encoder.finish();
        }
        finally { encoder.free(); }
    }
}

