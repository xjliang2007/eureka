package com.netflix.eureka2.server.channel;

import java.util.Collections;

import com.netflix.eureka2.channel.ReplicationChannel.STATE;
import com.netflix.eureka2.metric.server.ReplicationChannelMetrics;
import com.netflix.eureka2.protocol.replication.RegisterCopy;
import com.netflix.eureka2.protocol.replication.ReplicationHelloReply;
import com.netflix.eureka2.protocol.replication.UnregisterCopy;
import com.netflix.eureka2.transport.MessageConnection;
import com.netflix.eureka2.transport.TransportClient;
import org.junit.Before;
import org.junit.Test;
import rx.Observable;
import rx.observers.TestSubscriber;
import rx.subjects.PublishSubject;
import rx.subjects.ReplaySubject;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
* @author Tomasz Bak
*/
public class SenderReplicationChannelTest extends AbstractReplicationChannelTest {

    private final TransportClient transportClient = mock(TransportClient.class);
    private final MessageConnection connection = mock(MessageConnection.class);
    private final ReplaySubject<Void> connectionLifeCycle = ReplaySubject.create();

    private SenderReplicationChannel replicationChannel;
    private final PublishSubject<Object> connectionIncoming = PublishSubject.create();
    private final ReplicationChannelMetrics channelMetrics = mock(ReplicationChannelMetrics.class);

    @Before
    public void setUp() throws Exception {
        when(transportClient.connect()).thenReturn(Observable.just(connection));
        when(connection.lifecycleObservable()).thenReturn(connectionLifeCycle);
        when(connection.incoming()).thenReturn(connectionIncoming);

        replicationChannel = new SenderReplicationChannel(transportClient, channelMetrics);
    }

    @Test(timeout = 60000)
    public void testHandshake() throws Exception {
        // Initialize channel by sending hello
        TestSubscriber<ReplicationHelloReply> replySubscriber = sendHello();

        // Verify interactions
        verify(connection, times(1)).submit(HELLO);
        replySubscriber.assertReceivedOnNext(Collections.singletonList(HELLO_REPLY));
    }

    @Test(timeout = 60000)
    public void testRegistration() throws Exception {
        // Initialize channel by sending hello
        sendHello();

        // Test registration
        TestSubscriber<Void> replySubscriber = new TestSubscriber<>();

        RegisterCopy message = new RegisterCopy(APP_INFO);
        when(connection.submit(message)).thenReturn(Observable.<Void>empty());
        replicationChannel.register(APP_INFO).subscribe(replySubscriber);

        replySubscriber.assertNoErrors();
        replySubscriber.assertTerminalEvent();
        verify(connection, times(1)).submit(message);
    }

    @Test(timeout = 60000)
    public void testUnregister() throws Exception {
        // Initialize channel by sending hello
        sendHello();

        // Test unregistration
        TestSubscriber<Void> replySubscriber = new TestSubscriber<>();

        UnregisterCopy message = new UnregisterCopy(APP_INFO.getId());
        when(connection.submit(message)).thenReturn(Observable.<Void>empty());
        replicationChannel.unregister(APP_INFO.getId()).subscribe(replySubscriber);

        replySubscriber.assertNoErrors();
        replySubscriber.assertTerminalEvent();
        verify(connection, times(1)).submit(message);
    }

    @Test(timeout = 60000)
    public void testMetrics() throws Exception {
        // Idle -> Handshake -> Connected
        sendHello();

        verify(channelMetrics, times(1)).incrementStateCounter(STATE.Handshake);
        verify(channelMetrics, times(1)).incrementStateCounter(STATE.Connected);

        // Shutdown channel (Connected -> Closed)
        replicationChannel.close();
        verify(channelMetrics, times(1)).decrementStateCounter(STATE.Connected);
        verify(channelMetrics, times(1)).incrementStateCounter(STATE.Closed);
    }

    private TestSubscriber<ReplicationHelloReply> sendHello() {
        TestSubscriber<ReplicationHelloReply> replySubscriber = new TestSubscriber<>();

        // Send hello
        when(connection.submit(HELLO)).thenReturn(Observable.<Void>empty());
        replicationChannel.hello(HELLO).subscribe(replySubscriber);

        // Send reply
        connectionIncoming.onNext(HELLO_REPLY);

        replySubscriber.assertNoErrors();

        return replySubscriber;
    }
}