package com.couchbase.lite;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import java.net.SocketException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.jetbrains.annotations.NotNull;
import org.junit.After;
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
            (task, executor) -> {
                Report.log(LogLevel.INFO, new Exception("REJECTED"), logPrefix() + " Rejected execution");
            });
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
        wire.submit(() -> sendAsync(data, completion));
    }

    @Override
    public void close(@Nullable final Exception e, @NonNull final MessagingCloseCompletion completion) {
        final MessagingCloseCompletion closeCompletion;
        synchronized (this) {
            closing = true;
            closeCompletion = onClose;
        }

        Report.log(LogLevel.DEBUG, e, logPrefix() + ".close %s", closeCompletion);
        wire.submit(() -> closeAsync(completion, closeCompletion));
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
            if (disconnecting) { closeReplAsync(repl, error); }
            else { disconnectAsync(error, completion); }
        });
    }

    public void stop() { disconnect(null, this::terminate); }

    @NotNull
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
        wire.submit(() -> deliverAsync(message, repl));
    }

    private void terminate() {
        Report.log(LogLevel.DEBUG, logPrefix() + ".terminate");
        wire.shutdown();
    }
}


/////////////////////////////////////  MOCK SERVER CONNECTION  /////////////////////////////////////

class MockServerConnection extends MockConnection {
    @NonNull
    private final MessageEndpointListener listener;

    @Nullable
    private MockClientConnection client;

    public MockServerConnection(@NonNull String testName, @NonNull MessageEndpointListener listener) {
        super(testName + ":Server");

        this.listener = listener;

        Report.log(LogLevel.DEBUG, logPrefix() + ".!<init>(%s, %s)", listener.getConfig().getProtocolType(), listener);
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
            ClassUtils.objId(clientConnection),
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
            err = getLogicError(ErrorLogic.LifecycleLocation.CONNECT);
        }
        Report.log(LogLevel.DEBUG, logPrefix() + ".openAsync %s %s", err, completion);

        final boolean succeeded = err == null;
        if (succeeded) { server.clientOpened(this); }
        completion.complete(succeeded, err);
    }

    @Override
    void sendAsync(@NonNull byte[] data, @NonNull MessagingCompletion completion) {
        final MessagingError err = getLogicError(ErrorLogic.LifecycleLocation.SEND);
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

        server.disconnect(getLogicError(ErrorLogic.LifecycleLocation.CLOSE), completion);
    }

    @Override
    void deliverAsync(@NonNull Message message, @NonNull ReplicatorConnection repl) {
        final MessagingError err = getLogicError(ErrorLogic.LifecycleLocation.RECEIVE);
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

    private MessagingError getLogicError(ErrorLogic.LifecycleLocation loc) {
        MessagingError err;
        synchronized (this) {
            if (errorLogic.shouldClose(loc)) { error = errorLogic.createError(); }
            err = error;
        }
        Report.log(LogLevel.DEBUG, logPrefix() + ".getLogicError @%s: %s", loc, err);
        return err;
    }
}


/////////////////////////////////////   CONNECTION FACTORY   /////////////////////////////////////

class MockConnectionFactory implements MessageEndpointDelegate {
    private final AtomicInteger connections = new AtomicInteger(0);
    private final MockClientConnection.ErrorLogic errorLogic;
    private final int maxConnections;

    public MockConnectionFactory(MockClientConnection.ErrorLogic errorLogic) {
        this(
            Integer.MAX_VALUE,
            errorLogic);
    }

    public MockConnectionFactory(int maxConnections) { this(maxConnections, null); }

    public MockConnectionFactory(int maxConnections, MockClientConnection.ErrorLogic errorLogic) {
        this.errorLogic = errorLogic;
        this.maxConnections = maxConnections;
    }

    @NonNull
    @Override
    public MessageEndpointConnection createConnection(@NonNull MessageEndpoint endpoint) {
        final int connects = connections.incrementAndGet();
        if (connects > maxConnections) { throw new IllegalStateException("Too many connections: " + connects); }
        final MockClientConnection client = new MockClientConnection(endpoint, errorLogic);
        MessageEndpointTest.addConnection(client);
        return client;
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
    public void changed(@NonNull MessageEndpointListenerChange change) {
        if (change.getStatus().getError() != null) { exceptions.add(change.getStatus().getError()); }

        if (change.getStatus().getActivityLevel() != ReplicatorActivityLevel.STOPPED) { return; }

        listener.removeChangeListener(token);
        token = null;
        latch.countDown();
    }

    public void waitForListener() throws InterruptedException {
        latch.await(BaseTest.LONG_TIMEOUT_SEC, TimeUnit.SECONDS);
    }

    public void validate() { assertTrue(exceptions.isEmpty()); }

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


/////////////////////////////////   T E S T   S U I T E   //////////////////////////////////////

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

        MockServerConnection server = getServerConnection("PushWithMsg", ProtocolType.MESSAGE_STREAM);

        MessageEndpoint endpoint
            = new MessageEndpoint("UID:123", server, ProtocolType.MESSAGE_STREAM, new MockConnectionFactory(null));
        ReplicatorConfiguration config = makeConfig(endpoint, ReplicatorType.PUSH, false);

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

        MockServerConnection server = getServerConnection("PushWithStream", ProtocolType.BYTE_STREAM);

        MessageEndpoint endpoint
            = new MessageEndpoint("UID:123", server, ProtocolType.BYTE_STREAM, new MockConnectionFactory(null));
        ReplicatorConfiguration config = makeConfig(endpoint, ReplicatorType.PUSH, false);

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

        MockServerConnection server = getServerConnection("PushContinuousWithMsg", ProtocolType.MESSAGE_STREAM);

        MessageEndpoint endpoint
            = new MessageEndpoint("UID:123", server, ProtocolType.MESSAGE_STREAM, new MockConnectionFactory(null));
        ReplicatorConfiguration config = makeConfig(endpoint, ReplicatorType.PUSH, true);

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

        MockServerConnection server = getServerConnection("PushContinuousWithStream", ProtocolType.BYTE_STREAM);

        MessageEndpoint endpoint
            = new MessageEndpoint("UID:123", server, ProtocolType.BYTE_STREAM, new MockConnectionFactory(null));
        ReplicatorConfiguration config = makeConfig(endpoint, ReplicatorType.PUSH, true);

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

        MockServerConnection server = getServerConnection("PullWithMsg", ProtocolType.MESSAGE_STREAM);

        MessageEndpoint endpoint
            = new MessageEndpoint("UID:123", server, ProtocolType.MESSAGE_STREAM, new MockConnectionFactory(null));
        ReplicatorConfiguration config = makeConfig(endpoint, ReplicatorType.PULL, false);

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

        MockServerConnection server = getServerConnection("PullWithStream", ProtocolType.BYTE_STREAM);

        MessageEndpoint endpoint
            = new MessageEndpoint("UID:123", server, ProtocolType.BYTE_STREAM, new MockConnectionFactory(null));
        ReplicatorConfiguration config = makeConfig(endpoint, ReplicatorType.PULL, false);

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

        MockServerConnection server = getServerConnection("PushContinuousWithMsg", ProtocolType.MESSAGE_STREAM);

        MessageEndpoint endpoint
            = new MessageEndpoint("UID:123", server, ProtocolType.MESSAGE_STREAM, new MockConnectionFactory(null));
        ReplicatorConfiguration config = makeConfig(endpoint, ReplicatorType.PULL, true);

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

        MockServerConnection server = getServerConnection("PullContinuousWithStream", ProtocolType.BYTE_STREAM);

        MessageEndpoint endpoint
            = new MessageEndpoint("UID:123", server, ProtocolType.BYTE_STREAM, new MockConnectionFactory(null));
        final ReplicatorConfiguration config = makeConfig(endpoint, ReplicatorType.PULL, true);

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

        MockServerConnection server = getServerConnection("PushPullWithMsg", ProtocolType.MESSAGE_STREAM);

        MessageEndpoint endpoint
            = new MessageEndpoint("UID:123", server, ProtocolType.MESSAGE_STREAM, new MockConnectionFactory(null));
        ReplicatorConfiguration config = makeConfig(endpoint, ReplicatorType.PUSH_AND_PULL, false);

        run(config, 0, null);

        assertEquals(2, otherDB.getCount());
        assertEquals("Tiger", otherDB.getDocument("doc1").getString("name"));

        assertEquals(2, baseTestDb.getCount());
        assertEquals("Cat", baseTestDb.getDocument("doc2").getString("name"));
    }

    @FlakyTest
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

        MockServerConnection server = getServerConnection("PushPullWithStream", ProtocolType.BYTE_STREAM);

        MessageEndpoint endpoint
            = new MessageEndpoint("UID:123", server, ProtocolType.BYTE_STREAM, new MockConnectionFactory(null));
        ReplicatorConfiguration config = makeConfig(endpoint, ReplicatorType.PUSH_AND_PULL, false);

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

        MockServerConnection server = getServerConnection("PushPullContinuousWithMsg", ProtocolType.MESSAGE_STREAM);

        MessageEndpoint endpoint
            = new MessageEndpoint("UID:123", server, ProtocolType.MESSAGE_STREAM, new MockConnectionFactory(null));
        ReplicatorConfiguration config = makeConfig(endpoint, ReplicatorType.PUSH_AND_PULL, true);

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

        MockServerConnection server = getServerConnection("PushPullContinuousWithStream", ProtocolType.BYTE_STREAM);

        MessageEndpoint endpoint
            = new MessageEndpoint("UID:123", server, ProtocolType.BYTE_STREAM, new MockConnectionFactory(null));
        ReplicatorConfiguration config = makeConfig(endpoint, ReplicatorType.PUSH_AND_PULL, true);

        run(config, 0, null);

        assertEquals(2, otherDB.getCount());
        assertEquals("Tiger", otherDB.getDocument("doc1").getString("name"));

        assertEquals(2, baseTestDb.getCount());
        assertEquals("Cat", baseTestDb.getDocument("doc2").getString("name"));
    }

    @FlakyTest
    @Test
    public void testP2PRecoverableFailureDuringOpen() throws CouchbaseLiteException {
        testP2PError("P2PRecoverableFailInOpen", MockClientConnection.ErrorLogic.LifecycleLocation.CONNECT, true);
    }

    @FlakyTest
    @Test
    public void testP2PRecoverableFailureDuringSend() throws CouchbaseLiteException {
        testP2PError("P2PRecoverableFailInSend", MockClientConnection.ErrorLogic.LifecycleLocation.SEND, true);
    }

    @FlakyTest
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

        MockServerConnection server = getServerConnection("P2PPassiveClose", listener);
        MessageEndpoint endpoint
            = new MessageEndpoint("p2ptest1", server, ProtocolType.MESSAGE_STREAM, new MockConnectionFactory(null));

        Replicator replicator = testReplicator(makeConfig(
            baseTestDb,
            endpoint,
            ReplicatorType.PUSH_AND_PULL,
            true));

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

        latch.await(LONG_TIMEOUT_SEC, TimeUnit.SECONDS);
        awaiter.waitForListener();
        awaiter.validate();

        replicator.removeChangeListener(token);
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

        MockServerConnection server = getServerConnection("CloseDbWithListener", listener);

        final MessageEndpoint endpoint
            = new MessageEndpoint("p2ptest2", server, ProtocolType.MESSAGE_STREAM, new MockConnectionFactory(1));
        final ReplicatorConfiguration config = makeConfig(baseTestDb, endpoint, ReplicatorType.PUSH_AND_PULL, true);
        final Replicator repl = testReplicator(config);
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
        MutableDocument doc = new MutableDocument("test");
        doc.setString("name", "smokey");
        baseTestDb.save(doc);

        final MessageEndpointListener listener = new MessageEndpointListener(
            new MessageEndpointListenerConfiguration(otherDB, ProtocolType.MESSAGE_STREAM));

        final MockServerConnection serverConnection1 = getServerConnection("P2PPassiveCloseAll1", listener);
        final Replicator replicator1 = testReplicator(makeConfig(
            baseTestDb,
            new MessageEndpoint(
                "p2ptest3",
                serverConnection1,
                ProtocolType.MESSAGE_STREAM,
                new MockConnectionFactory(null)),
            ReplicatorType.PUSH_AND_PULL,
            true));

        final MockServerConnection serverConnection2 = getServerConnection("P2PPassiveCloseAll2", listener);
        final Replicator replicator2 = testReplicator(makeConfig(
            baseTestDb,
            new MessageEndpoint(
                "p2ptest4",
                serverConnection2,
                ProtocolType.MESSAGE_STREAM,
                new MockConnectionFactory(null)),
            ReplicatorType.PUSH_AND_PULL,
            true));

        final CountDownLatch closeWait1 = new CountDownLatch(1);
        final CountDownLatch closeWait2 = new CountDownLatch(1);
        listener.addChangeListener(change -> {
            final ReplicatorActivityLevel activityLevel = change.getStatus().getActivityLevel();

            if (!ReplicatorActivityLevel.STOPPED.equals(activityLevel)) { return; }

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

        idleLatch1.await(LONG_TIMEOUT_SEC, TimeUnit.SECONDS);
        idleLatch2.await(LONG_TIMEOUT_SEC, TimeUnit.SECONDS);

        listener.closeAll();

        // wait for replicators to stop
        stopLatch1.await(LONG_TIMEOUT_SEC, TimeUnit.SECONDS);
        stopLatch2.await(LONG_TIMEOUT_SEC, TimeUnit.SECONDS);

        replicator1.removeChangeListener(token1);
        replicator2.removeChangeListener(token2);

        // wait for all notifications to come in
        assertTrue(closeWait1.await(LONG_TIMEOUT_SEC, TimeUnit.SECONDS));
        assertTrue(closeWait2.await(LONG_TIMEOUT_SEC, TimeUnit.SECONDS));
    }

    @Test
    public void testP2PChangeListener() throws InterruptedException {
        final ArrayList<ReplicatorActivityLevel> statuses = new ArrayList<>();
        MessageEndpointListener listener = new MessageEndpointListener(
            new MessageEndpointListenerConfiguration(otherDB, ProtocolType.BYTE_STREAM));
        MockServerConnection serverConnection = getServerConnection("P2PChangeListener", listener);
        MessageEndpoint endpoint = new MessageEndpoint(
            "p2ptest5",
            serverConnection,
            ProtocolType.BYTE_STREAM,
            new MockConnectionFactory(null));
        ReplicatorConfiguration config = makeConfig(baseTestDb, endpoint, ReplicatorType.PUSH_AND_PULL, true);

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
        final ArrayList<ReplicatorActivityLevel> statuses = new ArrayList<>();

        MessageEndpointListener listener = new MessageEndpointListener(
            new MessageEndpointListenerConfiguration(otherDB, ProtocolType.BYTE_STREAM));
        MockServerConnection serverConnection = getServerConnection("RemoveChangeListener", listener);
        MessageEndpoint endpoint = new MessageEndpoint(
            "p2ptest6",
            serverConnection,
            ProtocolType.BYTE_STREAM,
            new MockConnectionFactory(null));
        ReplicatorConfiguration config = makeConfig(baseTestDb, endpoint, ReplicatorType.PUSH_AND_PULL, true);

        ListenerToken token = listener.addChangeListener(change -> statuses
            .add(change.getStatus().getActivityLevel()));
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

        MockServerConnection server = getServerConnection("PushWithDocIDsFilter", ProtocolType.BYTE_STREAM);
        MessageEndpoint endpoint
            = new MessageEndpoint("UID:123", server, ProtocolType.BYTE_STREAM, new MockConnectionFactory(null));
        ReplicatorConfiguration config = makeConfig(endpoint, ReplicatorType.PUSH, false);
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

        MockServerConnection server = getServerConnection("PullWithDocIDsFilter", ProtocolType.BYTE_STREAM);
        MessageEndpoint endpoint
            = new MessageEndpoint("UID:123", server, ProtocolType.BYTE_STREAM, new MockConnectionFactory(null));
        ReplicatorConfiguration config = makeConfig(endpoint, ReplicatorType.PULL, false);
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

        MockServerConnection server = getServerConnection("PushPullWithFilter", ProtocolType.BYTE_STREAM);
        MessageEndpoint endpoint
            = new MessageEndpoint("UID:123", server, ProtocolType.BYTE_STREAM, new MockConnectionFactory(null));
        ReplicatorConfiguration config = makeConfig(endpoint, ReplicatorType.PUSH_AND_PULL, false);
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
        MutableDocument mdoc = new MutableDocument("livesindb");
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
            new MessageEndpoint("p2ptest0", server, protocolType, new MockConnectionFactory(errorLocation)),
            ReplicatorType.PUSH,
            false);
    }
}
