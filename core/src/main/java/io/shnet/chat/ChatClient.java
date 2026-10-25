package io.shnet.chat;

import io.shnet.Shnet;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class ChatClient {
    private static final int DEFAULT_TIMEOUT_MS = 6000;

    private ChatClient() {
    }

    public static final class SendResult {
        public final boolean success;
        public final int statusCode;
        public final String responseBody;
        public final String error;

        public SendResult(boolean success, int statusCode, String responseBody, String error) {
            this.success = success;
            this.statusCode = statusCode;
            this.responseBody = responseBody;
            this.error = error;
        }

        public static SendResult ok(int code, String body) {
            return new SendResult(true, code, body, null);
        }

        public static SendResult fail(int code, String error) {
            return new SendResult(false, code, null, error);
        }
    }

    public static String formatPeerUrl(String rawPeer) {
        if (rawPeer == null || rawPeer.trim().isEmpty()) {
            throw new IllegalArgumentException("Peer address cannot be empty");
        }
        String s = rawPeer.trim();
        String scheme = "http://";
        if (s.startsWith("http://")) {
            scheme = "http://";
            s = s.substring(7);
        } else if (s.startsWith("https://")) {
            scheme = "https://";
            s = s.substring(8);
        }
        if (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        if (!s.startsWith("[") && s.contains(":")) {
            int firstColon = s.indexOf(':');
            int lastColon = s.lastIndexOf(':');
            if (firstColon != lastColon) {
                String lastPart = s.substring(lastColon + 1);
                boolean isPort = false;
                if (!lastPart.isEmpty() && lastPart.matches("\\d+")) {
                    try {
                        int p = Integer.parseInt(lastPart);
                        if (p > 0 && p <= 65535) {
                            isPort = true;
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
                if (isPort) {
                    String ipPart = s.substring(0, lastColon);
                    s = "[" + ipPart + "]:" + lastPart;
                } else {
                    s = "[" + s + "]:" + Shnet.DEFAULT_PORT;
                }
            }
        }
        return scheme + s + "/";
    }

    public static SendResult sendMessage(String peerUrl, ChatMessage message) {
        try {
            String baseUrl = formatPeerUrl(peerUrl);
            URL url = new URL(baseUrl + "api/chat/message");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("User-Agent", "shnet-chat/1.0.0");
            conn.setConnectTimeout(DEFAULT_TIMEOUT_MS);
            conn.setReadTimeout(DEFAULT_TIMEOUT_MS);
            conn.setDoOutput(true);

            byte[] jsonBytes = message.toJson().getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Length", String.valueOf(jsonBytes.length));

            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBytes);
                os.flush();
            }

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
            String responseText = "";
            if (is != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    responseText = sb.toString();
                }
            }

            if (code >= 200 && code < 300) {
                return SendResult.ok(code, responseText);
            } else {
                return SendResult.fail(code, "HTTP " + code + (responseText.isEmpty() ? "" : ": " + responseText));
            }
        } catch (Exception e) {
            return SendResult.fail(-1, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    public static String ping(String peerUrl) {
        try {
            String baseUrl = formatPeerUrl(peerUrl);
            URL url = new URL(baseUrl + "api/chat/info");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);

            int code = conn.getResponseCode();
            if (code == 200) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    return sb.toString();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public static final class ConnectResult {
        public final boolean success;
        public final String peerName;
        public final String peerUrl;
        public final String error;

        public ConnectResult(boolean success, String peerName, String peerUrl, String error) {
            this.success = success;
            this.peerName = peerName;
            this.peerUrl = peerUrl;
            this.error = error;
        }
    }

    public static ConnectResult connect(String peerUrl, String localName, int localPort) {
        try {
            String baseUrl = formatPeerUrl(peerUrl);
            URL url = new URL(baseUrl + "api/chat/connect");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("User-Agent", "shnet-chat/1.0.0");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setDoOutput(true);

            String body = String.format("{\"name\":\"%s\",\"port\":%d}",
                    ChatMessage.escapeJson(localName), localPort);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Length", String.valueOf(bytes.length));

            try (OutputStream os = conn.getOutputStream()) {
                os.write(bytes);
                os.flush();
            }

            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    String resp = sb.toString();
                    String peerName = ChatMessage.extractField(resp, "name");
                    return new ConnectResult(true, (peerName != null && !peerName.isEmpty()) ? peerName : "host", baseUrl, null);
                }
            } else {
                return new ConnectResult(false, null, null, "HTTP " + code);
            }
        } catch (Exception e) {
            return new ConnectResult(false, null, null, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    public static void disconnect(String peerUrl, String localName) {
        if (peerUrl == null || peerUrl.trim().isEmpty()) return;
        try {
            String baseUrl = formatPeerUrl(peerUrl);
            URL url = new URL(baseUrl + "api/chat/disconnect");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(1500);
            conn.setDoOutput(true);

            String body = String.format("{\"name\":\"%s\"}", ChatMessage.escapeJson(localName));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Length", String.valueOf(bytes.length));

            try (OutputStream os = conn.getOutputStream()) {
                os.write(bytes);
                os.flush();
            }
            conn.getResponseCode();
        } catch (Exception ignored) {
        }
    }

    public static String discoverHost(int targetPort) {
        // 1. Check localhost first
        String localPing = ping("http://127.0.0.1:" + targetPort);
        if (localPing != null) {
            return "http://127.0.0.1:" + targetPort + "/";
        }

        // 2. Scan active non-loopback network interfaces on LAN
        try {
            java.util.Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
            java.util.List<String> subnets = new java.util.ArrayList<>();
            while (interfaces.hasMoreElements()) {
                java.net.NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                java.util.Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress addr = addrs.nextElement();
                    if (addr instanceof java.net.Inet4Address) {
                        String ip = addr.getHostAddress();
                        int lastDot = ip.lastIndexOf('.');
                        if (lastDot > 0) {
                            subnets.add(ip.substring(0, lastDot + 1));
                        }
                    }
                }
            }

            if (!subnets.isEmpty()) {
                java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(40);
                java.util.List<java.util.concurrent.Future<String>> futures = new java.util.ArrayList<>();
                for (String prefix : subnets) {
                    for (int i = 1; i <= 254; i++) {
                        final String testIp = prefix + i;
                        futures.add(pool.submit(() -> {
                            try (java.net.Socket socket = new java.net.Socket()) {
                                socket.connect(new java.net.InetSocketAddress(testIp, targetPort), 150);
                                String info = ping("http://" + testIp + ":" + targetPort);
                                if (info != null && info.contains("shnet")) {
                                    return "http://" + testIp + ":" + targetPort + "/";
                                }
                            } catch (Exception ignored) {
                            }
                            return null;
                        }));
                    }
                }

                for (java.util.concurrent.Future<String> f : futures) {
                    try {
                        String found = f.get();
                        if (found != null) {
                            pool.shutdownNow();
                            return found;
                        }
                    } catch (Exception ignored) {
                    }
                }
                pool.shutdown();
            }
        } catch (Exception ignored) {
        }

        return null;
    }
}
