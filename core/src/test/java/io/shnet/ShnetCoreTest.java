package io.shnet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ShnetCoreTest {

    @Test
    public void testLinksDiscovery() {
        List<Shnet.Link> links = Shnet.links(8723);
        assertNotNull(links);
        assertFalse(links.isEmpty(), "Should discover at least localhost or LAN link");
        boolean hasLocalOrLan = false;
        for (Shnet.Link link : links) {
            assertNotNull(link.url);
            assertTrue(link.url.contains("8723"));
            if (link.url.contains("127.0.0.1") || link.url.contains("::1") || link.url.contains("localhost")
                    || link.url.startsWith("http://")) {
                hasLocalOrLan = true;
            }
        }
        assertTrue(hasLocalOrLan);
    }

    @Test
    public void testQrGeneration() {
        String ansi = Shnet.qrAnsi("http://localhost:8723/");
        assertNotNull(ansi);
        assertTrue(ansi.contains("█") || ansi.contains("▀") || ansi.contains("▄"));

        byte[] png = Shnet.qrPng("http://localhost:8723/", 200);
        assertNotNull(png);
        assertTrue(png.length > 50);
        // Verify PNG magic bytes: 0x89 0x50 0x4E 0x47
        assertEquals((byte) 0x89, png[0]);
        assertEquals((byte) 0x50, png[1]);
        assertEquals((byte) 0x4E, png[2]);
        assertEquals((byte) 0x47, png[3]);
    }

    @Test
    public void testServerLifecycleAndRequests() throws Exception {
        int testPort = 18723;
        Shnet.Config config = Shnet.Config.builder(testPort)
                .setBindMode(Shnet.BindMode.IPV4_ONLY) // ensure IPv4 loopback works reliably in test
                .build();

        Shnet.Handler handler = req -> {
            if ("/hello".equals(req.path)) {
                return Shnet.Response.text(200, "text/plain", "hello test");
            }
            if ("/json".equals(req.path)) {
                return Shnet.Response.json("{\"status\":\"ok\"}");
            }
            return Shnet.Response.notFound();
        };

        try (ShnetServer server = Shnet.start(config, handler)) {
            assertTrue(server.isRunning());
            assertEquals(testPort, server.getPort());

            // 1. Test /hello
            URL url = new URL("http://127.0.0.1:" + testPort + "/hello");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            assertEquals(200, conn.getResponseCode());
            assertEquals("text/plain", conn.getContentType());
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                assertEquals("hello test", reader.readLine());
            }

            // 2. Test /_shnet/status
            URL statusUrl = new URL("http://127.0.0.1:" + testPort + "/_shnet/status");
            HttpURLConnection statusConn = (HttpURLConnection) statusUrl.openConnection();
            assertEquals(200, statusConn.getResponseCode());
            assertTrue(statusConn.getContentType().contains("application/json"));

            // 3. Test 404
            URL notFoundUrl = new URL("http://127.0.0.1:" + testPort + "/nonexistent");
            HttpURLConnection nfConn = (HttpURLConnection) notFoundUrl.openConnection();
            assertEquals(404, nfConn.getResponseCode());
        }
    }

    @Test
    public void testStaticFileHandler(@TempDir Path tempDir) throws Exception {
        int testPort = 18724;
        Files.writeString(tempDir.resolve("test.txt"), "static file content");
        Files.createDirectory(tempDir.resolve("sub"));
        Files.writeString(tempDir.resolve("sub/index.html"), "<h1>Sub Index</h1>");

        Shnet.Config config = Shnet.Config.builder(testPort)
                .setBindMode(Shnet.BindMode.IPV4_ONLY)
                .build();

        try (ShnetServer server = Shnet.serve(config, tempDir.toFile())) {
            // Read test.txt
            URL fileUrl = new URL("http://127.0.0.1:" + testPort + "/test.txt");
            HttpURLConnection conn = (HttpURLConnection) fileUrl.openConnection();
            assertEquals(200, conn.getResponseCode());
            assertEquals("text/plain; charset=utf-8", conn.getContentType());
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                assertEquals("static file content", reader.readLine());
            }

            // Read sub/index.html via /sub/
            URL subUrl = new URL("http://127.0.0.1:" + testPort + "/sub/");
            HttpURLConnection subConn = (HttpURLConnection) subUrl.openConnection();
            assertEquals(200, subConn.getResponseCode());
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(subConn.getInputStream()))) {
                assertEquals("<h1>Sub Index</h1>", reader.readLine());
            }
        }
    }
}
