package com.couchbase.lite;

import androidx.annotation.NonNull;

import java.net.SocketException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.After;
import org.junit.Test;

import com.couchbase.lite.internal.utils.Report;
import com.couchbase.lite.internal.utils.SlowTest;
import com.couchbase.lite.mock.MockClientConnection;
import com.couchbase.lite.mock.MockConnection;
import com.couchbase.lite.mock.MockConnectionFactory;
import com.couchbase.lite.mock.MockServerConnection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;


class EndpointListenerAwaiter implements MessageEndpointListenerChangeListener {
    private ListenerToken token;
    private final CountDownLatch latch = new CountDownLatch(1);
    private final ArrayList<Exception> exceptions = new ArrayList<>();
    private final MessageEndpointListener listener;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    public EndpointListenerAwaiter(MessageEndpointListener listener) {
        token = listener.addChangeListener(executorService, this);
        this.listener = listener;
    }

    @Override
    public void changed(@NonNull MessageEndpointListenerChange change) {
        if (change.getStatus().getError() != null) { exceptions.add(change.getStatus().getError()); }

        if (change.getStatus().getActivityLevel() != ReplicatorActivityLevel.STOPPED) { return; }

        listener.removeChangeListener(token);
        token = null;
        latch.countDown();
    }

    public void awaitAndValidate() throws InterruptedException {
        assertTrue(latch.await(BaseTest.LONG_TIMEOUT_SEC, TimeUnit.SECONDS));
        assertTrue(exceptions.isEmpty());
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (token != null) { listener.removeChangeListener(token); }
            executorService.shutdown();
        }
        finally {
            super.finalize();
        }
    }
}


class TestErrorLogic implements MockClientConnection.ErrorLogic {
    private final LifecycleLocation location;
    private MessagingError error;
    private int current;
    private int total;

    public TestErrorLogic(LifecycleLocation location) { this.location = location; }

    public static TestErrorLogic failWhen(LifecycleLocation location) {
        return new TestErrorLogic(location);
    }

    public void withRecoverableException() { withRecoverableException(1); }

    public synchronized void withRecoverableException(int count) {
        error = new MessagingError(new SocketException("Test Recoverable Exception"), true);
        total = count;
    }

    public synchronized void withPermanentException() {
        error = new MessagingError(new SocketException("Test Permanent Exception"), false);
        total = Integer.MAX_VALUE;
    }

    @Override
    public synchronized boolean shouldClose(LifecycleLocation location) {
        return (current < total) && (this.location == location);
    }

    @Override
    public synchronized MessagingError createError() {
        current++;
        return error;
    }

    @NonNull
    @Override
    public String toString() { return "TestErrorLogic{" + location + "@" + total + "}"; }
}


/////////////////////////////////   T E S T   S U I T E   //////////////////////////////////////

@SuppressWarnings("ConstantConditions")
public class MessageEndpointTest extends BaseReplicatorTest {
    static final Set<MockConnection> CONNECTIONS = new HashSet<>();

    static void addConnection(MockConnection connection) {
        synchronized (CONNECTIONS) { CONNECTIONS.add(connection); }
        Report.log(LogLevel.DEBUG, "Add connection: %s", connection);
    }

    static Set<MockConnection> getAndClearConnections() {
        final Set<MockConnection> connections = new HashSet<>();
        synchronized (CONNECTIONS) {
            connections.addAll(CONNECTIONS);
            CONNECTIONS.clear();
        }
        return connections;
    }

    @After
    public void tearDownMessageEndpointTest() {
        Set<MockConnection> connections = getAndClearConnections();
        Report.log(LogLevel.DEBUG, "Test exiting: %d", connections.size());
        for (MockConnection connection: connections) {
            try { connection.stop(); }
            catch (Exception e) { Report.log(LogLevel.WARNING, "failed closing connection", e); }
        }
    }

    @Test
    public void testPushDocWithMessage() throws CouchbaseLiteException {
        MutableDocument doc1 = new MutableDocument("doc1");
        doc1.setValue("name", "Tiger");
        saveDocInBaseTestDb(doc1);
        assertEquals(1, baseTestDb.getCount());

        MutableDocument doc2 = new MutableDocument("doc2");
        doc2.setValue("name", "Cat");
        otherDB.save(doc2);
        assertEquals(1, otherDB.getCount());

        run(
            makeConfig(
                new MessageEndpoint(
                    "UID:123",
                    getServerConnection("PushWithMsg", ProtocolType.MESSAGE_STREAM),
                    ProtocolType.MESSAGE_STREAM,
                    getConnectionFactory()),
                ReplicatorType.PUSH,
                false),
            0,
            null);

        assertEquals(2, otherDB.getCount());
        assertEquals("Tiger", otherDB.getDocument("doc1").getString("name"));
    }

    @Test
    public void testPushDocWithStream() throws CouchbaseLiteException {
        MutableDocument doc1 = new MutableDocument("doc1");
        doc1.setValue("name", "Tiger");
        saveDocInBaseTestDb(doc1);
        assertEquals(1, baseTestDb.getCount());

        MutableDocument doc2 = new MutableDocument("doc2");
        doc2.setValue("name", "Cat");
        otherDB.save(doc2);
        assertEquals(1, otherDB.getCount());

        run(
            makeConfig(
                new MessageEndpoint(
                    "UID:123",
                    getServerConnection("PushWithStream", ProtocolType.BYTE_STREAM),
                    ProtocolType.BYTE_STREAM,
                    getConnectionFactory()),
                ReplicatorType.PUSH,
                false),
            0,
            null);

        assertEquals(2, otherDB.getCount());
        assertEquals("Tiger", otherDB.getDocument("doc1").getString("name"));
    }

    @Test
    public void testPushDocContinuousWithMessage() throws CouchbaseLiteException {
        MutableDocument doc1 = new MutableDocument("doc1");
        doc1.setValue("name", "Tiger");
        saveDocInBaseTestDb(doc1);
        assertEquals(1, baseTestDb.getCount());

        MutableDocument doc2 = new MutableDocument("doc2");
        doc2.setValue("name", "Cat");
        otherDB.save(doc2);
        assertEquals(1, otherDB.getCount());

        run(
            makeConfig(
                new MessageEndpoint(
                    "UID:123",
                    getServerConnection("PushContinuousWithMsg", ProtocolType.MESSAGE_STREAM),
                    ProtocolType.MESSAGE_STREAM,
                    getConnectionFactory()),
                ReplicatorType.PUSH,
                true),
            0,
            null);

        assertEquals(2, otherDB.getCount());
        assertEquals("Tiger", otherDB.getDocument("doc1").getString("name"));
    }

    @Test
    public void testPushDocContinuousWithStream() throws CouchbaseLiteException {
        MutableDocument doc1 = new MutableDocument("doc1");
        doc1.setValue("name", "Tiger");
        saveDocInBaseTestDb(doc1);
        assertEquals(1, baseTestDb.getCount());

        MutableDocument doc2 = new MutableDocument("doc2");
        doc2.setValue("name", "Cat");
        otherDB.save(doc2);
        assertEquals(1, otherDB.getCount());

        run(
            makeConfig(
                new MessageEndpoint(
                    "UID:123",
                    getServerConnection("PushContinuousWithStream", ProtocolType.BYTE_STREAM),
                    ProtocolType.BYTE_STREAM,
                    getConnectionFactory()),
                ReplicatorType.PUSH,
                true),
            0,
            null);

        assertEquals(2, otherDB.getCount());
        assertEquals("Tiger", otherDB.getDocument("doc1").getString("name"));
    }

    @Test
    public void testPullDocWithMessage() throws CouchbaseLiteException {
        MutableDocument doc1 = new MutableDocument("doc1");
        doc1.setValue("name", "Tiger");
        saveDocInBaseTestDb(doc1);
        assertEquals(1, baseTestDb.getCount());

        MutableDocument doc2 = new MutableDocument("doc2");
        doc2.setValue("name", "Cat");
        otherDB.save(doc2);
        assertEquals(1, otherDB.getCount());

        run(
            makeConfig(
                new MessageEndpoint(
                    "UID:123",
                    getServerConnection("PullWithMsg", ProtocolType.MESSAGE_STREAM),
                    ProtocolType.MESSAGE_STREAM,
                    getConnectionFactory()),
                ReplicatorType.PULL,
                false),
            0,
            null);

        assertEquals(2, baseTestDb.getCount());
        assertEquals("Cat", baseTestDb.getDocument("doc2").getString("name"));
    }

    @Test
    public void testPullDocWithStream() throws CouchbaseLiteException {
        MutableDocument doc1 = new MutableDocument("doc1");
        doc1.setValue("name", "Tiger");
        saveDocInBaseTestDb(doc1);
        assertEquals(1, baseTestDb.getCount());

        MutableDocument doc2 = new MutableDocument("doc2");
        doc2.setValue("name", "Cat");
        otherDB.save(doc2);
        assertEquals(1, otherDB.getCount());

        run(
            makeConfig(
                new MessageEndpoint(
                    "UID:123",
                    getServerConnection("PullWithStream", ProtocolType.BYTE_STREAM),
                    ProtocolType.BYTE_STREAM,
                    getConnectionFactory()),
                ReplicatorType.PULL,
                false),
            0,
            null);

        assertEquals(2, baseTestDb.getCount());
        assertEquals("Cat", baseTestDb.getDocument("doc2").getString("name"));
    }

    @Test
    public void testPullDocContinuousWithMessage() throws CouchbaseLiteException {
        MutableDocument doc1 = new MutableDocument("doc1");
        doc1.setValue("name", "Tiger");
        saveDocInBaseTestDb(doc1);
        assertEquals(1, baseTestDb.getCount());

        MutableDocument doc2 = new MutableDocument("doc2");
        doc2.setValue("name", "Cat");
        otherDB.save(doc2);
        assertEquals(1, otherDB.getCount());

        run(
            makeConfig(
                new MessageEndpoint(
                    "UID:123",
                    getServerConnection("PushContinuousWithMsg", ProtocolType.MESSAGE_STREAM),
                    ProtocolType.MESSAGE_STREAM,
                    getConnectionFactory()),
                ReplicatorType.PULL,
                true),
            0,
            null);

        assertEquals(2, baseTestDb.getCount());
        assertEquals("Cat", baseTestDb.getDocument("doc2").getString("name"));
    }

    @Test
    public void testPullDocContinuousWithStream() throws CouchbaseLiteException {
        MutableDocument doc1 = new MutableDocument("doc1");
        doc1.setValue("name", "Tiger");
        saveDocInBaseTestDb(doc1);
        assertEquals(1, baseTestDb.getCount());

        MutableDocument doc2 = new MutableDocument("doc2");
        doc2.setValue("name", "Cat");
        otherDB.save(doc2);
        assertEquals(1, otherDB.getCount());

        run(
            makeConfig(
                new MessageEndpoint(
                    "UID:123",
                    getServerConnection("PullContinuousWithStream", ProtocolType.BYTE_STREAM),
                    ProtocolType.BYTE_STREAM,
                    getConnectionFactory()),
                ReplicatorType.PULL,
                true),
            0,
            null);

        assertEquals(2, baseTestDb.getCount());
        assertEquals("Cat", baseTestDb.getDocument("doc2").getString("name"));
    }

    @Test
    public void testPushPullDocWithMessage() throws CouchbaseLiteException {
        MutableDocument doc1 = new MutableDocument("doc1");
        doc1.setValue("name", "Tiger");
        saveDocInBaseTestDb(doc1);
        assertEquals(1, baseTestDb.getCount());

        MutableDocument doc2 = new MutableDocument("doc2");
        doc2.setValue("name", "Cat");
        otherDB.save(doc2);
        assertEquals(1, otherDB.getCount());

        run(
            makeConfig(
                new MessageEndpoint(
                    "UID:123",
                    getServerConnection("PushPullWithMsg", ProtocolType.MESSAGE_STREAM),
                    ProtocolType.MESSAGE_STREAM,
                    getConnectionFactory()),
                ReplicatorType.PUSH_AND_PULL,
                false),
            0,
            null);

        assertEquals(2, otherDB.getCount());
        assertEquals("Tiger", otherDB.getDocument("doc1").getString("name"));

        assertEquals(2, baseTestDb.getCount());
        assertEquals("Cat", baseTestDb.getDocument("doc2").getString("name"));
    }

    @Test
    public void testPushPullDocWithStream() throws CouchbaseLiteException {
        MutableDocument doc1 = new MutableDocument("doc1");
        doc1.setValue("name", "Tiger");
        saveDocInBaseTestDb(doc1);
        assertEquals(1, baseTestDb.getCount());

        MutableDocument doc2 = new MutableDocument("doc2");
        doc2.setValue("name", "Cat");
        otherDB.save(doc2);
        assertEquals(1, otherDB.getCount());

        run(
            makeConfig(
                new MessageEndpoint(
                    "UID:123",
                    getServerConnection("PushPullWithStream", ProtocolType.BYTE_STREAM),
                    ProtocolType.BYTE_STREAM,
                    getConnectionFactory()),
                ReplicatorType.PUSH_AND_PULL,
                false),
            0,
            null);

        assertEquals(2, otherDB.getCount());
        assertEquals("Tiger", otherDB.getDocument("doc1").getString("name"));

        assertEquals(2, baseTestDb.getCount());
        assertEquals("Cat", baseTestDb.getDocument("doc2").getString("name"));
    }

    @Test
    public void testPushPullDocContinuousWithMessage() throws CouchbaseLiteException {
        MutableDocument doc1 = new MutableDocument("doc1");
        doc1.setValue("name", "Tiger");
        saveDocInBaseTestDb(doc1);
        assertEquals(1, baseTestDb.getCount());

        MutableDocument doc2 = new MutableDocument("doc2");
        doc2.setValue("name", "Cat");
        otherDB.save(doc2);
        assertEquals(1, otherDB.getCount());

        run(
            makeConfig(
                new MessageEndpoint(
                    "UID:123",
                    getServerConnection("PushPullContinuousWithMsg", ProtocolType.MESSAGE_STREAM),
                    ProtocolType.MESSAGE_STREAM,
                    getConnectionFactory()),
                ReplicatorType.PUSH_AND_PULL,
                true),
            0,
            null);

        assertEquals(2, otherDB.getCount());
        assertEquals("Tiger", otherDB.getDocument("doc1").getString("name"));

        assertEquals(2, baseTestDb.getCount());
        assertEquals("Cat", baseTestDb.getDocument("doc2").getString("name"));
    }

    @Test
    public void testPushPullDocContinuousWithStream() throws CouchbaseLiteException {
        MutableDocument doc1 = new MutableDocument("doc1");
        doc1.setValue("name", "Tiger");
        saveDocInBaseTestDb(doc1);
        assertEquals(1, baseTestDb.getCount());

        MutableDocument doc2 = new MutableDocument("doc2");
        doc2.setValue("name", "Cat");
        otherDB.save(doc2);
        assertEquals(1, otherDB.getCount());

        run(
            makeConfig(
                new MessageEndpoint(
                    "UID:123",
                    getServerConnection("PushPullContinuousWithStream", ProtocolType.BYTE_STREAM),
                    ProtocolType.BYTE_STREAM,
                    getConnectionFactory()),
                ReplicatorType.PUSH_AND_PULL,
                true),
            0,
            null);

        assertEquals(2, otherDB.getCount());
        assertEquals("Tiger", otherDB.getDocument("doc1").getString("name"));

        assertEquals(2, baseTestDb.getCount());
        assertEquals("Cat", baseTestDb.getDocument("doc2").getString("name"));
    }

    @Test
    public void testP2PRecoverableFailureDuringOpen() throws CouchbaseLiteException {
        testP2PError("P2PRecoverableFailInOpen", MockClientConnection.ErrorLogic.LifecycleLocation.CONNECT, true);
    }

    @Test
    public void testP2PRecoverableFailureDuringSend() throws CouchbaseLiteException {
        testP2PError("P2PRecoverableFailInSend", MockClientConnection.ErrorLogic.LifecycleLocation.SEND, true);
    }

    @SlowTest
    @Test
    public void testP2PRecoverableFailureDuringReceive() throws CouchbaseLiteException {
        testP2PError(
            "P2PRecoverableFailInReceive",
            MockClientConnection.ErrorLogic.LifecycleLocation.RECEIVE,
            true);
    }

    @Test
    public void testP2PPermanentFailureDuringOpen() throws CouchbaseLiteException {
        testP2PError("P2PPermanentFailInOpen", MockClientConnection.ErrorLogic.LifecycleLocation.CONNECT, false);
    }

    @Test
    public void testP2PPermanentFailureDuringSend() throws CouchbaseLiteException {
        testP2PError("P2PPermanentFailInSend", MockClientConnection.ErrorLogic.LifecycleLocation.SEND, false);
    }

    @Test
    public void testP2PPermanentFailureDuringReceive() throws CouchbaseLiteException {
        testP2PError("P2PPermanentFailInReceive", MockClientConnection.ErrorLogic.LifecycleLocation.RECEIVE, false);
    }

    @SlowTest
    @Test
    public void testP2PPassiveClose() throws InterruptedException {
        MessageEndpointListener listener = new MessageEndpointListener(
            new MessageEndpointListenerConfiguration(otherDB, ProtocolType.MESSAGE_STREAM));
        EndpointListenerAwaiter awaiter = new EndpointListenerAwaiter(listener);

        MockServerConnection server = getServerConnection("P2PPassiveClose", listener);

        Replicator replicator = testReplicator(
            makeConfig(
                baseTestDb,
                new MessageEndpoint("p2ptest1", server, ProtocolType.MESSAGE_STREAM, getConnectionFactory()),
                ReplicatorType.PUSH_AND_PULL,
                true));

        final CountDownLatch latch = new CountDownLatch(2);
        final AtomicBoolean alreadyClosed = new AtomicBoolean(false);
        final ListenerToken token = replicator.addChangeListener(change -> {
            switch (change.getStatus().getActivityLevel()) {
                case IDLE:
                    if (alreadyClosed.getAndSet(true)) { return; }
                    latch.countDown();
                    listener.close(server);
                    return;
                case STOPPED:
                    latch.countDown();
                    return;
                case OFFLINE:
                    latch.countDown();
                    replicator.stop();
            }
        });

        try {
            replicator.start(false);

            assertTrue(latch.await(LONG_TIMEOUT_SEC, TimeUnit.SECONDS));

            awaiter.awaitAndValidate();
        }
        finally { replicator.removeChangeListener(token); }
    }

    @Test
    public void testCloseDbWithActiveMessageListener() throws InterruptedException, CouchbaseLiteException {
        final CountDownLatch startLatch = new CountDownLatch(2);
        final CountDownLatch listenerStopLatch = new CountDownLatch(1);
        final CountDownLatch replStopLatch = new CountDownLatch(1);

        final MessageEndpointListener listener = new MessageEndpointListener(
            new MessageEndpointListenerConfiguration(otherDB, ProtocolType.MESSAGE_STREAM));

        listener.addChangeListener(ch -> {
            switch (ch.getStatus().getActivityLevel()) {
                case CONNECTING:
                    startLatch.countDown();
                    break;
                case STOPPED:
                    listenerStopLatch.countDown();
                    break;
                default:
                    break;
            }
        });

        final Replicator repl = testReplicator(
            makeConfig(
                baseTestDb,
                new MessageEndpoint(
                    "p2ptest2",
                    getServerConnection("CloseDbWithActiveMessageListener", listener),
                    ProtocolType.MESSAGE_STREAM,
                    getConnectionFactory(1)),
                ReplicatorType.PUSH_AND_PULL,
                true));
        repl.addChangeListener(ch -> {
            switch (ch.getStatus().getActivityLevel()) {
                case CONNECTING:
                    startLatch.countDown();
                    break;
                case STOPPED:
                    replStopLatch.countDown();
                    break;
                default:
                    break;
            }
        });

        repl.start(false);
        assertTrue(startLatch.await(LONG_TIMEOUT_SEC, TimeUnit.SECONDS));

        otherDB.close();
        assertTrue(listenerStopLatch.await(LONG_TIMEOUT_SEC, TimeUnit.SECONDS));
        assertTrue(listener.isStopped());

        baseTestDb.close();
        assertTrue(replStopLatch.await(LONG_TIMEOUT_SEC, TimeUnit.SECONDS));
        final ReplicatorStatus status = repl.getStatus();
        assertEquals(ReplicatorActivityLevel.STOPPED, status.getActivityLevel());
        final CouchbaseLiteException err = status.getError();
        assertNotNull(err);
        assertEquals(CBLError.Code.WEB_SOCKET_GOING_AWAY, err.getCode());
    }

    @SlowTest
    @Test
    public void testP2PPassiveCloseAll() throws InterruptedException, CouchbaseLiteException {
        final CountDownLatch idleLatch = new CountDownLatch(2);
        final CountDownLatch stopLatch = new CountDownLatch(2);
        final CountDownLatch closeLatch = new CountDownLatch(2);

        final AtomicBoolean alreadyIdle1 = new AtomicBoolean(false);
        final AtomicBoolean alreadyIdle2 = new AtomicBoolean(false);

        final AtomicBoolean alreadyStopped1 = new AtomicBoolean(false);
        final AtomicBoolean alreadyStopped2 = new AtomicBoolean(false);
        final AtomicBoolean alreadyStopped3 = new AtomicBoolean(false);
        final AtomicBoolean alreadyStopped4 = new AtomicBoolean(false);

        MutableDocument doc = new MutableDocument("test");
        doc.setString("name", "smokey");
        baseTestDb.save(doc);

        final MessageEndpointListener listener = new MessageEndpointListener(
            new MessageEndpointListenerConfiguration(otherDB, ProtocolType.MESSAGE_STREAM));

        final MockServerConnection serverConnection1 = getServerConnection("P2PPassiveCloseAll1", listener);
        final MockServerConnection serverConnection2 = getServerConnection("P2PPassiveCloseAll2", listener);

        listener.addChangeListener(change -> {
            ReplicatorActivityLevel level = change.getStatus().getActivityLevel();
            final MessageEndpointConnection conn = change.getConnection();
            Report.log("Connection %s: %s", conn, level);

            if (!ReplicatorActivityLevel.STOPPED.equals(level)) { return; }

            if (conn.equals(serverConnection1)) {
                if (!alreadyStopped1.getAndSet(true)) { closeLatch.countDown(); }
                return;
            }

            if (conn.equals(serverConnection2)) {
                if (!alreadyStopped2.getAndSet(true)) { closeLatch.countDown(); }
                return;
            }

            fail("unrecognized connection: " + conn);
        });

        final Replicator replicator1 = testReplicator(makeConfig(
            baseTestDb,
            new MessageEndpoint(
                "p2ptest3",
                serverConnection1,
                ProtocolType.MESSAGE_STREAM,
                getConnectionFactory()),
            ReplicatorType.PUSH_AND_PULL,
            true));
        final ListenerToken token1 = replicator1.addChangeListener(change -> {
            ReplicatorActivityLevel level = change.getStatus().getActivityLevel();
            Report.log("Repl #1: %s", level);
            switch (level) {
                case IDLE:
                    if (!alreadyIdle1.getAndSet(true)) { idleLatch.countDown(); }
                    return;
                case STOPPED:
                    if (!alreadyStopped3.getAndSet(true)) { stopLatch.countDown(); }
                    return;
                case OFFLINE:
                    replicator1.stop();
            }
        });

        final Replicator replicator2 = testReplicator(makeConfig(
            baseTestDb,
            new MessageEndpoint(
                "p2ptest4",
                serverConnection2,
                ProtocolType.MESSAGE_STREAM,
                getConnectionFactory()),
            ReplicatorType.PUSH_AND_PULL,
            true));
        final ListenerToken token2 = replicator2.addChangeListener(change -> {
            ReplicatorActivityLevel level = change.getStatus().getActivityLevel();
            Report.log("Repl #2: %s", level);
            switch (level) {
                case IDLE:
                    if (!alreadyIdle2.getAndSet(true)) { idleLatch.countDown(); }
                    return;
                case STOPPED:
                    if (!alreadyStopped4.getAndSet(true)) { stopLatch.countDown(); }
                    return;
                case OFFLINE:
                    replicator2.stop();
            }
        });

        try {
            replicator1.start(false);
            Report.log("Repl #1 started");
            replicator2.start(false);
            Report.log("Repl #1 started");

            // wait for botth replicators to go idle.
            assertTrue(idleLatch.await(LONG_TIMEOUT_SEC, TimeUnit.SECONDS));
            Report.log("Replicators idle");

            listener.closeAll();

            // wait for botth replicators to stop.
            assertTrue(stopLatch.await(LONG_TIMEOUT_SEC, TimeUnit.SECONDS));
            Report.log("Replicators stopped");
        }
        finally {
            replicator1.removeChangeListener(token1);
            replicator2.removeChangeListener(token2);
        }

        // wait for the listener to get both stopped notifications
        assertTrue(closeLatch.await(LONG_TIMEOUT_SEC, TimeUnit.SECONDS));
    }

    @Test
    public void testP2PChangeListener() throws InterruptedException {
        final ArrayList<ReplicatorActivityLevel> statuses = new ArrayList<>();

        MessageEndpointListener listener = new MessageEndpointListener(
            new MessageEndpointListenerConfiguration(otherDB, ProtocolType.BYTE_STREAM));
        listener.addChangeListener(c -> statuses.add(c.getStatus().getActivityLevel()));

        EndpointListenerAwaiter awaiter = new EndpointListenerAwaiter(listener);

        run(
            makeConfig(
                baseTestDb,
                new MessageEndpoint(
                    "p2ptest5",
                    getServerConnection("P2PChangeListener", listener),
                    ProtocolType.BYTE_STREAM,
                    getConnectionFactory()),
                ReplicatorType.PUSH_AND_PULL, true),
            0,
            null);

        awaiter.awaitAndValidate();

        assertTrue(statuses.size() > 1);
    }

    @Test
    public void testRemoveChangeListener() throws InterruptedException {
        final ArrayList<ReplicatorActivityLevel> statuses = new ArrayList<>();

        MessageEndpointListener listener = new MessageEndpointListener(
            new MessageEndpointListenerConfiguration(otherDB, ProtocolType.BYTE_STREAM));
        ListenerToken token = listener.addChangeListener(c -> statuses.add(c.getStatus().getActivityLevel()));

        EndpointListenerAwaiter awaiter = new EndpointListenerAwaiter(listener);

        listener.removeChangeListener(token);

        run(
            makeConfig(
                baseTestDb,
                new MessageEndpoint(
                    "p2ptest6",
                    getServerConnection("RemoveChangeListener", listener),
                    ProtocolType.BYTE_STREAM,
                    getConnectionFactory()),
                ReplicatorType.PUSH_AND_PULL,
                true),
            0,
            null);

        awaiter.awaitAndValidate();

        assertEquals(0, statuses.size());
    }

    @Test
    public void testPushWithDocIDsFilter() throws CouchbaseLiteException {
        MutableDocument doc1 = new MutableDocument("doc1");
        doc1.setValue("name", "doc1");
        saveDocInBaseTestDb(doc1);

        MutableDocument doc2 = new MutableDocument("doc2");
        doc2.setValue("name", "doc2");
        saveDocInBaseTestDb(doc2);

        MutableDocument doc3 = new MutableDocument("doc3");
        doc3.setValue("name", "doc3");
        saveDocInBaseTestDb(doc3);

        ReplicatorConfiguration config = makeConfig(
            new MessageEndpoint(
                "UID:123",
                getServerConnection("PushWithDocIDsFilter", ProtocolType.BYTE_STREAM),
                ProtocolType.BYTE_STREAM,
                getConnectionFactory()),
            ReplicatorType.PUSH,
            false);
        config.setDocumentIDs(Arrays.asList("doc1", "doc3"));

        run(config, 0, null);

        assertEquals(2, otherDB.getCount());
        assertNotNull(otherDB.getDocument("doc1"));
        assertNotNull(otherDB.getDocument("doc3"));
        assertNull(otherDB.getDocument("doc2"));
    }

    @Test
    public void testPullWithDocIDsFilter() throws CouchbaseLiteException {
        MutableDocument doc1 = new MutableDocument("doc1");
        doc1.setValue("name", "doc1");
        otherDB.save(doc1);

        MutableDocument doc2 = new MutableDocument("doc2");
        doc2.setValue("name", "doc2");
        otherDB.save(doc2);

        MutableDocument doc3 = new MutableDocument("doc3");
        doc3.setValue("name", "doc3");
        otherDB.save(doc3);

        ReplicatorConfiguration config = makeConfig(
            new MessageEndpoint(
                "UID:123",
                getServerConnection("PullWithDocIDsFilter", ProtocolType.BYTE_STREAM),
                ProtocolType.BYTE_STREAM,
                getConnectionFactory()),
            ReplicatorType.PULL,
            false);
        config.setDocumentIDs(Arrays.asList("doc1", "doc3"));

        run(config, 0, null);

        assertEquals(2, baseTestDb.getCount());
        assertNotNull(baseTestDb.getDocument("doc1"));
        assertNotNull(baseTestDb.getDocument("doc3"));
        assertNull(baseTestDb.getDocument("doc2"));
    }

    @Test
    public void testPushPullWithDocIDsFilter() throws CouchbaseLiteException {
        MutableDocument doc1 = new MutableDocument("doc1");
        doc1.setValue("name", "doc1");
        baseTestDb.save(doc1);

        MutableDocument doc2 = new MutableDocument("doc2");
        doc2.setValue("name", "doc2");
        baseTestDb.save(doc2);

        MutableDocument doc3 = new MutableDocument("doc3");
        doc3.setValue("name", "doc3");
        otherDB.save(doc3);

        MutableDocument doc4 = new MutableDocument("doc4");
        doc4.setValue("name", "doc4");
        otherDB.save(doc4);

        ReplicatorConfiguration config = makeConfig(
            new MessageEndpoint(
                "UID:123",
                getServerConnection("PushPullWithFilter", ProtocolType.BYTE_STREAM),
                ProtocolType.BYTE_STREAM,
                getConnectionFactory()),
            ReplicatorType.PUSH_AND_PULL,
            false);
        config.setDocumentIDs(Arrays.asList("doc1", "doc4"));

        run(config, 0, null);

        assertEquals(3, baseTestDb.getCount());
        assertNotNull(baseTestDb.getDocument("doc1"));
        assertNotNull(baseTestDb.getDocument("doc2"));
        assertNotNull(baseTestDb.getDocument("doc4"));
        assertNull(baseTestDb.getDocument("doc3"));

        assertEquals(3, otherDB.getCount());
        assertNotNull(otherDB.getDocument("doc1"));
        assertNotNull(otherDB.getDocument("doc3"));
        assertNotNull(otherDB.getDocument("doc4"));
        assertNull(otherDB.getDocument("doc2"));
    }


    /////////////////////////////////   H E L P E R S   //////////////////////////////////////

    Throwable onStateChange(
        String expectedDomain,
        int expectedCode,
        CountDownLatch latch,
        ReplicatorChange change) {
        final ReplicatorStatus status = change.getStatus();
        final ReplicatorActivityLevel level = status.getActivityLevel();
        final CouchbaseLiteException error = status.getError();
        final ReplicatorProgress progress = status.getProgress();
        final long completed = progress.getCompleted();
        final long total = progress.getTotal();

        final Replicator repl = change.getReplicator();

        final StringBuilder buf = new StringBuilder("test listener @").append(level)
            .append('(').append(completed).append('/').append(total).append(')')
            .append(": ").append(repl);
        if (expectedCode != 0) {
            buf.append(" expecting: ").append(expectedDomain).append('/').append(expectedCode)
                .append(", got: ").append(error);
        }
        Report.log(LogLevel.INFO, buf.toString());

        // Verify change status:
        try { verifyChangeStatus(expectedDomain, expectedCode, change); }
        catch (Throwable e) {
            latch.countDown();
            return e;
        }

        switch (level) {
            // Stop a continuous replicator:
            case IDLE:
                if (repl.getConfig().isContinuous() && (completed >= total)) { repl.stop(); }
                break;
            case STOPPED:
                latch.countDown();
                break;
        }

        return null;
    }

    void verifyChangeStatus(
        String expectedDomain,
        int expectedCode,
        ReplicatorChange change)
        throws CouchbaseLiteException {
        ReplicatorStatus status = change.getStatus();

        ReplicatorActivityLevel level = status.getActivityLevel();
        if (level != ReplicatorActivityLevel.STOPPED) { return; }

        CouchbaseLiteException error = status.getError();
        if (expectedCode == 0) {
            if (error == null) { return; }
            throw error;
        }

        assertNotNull(error);
        assertTrue(
            (expectedCode == error.getCode())
                && ((expectedDomain == null) || (expectedDomain.equals(error.getDomain()))));
    }

    private void run(ReplicatorConfiguration config, final int code, final String domain) {
        run(config, code, domain, false);
    }

    private void run(ReplicatorConfiguration config, int code, String domain, boolean reset) {
        baseTestReplicator = testReplicator(config);
        final CountDownLatch latch = new CountDownLatch(1);

        final Throwable[] fail = new Throwable[1];
        ListenerToken token = baseTestReplicator.addChangeListener(
            testSerialExecutor,
            change -> fail[0] = onStateChange(domain, code, latch, change));

        baseTestReplicator.start(reset);

        boolean success = false;
        try { success = latch.await(LONG_TIMEOUT_SEC, TimeUnit.SECONDS); }
        catch (InterruptedException ignore) { }
        finally { baseTestReplicator.removeChangeListener(token); }

        if (fail[0] != null) { throw new AssertionError("Test failed with exception", fail[0]); }

        assertTrue(success);
    }

    private void testP2PError(
        String testName,
        MockClientConnection.ErrorLogic.LifecycleLocation location,
        boolean recoverable)
        throws CouchbaseLiteException {
        MutableDocument mdoc = new MutableDocument("LivesInDb");
        mdoc.setString("name", "db");
        baseTestDb.save(mdoc);

        String expectedDomain = recoverable ? null : CBLError.Domain.CBLITE;
        int expectedCode = recoverable ? 0 : CBLError.Code.WEB_SOCKET_CLOSE_USER_PERMANENT;

        Report.log(LogLevel.DEBUG, "Test %s with BYTE-STREAM protocol", testName);
        run(
            createFailureP2PConfig(testName, ProtocolType.BYTE_STREAM, location, recoverable),
            expectedCode,
            expectedDomain);

        Report.log(LogLevel.DEBUG, "Test %s with MESSAGE-STREAM protocol", testName);
        run(
            createFailureP2PConfig(testName, ProtocolType.MESSAGE_STREAM, location, recoverable),
            expectedCode,
            expectedDomain,
            true);
    }

    @NonNull
    private MockServerConnection getServerConnection(String testName, ProtocolType stream) {
        return getServerConnection(
            testName,
            new MessageEndpointListener(new MessageEndpointListenerConfiguration(otherDB, stream)));
    }

    @NonNull
    private MockServerConnection getServerConnection(String testName, MessageEndpointListener listener) {
        MockServerConnection server = new MockServerConnection(testName, listener);
        MessageEndpointTest.addConnection(server);
        return server;
    }

    private ReplicatorConfiguration createFailureP2PConfig(
        String testName,
        ProtocolType protocolType,
        MockClientConnection.ErrorLogic.LifecycleLocation location,
        boolean recoverable) {
        TestErrorLogic errorLocation = TestErrorLogic.failWhen(location);

        if (recoverable) { errorLocation.withRecoverableException(); }
        else { errorLocation.withPermanentException(); }

        MockServerConnection server = getServerConnection(testName, protocolType);
        return makeConfig(
            baseTestDb,
            new MessageEndpoint("p2ptest0", server, protocolType, getConnectionFactory(errorLocation)),
            ReplicatorType.PUSH,
            false);
    }

    private MockConnectionFactory getConnectionFactory() {
        return new MockConnectionFactory(Integer.MAX_VALUE, MessageEndpointTest::addConnection, null);
    }

    private MockConnectionFactory getConnectionFactory(int maxConnections) {
        return new MockConnectionFactory(maxConnections, MessageEndpointTest::addConnection, null);
    }

    private MockConnectionFactory getConnectionFactory(MockClientConnection.ErrorLogic errorLogic) {
        return new MockConnectionFactory(Integer.MAX_VALUE, MessageEndpointTest::addConnection, errorLogic);
    }
}
