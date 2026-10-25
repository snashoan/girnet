package io.shnet.cli;

import io.shnet.Shnet;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

public class CliTest {

    @Test
    public void testDemoHandler() throws Exception {
        DemoHandler handler = new DemoHandler();

        Shnet.Request rootReq = new Shnet.Request("GET", "/", "", Collections.emptyMap(), new byte[0], null);
        Shnet.Response rootRes = handler.handle(rootReq);
        assertEquals(200, rootRes.statusCode);
        assertEquals("hello from shnet", new String(rootRes.body));

        Shnet.Request notFoundReq = new Shnet.Request("GET", "/other", "", Collections.emptyMap(), new byte[0], null);
        Shnet.Response notFoundRes = handler.handle(notFoundReq);
        assertEquals(404, notFoundRes.statusCode);
    }

    @Test
    public void testCliCommands() {
        PrintStream origOut = System.out;
        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(outContent));

            Main.main(new String[]{"version"});
            String outStr = outContent.toString();
            assertTrue(outStr.contains("shnet version"));

            outContent.reset();
            Main.main(new String[]{"links"});
            String linksStr = outContent.toString();
            assertTrue(linksStr.contains("Discovered links"));

            outContent.reset();
            Main.main(new String[]{"help"});
            String helpStr = outContent.toString();
            assertTrue(helpStr.contains("Usage:"));
            assertTrue(helpStr.contains("Commands:"));

            outContent.reset();
            Main.main(new String[]{"qr", "http://localhost:8723/"});
            String qrStr = outContent.toString();
            assertTrue(qrStr.contains("QR Code for: http://localhost:8723/"));
        } finally {
            System.setOut(origOut);
        }
    }

    @Test
    public void testDaemonState() {
        assertNotNull(DaemonManager.getRuntimeDir());
        assertNotNull(DaemonManager.getLogFile());
    }
}
