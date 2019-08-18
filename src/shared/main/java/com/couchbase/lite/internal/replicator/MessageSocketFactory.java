//
// MessageSocketFactory.java
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

import java.util.Map;

import com.couchbase.lite.MessageEndpoint;
import com.couchbase.lite.MessagingCompletion;
import com.couchbase.lite.MessagingError;
import com.couchbase.lite.Replicator;
import com.couchbase.lite.internal.core.C4Socket;
import com.couchbase.lite.internal.fleece.FLValue;


/* Internal MessageSocket factory class. */
public class MessageSocketFactory {
    // This method is called by reflection.  Don't change its name.
    @SuppressWarnings("MethodName")
    public static void socket_open(
        long socket,
        Object socketFactoryContext,
        String scheme,
        String hostname,
        int port,
        String path,
        byte[] optionsFleece) {
        final Replicator replicator = C4Socket.SOCKET_FACTORY_CONTEXT.get(socketFactoryContext);
        final MessageEndpoint endpoint = (MessageEndpoint) replicator.getConfig().getTarget();
        Map<String, Object> options = null;
        if (optionsFleece != null) { options = FLValue.fromData(optionsFleece).asDict(); }

        final MessageSocket messageSocket = new MessageSocket(socket, endpoint, options);
        messageSocket.getConnection().open(messageSocket, new MessagingCompletion() {
            @Override
            public void complete(boolean success, MessagingError error) {
                if (success) { messageSocket.connectionOpened(); }
                else { messageSocket.connectionClosed(error); }
            }
        });
    }
}
