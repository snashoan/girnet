package io.shnet.chat;

import java.util.Objects;
import java.util.UUID;

public final class ChatMessage {
    public enum Direction {
        IN,
        OUT
    }

    public final String id;
    public final String sender;
    public final String recipient;
    public final String peer;
    public final String text;
    public final long timestamp;
    public final Direction direction;
    public final int replyPort;

    public ChatMessage(String id, String sender, String recipient, String peer, String text, long timestamp, Direction direction) {
        this(id, sender, recipient, peer, text, timestamp, direction, 0);
    }

    public ChatMessage(String id, String sender, String recipient, String peer, String text, long timestamp, Direction direction, int replyPort) {
        this.id = id != null ? id : (System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8));
        this.sender = sender != null ? sender : "anonymous";
        this.recipient = recipient != null ? recipient : "";
        this.peer = peer != null ? peer : "";
        this.text = text != null ? text : "";
        this.timestamp = timestamp > 0 ? timestamp : System.currentTimeMillis();
        this.direction = direction != null ? direction : Direction.OUT;
        this.replyPort = replyPort;
    }

    public static ChatMessage createOutbound(String sender, String peer, String text) {
        return createOutbound(sender, peer, text, 0);
    }

    public static ChatMessage createOutbound(String sender, String peer, String text, int replyPort) {
        return new ChatMessage(null, sender, peer, peer, text, System.currentTimeMillis(), Direction.OUT, replyPort);
    }

    public static ChatMessage createInbound(String id, String sender, String peer, String text, long timestamp) {
        return new ChatMessage(id, sender, "me", peer, text, timestamp, Direction.IN, 0);
    }

    public static ChatMessage createInbound(String id, String sender, String peer, String text, long timestamp, int replyPort) {
        return new ChatMessage(id, sender, "me", peer, text, timestamp, Direction.IN, replyPort);
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"id\":\"").append(escapeJson(id)).append("\",");
        sb.append("\"sender\":\"").append(escapeJson(sender)).append("\",");
        sb.append("\"recipient\":\"").append(escapeJson(recipient)).append("\",");
        sb.append("\"peer\":\"").append(escapeJson(peer)).append("\",");
        sb.append("\"text\":\"").append(escapeJson(text)).append("\",");
        sb.append("\"timestamp\":").append(timestamp).append(",");
        if (replyPort > 0) {
            sb.append("\"reply_port\":").append(replyPort).append(",");
        }
        sb.append("\"direction\":\"").append(direction.name()).append("\"");
        sb.append("}");
        return sb.toString();
    }

    public static ChatMessage fromJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        String id = extractField(json, "id");
        String sender = extractField(json, "sender");
        String recipient = extractField(json, "recipient");
        String peer = extractField(json, "peer");
        String text = extractField(json, "text");
        String dirStr = extractField(json, "direction");
        long timestamp = 0;
        try {
            String tsStr = extractField(json, "timestamp");
            if (tsStr != null) {
                timestamp = Long.parseLong(tsStr);
            }
        } catch (NumberFormatException ignored) {
        }

        int replyPort = 0;
        try {
            String portStr = extractField(json, "reply_port");
            if (portStr != null) {
                replyPort = Integer.parseInt(portStr);
            }
        } catch (NumberFormatException ignored) {
        }

        Direction direction = Direction.OUT;
        if ("IN".equalsIgnoreCase(dirStr)) {
            direction = Direction.IN;
        }

        return new ChatMessage(id, sender, recipient, peer, text, timestamp, direction, replyPort);
    }

    public static String extractField(String json, String field) {
        String key = "\"" + field + "\"";
        int idx = json.indexOf(key);
        if (idx == -1) {
            return null;
        }
        int colon = json.indexOf(':', idx + key.length());
        if (colon == -1) {
            return null;
        }
        int start = colon + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
            start++;
        }
        if (start >= json.length()) {
            return null;
        }

        if (json.charAt(start) == '"') {
            start++;
            StringBuilder val = new StringBuilder();
            boolean escape = false;
            for (int i = start; i < json.length(); i++) {
                char c = json.charAt(i);
                if (escape) {
                    if (c == 'n') val.append('\n');
                    else if (c == 'r') val.append('\r');
                    else if (c == 't') val.append('\t');
                    else val.append(c);
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    return val.toString();
                } else {
                    val.append(c);
                }
            }
            return val.toString();
        } else {
            // Numeric or boolean or bare word
            int end = start;
            while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}' && !Character.isWhitespace(json.charAt(end))) {
                end++;
            }
            return json.substring(start, end).trim();
        }
    }

    public static String escapeJson(String str) {
        if (str == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 32) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ChatMessage)) return false;
        ChatMessage that = (ChatMessage) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "[" + sender + " -> " + peer + "]: " + text;
    }
}
