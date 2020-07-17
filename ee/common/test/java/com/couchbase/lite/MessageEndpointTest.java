package com.couchbase.lite;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import java.net.SocketException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.jetbrains.annotations.NotNull;
import org.junit.Test;

import com.couchbase.lite.internal.utils.ClassUtils;
import com.couchbase.lite.internal.utils.FlakyTest;
import com.couchbase.lite.internal.utils.Report;
import com.couchbase.lite.internal.utils.SlowTest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;


/////////////////////////////////////  MOCK CONNECTION  /////////////////////////////////////

abstract class MockConnection implements MessageEndpointConnection {
    private final ThreadFactory factory = new ThreadFactory() {
        final AtomicInteger id = new AtomicInteger(1);
        @Override
        public Thread newThread(@NonNull Runnable runnable) {
            final Thread thread = new Thread(runnable, "MockConnection #" + id.getAndIncrement());
            thread.setUncaughtExceptionHandler((t, e) ->
                Report.log(LogLevel.WARNING, "Uncaught exception on " + thread.getName(), e));
            return thread;
        }
    };
    private final ScheduledExecutorService queue = Executors.newSingleThreadScheduledExecutor(factory);

    private final Deque<Message> messageQueue = new LinkedList<>();

    private final String logName;

    protected ReplicatorConnection replicatorConnection;
    protected MessagingCloseCompletion onClose;
    protected boolean closing;

    MockConnection(@NonNull String logName) { this.logName = logName; }

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
    public void open(
        @NonNull final ReplicatorConnection connection,
        @NonNull final MessagingCompletion completion) {
        List<Message> outstanding;
        synchronized (this) {
            closing = false;
            replicatorConnection = connection;
            outstanding = new ArrayList<>(messageQueue);
            messageQueue.clear();
        }

        queue.submit(() -> {
            Report.log(
                LogLevel.DEBUG,
                logName + ".open(%s, %s)",
                String.valueOf(connection),
                String.valueOf(completion));

            openAsync(connection, completion);

            for (Message message: outstanding) { deliver(message, connection); }
        });
    }

    @Override
    public void send(@NonNull final Message message, @NonNull final MessagingCompletion completion) {
        final byte[] msg = message.toData();
        final byte[] data = new byte[msg.length];
        System.arraycopy(msg, 0, data, 0, data.length);

        queue.submit(() -> {
            Report.log(
                LogLevel.DEBUG,
                logName + ".send(%s, %s)",
                String.valueOf(data.length),
                String.valueOf(completion));

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

        queue.submit(() -> {
            Report.log(
                LogLevel.DEBUG,
                logName + ".close(%s, %s, %s)",
                String.valueOf(e),
                String.valueOf(completion),
                String.valueOf(closeCompletion));

            closeAsync(completion, closeCompletion);
        });
    }

    public void accept(@NonNull final byte[] data) {
        final byte[] msg = new byte[data.length];
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
            disconnecting = !(closing || (replicatorConnection == null));
            if (disconnecting) { onClose = completion; }
            repl = replicatorConnection;
        }

        queue.submit(() -> {
            Report.log(
                LogLevel.DEBUG,
                logName + ".disconnect(%s, %s, %s)",
                String.valueOf(disconnecting),
                String.valueOf(error),
                String.valueOf(completion));

            if (disconnecting) { closeReplAsync(repl, error); }
            else {disconnectAsync(error, completion); }
        });
    }

    @Override
    protected void finalize() throws Throwable {
        super.finalize();
        queue.shutdown();
    }

    private void deliver(@NonNull Message message, @NonNull ReplicatorConnection repl) {
        queue.submit(() -> {
            Report.log(LogLevel.DEBUG, logName + ".deliver(%d, %s)", message.toData().length, ClassUtils.objId(repl));
            deliverAsync(message, repl);
        });
    }
}


/////////////////////////////////////  MOCK SERVER CONNECTION  /////////////////////////////////////

class MockServerConnection extends MockConnection {
    @NonNull
    private final MessageEndpointListener listener;

    @Nullable
    private MockClientConnection client;

    public MockServerConnection(Database database, ProtocolType protocolType) {
        this(new MessageEndpointListener(new MessageEndpointListenerConfiguration(database, protocolType)));
    }

    public MockServerConnection(@NonNull MessageEndpointListener listener) {
        super("MockServerConnection");

        this.listener = listener;

        Report.log(
            LogLevel.DEBUG,
            "MockServerConnection.<init>(%s, %s)",
            String.valueOf(listener.getConfig().getProtocolType()),
            String.valueOf(listener));
    }

    public void clientOpened(MockClientConnection clientConnection) {
        Report.log(
            LogLevel.DEBUG,
            "MockServerConnection.connected %s => %s",
            ClassUtils.objId(this),
            ClassUtils.objId(clientConnection));

        synchronized (this) { client = clientConnection; }
        listener.accept(this);
    }

    @Override
    void openAsync(@NonNull final ReplicatorConnection connection, @NonNull final MessagingCompletion completion) {
        completion.complete(true, null);
    }

    @Override
    void sendAsync(@NonNull byte[] data, @NonNull MessagingCompletion completion) {
        final MockClientConnection clientConnection;
        synchronized (this) { clientConnection = client;}

        if (clientConnection != null) { clientConnection.accept(data); }
        completion.complete(true, null);
    }

    @Override
    void closeAsync(@NonNull MessagingCloseCompletion completion, @Nullable MessagingCloseCompletion closeCompletion) {
        final MockClientConnection clientConnection;
        synchronized (this) { clientConnection = client; }

        if (closeCompletion != null) {
            closeCompletion.complete();
            completion.complete();
            return;
        }

        if (clientConnection != null) { clientConnection.disconnect(null, completion); }
    }

    @Override
    void deliverAsync(@NonNull Message message, @NonNull ReplicatorConnection repl) {
        repl.receive(message);
    }

    @Override
    void disconnectAsync(@Nullable MessagingError error, @NonNull MessagingCloseCompletion completion) {
        completion.complete();
    }

    @Override
    void closeReplAsync(@NonNull ReplicatorConnection repl, @Nullable MessagingError error) {
        repl.close(error);
    }
}


/////////////////////////////////////  MOCK CLIENT CONNECTION  /////////////////////////////////////

class MockClientConnection extends MockConnection {
    public interface ErrorLogic {
        enum LifecycleLocation {CONNECT, SEND, RECEIVE, CLOSE}

        boolean shouldClose(LifecycleLocation location);

        MessagingError createError();
    }

    @NonNull
    protected final MockServerConnection server;
    @NonNull
    private final ErrorLogic errorLogic;

    private MessagingError error;

    public MockClientConnection(@NonNull MessageEndpoint endpoint, @Nullable ErrorLogic errorLogic) {
        super("MockClientConnection");

        server = (MockServerConnection) endpoint.getTarget();
        this.errorLogic = (errorLogic != null) ? errorLogic : new NoErrorLogic();

        Report.log(
            LogLevel.DEBUG,
            "MockClientConnection.<init>(%s, %s)",
            String.valueOf(endpoint),
            String.valueOf(errorLogic));
    }

    @Override
    void openAsync(@NonNull ReplicatorConnection connection, @NonNull MessagingCompletion completion) {
        final MessagingError err;
        synchronized (this) {
            error = null;
            err = getLogicError(ErrorLogic.LifecycleLocation.CONNECT);
        }

        final boolean succeeded = err == null;
        if (succeeded) { server.clientOpened(this); }
        completion.complete(succeeded, err);
    }

    @Override
    void sendAsync(@NonNull byte[] data, @NonNull MessagingCompletion completion) {
        final MessagingError err = getLogicError(ErrorLogic.LifecycleLocation.SEND);
        final boolean succeeded = err == null;
        if (succeeded) { server.accept(data); }
        completion.complete(succeeded, err);
    }

    @Override
    void closeAsync(
        @NonNull MessagingCloseCompletion completion,
        @Nullable MessagingCloseCompletion closeCompletion) {
        if (closeCompletion != null) {
            closeCompletion.complete();
            completion.complete();
            return;
        }

        final MessagingError err = getLogicError(ErrorLogic.LifecycleLocation.CLOSE);

        server.disconnect(err, completion);
    }

    @Override
    void deliverAsync(@NonNull Message message, @NonNull ReplicatorConnection repl) {
        final MessagingError err = getLogicError(ErrorLogic.LifecycleLocation.RECEIVE);
        if (err != null) {
            repl.close(error);
            return;
        }

        repl.receive(message);
    }

    @Override
    void disconnectAsync(@Nullable MessagingError error, @NonNull MessagingCloseCompletion completion) {
        setError(error);
        completion.complete();
    }

    @Override
    void closeReplAsync(@NonNull ReplicatorConnection repl, @Nullable MessagingError error) {
        setError(error);
        repl.close(error);
    }

    private void setError(@Nullable MessagingError error) {
        synchronized (this) { this.error = error; }
    }

    private MessagingError getLogicError(ErrorLogic.LifecycleLocation loc) {
        synchronized (this) {
            if (errorLogic.shouldClose(loc)) { error = errorLogic.createError(); }
            return error;
        }
    }
}


/////////////////////////////////////   CONNECTION FACTORY   /////////////////////////////////////

class MockConnectionFactory implements MessageEndpointDelegate {
    private final MockClientConnection.ErrorLogic errorLogic;

    public MockConnectionFactory(MockClientConnection.ErrorLogic errorLogic) { this.errorLogic = errorLogic; }

    @NonNull
    @Override
    public MessageEndpointConnection createConnection(@NonNull MessageEndpoint endpoint) {
        return new MockClientConnection(endpoint, errorLogic);
    }
}


/////////////////////////////////////  ERROR LOGIC /////////////////////////////////////

final class NoErrorLogic implements MockClientConnection.ErrorLogic {
    public NoErrorLogic() { }

    @Override
    public boolean shouldClose(LifecycleLocation location) { return false; }

    @Override
    public MessagingError createError() { return null; }

    @NonNull
    @Override
    public String toString() { return "NoErrorLogic"; }
}

final class TestErrorLogic implements MockClientConnection.ErrorLogic {
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


/////////////////////////////////////  LISTENER AWAITER  /////////////////////////////////////

final class ListenerAwaiter implements MessageEndpointListenerChangeListener {
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
    public void changed(@NotNull @NonNull MessageEndpointListenerChange change) {
        if (change.getStatus().getError() != null) { exceptions.add(change.getStatus().getError()); }

        if (change.getStatus().getActivityLevel() != Replicator.ActivityLevel.STOPPED) { return; }

        listener.removeChangeListener(token);
        token = null;
        latch.countDown();
    }

    public void waitForListener() throws InterruptedException {
        latch.await(MessageEndpointTest.LONG_DELAY_SEC, TimeUnit.SECONDS);
    }

    public void validate() { assertTrue(exceptions.isEmpty()); }

    @Override
    protected void finalize() throws Throwable {
        if (token != null) { listener.removeChangeListener(token); }
        executorService.shutdown();
        super.finalize();
    }
}


/////////////////////////////////   T E S T   S U I T E   //////////////////////////////////////

public class MessageEndpointTest extends BaseReplicatorTest {
    public static final long LONG_DELAY_SEC = 10;  // Core takes 5s to retry after a

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

        MockServerConnection server = new MockServerConnection(otherDB, ProtocolType.MESSAGE_STREAM);
        MessageEndpoint endpoint
            = new MessageEndpoint("UID:123", server, ProtocolType.MESSAGE_STREAM, new MockConnectionFactory(null));
        ReplicatorConfiguration config
            = makeConfig(AbstractReplicatorConfiguration.ReplicatorType.PUSH, false, endpoint);

        run(config, 0, null);

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

        MockServerConnection server = new MockServerConnection(otherDB, ProtocolType.BYTE_STREAM);
        MessageEndpoint endpoint
            = new MessageEndpoint("UID:123", server, ProtocolType.BYTE_STREAM, new MockConnectionFactory(null));
        ReplicatorConfiguration config
            = makeConfig(AbstractReplicatorConfiguration.ReplicatorType.PUSH, false, endpoint);

        run(config, 0, null);

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

        MockServerConnection server = new MockServerConnection(otherDB, ProtocolType.MESSAGE_STREAM);
        MessageEndpoint endpoint
            = new MessageEndpoint("UID:123", server, ProtocolType.MESSAGE_STREAM, new MockConnectionFactory(null));
        ReplicatorConfiguration config
            = makeConfig(AbstractReplicatorConfiguration.ReplicatorType.PUSH, true, endpoint);

        run(config, 0, null);

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

        MockServerConnection server = new MockServerConnection(otherDB, ProtocolType.BYTE_STREAM);
        MessageEndpoint endpoint
            = new MessageEndpoint("UID:123", server, ProtocolType.BYTE_STREAM, new MockConnectionFactory(null));
        ReplicatorConfiguration config
            = makeConfig(AbstractReplicatorConfiguration.ReplicatorType.PUSH, true, endpoint);

        run(config, 0, null);

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

        MockServerConnection server = new MockServerConnection(otherDB, ProtocolType.MESSAGE_STREAM);
        MessageEndpoint endpoint
            = new MessageEndpoint("UID:123", server, ProtocolType.MESSAGE_STREAM, new MockConnectionFactory(null));
        ReplicatorConfiguration config
            = makeConfig(AbstractReplicatorConfiguration.ReplicatorType.PULL, false, endpoint);

        run(config, 0, null);

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

        MockServerConnection server = new MockServerConnection(otherDB, ProtocolType.BYTE_STREAM);
        MessageEndpoint endpoint
            = new MessageEndpoint("UID:123", server, ProtocolType.BYTE_STREAM, new MockConnectionFactory(null));
        ReplicatorConfiguration config
            = makeConfig(AbstractReplicatorConfiguration.ReplicatorType.PULL, false, endpoint);

        run(config, 0, null);

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

        MockServerConnection server = new MockServerConnection(otherDB, ProtocolType.MESSAGE_STREAM);
        MessageEndpoint endpoint
            = new MessageEndpoint("UID:123", server, ProtocolType.MESSAGE_STREAM, new MockConnectionFactory(null));
        ReplicatorConfiguration config
            = makeConfig(AbstractReplicatorConfiguration.ReplicatorType.PULL, true, endpoint);

        run(config, 0, null);

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

        MockServerConnection server = new MockServerConnection(otherDB, ProtocolType.BYTE_STREAM);
        MessageEndpoint endpoint
            = new MessageEndpoint("UID:123", server, ProtocolType.BYTE_STREAM, new MockConnectionFactory(null));
        final ReplicatorConfiguration config
            = makeConfig(AbstractReplicatorConfiguration.ReplicatorType.PULL, true, endpoint);

        run(config, 0, null);

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

        MockServerConnection server = new MockServerConnection(otherDB, ProtocolType.MESSAGE_STREAM);
        MessageEndpoint endpoint
            = new MessageEndpoint("UID:123", server, ProtocolType.MESSAGE_STREAM, new MockConnectionFactory(null));
        ReplicatorConfiguration config
            = makeConfig(AbstractReplicatorConfiguration.ReplicatorType.PUSH_AND_PULL, false, endpoint);

        run(config, 0, null);

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

        MockServerConnection server = new MockServerConnection(otherDB, ProtocolType.BYTE_STREAM);
        MessageEndpoint endpoint
            = new MessageEndpoint("UID:123", server, ProtocolType.BYTE_STREAM, new MockConnectionFactory(null));
        ReplicatorConfiguration config
            = makeConfig(AbstractReplicatorConfiguration.ReplicatorType.PUSH_AND_PULL, false, endpoint);

        run(config, 0, null);

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

        MockServerConnection server = new MockServerConnection(otherDB, ProtocolType.MESSAGE_STREAM);
        MessageEndpoint endpoint
            = new MessageEndpoint("UID:123", server, ProtocolType.MESSAGE_STREAM, new MockConnectionFactory(null));
        ReplicatorConfiguration config
            = makeConfig(AbstractReplicatorConfiguration.ReplicatorType.PUSH_AND_PULL, true, endpoint);

        run(config, 0, null);

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

        MockServerConnection server = new MockServerConnection(otherDB, ProtocolType.BYTE_STREAM);
        MessageEndpoint endpoint
            = new MessageEndpoint("UID:123", server, ProtocolType.BYTE_STREAM, new MockConnectionFactory(null));
        ReplicatorConfiguration config
            = makeConfig(AbstractReplicatorConfiguration.ReplicatorType.PUSH_AND_PULL, true, endpoint);

        run(config, 0, null);

        assertEquals(2, otherDB.getCount());
        assertEquals("Tiger", otherDB.getDocument("doc1").getString("name"));

        assertEquals(2, baseTestDb.getCount());
        assertEquals("Cat", baseTestDb.getDocument("doc2").getString("name"));
    }

    @SlowTest
    @Test
    public void testP2PRecoverableFailureDuringOpen() throws CouchbaseLiteException {
        testP2PError(MockClientConnection.ErrorLogic.LifecycleLocation.CONNECT, true);
    }

    @SlowTest
    @Test
    public void testP2PRecoverableFailureDuringSend() throws CouchbaseLiteException {
        testP2PError(MockClientConnection.ErrorLogic.LifecycleLocation.SEND, true);
    }

    @SlowTest
    @Test
    public void testP2PRecoverableFailureDuringReceive() throws CouchbaseLiteException {
        testP2PError(MockClientConnection.ErrorLogic.LifecycleLocation.RECEIVE, true);
    }

    @Test
    public void testP2PPermanentFailureDuringOpen() throws CouchbaseLiteException {
        testP2PError(MockClientConnection.ErrorLogic.LifecycleLocation.CONNECT, false);
    }

    @Test
    public void testP2PPermanentFailureDuringSend() throws CouchbaseLiteException {
        testP2PError(MockClientConnection.ErrorLogic.LifecycleLocation.SEND, false);
    }

    @Test
    public void testP2PPermanentFailureDuringReceive() throws CouchbaseLiteException {
        testP2PError(MockClientConnection.ErrorLogic.LifecycleLocation.RECEIVE, false);
    }

    @Test
    public void testP2PPassiveClose() throws InterruptedException {
        MessageEndpointListener listener = new MessageEndpointListener(
            new MessageEndpointListenerConfiguration(otherDB, ProtocolType.MESSAGE_STREAM));

        MockServerConnection server = new MockServerConnection(listener);
        MessageEndpoint endpoint
            = new MessageEndpoint("p2ptest1", server, ProtocolType.MESSAGE_STREAM, new MockConnectionFactory(null));
        ReplicatorConfiguration config = new ReplicatorConfiguration(baseTestDb, endpoint).setContinuous(true);
        Replicator replicator = new Replicator(config);

        final CountDownLatch latch = new CountDownLatch(3);
        final AtomicBoolean didCloseListener = new AtomicBoolean(false);
        final ListenerToken token = replicator.addChangeListener(change -> {
            switch (change.getStatus().getActivityLevel()) {
                case IDLE:
                    if (!didCloseListener.getAndSet(true)) {
                        latch.countDown();
                        listener.close(server);
                    }
                    break;
                case OFFLINE:
                    latch.countDown();
                    replicator.stop();
                    break;
                case STOPPED:
                    latch.countDown();
                    break;
            }
        });

        ListenerAwaiter awaiter = new ListenerAwaiter(listener);

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

        final MockServerConnection serverConnection1 = new MockServerConnection(listener);
        final ReplicatorConfiguration config1 = new ReplicatorConfiguration(
            baseTestDb,
            new MessageEndpoint(
                "p2ptest1",
                serverConnection1,
                ProtocolType.MESSAGE_STREAM,
                new MockConnectionFactory(null)));
        config1.setContinuous(true);
        final Replicator replicator1 = new Replicator(config1);

        final MockServerConnection serverConnection2 = new MockServerConnection(listener);
        final ReplicatorConfiguration config2 = new ReplicatorConfiguration(
            baseTestDb,
            new MessageEndpoint(
                "p2ptest2",
                serverConnection2,
                ProtocolType.MESSAGE_STREAM,
                new MockConnectionFactory(null)));
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
            switch (change.getStatus().getActivityLevel()) {
                case IDLE:
                    idleLatch1.countDown();
                    break;
                case OFFLINE:
                    replicator1.stop();
                    break;
                case STOPPED:
                    stopLatch1.countDown();
                    break;
            }
        });

        final CountDownLatch idleLatch2 = new CountDownLatch(1);
        final CountDownLatch stopLatch2 = new CountDownLatch(1);
        final ListenerToken token2 = replicator2.addChangeListener(change -> {
            switch (change.getStatus().getActivityLevel()) {
                case IDLE:
                    idleLatch2.countDown();
                    break;
                case OFFLINE:
                    replicator2.stop();
                    break;
                case STOPPED:
                    stopLatch2.countDown();
                    break;
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

        // wait for all notifications to come in
        assertTrue(closeWait1.await(LONG_DELAY_SEC, TimeUnit.SECONDS));
        assertTrue(closeWait2.await(LONG_DELAY_SEC, TimeUnit.SECONDS));
    }

    @FlakyTest
    @Test
    public void testP2PChangeListener() throws InterruptedException {
        final ArrayList<Replicator.ActivityLevel> statuses = new ArrayList<>();
        MessageEndpointListener listener = new MessageEndpointListener(
            new MessageEndpointListenerConfiguration(otherDB, ProtocolType.BYTE_STREAM));
        MockServerConnection serverConnection = new MockServerConnection(listener);
        MessageEndpoint endpoint = new MessageEndpoint(
            "p2ptest1",
            serverConnection,
            ProtocolType.BYTE_STREAM,
            new MockConnectionFactory(null));
        ReplicatorConfiguration config = new ReplicatorConfiguration(baseTestDb, endpoint).setContinuous(true);

        listener.addChangeListener(change -> statuses.add(change.getStatus().getActivityLevel()));

        ListenerAwaiter awaiter = new ListenerAwaiter(listener);

        run(config, 0, null);

        awaiter.waitForListener();
        awaiter.validate();
        assertTrue(statuses.size() > 1);
    }

    @FlakyTest
    @Test
    public void testRemoveChangeListener() throws InterruptedException {
        final ArrayList<Replicator.ActivityLevel> statuses = new ArrayList<>();

        MessageEndpointListener listener = new MessageEndpointListener(
            new MessageEndpointListenerConfiguration(otherDB, ProtocolType.BYTE_STREAM));
        MockServerConnection serverConnection = new MockServerConnection(listener);
        MessageEndpoint endpoint = new MessageEndpoint(
            "p2ptest1",
            serverConnection,
            ProtocolType.BYTE_STREAM,
            new MockConnectionFactory(null));
        ReplicatorConfiguration config = new ReplicatorConfiguration(baseTestDb, endpoint).setContinuous(true);

        ListenerToken token = listener.addChangeListener(change -> statuses.add(change.getStatus().getActivityLevel()));
        listener.removeChangeListener(token);

        ListenerAwaiter awaiter = new ListenerAwaiter(listener);

        run(config, 0, null);

        awaiter.waitForListener();
        awaiter.validate();
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

    Throwable onChange(
        String expectedDomain,
        int expectedCode,
        CountDownLatch latch,
        ReplicatorChange change) {
        final Replicator.Status status = change.getStatus();
        final AbstractReplicator.ActivityLevel level = status.getActivityLevel();
        final CouchbaseLiteException error = status.getError();
        final Replicator.Progress progress = status.getProgress();
        final long completed = progress.getCompleted();
        final long total = progress.getTotal();

        Report.log(
            LogLevel.INFO,
            "State change " + ClassUtils.objId(change.getReplicator())
                + " #" + level + "(" + completed + "/" + total
                + ") expecting: " + expectedDomain + "/" + expectedCode + ", got: " + error);

        // Verify change status:
        try { verifyChangeStatus(expectedDomain, expectedCode, change); }
        catch (Throwable e) {
            latch.countDown();
            return e;
        }

        switch (level) {
            // Stop a continuous replicator:
            case IDLE:
                if (baseTestReplicator.getConfig().isContinuous() && (completed >= total)) {
                    baseTestReplicator.stop();
                }
                break;
            case STOPPED:
                latch.countDown();
                break;
        }

        return null;
    }

    void verifyChangeStatus(
        String expectedDomain,
        int expectedCode, ReplicatorChange change)
        throws CouchbaseLiteException {
        Replicator.Status status = change.getStatus();

        AbstractReplicator.ActivityLevel level = status.getActivityLevel();
        if (level != Replicator.ActivityLevel.STOPPED) { return; }

        CouchbaseLiteException error = status.getError();
        if (expectedCode == 0) {
            if (error == null) { return; }
            throw error;
        }

        assertNotNull(error);
        if ((expectedCode != error.getCode())
            || ((expectedDomain != null) && (!expectedDomain.equals(error.getDomain())))) {
            throw new RuntimeException("Expected error " + expectedDomain + "/" + expectedCode + " but got:", error);
        }
    }

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

        final Throwable[] fail = new Throwable[1];
        ListenerToken token = baseTestReplicator.addChangeListener(
            testSerialExecutor,
            change -> fail[0] = onChange(domain, code, latch, change));

        baseTestReplicator.start(reset);

        boolean success = false;
        try { success = latch.await(LONG_DELAY_SEC, TimeUnit.SECONDS); }
        catch (InterruptedException ignore) { }
        finally { baseTestReplicator.removeChangeListener(token); }

        if (fail[0] != null) { throw new AssertionError("Test failed with exception", fail[0]); }

        assertTrue(success);
    }

    private void testP2PError(MockClientConnection.ErrorLogic.LifecycleLocation location, boolean recoverable)
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
        MockClientConnection.ErrorLogic.LifecycleLocation location,
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
