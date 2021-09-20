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
import androidx.annotation.Nullable;

import com.couchbase.lite.BaseEEReplicatorTest;
import com.couchbase.lite.LogLevel;
import com.couchbase.lite.Message;
import com.couchbase.lite.MessageEndpointListener;
import com.couchbase.lite.MessagingCloseCompletion;
import com.couchbase.lite.MessagingCompletion;
import com.couchbase.lite.MessagingError;
import com.couchbase.lite.ReplicatorConnection;
import com.couchbase.lite.internal.utils.ClassUtils;
import com.couchbase.lite.internal.utils.Report;


public class MockServerConnection extends MockConnection {
    @NonNull
    private final MessageEndpointListener listener;

    @Nullable
    private MockClientConnection client;

    public MockServerConnection(@NonNull String testName, @NonNull MessageEndpointListener listener) {
        super(testName + ":Server");

        this.listener = listener;

        Report.log(
            LogLevel.DEBUG,
            logPrefix() + ".!<init>(%s, %s)",
            BaseEEReplicatorTest.getListenerProtocol(listener),
            listener);
    }

    public void clientOpened(MockClientConnection clientConnection) {
        Report.log(LogLevel.DEBUG, logPrefix() + ".!clientOpened =>%s", ClassUtils.objId(clientConnection));

        synchronized (this) { client = clientConnection; }
        listener.accept(this);
    }

    @Override
    void openAsync(@NonNull final ReplicatorConnection repl, @NonNull final MessagingCompletion completion) {
        Report.log(LogLevel.DEBUG, logPrefix() + ".!openAsync *%s %s", ClassUtils.objId(repl), completion);
        completion.complete(true, null);
    }

    @Override
    void sendAsync(@NonNull byte[] data, @NonNull MessagingCompletion completion) {
        final MockClientConnection clientConnection;
        synchronized (this) { clientConnection = client;}
        Report.log(
            LogLevel.DEBUG,
            logPrefix() + ".!sendAsync(%d) =>%s %s",
            data.length,
            (clientConnection == null) ? "null" : ClassUtils.objId(clientConnection),
            completion);

        if (clientConnection != null) { clientConnection.accept(data); }
        completion.complete(true, null);
    }

    @Override
    void closeAsync(
        @NonNull MessagingCloseCompletion completion,
        @Nullable MessagingCloseCompletion closeCompletion) {
        final MockClientConnection clientConnection;
        synchronized (this) { clientConnection = client; }
        Report.log(LogLevel.DEBUG, logPrefix() + ".!closeAsync =>%s %s", clientConnection, completion);

        if (closeCompletion != null) {
            closeCompletion.complete();
            completion.complete();
            return;
        }

        if (clientConnection != null) { clientConnection.disconnect(null, completion); }
    }

    @Override
    void deliverAsync(@NonNull Message msg, @NonNull ReplicatorConnection repl) {
        Report.log(LogLevel.DEBUG, logPrefix() + ".!deliverAsync(%d) *%s", msg.toData().length, ClassUtils.objId(repl));
        repl.receive(msg);
    }

    @Override
    void disconnectAsync(@Nullable MessagingError error, @NonNull MessagingCloseCompletion completion) {
        Report.log(LogLevel.DEBUG, logPrefix() + ".!disconnectAsync %s %s", error, completion);
        completion.complete();
    }

    @Override
    void closeReplAsync(@NonNull ReplicatorConnection repl, @Nullable MessagingError error) {
        Report.log(LogLevel.DEBUG, logPrefix() + ".!closeReplAsync *%s %s", ClassUtils.objId(repl), error);
        repl.close(error);
    }
}
