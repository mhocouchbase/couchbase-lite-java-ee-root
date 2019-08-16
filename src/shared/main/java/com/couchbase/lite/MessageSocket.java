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
package com.couchbase.lite;

import android.support.annotation.NonNull;

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import com.couchbase.lite.internal.core.C4Constants;
import com.couchbase.lite.internal.core.C4Socket;
import com.couchbase.lite.internal.core.C4WebSocketCloseCode;


/* Internal MessageSocket for MessageEndpoint replication. */
class MessageSocket extends C4Socket implements ReplicatorConnection {
    // ---------------------------------------------------------------------------------------------
    // Variables
    // ---------------------------------------------------------------------------------------------

    //!!! EXECUTOR
    private final Executor finalizer = CouchbaseLite.getExecutionService().getSerialExecutor();

    private final ProtocolType protocolType;
    private final MessageEndpointConnection connection;

    private boolean sendResponseStatus;
    private boolean closed;

    // ---------------------------------------------------------------------------------------------
    // constructor
    // ---------------------------------------------------------------------------------------------

    MessageSocket(long handle, MessageEndpoint endpoint, Map<String, Object> unused) {
        this(endpoint.getDelegate().createConnection(endpoint), endpoint.getProtocolType(), true);

        // ??? Ikk: set superclass fields.
        setHandle(handle);
    }

    MessageSocket(MessageEndpointConnection connection, ProtocolType protocolType) {
        this(connection, protocolType, true);

        C4Socket.SOCKET_FACTORY.put(this, MessageSocket.class);

        final long handle = C4Socket.fromNative(
            this,
            "x-msg-conn",
            "",
            0,
            "/" + Integer.toHexString(connection.hashCode()),
            (protocolType != ProtocolType.BYTE_STREAM) ? C4Socket.NO_FRAMING : C4Socket.WEB_SOCKET_CLIENT_FRAMING);

        // ??? Ikk: set superclass fields.
        setHandle(handle);
    }

    private MessageSocket(MessageEndpointConnection connection, ProtocolType protocolType, boolean sendResponseStatus) {
        this.connection = connection;
        this.protocolType = protocolType;
        this.sendResponseStatus = sendResponseStatus;
    }

    // ---------------------------------------------------------------------------------------------
    // Abstract method implementation of C4Socket
    // ---------------------------------------------------------------------------------------------

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
        final Exception error = status != C4WebSocketCloseCode.kWebSocketCloseNormal ?
            CBLStatus.convertException(C4Constants.ErrorDomain.WEB_SOCKET, status, message, null) : null;
        connection.close(
            error,
            () -> {
                MessagingError messagingError = null;
                if (error != null) {
                    messagingError
                        = new MessagingError(error, status == C4WebSocketCloseCode.kWebSocketCloseUserTransient);
                }
                connectionClosed(messagingError);
            });
    }

    // ---------------------------------------------------------------------------------------------
    // ReplicatorConnection
    // ---------------------------------------------------------------------------------------------

    @Override
    public void close(final MessagingError error) {
        synchronized (this) {
            if (handle == 0L || closed) { return; }

            if (protocolType == ProtocolType.MESSAGE_STREAM) {
                final int status = getStatusCode(error);
                final String message = error == null ? "" : error.getError().getMessage();
                closeRequested(handle, status, message);
            }
        }

        if (protocolType == ProtocolType.BYTE_STREAM) {
            connection.close(error == null ? null : error.getError(), () -> connectionClosed(error));
        }
    }

    @Override
    public void receive(@NonNull Message message) {
        synchronized (this) {
            if (message == null) { throw new IllegalArgumentException("message cannot be null."); }

            if (handle == 0L || closed) { return; }
            received(handle, message.toData());
        }
    }


    // ---------------------------------------------------------------------------------------------
    // Socket Factory Callbacks
    // ---------------------------------------------------------------------------------------------
    // This method is called by reflection.  Don't change its name.
    @SuppressWarnings({"MethodName", "PMD.MethodNamingConventions"})
    public static void socket_open(
        long socket,
        Object socketFactoryContext,
        String scheme,
        String hostname,
        int port,
        String path,
        byte[] optionsFleece) {
        final MessageSocket messageSocket = (MessageSocket) C4Socket.REVERSE_LOOKUP_TABLE.get(socket);
        messageSocket.connection.open(
            messageSocket,
            (success, error) -> {
                if (success) { messageSocket.connectionOpened(); }
                else { messageSocket.connectionClosed(error); }
            });
    }

    // ---------------------------------------------------------------------------------------------
    // Package Level
    // ---------------------------------------------------------------------------------------------

    MessageEndpointConnection getConnection() {
        return connection;
    }

    //-------------------------------------------------------------------------
    // Acknowledgement
    //-------------------------------------------------------------------------

    void connectionOpened() {
        synchronized (this) {
            if (handle == 0L) { return; }

            if (sendResponseStatus) { connectionGotResponse(200, null); }
            opened(handle);
        }
    }

    void connectionClosed(MessagingError error) {
        synchronized (this) {
            if (handle == 0L || closed) { return; }

            closed = true;

            final int domain = error == null ? 0 : C4Constants.ErrorDomain.WEB_SOCKET;
            final int code = error != null ? getStatusCode(error) : 0;
            final String message = error != null ? error.getError().getMessage() : "";

            try { finalizer.execute(() -> closed(handle, domain, code, message)); }
            catch (RejectedExecutionException ignored) { }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Private methods
    // ---------------------------------------------------------------------------------------------

    private void messageSent(int byteCount) {
        synchronized (this) {
            if (handle == 0L || closed) { return; }
            completedWrite(byteCount);
        }
    }

    private void connectionGotResponse(int httpStatus, Map headers) {
        if (handle == 0L) { return; }
        gotHTTPResponse(handle, httpStatus, null);
        sendResponseStatus = false;
    }

    private int getStatusCode(MessagingError error) {
        if (error == null) { return C4WebSocketCloseCode.kWebSocketCloseNormal; }
        return error.isRecoverable()
            ? C4WebSocketCloseCode.kWebSocketCloseUserTransient
            : C4WebSocketCloseCode.kWebSocketCloseUserPermanent;
    }
}
