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

import org.junit.After;
import org.junit.Before;

import com.couchbase.lite.utils.Fn;
import com.couchbase.lite.utils.Report;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;


public abstract class BaseEEReplicatorTest extends BaseReplicatorTest {
    private static final String OTHERDB = "otherdb";
    protected static final int STD_TIMEOUT_SECS = 15;

    protected Database otherDB;

    @Before
    public void setUp() throws CouchbaseLiteException {
        super.setUp();

        otherDB = new Database(OTHERDB);

        assertNotNull(otherDB);
        assertTrue(otherDB.isOpen());

        try { Thread.sleep(500); }
        catch (Exception ignore) { }

    }

    @After
    public void tearDown() {
        if ((otherDB == null) || !otherDB.isOpen()) {
            Report.log(LogLevel.INFO, "expected otherDB to be open");
            return;
        }

        try { deleteDb(otherDB); }
        catch (CouchbaseLiteException e) {
            Report.log(LogLevel.ERROR, "Failed closing DB", e);
        }
        finally {
            otherDB = null;
            super.tearDown();
        }

        try { Thread.sleep(500); }
        catch (Exception ignore) { }
    }

    // helper method allows kotlin to call isDocumentPending(null)
    @SuppressWarnings("ConstantConditions")
    protected boolean callIsDocumentPendingWithNullId(Replicator repl) throws CouchbaseLiteException {
        return repl.isDocumentPending(null);
    }

    protected ReplicatorConfiguration pullConfig() { return pullConfig(null); }

    protected ReplicatorConfiguration pullConfig(ConflictResolver resolver) {
        return makeConfig(false, true, resolver);
    }

    protected ReplicatorConfiguration pushConfig() { return pushConfig(null); }

    protected ReplicatorConfiguration pushConfig(ConflictResolver resolver) {
        return makeConfig(true, false, resolver);
    }

    protected ReplicatorConfiguration pushPullConfig() { return pushPullConfig(null); }

    protected ReplicatorConfiguration pushPullConfig(ConflictResolver resolver) {
        return makeConfig(true, true, resolver);
    }

    protected ReplicatorConfiguration makeConfig(boolean push, boolean pull, ConflictResolver resolver) {
        return makeConfig(push, pull, false, baseTestDb, new DatabaseEndpoint(otherDB), resolver);
    }

    protected Replicator run(ReplicatorConfiguration config, int code, String domain) {
        return run(config, code, domain, false);
    }

    protected Replicator run(
        final ReplicatorConfiguration config,
        final int code,
        final String domain,
        final boolean ignoreErrorAtStopped) {
        return run(new Replicator(config), code, domain, ignoreErrorAtStopped, false, null);
    }

    protected Replicator run(
        final ReplicatorConfiguration config,
        final int code,
        final String domain,
        final boolean ignoreErrorAtStopped,
        final boolean reset,
        final Fn.Consumer<Replicator> onReady) {
        return run(new Replicator(config), code, domain, ignoreErrorAtStopped, reset, onReady);
    }

    protected Replicator run(final Replicator r, final int code, final String domain) {
        return run(r, code, domain, false, false, null);
    }

    protected Replicator run(
        final Replicator r,
        final int code,
        final String domain,
        final boolean ignoreErrorAtStopped,
        final boolean reset,
        final Fn.Consumer<Replicator> onReady) {
        repl = r;

        TestReplicatorChangeListener listener = new TestReplicatorChangeListener(
            r,
            domain,
            code,
            ignoreErrorAtStopped);

        if (reset) { repl.resetCheckpoint(); }

        if (onReady != null) { onReady.accept(repl); }

        ListenerToken token = repl.addChangeListener(testSerialExecutor, listener);
        boolean success;
        try {
            repl.start();
            success = listener.awaitCompletion(STD_TIMEOUT_SECS, TimeUnit.SECONDS);
        }
        finally {
            repl.removeChangeListener(token);
        }

        // see if the replication succeeded
        Throwable err = listener.getFailureReason();
        if (err != null) { throw new RuntimeException(err); }

        assertTrue(success);

        return repl;
    }

    protected Replicator push() {
        return run(new Replicator(pushConfig()), 0, null, false, false, null);
    }

    protected Replicator pull() {
        return run(new Replicator(pullConfig()), 0, null, false, false, null);
    }

    protected Replicator pushPull() {
        return run(new Replicator(pushPullConfig()), 0, null, false, false, null);
    }

    void stopContinuousReplicator(Replicator repl) throws InterruptedException {
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
