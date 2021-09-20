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

import com.couchbase.lite.LogLevel;
import com.couchbase.lite.Message;
import com.couchbase.lite.MessageEndpoint;
import com.couchbase.lite.MessagingCloseCompletion;
import com.couchbase.lite.MessagingCompletion;
import com.couchbase.lite.MessagingError;
import com.couchbase.lite.ReplicatorConnection;
import com.couchbase.lite.internal.utils.ClassUtils;
import com.couchbase.lite.internal.utils.Report;


public class MockClientConnection extends MockConnection {
    public interface ErrorLogic {
        enum LifecycleLocation {CONNECT, SEND, RECEIVE, CLOSE}

        boolean shouldClose(MockClientConnection.ErrorLogic.LifecycleLocation location);

        MessagingError createError();
    }

    public static final class NoErrorLogic implements MockClientConnection.ErrorLogic {
        public NoErrorLogic() { }

        @Override
        public boolean shouldClose(LifecycleLocation location) { return false; }

        @Override
        public MessagingError createError() { return null; }

        @NonNull
        @Override
        public String toString() { return "NoErrorLogic"; }
    }


    @NonNull
    protected final MockServerConnection server;
    @NonNull
    private final MockClientConnection.ErrorLogic errorLogic;

    private MessagingError error;

    public MockClientConnection(
        @NonNull MessageEndpoint endpoint,
        @Nullable MockClientConnection.ErrorLogic errorLogic) {
        super("Client");

        server = (MockServerConnection) endpoint.getTarget();
        this.errorLogic = (errorLogic != null) ? errorLogic : new NoErrorLogic();

        Report.log(LogLevel.DEBUG, logPrefix() + ".<init>(%s, %s)", endpoint, errorLogic);
    }

    @Override
    void openAsync(@NonNull ReplicatorConnection ignore, @NonNull MessagingCompletion completion) {
        final MessagingError err;
        synchronized (this) {
            error = null;
            err = getLogicError(MockClientConnection.ErrorLogic.LifecycleLocation.CONNECT);
        }
        Report.log(LogLevel.DEBUG, logPrefix() + ".openAsync %s %s", err, completion);

        final boolean succeeded = err == null;
        if (succeeded) { server.clientOpened(this); }
        completion.complete(succeeded, err);
    }

    @Override
    void sendAsync(@NonNull byte[] data, @NonNull MessagingCompletion completion) {
        final MessagingError err = getLogicError(MockClientConnection.ErrorLogic.LifecycleLocation.SEND);
        Report.log(LogLevel.DEBUG, logPrefix() + ".sendAsync(%d) %s %s", data.length, err, completion);
        final boolean succeeded = err == null;
        if (succeeded) { server.accept(data); }
        completion.complete(succeeded, err);
    }

    @Override
    void closeAsync(
        @NonNull MessagingCloseCompletion completion,
        @Nullable MessagingCloseCompletion closeCompletion) {
        Report.log(LogLevel.DEBUG, logPrefix() + ".closeAsync %s %s", completion, closeCompletion);
        if (closeCompletion != null) {
            closeCompletion.complete();
            completion.complete();
            return;
        }

        server.disconnect(getLogicError(MockClientConnection.ErrorLogic.LifecycleLocation.CLOSE), completion);
    }

    @Override
    void deliverAsync(@NonNull Message message, @NonNull ReplicatorConnection repl) {
        final MessagingError err = getLogicError(MockClientConnection.ErrorLogic.LifecycleLocation.RECEIVE);
        Report.log(
            LogLevel.DEBUG,
            logPrefix() + ".deliverAsync(%d) *%s %s",
            message.toData().length,
            ClassUtils.objId(repl),
            err);

        if (err != null) {
            repl.close(error);
            return;
        }

        repl.receive(message);
    }

    @Override
    void disconnectAsync(@Nullable MessagingError error, @NonNull MessagingCloseCompletion completion) {
        setError(error);
        Report.log(LogLevel.DEBUG, logPrefix() + ".disconnectAsync %s %s", error, completion);
        completion.complete();
    }

    @Override
    void closeReplAsync(@NonNull ReplicatorConnection repl, @Nullable MessagingError error) {
        Report.log(LogLevel.DEBUG, logPrefix() + ".closeReplAsync *%s %s", ClassUtils.objId(repl), error);
        setError(error);
        repl.close(error);
    }

    private void setError(@Nullable MessagingError error) {
        Report.log(LogLevel.DEBUG, logPrefix() + ".setError %s", error);
        synchronized (this) { this.error = error; }
    }

    private MessagingError getLogicError(MockClientConnection.ErrorLogic.LifecycleLocation loc) {
        MessagingError err;
        synchronized (this) {
            if (errorLogic.shouldClose(loc)) { error = errorLogic.createError(); }
            err = error;
        }
        Report.log(LogLevel.DEBUG, logPrefix() + ".getLogicError @%s: %s", loc, err);
        return err;
    }
}
