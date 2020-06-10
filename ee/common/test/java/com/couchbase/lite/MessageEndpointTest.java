package com.couchbase.lite;

import android.support.annotation.NonNull;

import java.net.SocketException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jetbrains.annotations.NotNull;
import org.junit.Test;

import com.couchbase.lite.utils.FlakyTest;
import com.couchbase.lite.utils.Report;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;


public class MessageEndpointTest extends BaseReplicatorTest {
    private static final long LONG_DELAY_SEC = 10;

    private enum MockConnectionLifecycleLocation {CONNECT, SEND, RECEIVE, CLOSE}


    ///////////////////////////////////// MOCK CONNECTION ERROR LOGIC /////////////////////////////////////

    private interface MockConnectionErrorLogic {
        boolean shouldClose(MockConnectionLifecycleLocation location);

        MessagingError createError();
    }


    /////////////////////////////////////  ERROR LOGIC /////////////////////////////////////

    private static final class NoErrorLogic implements MockConnectionErrorLogic {
        public NoErrorLogic() { }

        @Override
        public boolean shouldClose(MockConnectionLifecycleLocation location) { return false; }

        @Override
        public MessagingError createError() { return null; }
    }

    private static final class TestErrorLogic implements MockConnectionErrorLogic {
        private final MockConnectionLifecycleLocation location;
        private MessagingError error;
        private int current;
        private int total;

        public TestErrorLogic(MockConnectionLifecycleLocation locations) { this.location = locations; }

        public static TestErrorLogic failWhen(MockConnectionLifecycleLocation locations) {
            return new TestErrorLogic(locations);
        }

        public void withRecoverableException() { withRecoverableException(1); }

        public void withRecoverableException(int count) {
            error = new MessagingError(new SocketException("Test Recoverable Exception"), true);
            total = count;
        }

        public void withPermanentException() {
            error = new MessagingError(new SocketException("Test Permanent Exception"), false);
            total = Integer.MAX_VALUE;
        }

        @Override
        public boolean shouldClose(MockConnectionLifecycleLocation location) {
            return current < total && this.location == location;
        }

        @Override
        public MessagingError createError() {
            current++;
            return error;
        }
    }


    /////////////////////////////////////  LISTENER AWAITER  /////////////////////////////////////

    private static final class ListenerAwaiter implements MessageEndpointListenerChangeListener {
        private ListenerToken token;
        private final CountDownLatch latch = new CountDownLatch(1);
        private final ArrayList<Exception> exceptions = new ArrayList<>();
        private final MessageEndpointListener listener;
        private final ExecutorService executorService = Executors.newSingleThreadExecutor();

        public ListenerAwaiter(MessageEndpointListener listener) {
            token = listener.addChangeListener(executorService, this);
            this.listener = listener;
        }

        @Override
        protected void finalize() throws Throwable {
            if (token != null) { listener.removeChangeListener(token); }
            executorService.shutdown();
            super.finalize();
        }

        @Override
        public void changed(@NotNull @NonNull MessageEndpointListenerChange change) {
            if (change.getStatus().getError() != null) { exceptions.add(change.getStatus().getError()); }

            if (change.getStatus().getActivityLevel() != Replicator.ActivityLevel.STOPPED) { return; }

            listener.removeChangeListener(token);
            token = null;
            latch.countDown();
        }

        public void waitForListener() throws InterruptedException { latch.await(LONG_DELAY_SEC, TimeUnit.SECONDS); }

        public void validate() { assert (exceptions.isEmpty()); }
    }


    /////////////////////////////////////  MOCK CONNECTION  /////////////////////////////////////

    private static abstract class MockConnection implements MessageEndpointConnection {
        protected MockConnection remoteConnection;

        protected ReplicatorConnection replicatorConnection;

        protected final ScheduledExecutorService queue;

        private final boolean isClient;
        private final ProtocolType protocolType;

        private MockConnectionErrorLogic errorLogic;

        private MessagingCloseCompletion disconnectCompletion;

        private MessagingError error;

        private boolean isClosing;

        protected MockConnection(boolean isClient, ProtocolType protocolType) {
            this.isClient = isClient;
            this.protocolType = protocolType;
            queue = Executors.newSingleThreadScheduledExecutor();
        }

        @Override
        public void open(
            @NonNull final ReplicatorConnection connection,
            @NonNull final MessagingCompletion completion) {
            queue.submit(() -> {
                this.error = null;
                this.isClosing = false;
                replicatorConnection = connection;
                MockConnectionErrorLogic errorLogic = getErrorLogic();
                if (isClient() && errorLogic.shouldClose(MockConnectionLifecycleLocation.CONNECT)) {
                    this.error = errorLogic.createError();
                    completion.complete(false, this.error);
                }
                else {
                    completion.complete(true, null);
                }
            });
        }

        @Override
        public void send(@NonNull final Message message, @NonNull final MessagingCompletion completion) {
            queue.submit(() -> {
                MockConnectionErrorLogic errorLogic = getErrorLogic();
                if (isClient() && errorLogic.shouldClose(MockConnectionLifecycleLocation.SEND)) {
                    this.error = errorLogic.createError();
                    completion.complete(false, this.error);
                }
                else {
                    if (this.error == null) {
                        remoteConnection.acceptBytes(message.toData());
                        completion.complete(true, null);
                    }
                    else {
                        completion.complete(false, this.error);
                    }
                }
            });
        }

        @Override
        public void close(final Exception e, @NonNull final MessagingCloseCompletion completion) {
            queue.submit(() -> {
                isClosing = true;
                if (disconnectCompletion != null) {
                    disconnectCompletion.complete();
                    completion.complete();
                }
                else {
                    MockConnectionErrorLogic errorLogic = getErrorLogic();
                    if (isClient() && errorLogic.shouldClose(MockConnectionLifecycleLocation.CLOSE)) {
                        this.error = errorLogic.createError();
                    }
                    remoteConnection.disconnect(this.error, completion);
                }
            });
        }

        protected boolean isClient() { return isClient; }

        protected MockConnectionErrorLogic getErrorLogic() {
            if (errorLogic == null) { errorLogic = new NoErrorLogic(); }
            return errorLogic;
        }

        protected void setErrorLogic(MockConnectionErrorLogic errorLogic) { this.errorLogic = errorLogic; }

        protected void acceptBytes(final byte[] data) {
            queue.submit(() -> {
                synchronized (this) {
                    MockConnectionErrorLogic errorLogic = getErrorLogic();
                    if (isClient() && errorLogic.shouldClose(MockConnectionLifecycleLocation.RECEIVE)) {
                        this.error = errorLogic.createError();
                        replicatorConnection.close(this.error);
                    }
                    else {
                        if (this.error == null) {
                            replicatorConnection.receive(Message.fromData(data));
                        }
                    }
                }
            });
        }

        // Tell the connection to disconnect.
        protected void disconnect(MessagingError error, MessagingCloseCompletion completion) {
            queue.submit(() -> {
                this.error = error;
                if (replicatorConnection != null && !isClosing) {
                    disconnectCompletion = completion;
                    replicatorConnection.close(this.error);
                }
                else {
                    completion.complete();
                }
            });
        }

        @Override
        public void finalize() throws Throwable {
            super.finalize();
            queue.shutdown();
        }
    }


    /////////////////////////////////////  MOCK CLIENT CONNECTION  /////////////////////////////////////

    private final class MockClientConnection extends MockConnection {
        private final MockServerConnection server;

        public MockClientConnection(MessageEndpoint endpoint) {
            super(true, endpoint.getProtocolType());
            server = (MockServerConnection) endpoint.getTarget();
            remoteConnection = server;
        }

        @Override
        public void open(@NonNull ReplicatorConnection connection, @NonNull final MessagingCompletion completion) {
            super.open(connection, (success, error) -> {
                if (success) { server.clientOpened(MockClientConnection.this); }
                completion.complete(success, error);
            });
        }
    }


    /////////////////////////////////////  MOCK SERVER CONNECTION  /////////////////////////////////////

    private final class MockServerConnection extends MockConnection {
        private final MessageEndpointListener listener;

        public MockServerConnection(MessageEndpointListener listener, ProtocolType protocolType) {
            super(false, protocolType);
            this.listener = listener;
        }

        public MockServerConnection(Database database, ProtocolType protocolType) {
            this(new MessageEndpointListener(
                new MessageEndpointListenerConfiguration(database, protocolType)), protocolType);
        }

        protected void clientOpened(MockClientConnection client) {
            remoteConnection = client;
            listener.accept(this);
        }
    }

    private final class MockConnectionFactory implements MessageEndpointDelegate {
        private final MockConnectionErrorLogic errorLogic;

        public MockConnectionFactory(MockConnectionErrorLogic errorLogic) {
            this.errorLogic = errorLogic;
        }

        @NonNull
        @Override
        public MessageEndpointConnection createConnection(@NonNull MessageEndpoint endpoint) {
            MockClientConnection retVal = new MockClientConnection(endpoint);
            retVal.setErrorLogic(errorLogic);
            return retVal;
        }
    }


    /////////////////////////////////   T E S T S   //////////////////////////////////////

    @Test
    public void testPushDocWithMessage() throws Exception {
        MutableDocument doc1 = new MutableDocument("doc1");
        doc1.setValue("name", "Tiger");
        saveDocInBaseTestDb(doc1);
        assertEquals(1, baseTestDb.getCount());

        MutableDocument doc2 = new MutableDocument("doc2");
        doc2.setValue("name", "Cat");
        otherDB.save(doc2);
        assertEquals(1, otherDB.getCount());

        MockServerConnection server = new MockServerConnection(otherDB, ProtocolType.MESSAGE_STREAM);
        MessageEndpoint endpoint
            = new MessageEndpoint("UID:123", server, ProtocolType.MESSAGE_STREAM, new MockConnectionFactory(null));
        ReplicatorConfiguration config
            = makeConfig(AbstractReplicatorConfiguration.ReplicatorType.PUSH, false, endpoint);
        run(config, 0, null);

        assertEquals(2, otherDB.getCount());
        Document savedDoc = otherDB.getDocument("doc1");
        assertEquals("Tiger", savedDoc.getString("name"));
    }

    @Test
    public void testPushDocWithStream() throws Exception {
        MutableDocument doc1 = new MutableDocument("doc1");
        doc1.setValue("name", "Tiger");
        saveDocInBaseTestDb(doc1);
        assertEquals(1, baseTestDb.getCount());

        MutableDocument doc2 = new MutableDocument("doc2");
        doc2.setValue("name", "Cat");
        otherDB.save(doc2);
        assertEquals(1, otherDB.getCount());

        MockServerConnection server = new MockServerConnection(otherDB, ProtocolType.BYTE_STREAM);
        MessageEndpoint endpoint
            = new MessageEndpoint("UID:123", server, ProtocolType.BYTE_STREAM, new MockConnectionFactory(null));
        ReplicatorConfiguration config
            = makeConfig(AbstractReplicatorConfiguration.ReplicatorType.PUSH, false, endpoint);
        run(config, 0, null);

        assertEquals(2, otherDB.getCount());
        Document savedDoc = otherDB.getDocument("doc1");
        assertEquals("Tiger", savedDoc.getString("name"));
    }

    @Test
    public void testPushDocContinuousWithMessage() throws Exception {
        MutableDocument doc1 = new MutableDocument("doc1");
        doc1.setValue("name", "Tiger");
        saveDocInBaseTestDb(doc1);
        assertEquals(1, baseTestDb.getCount());

        MutableDocument doc2 = new MutableDocument("doc2");
        doc2.setValue("name", "Cat");
        otherDB.save(doc2);
        assertEquals(1, otherDB.getCount());

        MockServerConnection server = new MockServerConnection(otherDB, ProtocolType.MESSAGE_STREAM);
        MessageEndpoint endpoint
            = new MessageEndpoint("UID:123", server, ProtocolType.MESSAGE_STREAM, new MockConnectionFactory(null));
        ReplicatorConfiguration config
            = makeConfig(AbstractReplicatorConfiguration.ReplicatorType.PUSH, true, endpoint);
        run(config, 0, null);

        assertEquals(2, otherDB.getCount());
        Document savedDoc = otherDB.getDocument("doc1");
        assertEquals("Tiger", savedDoc.getString("name"));
    }

    @Test
    public void testPushDocContinuousWithStream() throws Exception {
        MutableDocument doc1 = new MutableDocument("doc1");
        doc1.setValue("name", "Tiger");
        saveDocInBaseTestDb(doc1);
        assertEquals(1, baseTestDb.getCount());

        MutableDocument doc2 = new MutableDocument("doc2");
        doc2.setValue("name", "Cat");
        otherDB.save(doc2);
        assertEquals(1, otherDB.getCount());

        MockServerConnection server = new MockServerConnection(otherDB, ProtocolType.BYTE_STREAM);
        MessageEndpoint endpoint
            = new MessageEndpoint("UID:123", server, ProtocolType.BYTE_STREAM, new MockConnectionFactory(null));
        ReplicatorConfiguration config
            = makeConfig(AbstractReplicatorConfiguration.ReplicatorType.PUSH, true, endpoint);
        run(config, 0, null);

        assertEquals(2, otherDB.getCount());
        Document savedDoc = otherDB.getDocument("doc1");
        assertEquals("Tiger", savedDoc.getString("name"));
    }

    @Test
    public void testPullDocWithMessage() throws Exception {
        MutableDocument doc1 = new MutableDocument("doc1");
        doc1.setValue("name", "Tiger");
        saveDocInBaseTestDb(doc1);
        assertEquals(1, baseTestDb.getCount());

        MutableDocument doc2 = new MutableDocument("doc2");
        doc2.setValue("name", "Cat");
        otherDB.save(doc2);
        assertEquals(1, otherDB.getCount());

        MockServerConnection server = new MockServerConnection(otherDB, ProtocolType.MESSAGE_STREAM);
        MessageEndpoint endpoint
            = new MessageEndpoint("UID:123", server, ProtocolType.MESSAGE_STREAM, new MockConnectionFactory(null));
        ReplicatorConfiguration config
            = makeConfig(AbstractReplicatorConfiguration.ReplicatorType.PULL, false, endpoint);
        run(config, 0, null);

        assertEquals(2, baseTestDb.getCount());
        Document savedDoc = baseTestDb.getDocument("doc2");
        assertEquals("Cat", savedDoc.getString("name"));
    }

    @Test
    public void testPullDocWithStream() throws Exception {
        MutableDocument doc1 = new MutableDocument("doc1");
        doc1.setValue("name", "Tiger");
        saveDocInBaseTestDb(doc1);
        assertEquals(1, baseTestDb.getCount());

        MutableDocument doc2 = new MutableDocument("doc2");
        doc2.setValue("name", "Cat");
        otherDB.save(doc2);
        assertEquals(1, otherDB.getCount());

        MockServerConnection server = new MockServerConnection(otherDB, ProtocolType.BYTE_STREAM);
        MessageEndpoint endpoint
            = new MessageEndpoint("UID:123", server, ProtocolType.BYTE_STREAM, new MockConnectionFactory(null));
        ReplicatorConfiguration config
            = makeConfig(AbstractReplicatorConfiguration.ReplicatorType.PULL, false, endpoint);
        run(config, 0, null);

        assertEquals(2, baseTestDb.getCount());
        Document savedDoc = baseTestDb.getDocument("doc2");
        assertEquals("Cat", savedDoc.getString("name"));
    }

    @Test
    public void testPullDocContinuousWithMessage() throws Exception {
        MutableDocument doc1 = new MutableDocument("doc1");
        doc1.setValue("name", "Tiger");
        saveDocInBaseTestDb(doc1);
        assertEquals(1, baseTestDb.getCount());

        MutableDocument doc2 = new MutableDocument("doc2");
        doc2.setValue("name", "Cat");
        otherDB.save(doc2);
        assertEquals(1, otherDB.getCount());

        MockServerConnection server = new MockServerConnection(otherDB, ProtocolType.MESSAGE_STREAM);
        MessageEndpoint endpoint
            = new MessageEndpoint("UID:123", server, ProtocolType.MESSAGE_STREAM, new MockConnectionFactory(null));
        ReplicatorConfiguration config
            = makeConfig(AbstractReplicatorConfiguration.ReplicatorType.PULL, true, endpoint);
        run(config, 0, null);

        assertEquals(2, baseTestDb.getCount());
        Document savedDoc = baseTestDb.getDocument("doc2");
        assertEquals("Cat", savedDoc.getString("name"));
    }

    @Test
    public void testPullDocContinuousWithStream() throws Exception {
        MutableDocument doc1 = new MutableDocument("doc1");
        doc1.setValue("name", "Tiger");
        saveDocInBaseTestDb(doc1);
        assertEquals(1, baseTestDb.getCount());

        MutableDocument doc2 = new MutableDocument("doc2");
        doc2.setValue("name", "Cat");
        otherDB.save(doc2);
        assertEquals(1, otherDB.getCount());

        MockServerConnection server = new MockServerConnection(otherDB, ProtocolType.BYTE_STREAM);
        MessageEndpoint endpoint
            = new MessageEndpoint("UID:123", server, ProtocolType.BYTE_STREAM, new MockConnectionFactory(null));
        ReplicatorConfiguration config
            = makeConfig(AbstractReplicatorConfiguration.ReplicatorType.PULL, true, endpoint);
        run(config, 0, null);

        assertEquals(2, baseTestDb.getCount());
        Document savedDoc = baseTestDb.getDocument("doc2");
        assertEquals("Cat", savedDoc.getString("name"));
    }

    @Test
    public void testPushPullDocWithMessage() throws Exception {
        MutableDocument doc1 = new MutableDocument("doc1");
        doc1.setValue("name", "Tiger");
        saveDocInBaseTestDb(doc1);
        assertEquals(1, baseTestDb.getCount());

        MutableDocument doc2 = new MutableDocument("doc2");
        doc2.setValue("name", "Cat");
        otherDB.save(doc2);
        assertEquals(1, otherDB.getCount());

        MockServerConnection server = new MockServerConnection(otherDB, ProtocolType.MESSAGE_STREAM);
        MessageEndpoint endpoint
            = new MessageEndpoint("UID:123", server, ProtocolType.MESSAGE_STREAM, new MockConnectionFactory(null));
        ReplicatorConfiguration config
            = makeConfig(AbstractReplicatorConfiguration.ReplicatorType.PUSH_AND_PULL, false, endpoint);
        run(config, 0, null);

        assertEquals(2, otherDB.getCount());
        Document savedDoc1 = otherDB.getDocument("doc1");
        assertEquals("Tiger", savedDoc1.getString("name"));

        assertEquals(2, baseTestDb.getCount());
        Document savedDoc2 = baseTestDb.getDocument("doc2");
        assertEquals("Cat", savedDoc2.getString("name"));
    }

    @Test
    public void testPushPullDocWithStream() throws Exception {
        MutableDocument doc1 = new MutableDocument("doc1");
        doc1.setValue("name", "Tiger");
        saveDocInBaseTestDb(doc1);
        assertEquals(1, baseTestDb.getCount());

        MutableDocument doc2 = new MutableDocument("doc2");
        doc2.setValue("name", "Cat");
        otherDB.save(doc2);
        assertEquals(1, otherDB.getCount());

        MockServerConnection server = new MockServerConnection(otherDB, ProtocolType.BYTE_STREAM);
        MessageEndpoint endpoint
            = new MessageEndpoint("UID:123", server, ProtocolType.BYTE_STREAM, new MockConnectionFactory(null));
        ReplicatorConfiguration config
            = makeConfig(AbstractReplicatorConfiguration.ReplicatorType.PUSH_AND_PULL, false, endpoint);
        run(config, 0, null);

        assertEquals(2, otherDB.getCount());
        Document savedDoc1 = otherDB.getDocument("doc1");
        assertEquals("Tiger", savedDoc1.getString("name"));

        assertEquals(2, baseTestDb.getCount());
        Document savedDoc2 = baseTestDb.getDocument("doc2");
        assertEquals("Cat", savedDoc2.getString("name"));
    }

    @Test
    public void testPushPullDocContinuousWithMessage() throws Exception {
        MutableDocument doc1 = new MutableDocument("doc1");
        doc1.setValue("name", "Tiger");
        saveDocInBaseTestDb(doc1);
        assertEquals(1, baseTestDb.getCount());

        MutableDocument doc2 = new MutableDocument("doc2");
        doc2.setValue("name", "Cat");
        otherDB.save(doc2);
        assertEquals(1, otherDB.getCount());

        MockServerConnection server = new MockServerConnection(otherDB, ProtocolType.MESSAGE_STREAM);
        MessageEndpoint endpoint
            = new MessageEndpoint("UID:123", server, ProtocolType.MESSAGE_STREAM, new MockConnectionFactory(null));
        ReplicatorConfiguration config = makeConfig(
            AbstractReplicatorConfiguration.ReplicatorType.PUSH_AND_PULL,
            true,
            endpoint);

        run(config, 0, null);

        assertEquals(2, otherDB.getCount());
        Document savedDoc1 = otherDB.getDocument("doc1");
        assertEquals("Tiger", savedDoc1.getString("name"));

        assertEquals(2, baseTestDb.getCount());
        Document savedDoc2 = baseTestDb.getDocument("doc2");
        assertEquals("Cat", savedDoc2.getString("name"));
    }

    @Test
    public void testPushPullDocContinuousWithStream() throws Exception {
        MutableDocument doc1 = new MutableDocument("doc1");
        doc1.setValue("name", "Tiger");
        saveDocInBaseTestDb(doc1);
        assertEquals(1, baseTestDb.getCount());

        MutableDocument doc2 = new MutableDocument("doc2");
        doc2.setValue("name", "Cat");
        otherDB.save(doc2);
        assertEquals(1, otherDB.getCount());

        MockServerConnection server = new MockServerConnection(otherDB, ProtocolType.BYTE_STREAM);
        MessageEndpoint endpoint
            = new MessageEndpoint("UID:123", server, ProtocolType.BYTE_STREAM, new MockConnectionFactory(null));
        ReplicatorConfiguration config
            = makeConfig(AbstractReplicatorConfiguration.ReplicatorType.PUSH_AND_PULL, true, endpoint);
        run(config, 0, null);

        assertEquals(2, otherDB.getCount());
        Document savedDoc1 = otherDB.getDocument("doc1");
        assertEquals("Tiger", savedDoc1.getString("name"));

        assertEquals(2, baseTestDb.getCount());
        Document savedDoc2 = baseTestDb.getDocument("doc2");
        assertEquals("Cat", savedDoc2.getString("name"));
    }

    @Test
    public void testP2PRecoverableFailureDuringOpen() throws Exception {
        testP2PError(MockConnectionLifecycleLocation.CONNECT, true);
    }

    @FlakyTest
    @Test
    public void testP2PRecoverableFailureDuringSend() throws Exception {
        testP2PError(MockConnectionLifecycleLocation.SEND, true);
    }

    @FlakyTest
    @Test
    public void testP2PRecoverableFailureDuringReceive() throws Exception {
        testP2PError(MockConnectionLifecycleLocation.RECEIVE, true);
    }

    @Test
    public void testP2PPermanentFailureDuringOpen() throws Exception {
        testP2PError(MockConnectionLifecycleLocation.CONNECT, false);
    }

    @Test
    public void testP2PPermanentFailureDuringSend() throws Exception {
        testP2PError(MockConnectionLifecycleLocation.SEND, false);
    }

    @Test
    public void testP2PPermanentFailureDuringReceive() throws Exception {
        testP2PError(MockConnectionLifecycleLocation.RECEIVE, false);
    }

    @FlakyTest
    @Test
    public void testP2PPassiveClose() throws Exception {
        MessageEndpointListener listener = new MessageEndpointListener(
            new MessageEndpointListenerConfiguration(otherDB, ProtocolType.MESSAGE_STREAM));
        ListenerAwaiter awaiter = new ListenerAwaiter(listener);
        MockServerConnection serverConnection = new MockServerConnection(listener, ProtocolType.MESSAGE_STREAM);
        ReplicatorConfiguration config = new ReplicatorConfiguration(
            baseTestDb,
            new MessageEndpoint(
                "p2ptest1",
                serverConnection,
                ProtocolType.MESSAGE_STREAM,
                new MockConnectionFactory(null)));
        config.setContinuous(true);

        Replicator replicator = new Replicator(config);

        final AtomicBoolean didCloseListener = new AtomicBoolean(false);
        final CountDownLatch latch = new CountDownLatch(3);
        final ListenerToken token = replicator.addChangeListener(change -> {
            if (change.getStatus().getActivityLevel() == Replicator.ActivityLevel.IDLE) {
                if (!didCloseListener.getAndSet(true)) {
                    latch.countDown();
                    listener.close(serverConnection);
                }
            }

            if (change.getStatus().getActivityLevel() == Replicator.ActivityLevel.OFFLINE) {
                latch.countDown();
                replicator.stop();
            }

            if (change.getStatus().getActivityLevel() == Replicator.ActivityLevel.STOPPED) {
                latch.countDown();
            }
        });

        replicator.start(false);

        latch.await(LONG_DELAY_SEC, TimeUnit.SECONDS);
        awaiter.waitForListener();
        awaiter.validate();

        replicator.removeChangeListener(token);
    }

    @Test
    public void testP2PPassiveCloseAll() throws InterruptedException, CouchbaseLiteException {
        MutableDocument doc = new MutableDocument("test");
        doc.setString("name", "smokey");
        baseTestDb.save(doc);

        final MessageEndpointListener listener = new MessageEndpointListener(
            new MessageEndpointListenerConfiguration(otherDB, ProtocolType.MESSAGE_STREAM));

        final MockServerConnection serverConnection1 = new MockServerConnection(listener, ProtocolType.MESSAGE_STREAM);
        final ReplicatorConfiguration config1 = new ReplicatorConfiguration(baseTestDb, new MessageEndpoint(
            "p2ptest1", serverConnection1, ProtocolType.MESSAGE_STREAM, new MockConnectionFactory(null)));
        config1.setContinuous(true);
        final Replicator replicator1 = new Replicator(config1);

        final MockServerConnection serverConnection2 = new MockServerConnection(listener, ProtocolType.MESSAGE_STREAM);
        final ReplicatorConfiguration config2 = new ReplicatorConfiguration(baseTestDb, new MessageEndpoint(
            "p2ptest2", serverConnection2, ProtocolType.MESSAGE_STREAM, new MockConnectionFactory(null)));
        config2.setContinuous(true);
        final Replicator replicator2 = new Replicator(config2);

        final CountDownLatch closeWait1 = new CountDownLatch(1);
        final CountDownLatch closeWait2 = new CountDownLatch(1);
        listener.addChangeListener(change -> {
            final Replicator.ActivityLevel activityLevel = change.getStatus().getActivityLevel();

            if (!Replicator.ActivityLevel.STOPPED.equals(activityLevel)) { return; }

            final MessageEndpointConnection conn = change.getConnection();
            if (conn.equals(serverConnection1)) { closeWait1.countDown(); }
            else if (conn.equals(serverConnection2)) { closeWait2.countDown(); }
            else { fail("unrecognized connection: " + conn); }
        });

        final CountDownLatch idleLatch1 = new CountDownLatch(1);
        final CountDownLatch stopLatch1 = new CountDownLatch(1);
        final ListenerToken token1 = replicator1.addChangeListener(change -> {
            if (change.getStatus().getActivityLevel() == Replicator.ActivityLevel.IDLE) {
                idleLatch1.countDown();
            }

            if (change.getStatus().getActivityLevel() == Replicator.ActivityLevel.OFFLINE) {
                replicator1.stop();
            }

            if (change.getStatus().getActivityLevel() == Replicator.ActivityLevel.STOPPED) {
                stopLatch1.countDown();
            }
        });

        final CountDownLatch idleLatch2 = new CountDownLatch(1);
        final CountDownLatch stopLatch2 = new CountDownLatch(1);
        final ListenerToken token2 = replicator2.addChangeListener(change -> {
            if (change.getStatus().getActivityLevel() == Replicator.ActivityLevel.IDLE) {
                idleLatch2.countDown();
            }

            if (change.getStatus().getActivityLevel() == Replicator.ActivityLevel.OFFLINE) {
                replicator2.stop();
            }

            if (change.getStatus().getActivityLevel() == Replicator.ActivityLevel.STOPPED) {
                stopLatch2.countDown();
            }
        });

        replicator1.start(false);
        replicator2.start(false);

        idleLatch1.await(LONG_DELAY_SEC, TimeUnit.SECONDS);
        idleLatch2.await(LONG_DELAY_SEC, TimeUnit.SECONDS);

        listener.closeAll();

        // wait for replicators to stop
        stopLatch1.await(LONG_DELAY_SEC, TimeUnit.SECONDS);
        stopLatch2.await(LONG_DELAY_SEC, TimeUnit.SECONDS);

        replicator1.removeChangeListener(token1);
        replicator2.removeChangeListener(token2);

        // wait for all notifications to come it
        assertTrue(closeWait1.await(LONG_DELAY_SEC, TimeUnit.SECONDS));
        assertTrue(closeWait2.await(LONG_DELAY_SEC, TimeUnit.SECONDS));
    }

    @Test
    public void testP2PChangeListener() throws Exception {
        final ArrayList<Replicator.ActivityLevel> statuses = new ArrayList<>();
        MessageEndpointListener listener = new MessageEndpointListener(
            new MessageEndpointListenerConfiguration(otherDB, ProtocolType.BYTE_STREAM));
        ListenerAwaiter awaiter = new ListenerAwaiter(listener);
        MockServerConnection serverConnection = new MockServerConnection(listener, ProtocolType.BYTE_STREAM);
        ReplicatorConfiguration config = new ReplicatorConfiguration(baseTestDb, new MessageEndpoint(
            "p2ptest1", serverConnection, ProtocolType.BYTE_STREAM, new MockConnectionFactory(null)));
        config.setContinuous(true);

        listener.addChangeListener(change -> statuses.add(change.getStatus().getActivityLevel()));

        run(config, 0, null);
        awaiter.waitForListener();
        awaiter.validate();
        assertTrue(statuses.size() > 1);
    }

    @Test
    public void testRemoveChangeListener() throws Exception {
        final ArrayList<Replicator.ActivityLevel> statuses = new ArrayList<>();
        MessageEndpointListener listener = new MessageEndpointListener(
            new MessageEndpointListenerConfiguration(otherDB, ProtocolType.BYTE_STREAM));
        ListenerAwaiter awaiter = new ListenerAwaiter(listener);
        MockServerConnection serverConnection = new MockServerConnection(listener, ProtocolType.BYTE_STREAM);
        ReplicatorConfiguration config = new ReplicatorConfiguration(baseTestDb, new MessageEndpoint(
            "p2ptest1", serverConnection, ProtocolType.BYTE_STREAM, new MockConnectionFactory(null)));
        config.setContinuous(true);

        ListenerToken token = listener.addChangeListener(change -> statuses.add(change.getStatus().getActivityLevel()));
        listener.removeChangeListener(token);

        run(config, 0, null);
        awaiter.waitForListener();
        awaiter.validate();
        assertEquals(0, statuses.size());
    }

    @Test
    public void testPushWithDocIDsFilter() throws Exception {
        MutableDocument doc1 = new MutableDocument("doc1");
        doc1.setValue("name", "doc1");
        saveDocInBaseTestDb(doc1);

        MutableDocument doc2 = new MutableDocument("doc2");
        doc2.setValue("name", "doc2");
        saveDocInBaseTestDb(doc2);

        MutableDocument doc3 = new MutableDocument("doc3");
        doc3.setValue("name", "doc3");
        saveDocInBaseTestDb(doc3);

        MockServerConnection server = new MockServerConnection(otherDB, ProtocolType.BYTE_STREAM);
        MessageEndpoint endpoint
            = new MessageEndpoint("UID:123", server, ProtocolType.BYTE_STREAM, new MockConnectionFactory(null));
        ReplicatorConfiguration config
            = makeConfig(AbstractReplicatorConfiguration.ReplicatorType.PUSH, false, endpoint);
        config.setDocumentIDs(Arrays.asList("doc1", "doc3"));
        run(config, 0, null);

        assertEquals(2, otherDB.getCount());
        assertNotNull(otherDB.getDocument("doc1"));
        assertNotNull(otherDB.getDocument("doc3"));
        assertNull(otherDB.getDocument("doc2"));
    }

    @Test
    public void testPullWithDocIDsFilter() throws Exception {
        MutableDocument doc1 = new MutableDocument("doc1");
        doc1.setValue("name", "doc1");
        otherDB.save(doc1);

        MutableDocument doc2 = new MutableDocument("doc2");
        doc2.setValue("name", "doc2");
        otherDB.save(doc2);

        MutableDocument doc3 = new MutableDocument("doc3");
        doc3.setValue("name", "doc3");
        otherDB.save(doc3);

        MockServerConnection server = new MockServerConnection(otherDB, ProtocolType.BYTE_STREAM);
        MessageEndpoint endpoint
            = new MessageEndpoint("UID:123", server, ProtocolType.BYTE_STREAM, new MockConnectionFactory(null));
        ReplicatorConfiguration config
            = makeConfig(AbstractReplicatorConfiguration.ReplicatorType.PULL, false, endpoint);
        config.setDocumentIDs(Arrays.asList("doc1", "doc3"));
        run(config, 0, null);

        assertEquals(2, baseTestDb.getCount());
        assertNotNull(baseTestDb.getDocument("doc1"));
        assertNotNull(baseTestDb.getDocument("doc3"));
        assertNull(baseTestDb.getDocument("doc2"));
    }

    @Test
    public void testPushPullWithDocIDsFilter() throws Exception {
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

        MockServerConnection server = new MockServerConnection(otherDB, ProtocolType.BYTE_STREAM);
        MessageEndpoint endpoint
            = new MessageEndpoint("UID:123", server, ProtocolType.BYTE_STREAM, new MockConnectionFactory(null));
        ReplicatorConfiguration config
            = makeConfig(AbstractReplicatorConfiguration.ReplicatorType.PUSH_AND_PULL, false, endpoint);
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

    private ReplicatorConfiguration makeConfig(
        ReplicatorConfiguration.ReplicatorType type,
        boolean continuous,
        Endpoint target) {
        ReplicatorConfiguration config = new ReplicatorConfiguration(baseTestDb, target);
        config.setReplicatorType(type);
        config.setContinuous(continuous);
        return config;
    }

    private void run(ReplicatorConfiguration config, final int code, final String domain) {
        run(config, code, domain, false);
    }

    private void run(ReplicatorConfiguration config, final int code, final String domain, final boolean reset) {
        baseTestReplicator = new Replicator(config);
        final CountDownLatch latch = new CountDownLatch(1);

        final AssertionError[] fail = new AssertionError[1];
        ListenerToken token = baseTestReplicator.addChangeListener(
            testSerialExecutor,
            change -> {
                // Verify change status:
                try { verifyChangeStatus(change, code, domain); }
                catch (AssertionError e) { fail[0] = e; }

                // Stop continuous replicator:
                Replicator.Status status = change.getStatus();
                switch (status.getActivityLevel()) {
                    case IDLE:
                        if (baseTestReplicator.getConfig().isContinuous()
                            && (status.getProgress().getCompleted() == status.getProgress().getTotal())) {
                            baseTestReplicator.stop();
                        }
                        break;
                    case STOPPED:
                        latch.countDown();
                        break;
                }
            });

        baseTestReplicator.start(reset);

        boolean success = false;
        try { success = latch.await(LONG_DELAY_SEC, TimeUnit.SECONDS); }
        catch (InterruptedException ignore) { }

        baseTestReplicator.removeChangeListener(token);

        if (fail[0] != null) { throw fail[0]; }

        assertTrue(success);
    }

    private void verifyChangeStatus(ReplicatorChange change, int code, String domain) {
        Replicator.Status status = change.getStatus();
        CouchbaseLiteException error = status.getError();
        long completed = status.getProgress().getCompleted();
        long total = status.getProgress().getTotal();
        AbstractReplicator.ActivityLevel level = status.getActivityLevel();

        Report.log(
            LogLevel.INFO,
            "Verify state @" + domain + "/" + code + " #" + level + " (" + completed + "/" + total + "): " + error);

        if (status.getActivityLevel() != Replicator.ActivityLevel.STOPPED) { return; }

        if (code == 0) {
            assertNull(error);
            return;
        }

        assertNotNull(error);
        assertEquals(code, error.getCode());
        if (domain != null) { assertEquals(domain, error.getDomain()); }
    }

    private void testP2PError(MockConnectionLifecycleLocation location, boolean recoverable)
        throws CouchbaseLiteException {
        MutableDocument mdoc = new MutableDocument("livesindb");
        mdoc.setString("name", "db");
        baseTestDb.save(mdoc);

        String expectedDomain = recoverable ? null : CBLError.Domain.CBLITE;
        int expectedCode = recoverable ? 0 : CBLError.Code.WEB_SOCKET_CLOSE_USER_PERMANENT;

        Report.log(LogLevel.DEBUG, "Run testP2PError with BYTE-STREAM protocol ...");
        run(createFailureP2PConfig(ProtocolType.BYTE_STREAM, location, recoverable), expectedCode, expectedDomain);

        Report.log(LogLevel.DEBUG, "Run testP2PError with MESSAGE-STREAM protocol ...");
        run(
            createFailureP2PConfig(ProtocolType.MESSAGE_STREAM, location, recoverable),
            expectedCode,
            expectedDomain,
            true);
    }

    private ReplicatorConfiguration createFailureP2PConfig(
        ProtocolType protocolType,
        MockConnectionLifecycleLocation location,
        boolean recoverable) {
        TestErrorLogic errorLocation = TestErrorLogic.failWhen(location);
        if (recoverable) { errorLocation.withRecoverableException(); }
        else { errorLocation.withPermanentException(); }

        MockServerConnection server = new MockServerConnection(otherDB, protocolType);
        ReplicatorConfiguration config = new ReplicatorConfiguration(
            baseTestDb,
            new MessageEndpoint("p2ptest1", server, protocolType, new MockConnectionFactory(errorLocation)));
        config.setReplicatorType(ReplicatorConfiguration.ReplicatorType.PUSH);
        return config;
    }
}
