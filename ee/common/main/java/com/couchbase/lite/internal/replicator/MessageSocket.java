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
package com.couchbase.lite.internal.replicator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.couchbase.lite.CouchbaseLiteException;
import com.couchbase.lite.LogDomain;
import com.couchbase.lite.Message;
import com.couchbase.lite.MessageEndpointConnection;
import com.couchbase.lite.MessagingError;
import com.couchbase.lite.ReplicatorConnection;
import com.couchbase.lite.internal.CouchbaseLiteInternal;
import com.couchbase.lite.internal.core.C4Constants;
import com.couchbase.lite.internal.sockets.MessageFraming;
import com.couchbase.lite.internal.sockets.SocketFromCore;
import com.couchbase.lite.internal.sockets.SocketToCore;
import com.couchbase.lite.internal.support.Log;
import com.couchbase.lite.internal.utils.ClassUtils;


public abstract class MessageSocket implements ReplicatorConnection, SocketFromCore, AutoCloseable {
    private static final LogDomain LOG_DOMAIN = LogDomain.NETWORK;

    private static final class FramedStreamSocket extends MessageSocket {
        FramedStreamSocket(@NonNull SocketToCore toCore, @NonNull MessageEndpointConnection remote) {
            super(toCore, remote);
        }

        @Override
        public void remoteRequestedClose(@Nullable MessagingError err) {
            closeRemote(err == null ? null : err.getError(), err);
        }
    }

    private static final class UnframedStreamSocket extends MessageSocket {
        UnframedStreamSocket(@NonNull SocketToCore toCore, @NonNull MessageEndpointConnection remote) {
            super(toCore, remote);
        }

        @Override
        public void remoteRequestedClose(@Nullable MessagingError err) { requestCloseToCore(err); }
    }

    @NonNull
    public static MessageSocket create(
        @NonNull SocketToCore toCore,
        @NonNull MessageEndpointConnection remote,
        @NonNull MessageFraming framing) {
        final MessageSocket socket;
        switch (framing) {
            case NO_FRAMING:
                socket = new UnframedStreamSocket(toCore, remote);
                break;
            case CLIENT_FRAMING:
                socket = new FramedStreamSocket(toCore, remote);
                break;
            default:
                throw new IllegalStateException("unrecognised protocol: " + framing);
        }
        Log.d(LOG_DOMAIN, "%s.created", socket);
        return socket;
    }


    // ---------------------------------------------------------------------------------------------
    // Fields
    // ---------------------------------------------------------------------------------------------;

    @NonNull
    private final MessageEndpointConnection remote;
    @NonNull
    protected final SocketToCore toCore;

    // ---------------------------------------------------------------------------------------------
    // Constructors
    // ---------------------------------------------------------------------------------------------

    private MessageSocket(@NonNull SocketToCore toCore, @NonNull MessageEndpointConnection remote) {
        this.toCore = toCore;
        this.remote = remote;
    }

    protected abstract void remoteRequestedClose(@Nullable MessagingError err);

    @Override
    @NonNull
    public String toString() {
        return getClass().getSimpleName() + ClassUtils.objId(this) + "{" + toCore + " <=> " + remote + "}";
    }

    // ---------------------------------------------------------------------------------------------
    // Implementation of Autocloseable
    // ---------------------------------------------------------------------------------------------

    public void close() { Log.d(LOG_DOMAIN, "%s.close", this); }

    //-------------------------------------------------------------------------
    // Implementation of SocketFromCore (Core to Remote)
    //-------------------------------------------------------------------------

    // Core needs a connection to the remote
    @Override
    public void coreRequestedOpen() {
        if (CouchbaseLiteInternal.debugging()) { Log.d(LOG_DOMAIN, "%s.coreRequestedOpen", this); }
        remote.open(
            this,
            (success, error) -> {
                if (success) { ackOpenToCore(); }
                else { closeCore(error); }
            });
    }

    @Override
    public void coreWrites(@NonNull byte[] data) {
        final int dLen = data.length;
        Log.d(LOG_DOMAIN, "%s.coreWrites: %d", this, dLen);
        remote.send(
            Message.fromData(data),
            (success, error) -> {
                if (success) { ackMessageToCore(dLen); }
                else { close(error); }
            });
    }

    @Override
    public void coreAckReceive(long n) {
        Log.d(LOG_DOMAIN, "%s.coreAckReceive: %d", this, n);
    }

    @Override
    public void coreRequestedClose(int code, String msg) {
        Log.d(LOG_DOMAIN, "%s.coreRequestedClose(%d): %s", this, code, msg);
        final Exception error = (code == C4Constants.WebSocketError.NORMAL)
            ? null
            : CouchbaseLiteException.toCouchbaseLiteException(C4Constants.ErrorDomain.WEB_SOCKET, code, msg, null);

        final MessagingError messagingError = (error == null)
            ? null
            : new MessagingError(error, code == C4Constants.WebSocketError.USER_TRANSIENT);

        closeRemote(error, messagingError);
    }

    @Override
    public void coreClosed() {
        Log.d(LOG_DOMAIN, "%s.coreClosed", this);
        closeRemote(null, null);
    }

    // ---------------------------------------------------------------------------------------------
    // Implementation of ReplicatorConnection (Remote to Core)
    // ---------------------------------------------------------------------------------------------

    @Override
    public void receive(@NonNull Message msg) {
        Log.d(LOG_DOMAIN, "%s.remoteRequestedSend: %s", this, msg);
        if (msg == null) { return; }
        toCore.sendToCore(msg.toData());
    }

    @Override
    public void close(@Nullable MessagingError err) {
        Log.d(LOG_DOMAIN, "%s.remoteRequestedClose: %s", (err == null) ? null : err.getError(), this, err);
        remoteRequestedClose(err);
    }

    //-------------------------------------------------------------------------
    // Protected methods
    //-------------------------------------------------------------------------

    protected void requestCloseToCore(@Nullable MessagingError err) {
        Log.d(LOG_DOMAIN, "%s.requestCloseToCore: %s", this, err);
        toCore.requestCoreClose(getStatusCode(err), (err == null) ? null : err.getError().getMessage());
    }

    protected void closeRemote(@Nullable Exception error, @Nullable MessagingError err) {
        Log.d(LOG_DOMAIN, "%s.closeRemote (%s): %s", this, error, err);
        remote.close(error, () -> closeCore(err));
    }

    //-------------------------------------------------------------------------
    // Private methods
    //-------------------------------------------------------------------------

    private void ackOpenToCore() {
        Log.d(LOG_DOMAIN, "%s.ackOpenToCore", this);
        toCore.ackHttpToCore(200, null);
        toCore.ackOpenToCore();
    }

    private void ackMessageToCore(int byteCount) {
        Log.d(LOG_DOMAIN, "%s.ackMessageToCore (%d)", this, byteCount);
        toCore.ackWriteToCore(byteCount);
    }

    private void closeCore(@Nullable MessagingError err) {
        Log.d(LOG_DOMAIN, "%s.closeCore: %s", this, err);
        toCore.closeCore(
            (err == null) ? 0 : C4Constants.ErrorDomain.WEB_SOCKET,
            (err == null) ? 0 : getStatusCode(err),
            (err == null) ? null : err.getError().getMessage());
    }

    private int getStatusCode(@Nullable MessagingError error) {
        if (error == null) { return C4Constants.WebSocketError.NORMAL; }
        return error.isRecoverable()
            ? C4Constants.WebSocketError.USER_TRANSIENT
            : C4Constants.WebSocketError.USER_PERMANENT;
    }
}
