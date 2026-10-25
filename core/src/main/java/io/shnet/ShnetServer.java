package io.shnet;

import javax.net.ServerSocketFactory;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class ShnetServer implements AutoCloseable {
    private static final int MAX_HEADER_BYTES = 32 * 1024;
    private static final int MAX_LINE_BYTES = 8192;

    private final Shnet.Config config;
    private final Shnet.Handler handler;
    private ServerSocket ipv6Socket;
    private ServerSocket ipv4Socket;
    private Thread ipv6Thread;
    private Thread ipv4Thread;
    private ExecutorService workers;
    private volatile boolean running;
    private String bindHost = "";
    private long startedAt;
    private final AtomicLong totalRequests = new AtomicLong(0);

    public ShnetServer(Shnet.Config config, Shnet.Handler handler) {
        if (config == null) {
            throw new IllegalArgumentException("Config must not be null");
        }
        this.config = config;
        this.handler = handler != null ? handler : (req -> Shnet.Response.notFound());
    }

    public synchronized void start() throws IOException {
        if (running) {
            return;
        }
        running = true;
        startedAt = System.currentTimeMillis();

        ServerSocketFactory socketFactory = null;
        if (config.tlsConfig != null) {
            try {
                socketFactory = buildTlsSocketFactory();
            } catch (Exception e) {
                running = false;
                if (e instanceof IOException) throw (IOException) e;
                throw new IOException("TLS init failed: " + e.getMessage(), e);
            }
        }

        workers = Executors.newFixedThreadPool(
                Math.max(1, config.workerThreads),
                new ShnetThreadFactory()
        );

        ipv6Socket = null;
        ipv4Socket = null;
        IOException lastException = null;

        if (config.bindMode == Shnet.BindMode.IPV6_ONLY
                || config.bindMode == Shnet.BindMode.DUAL
                || config.bindMode == Shnet.BindMode.IPV6_PREFERRED) {
            try {
                ipv6Socket = bindSocket("::", config.port, socketFactory);
            } catch (IOException ex) {
                lastException = ex;
                ipv6Socket = null;
            }
        }

        if (config.bindMode == Shnet.BindMode.IPV4_ONLY
                || config.bindMode == Shnet.BindMode.DUAL
                || (config.bindMode == Shnet.BindMode.IPV6_PREFERRED && ipv6Socket == null)) {
            try {
                ipv4Socket = bindSocket("0.0.0.0", config.port, socketFactory);
            } catch (IOException ex) {
                if (lastException == null) {
                    lastException = ex;
                }
                ipv4Socket = null;
            }
        }

        if (ipv6Socket == null && ipv4Socket == null) {
            running = false;
            shutdownWorkers();
            if (lastException != null) {
                throw lastException;
            }
            throw new IOException("Unable to bind shnet server to port " + config.port);
        }

        if (ipv6Socket != null) {
            ipv6Thread = new Thread(() -> runLoop(ipv6Socket), "ShnetServer-v6");
            ipv6Thread.setDaemon(false);
            ipv6Thread.start();
        }
        if (ipv4Socket != null) {
            ipv4Thread = new Thread(() -> runLoop(ipv4Socket), "ShnetServer-v4");
            ipv4Thread.setDaemon(false);
            ipv4Thread.start();
        }

        if (ipv6Socket != null && ipv4Socket != null) {
            bindHost = "dual";
        } else if (ipv6Socket != null) {
            bindHost = "::";
        } else {
            bindHost = "0.0.0.0";
        }
    }

    public synchronized void stop() {
        running = false;
        closeQuietly(ipv6Socket);
        closeQuietly(ipv4Socket);
        ipv6Socket = null;
        ipv4Socket = null;
        if (ipv6Thread != null) {
            ipv6Thread.interrupt();
            ipv6Thread = null;
        }
        if (ipv4Thread != null) {
            ipv4Thread.interrupt();
            ipv4Thread = null;
        }
        shutdownWorkers();
    }

    @Override
    public void close() {
        stop();
    }

    public boolean isRunning() {
        return running;
    }

    public int getPort() {
        return config.port;
    }

    public String getBindHost() {
        return bindHost;
    }

    public long getStartedAt() {
        return startedAt;
    }

    public long getUptimeSeconds() {
        return running ? (System.currentTimeMillis() - startedAt) / 1000 : 0;
    }

    public long getTotalRequests() {
        return totalRequests.get();
    }

    public Shnet.Config getConfig() {
        return config;
    }

    private ServerSocket bindSocket(String host, int port, ServerSocketFactory factory) throws IOException {
        ServerSocket socket = factory == null ? new ServerSocket() : factory.createServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(InetAddress.getByName(host), port));
        if (socket instanceof SSLServerSocket) {
            ((SSLServerSocket) socket).setUseClientMode(false);
        }
        return socket;
    }

    private ServerSocketFactory buildTlsSocketFactory() throws Exception {
        Shnet.TlsConfig tlsConfig = config.tlsConfig;
        if (tlsConfig == null) {
            return null;
        }

        KeyStore keyStore;
        char[] keyPass;

        if (tlsConfig.source == Shnet.TlsConfig.Source.PEM_FILES) {
            keyPass = "shnet_temp".toCharArray();
            keyStore = loadKeyStoreFromPem(tlsConfig.certPath, tlsConfig.privateKeyPath, keyPass);
        } else {
            keyStore = KeyStore.getInstance(tlsConfig.keyStoreType);
            char[] storePass = tlsConfig.keyStorePassword != null ? tlsConfig.keyStorePassword.toCharArray() : null;
            try (InputStream is = new FileInputStream(tlsConfig.keyStorePath)) {
                keyStore.load(is, storePass);
            }
            keyPass = tlsConfig.keyPassword != null ? tlsConfig.keyPassword.toCharArray() : storePass;
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, keyPass);
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), null, null);
        return sslContext.getServerSocketFactory();
    }

    private KeyStore loadKeyStoreFromPem(String certPath, String keyPath, char[] password) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        Collection<? extends Certificate> certs;
        try (InputStream certIn = new FileInputStream(certPath)) {
            certs = cf.generateCertificates(certIn);
        }
        if (certs.isEmpty()) {
            throw new IllegalArgumentException("No certificates found in " + certPath);
        }
        Certificate[] certChain = certs.toArray(new Certificate[0]);

        String keyPem = new String(Files.readAllBytes(new File(keyPath).toPath()), StandardCharsets.UTF_8);
        String cleanedKey = keyPem.replaceAll("-----BEGIN [A-Z ]+-----", "")
                .replaceAll("-----END [A-Z ]+-----", "")
                .replaceAll("\\s+", "");
        byte[] keyBytes = Base64.getDecoder().decode(cleanedKey);

        PrivateKey privateKey;
        try {
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            privateKey = kf.generatePrivate(spec);
        } catch (Exception ex) {
            try {
                PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
                KeyFactory kf = KeyFactory.getInstance("EC");
                privateKey = kf.generatePrivate(spec);
            } catch (Exception ex2) {
                throw new GeneralSecurityException("Unable to decode private key (must be PKCS#8 RSA/EC): " + ex.getMessage());
            }
        }

        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, password);
        ks.setKeyEntry("shnet", privateKey, password, certChain);
        return ks;
    }

    private void runLoop(ServerSocket socket) {
        while (running && socket != null && !socket.isClosed()) {
            try {
                Socket clientSocket = socket.accept();
                if (workers != null && !workers.isShutdown()) {
                    workers.execute(() -> handleClient(clientSocket));
                } else {
                    handleClient(clientSocket);
                }
            } catch (IOException ignored) {
                if (!running) {
                    return;
                }
            }
        }
    }

    private void handleClient(Socket socket) {
        long startNanos = System.nanoTime();
        try (Socket client = socket) {
            client.setSoTimeout(config.readTimeoutMs);
            InputStream input = client.getInputStream();
            OutputStream output = client.getOutputStream();

            String requestLine = readLine(input, MAX_LINE_BYTES);
            if (requestLine == null || requestLine.isEmpty()) {
                return;
            }

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                sendResponse(output, Shnet.Response.text(400, "text/plain; charset=utf-8", "Bad Request"));
                return;
            }

            String method = parts[0].trim();
            String rawPath = parts[1].trim();
            String path = rawPath;
            String query = "";
            int queryIndex = rawPath.indexOf('?');
            if (queryIndex >= 0) {
                path = rawPath.substring(0, queryIndex);
                query = rawPath.substring(queryIndex + 1);
            }

            Map<String, String> headers = new HashMap<>();
            int headerBytes = 0;
            String headerLine;
            while ((headerLine = readLine(input, MAX_LINE_BYTES)) != null && !headerLine.isEmpty()) {
                headerBytes += headerLine.length();
                if (headerBytes > MAX_HEADER_BYTES) {
                    sendResponse(output, Shnet.Response.text(400, "text/plain; charset=utf-8", "Bad Request: Headers Too Large"));
                    return;
                }
                int idx = headerLine.indexOf(':');
                if (idx > 0) {
                    String key = headerLine.substring(0, idx).trim().toLowerCase(Locale.ROOT);
                    String value = headerLine.substring(idx + 1).trim();
                    headers.put(key, value);
                }
            }

            int contentLength = 0;
            String lengthHeader = headers.get("content-length");
            if (lengthHeader != null) {
                try {
                    contentLength = Integer.parseInt(lengthHeader);
                } catch (NumberFormatException ignored) {
                    contentLength = 0;
                }
            }

            if (contentLength > config.maxBodyBytes) {
                sendResponse(output, Shnet.Response.text(413, "text/plain; charset=utf-8", "Payload Too Large"));
                return;
            }

            byte[] body = contentLength > 0 ? readBody(input, contentLength) : new byte[0];
            Shnet.Request request = new Shnet.Request(method, path, query, headers, body, client.getInetAddress());
            totalRequests.incrementAndGet();

            Shnet.Response response;
            try {
                // Check internal endpoints
                if ("/_shnet/status".equals(path)) {
                    response = handleInternalStatus();
                } else if ("/_shnet/links".equals(path)) {
                    response = handleInternalLinks();
                } else if ("/_shnet/qr".equals(path)) {
                    response = handleInternalQr();
                } else {
                    response = handler.handle(request);
                }
            } catch (Exception ex) {
                response = Shnet.Response.text(500, "text/plain; charset=utf-8", "Internal Server Error: " + ex.getMessage());
            }

            if (response == null) {
                response = Shnet.Response.text(404, "text/plain; charset=utf-8", "Not Found");
            }

            if (response.file != null) {
                sendFileResponse(output, response);
            } else {
                sendResponse(output, response);
            }

            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
            if (config.requestListener != null) {
                config.requestListener.onRequest(request, response, durationMs);
            }
        } catch (IOException ignored) {
            // Ignore socket disconnects
        }
    }

    private Shnet.Response handleInternalStatus() {
        StringBuilder json = new StringBuilder();
        json.append("{\n")
                .append("  \"status\": \"running\",\n")
                .append("  \"version\": \"").append(Shnet.VERSION).append("\",\n")
                .append("  \"port\": ").append(config.port).append(",\n")
                .append("  \"bindHost\": \"").append(bindHost).append("\",\n")
                .append("  \"bindMode\": \"").append(config.bindMode.name()).append("\",\n")
                .append("  \"tls\": ").append(config.tlsConfig != null).append(",\n")
                .append("  \"uptimeSeconds\": ").append(getUptimeSeconds()).append(",\n")
                .append("  \"totalRequests\": ").append(totalRequests.get()).append(",\n")
                .append("  \"links\": [\n");

        List<Shnet.Link> links = Shnet.links(config, Shnet.LinkPreference.ALL);
        for (int i = 0; i < links.size(); i++) {
            Shnet.Link l = links.get(i);
            json.append("    {\"label\": \"").append(l.label).append("\", \"url\": \"")
                    .append(l.url).append("\", \"isIpv6\": ").append(l.isIpv6).append("}");
            if (i < links.size() - 1) json.append(",");
            json.append("\n");
        }
        json.append("  ]\n}");
        return Shnet.Response.json(json.toString());
    }

    private Shnet.Response handleInternalLinks() {
        List<Shnet.Link> links = Shnet.links(config, Shnet.LinkPreference.ALL);
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                .append("<title>shnet node links</title>")
                .append("<style>body{font-family:sans-serif;max-width:600px;margin:40px auto;padding:0 20px;}")
                .append("ul{list-style:none;padding:0;}li{margin:12px 0;padding:12px;background:#f5f5f5;border-radius:6px;}")
                .append("a{color:#0969da;font-weight:bold;text-decoration:none;word-break:break-all;}</style>")
                .append("</head><body><h2>⚡ shnet node links</h2><ul>");
        for (Shnet.Link link : links) {
            html.append("<li><strong>").append(link.label).append("</strong><br><a href=\"")
                    .append(link.url).append("\">").append(link.url).append("</a></li>");
        }
        html.append("</ul></body></html>");
        return Shnet.Response.html(html.toString());
    }

    private Shnet.Response handleInternalQr() {
        List<Shnet.Link> links = Shnet.links(config, Shnet.LinkPreference.IPV6_FIRST);
        String targetUrl = links.isEmpty() ? ("http://127.0.0.1:" + config.port + "/") : links.get(0).url;
        byte[] png = ShnetQr.renderPng(targetUrl, 320);
        String b64 = Base64.getEncoder().encodeToString(png);

        String html = "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>shnet QR</title>"
                + "<style>body{font-family:sans-serif;text-align:center;padding:40px 20px;}img{border-radius:12px;box-shadow:0 4px 12px rgba(0,0,0,0.1);}</style>"
                + "</head><body><h2>⚡ Scan to connect</h2>"
                + "<img src=\"data:image/png;base64," + b64 + "\" width=\"320\" height=\"320\"><p><a href=\"" + targetUrl + "\">" + targetUrl + "</a></p></body></html>";
        return Shnet.Response.html(html);
    }

    private byte[] readBody(InputStream input, int length) throws IOException {
        byte[] data = new byte[length];
        int total = 0;
        while (total < length) {
            int read = input.read(data, total, length - total);
            if (read == -1) {
                break;
            }
            total += read;
        }
        if (total == length) {
            return data;
        }
        return Arrays.copyOf(data, total);
    }

    private String readLine(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int b;
        while ((b = input.read()) != -1) {
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                buffer.write(b);
                if (buffer.size() > limit) {
                    throw new IOException("Line too long");
                }
            }
        }
        if (b == -1 && buffer.size() == 0) {
            return null;
        }
        return buffer.toString(StandardCharsets.US_ASCII.name());
    }

    private void sendResponse(OutputStream output, Shnet.Response response) throws IOException {
        byte[] body = response.body == null ? new byte[0] : response.body;
        String contentType = response.contentType == null ? "text/plain; charset=utf-8" : response.contentType;
        StringBuilder header = new StringBuilder();
        header.append("HTTP/1.1 ").append(response.statusCode).append(" ").append(response.statusMessage).append("\r\n");
        header.append("Server: shnet/").append(Shnet.VERSION).append("\r\n");
        header.append("Content-Type: ").append(contentType).append("\r\n");
        header.append("Content-Length: ").append(body.length).append("\r\n");
        header.append("Cache-Control: no-store\r\n");
        for (Map.Entry<String, String> entry : response.headers.entrySet()) {
            header.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
        }
        header.append("Connection: close\r\n\r\n");
        output.write(header.toString().getBytes(StandardCharsets.UTF_8));
        output.write(body);
        output.flush();
    }

    private void sendFileResponse(OutputStream output, Shnet.Response response) throws IOException {
        File file = response.file;
        if (file == null || !file.exists()) {
            sendResponse(output, Shnet.Response.text(404, "text/plain; charset=utf-8", "Not Found"));
            return;
        }
        String contentType = response.contentType == null ? "application/octet-stream" : response.contentType;
        long length = file.length();
        StringBuilder header = new StringBuilder();
        header.append("HTTP/1.1 ").append(response.statusCode).append(" ").append(response.statusMessage).append("\r\n");
        header.append("Server: shnet/").append(Shnet.VERSION).append("\r\n");
        header.append("Content-Type: ").append(contentType).append("\r\n");
        header.append("Content-Length: ").append(length).append("\r\n");
        if (response.downloadName != null && !response.downloadName.isEmpty()) {
            header.append("Content-Disposition: attachment; filename=\"")
                    .append(response.downloadName)
                    .append("\"\r\n");
        }
        header.append("Cache-Control: no-store\r\n");
        for (Map.Entry<String, String> entry : response.headers.entrySet()) {
            header.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
        }
        header.append("Connection: close\r\n\r\n");
        output.write(header.toString().getBytes(StandardCharsets.UTF_8));

        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = fis.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
        output.flush();
    }

    private void closeQuietly(ServerSocket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private void shutdownWorkers() {
        if (workers != null) {
            workers.shutdownNow();
            workers = null;
        }
    }

    private static final class ShnetThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread t = new Thread(runnable, "ShnetWorker-" + counter.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    }
}
