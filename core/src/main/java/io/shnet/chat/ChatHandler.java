package io.shnet.chat;

import io.shnet.Shnet;

import java.io.IOException;
import java.util.List;

public final class ChatHandler implements Shnet.Handler {
    @FunctionalInterface
    public interface MessageListener {
        void onMessageReceived(ChatMessage message);
    }

    public interface ChatEventListener extends MessageListener {
        @Override
        void onMessageReceived(ChatMessage message);
        default void onPeerConnected(String peerName, String peerUrl) {}
        default void onPeerDisconnected(String peerName) {}
    }

    private final ChatStorage storage;
    private volatile String nodeName;
    private final ChatEventListener listener;
    private final Shnet.Handler fallback;
    private int localPort = Shnet.DEFAULT_PORT;

    public ChatHandler(ChatStorage storage, String nodeName, MessageListener listener) {
        this(storage, nodeName, wrapListener(listener), null);
    }

    private static ChatEventListener wrapListener(MessageListener listener) {
        return message -> {
            if (listener != null) {
                listener.onMessageReceived(message);
            }
        };
    }

    public ChatHandler(ChatStorage storage, String nodeName, ChatEventListener listener) {
        this(storage, nodeName, listener, null);
    }

    public ChatHandler(ChatStorage storage, String nodeName, ChatEventListener listener, Shnet.Handler fallback) {
        this.storage = storage != null ? storage : new ChatStorage();
        this.nodeName = nodeName != null && !nodeName.trim().isEmpty() ? nodeName : "rei";
        this.listener = listener;
        this.fallback = fallback;
    }

    public void setLocalPort(int port) {
        this.localPort = port;
    }

    public void setNodeName(String name) {
        if (name != null && !name.trim().isEmpty()) {
            this.nodeName = name.trim();
        }
    }

    public String getNodeName() {
        return this.nodeName;
    }

    @Override
    public Shnet.Response handle(Shnet.Request request) throws Exception {
        String path = request.path;
        if (path == null) path = "/";

        if ("/api/chat/connect".equals(path)) {
            if (!"POST".equalsIgnoreCase(request.method)) {
                return Shnet.Response.text(405, "application/json", "{\"error\":\"Method Not Allowed\"}");
            }
            return handleConnect(request);
        }

        if ("/api/chat/disconnect".equals(path)) {
            if (!"POST".equalsIgnoreCase(request.method)) {
                return Shnet.Response.text(405, "application/json", "{\"error\":\"Method Not Allowed\"}");
            }
            return handleDisconnect(request);
        }

        if ("/api/chat/message".equals(path)) {
            if (!"POST".equalsIgnoreCase(request.method)) {
                return Shnet.Response.text(405, "application/json", "{\"error\":\"Method Not Allowed\"}");
            }
            return handleIncomingMessage(request);
        }

        if ("/api/chat/info".equals(path)) {
            String infoJson = String.format("{\"name\":\"%s\",\"version\":\"%s\",\"service\":\"shnet-chat\",\"port\":%d}",
                    escapeJson(nodeName), Shnet.VERSION, localPort);
            return Shnet.Response.json(infoJson);
        }

        if ("/api/chat/history".equals(path)) {
            String peerParam = request.queryParam("peer");
            int limit = 50;
            String limitStr = request.queryParam("limit");
            if (limitStr != null) {
                try {
                    limit = Integer.parseInt(limitStr);
                } catch (NumberFormatException ignored) {
                }
            }
            List<ChatMessage> history = storage.getHistory(peerParam, limit);
            StringBuilder sb = new StringBuilder();
            sb.append("[");
            for (int i = 0; i < history.size(); i++) {
                sb.append(history.get(i).toJson());
                if (i < history.size() - 1) sb.append(",");
            }
            sb.append("]");
            return Shnet.Response.json(sb.toString());
        }

        if (fallback != null) {
            return fallback.handle(request);
        }

        return Shnet.Response.notFound();
    }

    private Shnet.Response handleConnect(Shnet.Request request) {
        String body = request.bodyAsString();
        String clientName = ChatMessage.extractField(body, "name");
        if (clientName == null || clientName.trim().isEmpty()) {
            clientName = "anonymous";
        }
        int clientPort = 0;
        try {
            String pStr = ChatMessage.extractField(body, "port");
            if (pStr != null) clientPort = Integer.parseInt(pStr);
        } catch (NumberFormatException ignored) {}

        String internetUrl = ChatMessage.extractField(body, "internet_url");
        String remoteIp = resolveRemoteIp(request);
        String hostPart = (remoteIp.contains(":") && !remoteIp.startsWith("[")) ? ("[" + remoteIp + "]") : remoteIp;
        String peerUrl;
        if ("127.0.0.1".equals(remoteIp)) {
            peerUrl = (clientPort > 0) ? ("http://127.0.0.1:" + clientPort + "/") : "http://127.0.0.1/";
        } else if (internetUrl != null && !internetUrl.isEmpty()) {
            peerUrl = internetUrl;
        } else {
            peerUrl = (clientPort > 0) ? ("http://" + hostPart + ":" + clientPort + "/") : ("http://" + hostPart + "/");
        }

        if (listener != null) {
            try {
                listener.onPeerConnected(clientName, peerUrl);
            } catch (Exception ignored) {}
        }

        String primaryUrl = io.shnet.ShnetLinks.getPrimaryUrl(localPort);
        String ackJson = String.format("{\"status\":\"connected\",\"name\":\"%s\",\"port\":%d,\"internet_url\":\"%s\",\"peer\":\"%s\"}",
                escapeJson(nodeName), localPort, escapeJson(primaryUrl), escapeJson(peerUrl));
        return Shnet.Response.json(ackJson);
    }

    private Shnet.Response handleDisconnect(Shnet.Request request) {
        String body = request.bodyAsString();
        String clientName = ChatMessage.extractField(body, "name");
        if (clientName == null || clientName.trim().isEmpty()) {
            clientName = "peer";
        }

        if (listener != null) {
            try {
                listener.onPeerDisconnected(clientName);
            } catch (Exception ignored) {}
        }

        return Shnet.Response.json("{\"status\":\"disconnected\"}");
    }

    private Shnet.Response handleIncomingMessage(Shnet.Request request) {
        String body = request.bodyAsString();
        ChatMessage parsed = ChatMessage.fromJson(body);
        if (parsed == null || parsed.text == null || parsed.text.trim().isEmpty()) {
            return Shnet.Response.badRequest("Missing message text");
        }

        String remoteIp = resolveRemoteIp(request);
        String hostPart = (remoteIp.contains(":") && !remoteIp.startsWith("[")) ? ("[" + remoteIp + "]") : remoteIp;
        String peerHost = (parsed.replyPort > 0)
                ? ("http://" + hostPart + ":" + parsed.replyPort + "/")
                : ((parsed.peer != null && !parsed.peer.isEmpty()) ? parsed.peer : hostPart);

        ChatMessage inbound = new ChatMessage(
                parsed.id,
                parsed.sender,
                nodeName,
                peerHost,
                parsed.text,
                parsed.timestamp > 0 ? parsed.timestamp : System.currentTimeMillis(),
                ChatMessage.Direction.IN,
                parsed.replyPort
        );

        try {
            storage.save(inbound);
        } catch (IOException e) {
            return Shnet.Response.text(500, "application/json", "{\"error\":\"Storage failure\"}");
        }

        if (listener != null) {
            try {
                if (parsed.replyPort > 0) {
                    listener.onPeerConnected(parsed.sender, peerHost);
                }
                listener.onMessageReceived(inbound);
            } catch (Exception ignored) {
            }
        }

        String ackJson = String.format("{\"status\":\"ok\",\"id\":\"%s\",\"recipient\":\"%s\"}",
                escapeJson(inbound.id), escapeJson(nodeName));
        return Shnet.Response.json(ackJson);
    }

    private static String resolveRemoteIp(Shnet.Request request) {
        String remoteIp = (request.clientAddress != null) ? request.clientAddress.getHostAddress() : "127.0.0.1";
        if (remoteIp.startsWith("/")) {
            remoteIp = remoteIp.substring(1);
        }
        int zoneIdx = remoteIp.indexOf('%');
        if (zoneIdx >= 0) {
            remoteIp = remoteIp.substring(0, zoneIdx);
        }
        if (remoteIp.startsWith("::ffff:")) {
            remoteIp = remoteIp.substring(7);
        }
        if ("0:0:0:0:0:0:0:1".equals(remoteIp) || "::1".equals(remoteIp)) {
            remoteIp = "127.0.0.1";
        }
        return remoteIp;
    }

    private static String escapeJson(String s) {
        return ChatMessage.escapeJson(s);
    }
}
