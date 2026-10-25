package io.shnet.chat;

import io.shnet.Shnet;
import io.shnet.ShnetServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class ChatTest {

    @Test
    public void testChatMessageJsonSerialization() {
        ChatMessage msg = new ChatMessage("msg-1", "alice", "bob", "192.168.1.10:8723",
                "Hello \"world\"!\nLine 2\tTabbed", 1700000000000L, ChatMessage.Direction.OUT);

        String json = msg.toJson();
        assertNotNull(json);
        assertTrue(json.contains("\"id\":\"msg-1\""));
        assertTrue(json.contains("\"sender\":\"alice\""));
        assertTrue(json.contains("\"peer\":\"192.168.1.10:8723\""));

        ChatMessage parsed = ChatMessage.fromJson(json);
        assertNotNull(parsed);
        assertEquals("msg-1", parsed.id);
        assertEquals("alice", parsed.sender);
        assertEquals("bob", parsed.recipient);
        assertEquals("192.168.1.10:8723", parsed.peer);
        assertEquals("Hello \"world\"!\nLine 2\tTabbed", parsed.text);
        assertEquals(1700000000000L, parsed.timestamp);
        assertEquals(ChatMessage.Direction.OUT, parsed.direction);
    }

    @Test
    public void testChatStorage(@TempDir Path tempDir) throws Exception {
        ChatStorage storage = new ChatStorage(tempDir.toFile());

        ChatMessage msg1 = ChatMessage.createOutbound("rei", "192.168.1.50:8723", "First message");
        ChatMessage msg2 = ChatMessage.createInbound("in-1", "alice", "192.168.1.50:8723", "Reply from alice", System.currentTimeMillis());
        ChatMessage msg3 = ChatMessage.createOutbound("rei", "10.0.0.1:8723", "Message to another peer");

        storage.save(msg1);
        storage.save(msg2);
        storage.save(msg3);

        List<ChatMessage> peerHistory = storage.getHistory("192.168.1.50:8723", 10);
        assertEquals(2, peerHistory.size());
        assertEquals("First message", peerHistory.get(0).text);
        assertEquals("Reply from alice", peerHistory.get(1).text);

        List<ChatMessage> allHistory = storage.getAllHistory(10);
        assertEquals(3, allHistory.size());

        List<String> peers = storage.getRecentPeers();
        assertEquals(2, peers.size());

        // Clear history for 192.168.1.50:8723
        storage.clearHistory("192.168.1.50:8723");
        List<ChatMessage> remaining = storage.getAllHistory(10);
        assertEquals(1, remaining.size());
        assertEquals("10.0.0.1:8723", remaining.get(0).peer);
    }

    @Test
    public void testEndToEndChat(@TempDir Path tempDir) throws Exception {
        int testPort = 18730;
        ChatStorage serverStorage = new ChatStorage(tempDir.toFile());
        AtomicReference<ChatMessage> received = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        ChatHandler chatHandler = new ChatHandler(serverStorage, "serverNode", msg -> {
            received.set(msg);
            latch.countDown();
        });

        Shnet.Config config = Shnet.Config.builder(testPort)
                .setBindMode(Shnet.BindMode.IPV4_ONLY)
                .build();

        try (ShnetServer server = new ShnetServer(config, chatHandler)) {
            server.start();

            // 1. Test ping
            String pingInfo = ChatClient.ping("http://127.0.0.1:" + testPort + "/");
            assertNotNull(pingInfo);
            assertTrue(pingInfo.contains("serverNode"));

            // 2. Send message
            ChatMessage outgoing = ChatMessage.createOutbound("clientUser", "127.0.0.1:" + testPort, "Hello over shnet chat!");
            ChatClient.SendResult result = ChatClient.sendMessage("http://127.0.0.1:" + testPort + "/", outgoing);

            assertTrue(result.success, "Send should succeed: " + result.error);
            assertEquals(200, result.statusCode);

            boolean gotMessage = latch.await(3, TimeUnit.SECONDS);
            assertTrue(gotMessage, "MessageListener should be triggered within 3s");

            ChatMessage in = received.get();
            assertNotNull(in);
            assertEquals("clientUser", in.sender);
            assertEquals("Hello over shnet chat!", in.text);
            assertEquals(ChatMessage.Direction.IN, in.direction);

            // 3. Verify server storage persisted it
            List<ChatMessage> stored = serverStorage.getAllHistory(10);
            assertFalse(stored.isEmpty());
            assertEquals("Hello over shnet chat!", stored.get(0).text);
        }
    }

    @Test
    public void testConnectAndDisconnect(@TempDir Path tempDir) throws Exception {
        int testPort = 18731;
        ChatStorage serverStorage = new ChatStorage(tempDir.toFile());
        AtomicReference<String> connectedPeer = new AtomicReference<>();
        AtomicReference<String> disconnectedPeer = new AtomicReference<>();
        CountDownLatch connectLatch = new CountDownLatch(1);
        CountDownLatch disconnectLatch = new CountDownLatch(1);

        ChatHandler chatHandler = new ChatHandler(serverStorage, "hostRei", new ChatHandler.ChatEventListener() {
            @Override
            public void onMessageReceived(ChatMessage message) {}

            @Override
            public void onPeerConnected(String peerName, String peerUrl) {
                connectedPeer.set(peerName + "@" + peerUrl);
                connectLatch.countDown();
            }

            @Override
            public void onPeerDisconnected(String peerName) {
                disconnectedPeer.set(peerName);
                disconnectLatch.countDown();
            }
        });
        chatHandler.setLocalPort(testPort);

        Shnet.Config config = Shnet.Config.builder(testPort)
                .setBindMode(Shnet.BindMode.IPV4_ONLY)
                .build();

        try (ShnetServer server = new ShnetServer(config, chatHandler)) {
            server.start();

            // Connect
            ChatClient.ConnectResult connectRes = ChatClient.connect("http://127.0.0.1:" + testPort, "thisthat", 18732);
            assertTrue(connectRes.success);
            assertEquals("hostRei", connectRes.peerName);

            boolean gotConnect = connectLatch.await(3, TimeUnit.SECONDS);
            assertTrue(gotConnect);
            assertTrue(connectedPeer.get().startsWith("thisthat@http://127.0.0.1:18732/"));

            // Disconnect
            ChatClient.disconnect("http://127.0.0.1:" + testPort, "thisthat");
            boolean gotDisconnect = disconnectLatch.await(3, TimeUnit.SECONDS);
            assertTrue(gotDisconnect);
            assertEquals("thisthat", disconnectedPeer.get());
        }
    }
}
