//
// BaseReplicatorTest.java
//
// Copyright (c) 2017 Couchbase, Inc All rights reserved.
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
package com.couchbase.lite;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.couchbase.lite.utils.Fn;

import static org.junit.Assert.assertTrue;


public abstract class BaseEEReplicatorTest extends BaseReplicatorTest {
    protected static final int STD_TIMEOUT_SECS = 5;


    // helper method allows kotlin to call isDocumentPending(null)
    @SuppressWarnings("ConstantConditions")
    protected final boolean callIsDocumentPendingWithNullId(Replicator repl) throws CouchbaseLiteException {
        return repl.isDocumentPending(null);
    }

    protected final ReplicatorConfiguration pullConfig() { return pullConfig(null); }

    protected final ReplicatorConfiguration pullConfig(ConflictResolver resolver) {
        return makeConfig(false, true, resolver);
    }

    protected final ReplicatorConfiguration pushConfig() { return pushConfig(null); }

    protected final ReplicatorConfiguration pushConfig(ConflictResolver resolver) {
        return makeConfig(true, false, resolver);
    }

    protected final ReplicatorConfiguration pushPullConfig() { return pushPullConfig(null); }

    protected final ReplicatorConfiguration pushPullConfig(ConflictResolver resolver) {
        return makeConfig(true, true, resolver);
    }

    protected final ReplicatorConfiguration makeConfig(boolean push, boolean pull, ConflictResolver resolver) {
        return makeConfig(push, pull, false, baseTestDb, new DatabaseEndpoint(otherDB), resolver);
    }

    protected final Replicator run(ReplicatorConfiguration config, int code, String domain) {
        return run(config, code, domain, false);
    }

    protected final Replicator run(
        ReplicatorConfiguration config,
        int code,
        String domain,
        boolean ignoreErrorAtStopped) {
        return run(new Replicator(config), code, domain, ignoreErrorAtStopped, false, null);
    }

    protected final Replicator run(
        ReplicatorConfiguration config,
        int code,
        String domain,
        boolean ignoreErrorAtStopped,
        boolean reset,
        Fn.Consumer<Replicator> onReady) {
        return run(new Replicator(config), code, domain, ignoreErrorAtStopped, reset, onReady);
    }

    protected final Replicator run(Replicator r, int code, String domain) {
        return run(r, code, domain, false, false, null);
    }

    protected final Replicator run(
        Replicator r,
        int code,
        String domain,
        boolean ignoreErrorAtStopped,
        boolean reset,
        Fn.Consumer<Replicator> onReady) {
        baseTestReplicator = r;

        TestReplicatorChangeListener listener = new TestReplicatorChangeListener(r, domain, code, ignoreErrorAtStopped);
        if (reset) { baseTestReplicator.resetCheckpoint(); }

        if (onReady != null) { onReady.accept(baseTestReplicator); }

        ListenerToken token = baseTestReplicator.addChangeListener(testSerialExecutor, listener);
        boolean success;
        try {
            baseTestReplicator.start();
            success = listener.awaitCompletion(STD_TIMEOUT_SECS, TimeUnit.SECONDS);
        }
        finally {
            baseTestReplicator.removeChangeListener(token);
        }

        // see if the replication succeeded
        Throwable err = listener.getFailureReason();
        if (err != null) { throw new RuntimeException(err); }

        assertTrue(success);

        return baseTestReplicator;
    }

    protected final Replicator push() { return run(new Replicator(pushConfig()), 0, null, false, false, null); }

    protected final Replicator pull() { return run(new Replicator(pullConfig()), 0, null, false, false, null); }

    protected final Replicator pushPull() { return run(new Replicator(pushPullConfig()), 0, null, false, false, null); }

    protected final void stopContinuousReplicator(Replicator repl) throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);
        ListenerToken token = repl.addChangeListener(
            testSerialExecutor,
            change -> {
                if (change.getStatus().getActivityLevel() == Replicator.ActivityLevel.STOPPED) { latch.countDown(); }
            });

        try {
            repl.stop();
            if (repl.getStatus().getActivityLevel() != Replicator.ActivityLevel.STOPPED) {
                assertTrue(latch.await(STD_TIMEOUT_SECS, TimeUnit.SECONDS));
            }
        }
        finally {
            repl.removeChangeListener(token);
        }
    }
}
