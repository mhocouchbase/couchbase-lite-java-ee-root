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

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.couchbase.lite.LogLevel;
import com.couchbase.lite.Message;
import com.couchbase.lite.MessageEndpointConnection;
import com.couchbase.lite.MessagingCloseCompletion;
import com.couchbase.lite.MessagingCompletion;
import com.couchbase.lite.MessagingError;
import com.couchbase.lite.ReplicatorConnection;
import com.couchbase.lite.internal.utils.ClassUtils;
import com.couchbase.lite.internal.utils.Report;


public abstract class MockConnection implements MessageEndpointConnection {
    private final Deque<Message> messageQueue = new LinkedList<>();

    private final ExecutorService wire;

    private final String logName;

    protected ReplicatorConnection replicatorConnection;
    protected MessagingCloseCompletion onClose;
    protected boolean closing;

    MockConnection(@NonNull String logName) {
        this.logName = logName;
        wire = new ThreadPoolExecutor(
            1,
            1,
            30, TimeUnit.SECONDS,
            new LinkedBlockingDeque<>(),
            runnable -> {
                final Thread thread = new Thread(runnable, logName);
                Report.log(LogLevel.DEBUG, logPrefix() + " New thread: %s(%d)", thread.getName(), thread.getId());
                thread.setUncaughtExceptionHandler((t, e) ->
                    Report.log(LogLevel.INFO, e, logPrefix() + " Uncaught exception"));
                return thread;
            },
            // if the executor is shut down, just dump the message on the floor.
            (task, executor) ->
                Report.log(LogLevel.INFO, new Exception("REJECTED"), logPrefix() + " Rejected execution"));
    }

    abstract void openAsync(
        @NonNull final ReplicatorConnection connection,
        @NonNull final MessagingCompletion completion);

    abstract void sendAsync(@NonNull byte[] data, @NonNull MessagingCompletion completion);

    abstract void closeAsync(
        @NonNull MessagingCloseCompletion completion,
        @Nullable MessagingCloseCompletion closeCompletion);

    abstract void deliverAsync(@NonNull Message message, @NonNull ReplicatorConnection repl);

    abstract void closeReplAsync(@NonNull ReplicatorConnection repl, @Nullable MessagingError error);

    abstract void disconnectAsync(
        @Nullable MessagingError error,
        @NonNull MessagingCloseCompletion completion);

    @Override
    public void open(@NonNull final ReplicatorConnection repl, @NonNull final MessagingCompletion completion) {
        Report.log(LogLevel.DEBUG, logPrefix() + ".open *%s %s", ClassUtils.objId(repl), completion);

        List<Message> outstanding;
        synchronized (this) {
            closing = false;
            replicatorConnection = repl;
            outstanding = new ArrayList<>(messageQueue);
            messageQueue.clear();
        }

        wire.submit(() -> {
            Report.log(LogLevel.DEBUG, logPrefix() + " deliver open *%s %s", ClassUtils.objId(repl), completion);
            openAsync(repl, completion);
            for (Message message: outstanding) { deliver(message, repl); }
        });
    }

    @Override
    public void send(@NonNull final Message message, @NonNull final MessagingCompletion completion) {
        final byte[] msg = message.toData();
        final byte[] data = new byte[msg.length];
        System.arraycopy(msg, 0, data, 0, data.length);

        Report.log(LogLevel.DEBUG, logPrefix() + ".send(%d) %s", data.length, completion);
        wire.submit(() -> {
            Report.log(LogLevel.DEBUG, logPrefix() + " deliver send(%d) %s", data.length, completion);
            sendAsync(data, completion);
        });
    }

    @Override
    public void close(@Nullable final Exception e, @NonNull final MessagingCloseCompletion completion) {
        final MessagingCloseCompletion closeCompletion;
        synchronized (this) {
            closing = true;
            closeCompletion = onClose;
        }

        Report.log(LogLevel.DEBUG, e, logPrefix() + ".close %s", closeCompletion);
        wire.submit(() -> {
            Report.log(LogLevel.DEBUG, e, logPrefix() + " deliver close %s", closeCompletion);
            closeAsync(completion, closeCompletion);
        });
    }

    public void accept(@NonNull final byte[] data) {
        final byte[] msg = new byte[data.length];
        Report.log(LogLevel.DEBUG, logPrefix() + ".accept(%d)", data.length);

        System.arraycopy(data, 0, msg, 0, msg.length);
        final Message message = Message.fromData(msg);

        final ReplicatorConnection repl;
        synchronized (this) {
            repl = replicatorConnection;
            if (repl == null) {
                messageQueue.addLast(message);
                return;
            }
        }

        deliver(message, repl);
    }

    // Tell the connection to disconnect.
    public void disconnect(@Nullable MessagingError error, @NonNull MessagingCloseCompletion completion) {
        final boolean disconnecting;
        final ReplicatorConnection repl;
        synchronized (this) {
            disconnecting = !closing && (replicatorConnection != null);
            if (disconnecting) { onClose = completion; }
            repl = replicatorConnection;
        }

        Report.log(
            LogLevel.DEBUG,
            new Exception("DISCONNECT"),
            logPrefix() + ".disconnect(%s) %s, %s",
            disconnecting,
            error,
            completion);
        wire.submit(() -> {
            Report.log(
                LogLevel.DEBUG,
                new Exception("DISCONNECT"),
                logPrefix() + "deliver disconnect(%s) %s, %s",
                disconnecting,
                error,
                completion);
            if (disconnecting) { closeReplAsync(repl, error); }
            else { disconnectAsync(error, completion); }
        });
    }

    public void stop() { disconnect(null, this::terminate); }

    @NonNull
    @Override
    public String toString() {
        return "[" + ClassUtils.objId(this) + "]" + logName
            + "(*" + ClassUtils.objId(replicatorConnection) + "," + closing + ")";
    }

    protected String logPrefix() {
        final Thread t = Thread.currentThread();
        return "MOCK CONNECTION " + t.getName() + "(" + t.getId() + ") " + this;
    }

    private void deliver(@NonNull Message message, @NonNull ReplicatorConnection repl) {
        Report.log(LogLevel.DEBUG, logPrefix() + ".deliver(%d) *%s", message.toData().length, ClassUtils.objId(repl));
        wire.submit(() -> {
            Report.log(
                LogLevel.DEBUG,
                logPrefix() + "deliver message (%d) *%s",
                message.toData().length,
                ClassUtils.objId(repl));
            deliverAsync(message, repl);
        });
    }

    private void terminate() {
        Report.log(LogLevel.DEBUG, logPrefix() + ".terminate");
        wire.shutdown();
    }
}
