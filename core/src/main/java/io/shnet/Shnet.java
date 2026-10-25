package io.shnet;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * shnet - Tiny, self-contained node runtime for device-owned apps.
 */
public final class Shnet {
    public static final String VERSION = "1.0.0";
    public static final int DEFAULT_PORT = 8723;

    private Shnet() {
    }

    public enum BindMode {
        IPV6_PREFERRED,
        DUAL,
        IPV6_ONLY,
        IPV4_ONLY
    }

    public enum LinkPreference {
        IPV6_FIRST,
        IPV4_FIRST,
        ALL
    }

    @FunctionalInterface
    public interface Handler {
        Response handle(Request request) throws Exception;
    }

    @FunctionalInterface
    public interface HandlerFactory {
        Handler create();
    }

    @FunctionalInterface
    public interface RequestListener {
        void onRequest(Request request, Response response, long durationMs);
    }

    public static final class Config {
        public final int port;
        public final BindMode bindMode;
        public final int readTimeoutMs;
        public final int maxBodyBytes;
        public final int workerThreads;
        public final TlsConfig tlsConfig;
        public final File staticDir;
        public final boolean verboseLogging;
        public final RequestListener requestListener;

        private Config(Builder builder) {
            this.port = builder.port;
            this.bindMode = builder.bindMode;
            this.readTimeoutMs = builder.readTimeoutMs;
            this.maxBodyBytes = builder.maxBodyBytes;
            this.workerThreads = builder.workerThreads;
            this.tlsConfig = builder.tlsConfig;
            this.staticDir = builder.staticDir;
            this.verboseLogging = builder.verboseLogging;
            this.requestListener = builder.requestListener;
        }

        public static Builder builder() {
            return new Builder(DEFAULT_PORT);
        }

        public static Builder builder(int port) {
            return new Builder(port);
        }

        public static Config basic(int port) {
            return builder(port).build();
        }

        public static final class Builder {
            private static final int DEFAULT_READ_TIMEOUT_MS = 4000;
            private static final int DEFAULT_MAX_BODY_BYTES = 256 * 1024;
            private static final int DEFAULT_WORKER_THREADS = 4;

            private int port;
            private BindMode bindMode = BindMode.IPV6_PREFERRED;
            private int readTimeoutMs = DEFAULT_READ_TIMEOUT_MS;
            private int maxBodyBytes = DEFAULT_MAX_BODY_BYTES;
            private int workerThreads = DEFAULT_WORKER_THREADS;
            private TlsConfig tlsConfig;
            private File staticDir;
            private boolean verboseLogging = false;
            private RequestListener requestListener;

            private Builder(int port) {
                this.port = port <= 0 ? DEFAULT_PORT : port;
            }

            public Builder setPort(int port) {
                if (port > 0 && port <= 65535) {
                    this.port = port;
                }
                return this;
            }

            public Builder setBindMode(BindMode mode) {
                if (mode != null) {
                    this.bindMode = mode;
                }
                return this;
            }

            public Builder setReadTimeoutMs(int readTimeoutMs) {
                if (readTimeoutMs > 0) {
                    this.readTimeoutMs = readTimeoutMs;
                }
                return this;
            }

            public Builder setMaxBodyBytes(int maxBodyBytes) {
                if (maxBodyBytes > 0) {
                    this.maxBodyBytes = maxBodyBytes;
                }
                return this;
            }

            public Builder setWorkerThreads(int workerThreads) {
                if (workerThreads > 0) {
                    this.workerThreads = workerThreads;
                }
                return this;
            }

            public Builder setTlsConfig(TlsConfig tlsConfig) {
                this.tlsConfig = tlsConfig;
                return this;
            }

            public Builder setStaticDir(File staticDir) {
                this.staticDir = staticDir;
                return this;
            }

            public Builder setVerboseLogging(boolean verbose) {
                this.verboseLogging = verbose;
                return this;
            }

            public Builder setRequestListener(RequestListener listener) {
                this.requestListener = listener;
                return this;
            }

            public Builder clearTls() {
                this.tlsConfig = null;
                return this;
            }

            public Config build() {
                return new Config(this);
            }
        }
    }

    public static final class TlsConfig {
        public enum Source {
            KEYSTORE_FILE,
            PEM_FILES
        }

        public final Source source;
        public final String keyStorePath;
        public final String keyStoreType;
        public final String keyStorePassword;
        public final String keyPassword;
        public final String certPath;
        public final String privateKeyPath;

        private TlsConfig(Source source,
                          String keyStorePath,
                          String keyStoreType,
                          String keyStorePassword,
                          String keyPassword,
                          String certPath,
                          String privateKeyPath) {
            this.source = source;
            this.keyStorePath = keyStorePath;
            this.keyStoreType = normalizeType(keyStoreType);
            this.keyStorePassword = keyStorePassword;
            this.keyPassword = keyPassword;
            this.certPath = certPath;
            this.privateKeyPath = privateKeyPath;
        }

        public static TlsConfig fromFile(String path, String keyStorePassword) {
            return fromFile(path, keyStorePassword, null, null);
        }

        public static TlsConfig fromFile(String path,
                                         String keyStorePassword,
                                         String keyPassword,
                                         String keyStoreType) {
            if (path == null || path.trim().isEmpty()) {
                throw new IllegalArgumentException("Invalid keystore path");
            }
            return new TlsConfig(Source.KEYSTORE_FILE, path, keyStoreType, keyStorePassword, keyPassword, null, null);
        }

        public static TlsConfig fromPemFiles(String certPath, String privateKeyPath) {
            if (certPath == null || privateKeyPath == null) {
                throw new IllegalArgumentException("Cert and key path must not be null");
            }
            return new TlsConfig(Source.PEM_FILES, null, null, null, null, certPath, privateKeyPath);
        }

        private static String normalizeType(String keyStoreType) {
            if (keyStoreType == null || keyStoreType.isEmpty()) {
                return KeyStore.getDefaultType();
            }
            return keyStoreType;
        }
    }

    public static final class Request {
        public final String method;
        public final String path;
        public final String query;
        public final Map<String, String> headers;
        public final byte[] body;
        public final InetAddress clientAddress;

        public Request(String method, String path, String query, Map<String, String> headers, byte[] body, InetAddress clientAddress) {
            this.method = method;
            this.path = path;
            this.query = query != null ? query : "";
            this.headers = headers != null ? headers : Collections.emptyMap();
            this.body = body != null ? body : new byte[0];
            this.clientAddress = clientAddress;
        }

        public String bodyAsString() {
            return new String(body, StandardCharsets.UTF_8);
        }

        public String header(String name) {
            if (name == null) {
                return null;
            }
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        public String queryParam(String name) {
            if (query.isEmpty() || name == null) {
                return null;
            }
            for (String pair : query.split("&")) {
                int idx = pair.indexOf('=');
                if (idx > 0 && pair.substring(0, idx).equals(name)) {
                    return pair.substring(idx + 1);
                }
            }
            return null;
        }
    }

    public static final class Response {
        public final int statusCode;
        public final String statusMessage;
        public final String contentType;
        public final byte[] body;
        public final File file;
        public final String downloadName;
        public final Map<String, String> headers;

        private Response(int statusCode,
                         String statusMessage,
                         String contentType,
                         byte[] body,
                         File file,
                         String downloadName,
                         Map<String, String> headers) {
            this.statusCode = statusCode;
            this.statusMessage = statusMessage;
            this.contentType = contentType;
            this.body = body;
            this.file = file;
            this.downloadName = downloadName;
            this.headers = headers == null ? Collections.emptyMap() : headers;
        }

        public static Response text(int statusCode, String contentType, String text) {
            String safeType = contentType == null ? "text/plain; charset=utf-8" : contentType;
            byte[] payload = text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8);
            return new Response(statusCode, statusMessageFor(statusCode), safeType, payload, null, null, null);
        }

        public static Response text(String text) {
            return text(200, "text/plain; charset=utf-8", text);
        }

        public static Response html(String html) {
            return text(200, "text/html; charset=utf-8", html);
        }

        public static Response json(String json) {
            return text(200, "application/json; charset=utf-8", json);
        }

        public static Response bytes(int statusCode, String contentType, byte[] body) {
            String safeType = contentType == null ? "application/octet-stream" : contentType;
            return new Response(statusCode, statusMessageFor(statusCode), safeType, body, null, null, null);
        }

        public static Response file(File file, String contentType, String downloadName) {
            return new Response(200, "OK", contentType, null, file, downloadName, null);
        }

        public static Response file(File file, String contentType) {
            return file(file, contentType, null);
        }

        public static Response notFound() {
            return text(404, "text/plain; charset=utf-8", "Not Found");
        }

        public static Response badRequest(String message) {
            return text(400, "text/plain; charset=utf-8", message != null ? message : "Bad Request");
        }

        public static Response redirect(String location) {
            return new Response(302, "Found", "text/plain; charset=utf-8", new byte[0], null, null,
                    Collections.singletonMap("Location", location));
        }

        public Response withHeader(String key, String value) {
            Map<String, String> merged = new HashMap<>(headers);
            merged.put(key, value);
            return new Response(statusCode, statusMessage, contentType, body, file, downloadName, merged);
        }

        static String statusMessageFor(int code) {
            switch (code) {
                case 200: return "OK";
                case 204: return "No Content";
                case 301: return "Moved Permanently";
                case 302: return "Found";
                case 304: return "Not Modified";
                case 400: return "Bad Request";
                case 401: return "Unauthorized";
                case 403: return "Forbidden";
                case 404: return "Not Found";
                case 405: return "Method Not Allowed";
                case 413: return "Payload Too Large";
                case 500: return "Internal Server Error";
                default: return "Response";
            }
        }
    }

    public static final class Link {
        public final String label;
        public final String url;
        public final boolean isIpv6;
        public final String interfaceName;
        public final InetAddress address;

        public Link(String label, String url, boolean isIpv6) {
            this(label, url, isIpv6, "", null);
        }

        public Link(String label, String url, boolean isIpv6, String interfaceName, InetAddress address) {
            this.label = label;
            this.url = url;
            this.isIpv6 = isIpv6;
            this.interfaceName = interfaceName != null ? interfaceName : "";
            this.address = address;
        }

        @Override
        public String toString() {
            return label + " -> " + url;
        }
    }

    // Server lifecycle
    public static ShnetServer start(Config config, Handler handler) throws IOException {
        ShnetServer server = new ShnetServer(config, handler);
        server.start();
        return server;
    }

    public static ShnetServer serve(Config config, File rootDir) throws IOException {
        Handler handler = new StaticFileHandler(rootDir, null);
        ShnetServer server = new ShnetServer(config, handler);
        server.start();
        return server;
    }

    // Link discovery
    public static List<Link> links(int port) {
        return links(port, LinkPreference.IPV6_FIRST, false);
    }

    public static List<Link> links(int port, LinkPreference preference) {
        return links(port, preference, false);
    }

    public static List<Link> links(int port, LinkPreference preference, boolean tls) {
        return ShnetLinks.discover(port, tls, preference);
    }

    public static List<Link> links(Config config, LinkPreference preference) {
        if (config == null) {
            return Collections.emptyList();
        }
        return ShnetLinks.discover(config.port, config.tlsConfig != null, preference);
    }

    // QR generation
    public static String qrAnsi(String text) {
        return ShnetQr.renderAnsi(text);
    }

    public static byte[] qrPng(String text, int size) {
        return ShnetQr.renderPng(text, size);
    }

    public static void qrPngFile(String text, int size, File outputFile) throws IOException {
        ShnetQr.writePng(text, size, outputFile);
    }
}
