package com.couchbase.lite;

import android.support.annotation.NonNull;

import java.net.SocketException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.couchbase.lite.utils.Report;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;


public class MessageEndpointTest extends BaseTest {
    private static final long LONG_DELAY_SEC = 10;
    private static final long SHORT_DELAY_MS = 100;
    private static final String OTHER_DATABASE_NAME = "otherdb";
    private static final List<String> ACTIVITY_NAMES
        = Collections.unmodifiableList(Arrays.asList("stopped", "offline", "connecting", "idle", "busy"));

    enum MockConnectionLifecycleLocation {CONNECT, SEND, RECEIVE, CLOSE}

    interface MockConnectionErrorLogic {
        boolean shouldClose(MockConnectionLifecycleLocation location);

        MessagingError createError();
    }

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

        public TestErrorLogic(MockConnectionLifecycleLocation locations) {
            this.location = locations;
        }

        public static TestErrorLogic failWhen(MockConnectionLifecycleLocation locations) {
            return new TestErrorLogic(locations);
        }

        public void withRecoverableException() {
            withRecoverableException(1);
        }

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

    private static final class ReconnectErrorLogic implements MockConnectionErrorLogic {
        private boolean errorActive;

        public ReconnectErrorLogic() { }

        public void setErrorActive(boolean errorActive) {
            this.errorActive = errorActive;
        }

        @Override
        public boolean shouldClose(MockConnectionLifecycleLocation location) {
            return errorActive;
        }

        @Override
        public MessagingError createError() {
            return new MessagingError(new RuntimeException("Server no longer listening"), false);
        }
    }

    private static final class ListenerAwaiter implements MessageEndpointListenerChangeListener {
        private final ListenerToken token;
        private final CountDownLatch latch = new CountDownLatch(1);
        private final ArrayList<Exception> exceptions = new ArrayList<>();
        private final MessageEndpointListener listener;
        private final ExecutorService _executorService = Executors.newSingleThreadExecutor();

        public ListenerAwaiter(MessageEndpointListener listener) {
            token = listener.addChangeListener(_executorService, this);
            this.listener = listener;
        }

        @Override
        protected void finalize() throws Throwable {
            _executorService.shutdown();
            super.finalize();
        }

        @Override
        public void changed(@NonNull MessageEndpointListenerChange change) {
            if (change.getStatus().getError() != null) {
                exceptions.add(change.getStatus().getError());
            }

            if (change.getStatus().getActivityLevel() == Replicator.ActivityLevel.STOPPED) {
                listener.removeChangeListener(token);
                latch.countDown();
            }
        }

        public void waitForListener() throws InterruptedException {
            latch.await(LONG_DELAY_SEC, TimeUnit.SECONDS);
        }

        public void validate() {
            assert (exceptions.isEmpty());
        }
    }

    private abstract class MockConnection implements MessageEndpointConnection {
        private final ProtocolType protocolType;

        protected ReplicatorConnection replicatorConnection;

        protected boolean noCloseRequest;

        protected final ScheduledExecutorService queue;

        protected final MessageEndpointListener host;

        private MockConnectionErrorLogic errorLogic;

        protected MockConnection(MessageEndpointListener listener, ProtocolType protocolType) {
            this.protocolType = protocolType;
            this.host = listener;
            queue = Executors.newSingleThreadScheduledExecutor();
        }

        protected abstract void connectionBroken(MessagingError error);

        protected abstract void performWrite(byte[] data);

        @Override
        public void open(
            @NonNull final ReplicatorConnection connection,
            @NonNull final MessagingCompletion completion) {
            queue.submit(() -> {
                noCloseRequest = false;
                replicatorConnection = connection;
                MockConnectionErrorLogic errorLogic = getErrorLogic();
                if (isClient() && errorLogic.shouldClose(MockConnectionLifecycleLocation.CONNECT)) {
                    MessagingError error = errorLogic.createError();
                    connectionBroken(error);
                    completion.complete(false, error);
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
                    MessagingError error = errorLogic.createError();
                    connectionBroken(error);
                    completion.complete(false, error);
                }
                else {
                    performWrite(message.toData());
                    completion.complete(true, null);
                }
            });
        }

        @Override
        public void close(final Exception error, @NonNull final MessagingCloseCompletion completion) {
            queue.submit(completion::complete);
        }

        @Override
        protected void finalize() throws Throwable {
            super.finalize();
            queue.shutdown();
        }

        protected MockConnectionErrorLogic getErrorLogic() {
            if (errorLogic == null) {
                errorLogic = new NoErrorLogic();
            }
            return errorLogic;
        }

        protected void setErrorLogic(MockConnectionErrorLogic errorLogic) { this.errorLogic = errorLogic; }

        protected ProtocolType getProtocolType() { return protocolType; }

        boolean isClient() { return host == null; }

        void acceptBytes(final byte[] data) {
            queue.submit(() -> {
                if (replicatorConnection == null) { return; }

                MockConnectionErrorLogic errorLogic = getErrorLogic();
                if (isClient() && errorLogic.shouldClose(MockConnectionLifecycleLocation.RECEIVE)) {
                    MessagingError error = errorLogic.createError();
                    connectionBroken(error);
                    replicatorConnection.close(error);
                    replicatorConnection = null;
                }
                else {
                    replicatorConnection.receive(Message.fromData(data));
                }
            });
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

    private final class MockClientConnection extends MockConnection {
        private final MockServerConnection server;

        public MockClientConnection(MessageEndpoint endpoint) {
            super(null, endpoint.getProtocolType());
            this.server = (MockServerConnection) endpoint.getTarget();
        }

        @Override
        public void open(@NonNull ReplicatorConnection connection, @NonNull final MessagingCompletion completion) {
            super.open(connection, (success, error) -> {
                if (success) {
                    server.clientOpened(MockClientConnection.this);
                }
                completion.complete(success, error);
            });
        }

        @Override
        public void close(@NonNull Exception error, @NonNull final MessagingCloseCompletion completion) {
            queue.submit(() -> {
                if (replicatorConnection == null) {
                    completion.complete();
                    return;
                }

                replicatorConnection = null;

                if (getProtocolType() == ProtocolType.MESSAGE_STREAM && !noCloseRequest) { connectionBroken(null); }

                completion.complete();
            });
        }

        @Override
        protected void connectionBroken(MessagingError error) {
            server.clientDisconnected(error);
        }

        @Override
        protected void performWrite(byte[] data) {
            server.acceptBytes(data);
        }

        private void serverDisconnected() {
            if (replicatorConnection == null) { return; }

            MessagingError error = null;
            MockConnectionErrorLogic errorLogic = getErrorLogic();
            if (errorLogic.shouldClose(MockConnectionLifecycleLocation.CLOSE)) {
                error = errorLogic.createError();
            }

            noCloseRequest = true;
            replicatorConnection.close(error);
        }
    }

    private final class MockServerConnection extends MockConnection {
        MockClientConnection client = null;

        public MockServerConnection(MessageEndpointListener listener, ProtocolType protocolType) {
            super(listener, protocolType);
        }

        public MockServerConnection(Database database, ProtocolType protocolType) {
            this(new MessageEndpointListener(
                new MessageEndpointListenerConfiguration(database, protocolType)), protocolType);
        }

        @Override
        public void close(final Exception error, @NonNull final MessagingCloseCompletion completion) {
            queue.submit(() -> {
                replicatorConnection = null;

                if ((error == null) && ProtocolType.MESSAGE_STREAM.equals(getProtocolType())) {
                    client.serverDisconnected();
                }
                completion.complete();
            });
        }

        @Override
        protected void connectionBroken(MessagingError error) { }

        @Override
        protected void performWrite(final byte[] data) {
            client.acceptBytes(data);
        }

        protected void clientOpened(MockClientConnection client) {
            this.client = client;
            host.accept(this);
        }

        protected void clientDisconnected(MessagingError error) {
            if (replicatorConnection != null) {
                noCloseRequest = true;
                replicatorConnection.close(error);
            }
        }
    }


    private Database otherDB;
    private Replicator repl;

    @Before
    public void setUp() throws Exception {
        super.setUp();

        otherDB = openDB(OTHER_DATABASE_NAME);
        assertTrue(otherDB.isOpen());
        assertNotNull(otherDB);
    }

    @After
    public void tearDown() {
        if (!otherDB.isOpen()) {
            Report.log(LogLevel.ERROR, "OtherDB is unexpectedly closed, in tearDown");
        }
        else {
            if (otherDB != null) {
                try { otherDB.close(); }
                catch (CouchbaseLiteException e) {
                    Report.log(LogLevel.ERROR, "Failed closing DB: " + OTHER_DATABASE_NAME, e);
                }
                otherDB = null;
            }

            try { deleteDatabase(OTHER_DATABASE_NAME); }
            catch (CouchbaseLiteException e) {
                Report.log(LogLevel.ERROR, "Failed deleting DB: " + OTHER_DATABASE_NAME, e);
            }
        }

        super.tearDown();

        try { Thread.sleep(SHORT_DELAY_MS); } catch (InterruptedException ignore) { }
    }

    @Test
    public void testPushDocWithMessage() throws Exception {
        MutableDocument doc1 = new MutableDocument("doc1");
        doc1.setValue("name", "Tiger");
        save(doc1);
        assertEquals(1, db.getCount());

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
        save(doc1);
        assertEquals(1, db.getCount());

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
        save(doc1);
        assertEquals(1, db.getCount());

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
        save(doc1);
        assertEquals(1, db.getCount());

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
        save(doc1);
        assertEquals(1, db.getCount());

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

        assertEquals(2, db.getCount());
        Document savedDoc = db.getDocument("doc2");
        assertEquals("Cat", savedDoc.getString("name"));
    }

    @Test
    public void testPullDocWithStream() throws Exception {
        MutableDocument doc1 = new MutableDocument("doc1");
        doc1.setValue("name", "Tiger");
        save(doc1);
        assertEquals(1, db.getCount());

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

        assertEquals(2, db.getCount());
        Document savedDoc = db.getDocument("doc2");
        assertEquals("Cat", savedDoc.getString("name"));
    }

    @Test
    public void testPullDocContinuousWithMessage() throws Exception {
        MutableDocument doc1 = new MutableDocument("doc1");
        doc1.setValue("name", "Tiger");
        save(doc1);
        assertEquals(1, db.getCount());

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

        assertEquals(2, db.getCount());
        Document savedDoc = db.getDocument("doc2");
        assertEquals("Cat", savedDoc.getString("name"));
    }

    @Test
    public void testPullDocContinuousWithStream() throws Exception {
        MutableDocument doc1 = new MutableDocument("doc1");
        doc1.setValue("name", "Tiger");
        save(doc1);
        assertEquals(1, db.getCount());

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

        assertEquals(2, db.getCount());
        Document savedDoc = db.getDocument("doc2");
        assertEquals("Cat", savedDoc.getString("name"));
    }

    @Test
    public void testPushPullDocWithMessage() throws Exception {
        MutableDocument doc1 = new MutableDocument("doc1");
        doc1.setValue("name", "Tiger");
        save(doc1);
        assertEquals(1, db.getCount());

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

        assertEquals(2, db.getCount());
        Document savedDoc2 = db.getDocument("doc2");
        assertEquals("Cat", savedDoc2.getString("name"));
    }

    @Test
    public void testPushPullDocWithStream() throws Exception {
        MutableDocument doc1 = new MutableDocument("doc1");
        doc1.setValue("name", "Tiger");
        save(doc1);
        assertEquals(1, db.getCount());

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

        assertEquals(2, db.getCount());
        Document savedDoc2 = db.getDocument("doc2");
        assertEquals("Cat", savedDoc2.getString("name"));
    }

    @Test
    public void testPushPullDocContinuousWithMessage() throws Exception {
        MutableDocument doc1 = new MutableDocument("doc1");
        doc1.setValue("name", "Tiger");
        save(doc1);
        assertEquals(1, db.getCount());

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

        assertEquals(2, db.getCount());
        Document savedDoc2 = db.getDocument("doc2");
        assertEquals("Cat", savedDoc2.getString("name"));
    }

    @Test
    public void testPushPullDocContinuousWithStream() throws Exception {
        MutableDocument doc1 = new MutableDocument("doc1");
        doc1.setValue("name", "Tiger");
        save(doc1);
        assertEquals(1, db.getCount());

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

        assertEquals(2, db.getCount());
        Document savedDoc2 = db.getDocument("doc2");
        assertEquals("Cat", savedDoc2.getString("name"));
    }

    @Test
    public void testP2PRecoverableFailureDuringOpen() throws Exception {
        testP2PError(MockConnectionLifecycleLocation.CONNECT, true);
    }

    @Test
    public void testP2PRecoverableFailureDuringSend() throws Exception {
        testP2PError(MockConnectionLifecycleLocation.SEND, true);
    }

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

    @Test
    public void testP2PPassiveClose() throws Exception {
        MessageEndpointListener listener = new MessageEndpointListener(
            new MessageEndpointListenerConfiguration(otherDB, ProtocolType.MESSAGE_STREAM));
        ListenerAwaiter awaiter = new ListenerAwaiter(listener);
        MockServerConnection serverConnection = new MockServerConnection(listener, ProtocolType.MESSAGE_STREAM);
        ReconnectErrorLogic errorLogic = new ReconnectErrorLogic();
        ReplicatorConfiguration config = new ReplicatorConfiguration(
            db,
            new MessageEndpoint(
                "p2ptest1",
                serverConnection,
                ProtocolType.MESSAGE_STREAM,
                new MockConnectionFactory(errorLogic)));
        config.setContinuous(true);

        Replicator replicator = new Replicator(config);
        replicator.start();

        int count = 0;
        while (replicator.getStatus().getActivityLevel() != Replicator.ActivityLevel.IDLE) {
            Thread.sleep(SHORT_DELAY_MS);
            assertTrue(count++ < 20);
        }

        errorLogic.setErrorActive(true);
        listener.close(serverConnection);
        count = 0;
        while (replicator.getStatus().getActivityLevel() != Replicator.ActivityLevel.STOPPED) {
            Thread.sleep(SHORT_DELAY_MS);
            assertTrue(count++ < 20);
        }

        awaiter.waitForListener();
        awaiter.validate();

        assertNotNull(replicator.getStatus().getError());
    }

    @Test
    public void testP2PPassiveCloseAll() throws InterruptedException, CouchbaseLiteException {
        MutableDocument doc = new MutableDocument("test");
        doc.setString("name", "smokey");
        db.save(doc);

        final MessageEndpointListener listener = new MessageEndpointListener(
            new MessageEndpointListenerConfiguration(otherDB, ProtocolType.MESSAGE_STREAM));

        final ReconnectErrorLogic errorLogic = new ReconnectErrorLogic();

        final MockServerConnection serverConnection1 = new MockServerConnection(listener, ProtocolType.MESSAGE_STREAM);
        final ReplicatorConfiguration config1 = new ReplicatorConfiguration(db, new MessageEndpoint(
            "p2ptest1", serverConnection1, ProtocolType.MESSAGE_STREAM, new MockConnectionFactory(errorLogic)));
        config1.setContinuous(true);
        final Replicator replicator1 = new Replicator(config1);

        final MockServerConnection serverConnection2 = new MockServerConnection(listener, ProtocolType.MESSAGE_STREAM);
        final ReplicatorConfiguration config2 = new ReplicatorConfiguration(db, new MessageEndpoint(
            "p2ptest2", serverConnection2, ProtocolType.MESSAGE_STREAM, new MockConnectionFactory(errorLogic)));
        config2.setContinuous(true);
        final Replicator replicator2 = new Replicator(config2);

        final CountDownLatch closeWait1 = new CountDownLatch(1);
        final CountDownLatch closeWait2 = new CountDownLatch(1);
        listener.addChangeListener(
            change -> {
                final Replicator.ActivityLevel activityLevel = change.getStatus().getActivityLevel();

                if (!Replicator.ActivityLevel.STOPPED.equals(activityLevel)) { return; }

                final MessageEndpointConnection conn = change.getConnection();
                if (conn.equals(serverConnection1)) { closeWait1.countDown(); }
                else if (conn.equals(serverConnection2)) { closeWait2.countDown(); }
                else { fail("unrecognized connection: " + conn); }
            });

        replicator1.start();
        replicator2.start();

        // wait for *either* replicator to become idle
        int count = 0;
        while (
            !(Replicator.ActivityLevel.IDLE.equals(replicator1.getStatus().getActivityLevel())
                || Replicator.ActivityLevel.IDLE.equals(replicator2.getStatus().getActivityLevel()))) {
            assertTrue(count++ < 20);
            Thread.sleep(SHORT_DELAY_MS);
        }

        errorLogic.setErrorActive(true);

        listener.closeAll();

        // wait for *both* replicators to be stopped
        count = 0;
        while (
            !(Replicator.ActivityLevel.STOPPED.equals(replicator1.getStatus().getActivityLevel())
                && Replicator.ActivityLevel.STOPPED.equals(replicator2.getStatus().getActivityLevel()))) {
            assertTrue(count++ < 20);
            Thread.sleep(SHORT_DELAY_MS);
        }

        // wait for all notifications to come it
        assertTrue(closeWait1.await(LONG_DELAY_SEC, TimeUnit.SECONDS));
        assertTrue(closeWait2.await(LONG_DELAY_SEC, TimeUnit.SECONDS));

        // verify the errors
        assertNotNull(replicator1.getStatus().getError());
        assertNotNull(replicator2.getStatus().getError());
    }

    @Test
    public void testP2PChangeListener() throws Exception {
        final ArrayList<Replicator.ActivityLevel> statuses = new ArrayList<>();
        MessageEndpointListener listener = new MessageEndpointListener(
            new MessageEndpointListenerConfiguration(otherDB, ProtocolType.BYTE_STREAM));
        ListenerAwaiter awaiter = new ListenerAwaiter(listener);
        MockServerConnection serverConnection = new MockServerConnection(listener, ProtocolType.BYTE_STREAM);
        ReplicatorConfiguration config = new ReplicatorConfiguration(db, new MessageEndpoint(
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
        ReplicatorConfiguration config = new ReplicatorConfiguration(db, new MessageEndpoint(
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
        save(doc1);

        MutableDocument doc2 = new MutableDocument("doc2");
        doc2.setValue("name", "doc2");
        save(doc2);

        MutableDocument doc3 = new MutableDocument("doc3");
        doc3.setValue("name", "doc3");
        save(doc3);

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

        assertEquals(2, db.getCount());
        assertNotNull(db.getDocument("doc1"));
        assertNotNull(db.getDocument("doc3"));
        assertNull(db.getDocument("doc2"));
    }

    @Test
    public void testPushPullWithDocIDsFilter() throws Exception {
        MutableDocument doc1 = new MutableDocument("doc1");
        doc1.setValue("name", "doc1");
        db.save(doc1);

        MutableDocument doc2 = new MutableDocument("doc2");
        doc2.setValue("name", "doc2");
        db.save(doc2);

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

        assertEquals(3, db.getCount());
        assertNotNull(db.getDocument("doc1"));
        assertNotNull(db.getDocument("doc2"));
        assertNotNull(db.getDocument("doc4"));
        assertNull(db.getDocument("doc3"));

        assertEquals(3, otherDB.getCount());
        assertNotNull(otherDB.getDocument("doc1"));
        assertNotNull(otherDB.getDocument("doc3"));
        assertNotNull(otherDB.getDocument("doc4"));
        assertNull(otherDB.getDocument("doc2"));
    }

    private ReplicatorConfiguration makeConfig(
        ReplicatorConfiguration.ReplicatorType type,
        boolean continuous,
        Endpoint target) {
        ReplicatorConfiguration config = new ReplicatorConfiguration(db, target);
        config.setReplicatorType(type);
        config.setContinuous(continuous);
        return config;
    }

    private void run(ReplicatorConfiguration config, final int code, final String domain) throws InterruptedException {
        run(config, code, domain, false);
    }

    private void run(ReplicatorConfiguration config, final int code, final String domain, final boolean reset)
        throws InterruptedException {
        repl = new Replicator(config);
        final CountDownLatch latch = new CountDownLatch(1);
        ListenerToken token = repl.addChangeListener(
            executor,
            change -> {
                // Verify change status:
                verifyChangeStatus(change, code, domain);

                // Stop continuous replicator:
                Replicator.Status status = change.getStatus();
                boolean isContinuous = repl.getConfig().isContinuous();
                if (isContinuous
                    && (status.getActivityLevel() == Replicator.ActivityLevel.IDLE)
                    && (status.getProgress().getCompleted() == status.getProgress().getTotal())) {
                    repl.stop();
                }

                if (status.getActivityLevel() == Replicator.ActivityLevel.STOPPED) { latch.countDown(); }
            });

        if (reset) { repl.resetCheckpoint(); }

        repl.start();
        boolean success = latch.await(15, TimeUnit.SECONDS);
        repl.removeChangeListener(token);
        assertTrue(success);
    }

    private void verifyChangeStatus(ReplicatorChange change, int code, String domain) {
        Replicator.Status status = change.getStatus();
        CouchbaseLiteException error = status.getError();
        String activity = ACTIVITY_NAMES.get(status.getActivityLevel().getValue());
        long completed = status.getProgress().getCompleted();
        long total = status.getProgress().getTotal();
        Report.log(LogLevel.INFO, "ReplicatorChangeListener.changed() status: " + activity +
            "(" + completed + "/" + total + "), lastError: " + error);

        if (status.getActivityLevel() != Replicator.ActivityLevel.STOPPED) { return; }

        if (code == 0) {
            assertNull(error);
            return;
        }

        assertNotNull(error);
        assertEquals(code, error.getCode());
        if (domain != null) { assertEquals(domain, error.getDomain()); }
    }

    private ReplicatorConfiguration createFailureP2PConfiguration(
        ProtocolType protocolType,
        MockConnectionLifecycleLocation location,
        boolean recoverable) {
        TestErrorLogic errorLocation = TestErrorLogic.failWhen(location);
        if (recoverable) { errorLocation.withRecoverableException(); }
        else { errorLocation.withPermanentException(); }

        MockServerConnection server = new MockServerConnection(otherDB, protocolType);
        ReplicatorConfiguration config = new ReplicatorConfiguration(db, new MessageEndpoint(
            "p2ptest1", server, protocolType, new MockConnectionFactory(errorLocation)));
        config.setReplicatorType(ReplicatorConfiguration.ReplicatorType.PUSH);
        return config;
    }

    private void testP2PError(MockConnectionLifecycleLocation location, boolean recoverable) throws Exception {
        MutableDocument mdoc = new MutableDocument("livesindb");
        mdoc.setString("name", "db");
        db.save(mdoc);

        String expectedDomain = recoverable ? null : CBLError.Domain.CBLITE;
        int expectedCode = recoverable ? 0 : CBLError.Code.WEB_SOCKET_CLOSE_USER_PERMANENT;

        ReplicatorConfiguration config
            = createFailureP2PConfiguration(ProtocolType.BYTE_STREAM, location, recoverable);
        run(config, expectedCode, expectedDomain);

        try { Thread.sleep(SHORT_DELAY_MS); } catch (InterruptedException ignore) { }

        config = createFailureP2PConfiguration(ProtocolType.MESSAGE_STREAM, location, recoverable);
        run(config, expectedCode, expectedDomain, true);
    }
}
