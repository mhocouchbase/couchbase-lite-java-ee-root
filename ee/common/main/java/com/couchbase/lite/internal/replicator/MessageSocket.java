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

import android.support.annotation.GuardedBy;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import java.util.concurrent.Executor;

import com.couchbase.lite.Message;
import com.couchbase.lite.MessageEndpoint;
import com.couchbase.lite.MessageEndpointConnection;
import com.couchbase.lite.MessagingError;
import com.couchbase.lite.ProtocolType;
import com.couchbase.lite.ReplicatorConnection;
import com.couchbase.lite.internal.CBLStatus;
import com.couchbase.lite.internal.CouchbaseLiteInternal;
import com.couchbase.lite.internal.core.C4Constants;
import com.couchbase.lite.internal.core.C4Socket;
import com.couchbase.lite.internal.utils.Preconditions;


/**
 * This class should be re-implemented using the paradigms described in C4Socket and AbstractCBLWebSocket.
 * It should be re-architected to use a single-threaded executor.
 * It might need to be a state machine.
 */
public class MessageSocket extends C4Socket implements ReplicatorConnection {

    // ---------------------------------------------------------------------------------------------
    // Fields
    // ---------------------------------------------------------------------------------------------;
    private final Executor finalizer = CouchbaseLiteInternal.getExecutionService().getSerialExecutor();

    private final MessageEndpointConnection connection;
    private final ProtocolType protocolType;

    @GuardedBy("getLock()")
    private boolean sendResponseStatus = true;
    @GuardedBy("getLock()")
    private boolean closing;

    // ---------------------------------------------------------------------------------------------
    // constructors
    // ---------------------------------------------------------------------------------------------

    public MessageSocket(MessageEndpointConnection connection, ProtocolType protocolType) {
        super(
            "x-msg-conn",
            "",
            0,
            "/" + Integer.toHexString(connection.hashCode()),
            (protocolType == ProtocolType.MESSAGE_STREAM)
                ? C4Socket.NO_FRAMING
                : C4Socket.WEB_SOCKET_CLIENT_FRAMING);
        this.connection = connection;
        this.protocolType = protocolType;
    }

    public MessageSocket(long peer, MessageEndpoint endpoint) {
        super(peer);
        this.connection = endpoint.getDelegate().createConnection(endpoint);
        this.protocolType = endpoint.getProtocolType();
    }

    @Override
    @NonNull
    public String toString() {
        return "MessageSocket{@" + super.toString() + ": " + protocolType + ", " + connection + "}";
    }

    public void close() { throw new UnsupportedOperationException("close() not supported for MessageSocket"); }

    // ---------------------------------------------------------------------------------------------
    // Implementation of ReplicatorConnection
    // ---------------------------------------------------------------------------------------------

    @Override
    public final void receive(@NonNull Message message) {
        Preconditions.assertNotNull(message, "message");
        synchronized (getLock()) {
            if (isClosing()) { return; }
            received(message.toData());
        }
    }

    @Override
    public final void close(@Nullable MessagingError error) {
        synchronized (getLock()) {
            if (isClosing()) { return; }

            switch (protocolType) {
                case MESSAGE_STREAM:
                    closeRequested(getStatusCode(error), error == null ? "" : error.getError().getMessage());
                    break;

                case BYTE_STREAM:
                    closeConnection(error == null ? null : error.getError(), error);
                    break;

                default:
                    throw new IllegalStateException("Unrecognized protocol: " + protocolType);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Implementation of abstract methods from C4Socket (Core to Remote)
    // ---------------------------------------------------------------------------------------------

    @Override
    protected final void openSocket() {
        connection.open(
            this,
            (success, error) -> {
                if (success) { connectionOpened(); }
                else { connectionClosed(error); }
            });
    }

    @Override // socket_write
    protected final void send(@NonNull byte[] allocatedData) {
        connection.send(
            Message.fromData(allocatedData),
            (success, error) -> {
                if (success) { messageSent(allocatedData.length); }
                else { close(error); }
            });
    }

    @Override // socket_completedReceive
    protected final void completedReceive(long byteCount) { }

    @Override // socket_requestClose
    protected final void requestClose(final int status, String message) {
        final Exception error = (status == C4Constants.WebSocketError.NORMAL)
            ? null
            : CBLStatus.toCouchbaseLiteException(C4Constants.ErrorDomain.WEB_SOCKET, status, message, null);
        final MessagingError messagingError = (error == null)
            ? null
            : new MessagingError(error, status == C4Constants.WebSocketError.USER_TRANSIENT);

        closeConnection(error, messagingError);
    }

    @Override // socket_close
    protected final void closeSocket() { closeConnection(null, null); }

    //-------------------------------------------------------------------------
    // Private methods
    //-------------------------------------------------------------------------

    private void connectionOpened() {
        synchronized (getLock()) {
            if (isClosing()) { return; }

            if (sendResponseStatus) { connectionGotResponse(200); }

            opened();
        }
    }

    private void messageSent(int byteCount) {
        synchronized (getLock()) {
            if (isClosing()) { return; }
            completedWrite(byteCount);
        }
    }

    private void closeConnection(@Nullable Exception error, @Nullable MessagingError messagingError) {
        connection.close(error, () -> connectionClosed(messagingError));
    }

    private void connectionClosed(MessagingError error) {
        synchronized (getLock()) {
            if (isClosing()) { return; }

            closing = true;

            final int domain = (error == null) ? 0 : C4Constants.ErrorDomain.WEB_SOCKET;
            final int code = (error != null) ? getStatusCode(error) : 0;
            final String msg = (error != null) ? error.getError().getMessage() : "";

            // I think that this is a weak attempt to guarantee that the actual call
            // to `closed` happens after any task that the caller might have enqueued.
            // ... of course, since there's no guess as to where the caller enqueued tasks....
            finalizer.execute(() -> closed(domain, code, msg));
        }
    }

    @GuardedBy("getLock()")
    private boolean isClosing() { return closing || isC4SocketClosing(); }

    @GuardedBy("getLock()")
    private void connectionGotResponse(int httpStatus) {
        if (isClosing()) { return; }
        gotHTTPResponse(httpStatus, null);
        sendResponseStatus = false;
    }

    private int getStatusCode(MessagingError error) {
        if (error == null) { return C4Constants.WebSocketError.NORMAL; }
        return error.isRecoverable()
            ? C4Constants.WebSocketError.USER_TRANSIENT
            : C4Constants.WebSocketError.USER_PERMANENT;
    }
}
