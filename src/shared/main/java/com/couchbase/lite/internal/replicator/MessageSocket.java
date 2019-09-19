//
// MessageSocket.java
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
package com.couchbase.lite.internal.replicator;

import android.support.annotation.NonNull;

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import com.couchbase.lite.CouchbaseLite;
import com.couchbase.lite.LogDomain;
import com.couchbase.lite.Message;
import com.couchbase.lite.MessageEndpoint;
import com.couchbase.lite.MessageEndpointConnection;
import com.couchbase.lite.MessagingError;
import com.couchbase.lite.ProtocolType;
import com.couchbase.lite.ReplicatorConnection;
import com.couchbase.lite.internal.CBLStatus;
import com.couchbase.lite.internal.core.C4Constants;
import com.couchbase.lite.internal.core.C4Socket;
import com.couchbase.lite.internal.core.C4WebSocketCloseCode;
import com.couchbase.lite.internal.support.Log;
import com.couchbase.lite.internal.utils.Preconditions;


/* Internal MessageSocket for MessageEndpoint replication. */
public class MessageSocket extends C4Socket implements ReplicatorConnection {

    // ---------------------------------------------------------------------------------------------
    // Variables
    // ---------------------------------------------------------------------------------------------

    private final Executor finalizer = CouchbaseLite.getExecutionService().getSerialExecutor();

    private final MessageEndpointConnection connection;
    private final ProtocolType protocolType;

    private boolean sendResponseStatus;
    private boolean closed;

    // ---------------------------------------------------------------------------------------------
    // constructors
    // ---------------------------------------------------------------------------------------------

    public MessageSocket(long handle, MessageEndpoint endpoint) {
        super(handle);
        this.connection = endpoint.getDelegate().createConnection(endpoint);
        this.protocolType = endpoint.getProtocolType();
        this.sendResponseStatus = true;
    }

    public MessageSocket(MessageEndpointConnection connection, ProtocolType protocolType) {
        super(
            "x-msg-conn",
            "",
            0,
            "/" + Integer.toHexString(connection.hashCode()),
            (protocolType != ProtocolType.BYTE_STREAM)
                ? C4Socket.NO_FRAMING
                : C4Socket.WEB_SOCKET_CLIENT_FRAMING);
        this.connection = connection;
        this.protocolType = protocolType;
        this.sendResponseStatus = true;
    }

    // ---------------------------------------------------------------------------------------------
    // Implementation of ReplicatorConnection
    // ---------------------------------------------------------------------------------------------

    @Override
    public void close(final MessagingError error) {
        synchronized (this) {
            if (released() || closed) { return; }

            if (protocolType == ProtocolType.BYTE_STREAM) {
                connection.close(error == null ? null : error.getError(), () -> connectionClosed(error));
                return;
            }

            if (protocolType == ProtocolType.MESSAGE_STREAM) {
                closeRequested(getStatusCode(error), error == null ? "" : error.getError().getMessage());
            }
        }
    }

    @Override
    public void receive(@NonNull Message message) {
        synchronized (this) {
            Preconditions.checkArgNotNull(message, "message");

            if (released() || closed) { return; }
            received(message.toData());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Implementation of Abstract methods from C4Socket
    // ---------------------------------------------------------------------------------------------

    @Override
    protected void openSocket() {
        connection.open(
            this,
            (success, error) -> {
                if (success) { connectionOpened(); }
                else { connectionClosed(error); }
            });
    }

    @Override // socket_write
    protected void send(byte[] allocatedData) {
        final int length = allocatedData.length;
        connection.send(
            Message.fromData(allocatedData),
            (success, error) -> {
                if (success) { messageSent(length); }
                else { close(error); }
            });
    }

    @Override // socket_completedReceive
    protected void completedReceive(long byteCount) { /* Not Implemented */ }

    @Override // socket_close
    protected void close() { connection.close(null, () -> connectionClosed(null)); }

    @Override // socket_requestClose
    protected void requestClose(final int status, String message) {
        final Exception error = (status == C4WebSocketCloseCode.kWebSocketCloseNormal)
            ? null
            : CBLStatus.convertException(C4Constants.ErrorDomain.WEB_SOCKET, status, message, null);
        final MessagingError messagingError = (error == null)
            ? null
            : new MessagingError(error, status == C4WebSocketCloseCode.kWebSocketCloseUserTransient);

        connection.close(error, () -> connectionClosed(messagingError));
    }

    // ---------------------------------------------------------------------------------------------
    // Package Level
    // ---------------------------------------------------------------------------------------------

    MessageEndpointConnection getConnection() { return connection; }

    //-------------------------------------------------------------------------
    // Acknowledgement
    //-------------------------------------------------------------------------

    void connectionOpened() {
        synchronized (this) {
            if (released()) { return; }

            if (sendResponseStatus) { connectionGotResponse(200, null); }

            opened();
        }
    }

    void connectionClosed(MessagingError error) {
        synchronized (this) {
            if (released() || closed) { return; }

            closed = true;

            final int domain = error == null ? 0 : C4Constants.ErrorDomain.WEB_SOCKET;
            final int code = error != null ? getStatusCode(error) : 0;
            final String message = error != null ? error.getError().getMessage() : "";

            try { finalizer.execute(() -> closed(domain, code, message)); }
            catch (RejectedExecutionException e) {
                Log.e(LogDomain.NETWORK, "Message socket cannot be closed", e);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Private methods
    // ---------------------------------------------------------------------------------------------

    private void messageSent(int byteCount) {
        synchronized (this) {
            if (released() || closed) { return; }
            completedWrite(byteCount);
        }
    }

    private void connectionGotResponse(int httpStatus, Map headers) {
        if (released()) { return; }
        gotHTTPResponse(httpStatus, null);
        sendResponseStatus = false;
    }

    private int getStatusCode(MessagingError error) {
        if (error == null) { return C4WebSocketCloseCode.kWebSocketCloseNormal; }
        return error.isRecoverable()
            ? C4WebSocketCloseCode.kWebSocketCloseUserTransient
            : C4WebSocketCloseCode.kWebSocketCloseUserPermanent;
    }
}
