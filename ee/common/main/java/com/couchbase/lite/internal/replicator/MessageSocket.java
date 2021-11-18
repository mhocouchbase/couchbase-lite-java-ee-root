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

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.Executor;

import com.couchbase.lite.CouchbaseLiteException;
import com.couchbase.lite.LogDomain;
import com.couchbase.lite.Message;
import com.couchbase.lite.MessageEndpoint;
import com.couchbase.lite.MessageEndpointConnection;
import com.couchbase.lite.MessagingError;
import com.couchbase.lite.ProtocolType;
import com.couchbase.lite.ReplicatorConnection;
import com.couchbase.lite.internal.CouchbaseLiteInternal;
import com.couchbase.lite.internal.core.C4Constants;
import com.couchbase.lite.internal.sockets.CoreSocketDelegate;
import com.couchbase.lite.internal.sockets.CoreSocketListener;
import com.couchbase.lite.internal.support.Log;
import com.couchbase.lite.internal.utils.Preconditions;


/**
 * This class should be re-implemented using the paradigms described in C4Socket and AbstractCBLWebSocket.
 * It should be re-architected to use a single-threaded executor.
 * It might need to be a state machine.
 */
public class MessageSocket implements CoreSocketListener, ReplicatorConnection, AutoCloseable {
    private static final LogDomain TAG = LogDomain.NETWORK;

    // ---------------------------------------------------------------------------------------------
    // Fields
    // ---------------------------------------------------------------------------------------------;
    private final Executor finalizer = CouchbaseLiteInternal.getExecutionService().getSerialExecutor();

    @NonNull
    protected final CoreSocketDelegate delegate;
    @NonNull
    private final MessageEndpointConnection connection;
    @NonNull
    private final ProtocolType protocolType;

    @GuardedBy("getPeerLock()")
    private boolean sendResponseStatus = true;
    @GuardedBy("getPeerLock()")
    private boolean closing;

    // ---------------------------------------------------------------------------------------------
    // Constructors
    // ---------------------------------------------------------------------------------------------

    public MessageSocket(@NonNull CoreSocketDelegate delegate, @NonNull MessageEndpoint endpoint) {
        this(delegate, endpoint.getDelegate().createConnection(endpoint), endpoint.getProtocolType());
    }

    public MessageSocket(
        @NonNull CoreSocketDelegate delegate,
        @NonNull MessageEndpointConnection connection,
        @NonNull ProtocolType protocolType) {
        this.delegate = delegate;
        this.connection = connection;
        this.protocolType = protocolType;
    }

    @Override
    @NonNull
    public String toString() {
        return "MessageSocket{@" + super.toString() + ": " + protocolType + ", " + connection + "}";
    }

    // ---------------------------------------------------------------------------------------------
    // Implementation of Autocloseable
    // ---------------------------------------------------------------------------------------------

    public void close() { close(new MessagingError(new Exception("Closed by client"), false)); }

    // ---------------------------------------------------------------------------------------------
    // Implementation of ReplicatorConnection
    // ---------------------------------------------------------------------------------------------

    @Override
    public final void receive(@NonNull Message message) {
        Preconditions.assertNotNull(message, "message");
        Log.d(TAG, "%s#MessageSocket.receive: %s", this, message);
        synchronized (delegate.getLock()) {
            if (isClosing()) { return; }
            delegate.received(message.toData());
        }
    }

    @Override
    public final void close(@Nullable MessagingError error) {
        Log.d(TAG, "%s#MessageSocket.close: %s", this, error);
        synchronized (delegate.getLock()) {
            if (isClosing()) { return; }

            switch (protocolType) {
                case MESSAGE_STREAM:
                    delegate.closeRequested(getStatusCode(error), error == null ? "" : error.getError().getMessage());
                    break;

                case BYTE_STREAM:
                    closeConnection(error == null ? null : error.getError(), error);
                    break;

                default:
                    throw new IllegalStateException("Unrecognized protocol: " + protocolType);
            }
        }
    }

    //-------------------------------------------------------------------------
    // Implementation of CoreSocketListener (Core to Remote)
    //-------------------------------------------------------------------------

    @Override
    public final void onCoreRequestOpen() {
        Log.d(TAG, "%s#MessageSocket.Core connect", this);
        connection.open(
            this,
            (success, error) -> {
                if (success) { connectionOpened(); }
                else { connectionClosed(error); }
            });
    }

    @Override
    public final void onCoreSend(@NonNull byte[] data) {
        final int dLen = data.length;
        Log.d(TAG, "%s#MessageSocket.Core send: %d", this, dLen);
        connection.send(
            Message.fromData(data),
            (success, error) -> {
                if (success) { messageSent(dLen); }
                else { close(error); }
            });
    }

    @Override
    public final void onCoreCompletedReceive(long n) { Log.d(TAG, "%s#Core complete receive: %d", this, n); }

    @Override
    public final void onCoreRequestClose(int code, String msg) {
        Log.d(TAG, "%s#MessageSocket.Core request close (%d): %s", this, code, msg);
        final Exception error = (code == C4Constants.WebSocketError.NORMAL)
            ? null
            : CouchbaseLiteException.toCouchbaseLiteException(C4Constants.ErrorDomain.WEB_SOCKET, code, msg, null);
        final MessagingError messagingError = (error == null)
            ? null
            : new MessagingError(error, code == C4Constants.WebSocketError.USER_TRANSIENT);

        closeConnection(error, messagingError);
    }

    @Override
    public final void onCoreClosed() {
        Log.d(TAG, "%s#MessageSocket.Core closed", this);
        closeConnection(null, null);
    }

    //-------------------------------------------------------------------------
    // Private methods
    //-------------------------------------------------------------------------

    private void connectionOpened() {
        Log.d(TAG, "%s#MessageSocket.connectionOpened", this);
        synchronized (delegate.getLock()) {
            if (isClosing()) { return; }

            if (sendResponseStatus) { connectionGotResponse(200); }

            delegate.opened();
        }
    }

    private void messageSent(int byteCount) {
        Log.d(TAG, "%s#MessageSocket.messageSent (%d)", this, byteCount);
        synchronized (delegate.getLock()) {
            if (isClosing()) { return; }
            delegate.completedWrite(byteCount);
        }
    }

    private void closeConnection(@Nullable Exception error, @Nullable MessagingError messagingError) {
        Log.d(TAG, "%s#MessageSocket.closeConnection (%s): %s", this, error, messagingError);
        connection.close(error, () -> connectionClosed(messagingError));
    }

    private void connectionClosed(@Nullable MessagingError error) {
        Log.d(TAG, "%s#MessageSocket.connectionClosed: %s", this, error);
        synchronized (delegate.getLock()) {
            if (isClosing()) { return; }

            closing = true;

            final int domain = (error == null) ? 0 : C4Constants.ErrorDomain.WEB_SOCKET;
            final int code = (error != null) ? getStatusCode(error) : 0;
            final String msg = (error != null) ? error.getError().getMessage() : "";

            // I think that this is a weak attempt to guarantee that the actual call
            // to `closed` happens after any task that the caller might have enqueued.
            // ... of course, since there's no guess as to where the caller enqueued tasks....
            finalizer.execute(() -> delegate.closed(domain, code, msg));
        }
    }

    @GuardedBy("delegate.getLock()")
    private boolean isClosing() { return closing; }

    @GuardedBy("delegate.getLock()")
    private void connectionGotResponse(int httpStatus) {
        Log.d(TAG, "%s#MessageSocket.connectionGotResponse: %d", this, httpStatus);
        if (isClosing()) { return; }
        delegate.gotHTTPResponse(httpStatus, null);
        sendResponseStatus = false;
    }

    private int getStatusCode(@Nullable MessagingError error) {
        Log.d(TAG, "%s#MessageSocket.getStatusCode: %s", this, error);
        if (error == null) { return C4Constants.WebSocketError.NORMAL; }
        return error.isRecoverable()
            ? C4Constants.WebSocketError.USER_TRANSIENT
            : C4Constants.WebSocketError.USER_PERMANENT;
    }
}
