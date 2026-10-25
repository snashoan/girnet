package io.shnet.cli;

import io.shnet.Shnet;
import io.shnet.ShnetServer;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;

public final class ForegroundRunner {
    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_BOLD = "\u001B[1m";
    private static final String ANSI_CYAN = "\u001B[36m";
    private static final String ANSI_GREEN = "\u001B[32m";
    private static final String ANSI_YELLOW = "\u001B[33m";
    private static final String ANSI_RED = "\u001B[31m";
    private static final String ANSI_GRAY = "\u001B[90m";

    private ForegroundRunner() {
    }

    public static void run(Shnet.Config config, Shnet.Handler handler, boolean showQr, String saveQrPath) throws Exception {
        SimpleDateFormat timeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

        Shnet.Config.Builder builder = Shnet.Config.builder(config.port)
                .setBindMode(config.bindMode)
                .setReadTimeoutMs(config.readTimeoutMs)
                .setMaxBodyBytes(config.maxBodyBytes)
                .setWorkerThreads(config.workerThreads)
                .setStaticDir(config.staticDir)
                .setTlsConfig(config.tlsConfig)
                .setVerboseLogging(config.verboseLogging)
                .setRequestListener((req, res, durationMs) -> {
                    String timeStr = timeFormat.format(new Date());
                    String statusColor = colorForStatus(res.statusCode);
                    String clientIp = req.clientAddress != null ? req.clientAddress.getHostAddress() : "unknown";
                    System.out.printf(Locale.US, "%s[%s]%s %s%3d %s%s %-6s %s %s(%s, %dms)%s%n",
                            ANSI_GRAY, timeStr, ANSI_RESET,
                            statusColor, res.statusCode, res.statusMessage, ANSI_RESET,
                            req.method, req.path + (req.query.isEmpty() ? "" : "?" + req.query),
                            ANSI_GRAY, clientIp, durationMs, ANSI_RESET);
                });

        ShnetServer server = new ShnetServer(builder.build(), handler);
        server.start();

        printBanner(server.getPort(), config.bindMode.name(), config.tlsConfig != null, config.staticDir);

        List<Shnet.Link> links = Shnet.links(server.getPort(), Shnet.LinkPreference.IPV6_FIRST, config.tlsConfig != null);
        System.out.println(ANSI_BOLD + "Discovered Network Links:" + ANSI_RESET);
        for (Shnet.Link link : links) {
            String tag = link.isIpv6 ? ANSI_CYAN + "[IPv6]" + ANSI_RESET : ANSI_GREEN + "[IPv4]" + ANSI_RESET;
            System.out.println("  " + tag + " " + link.label + ": " + ANSI_BOLD + link.url + ANSI_RESET);
        }

        if (showQr && !links.isEmpty()) {
            String primaryUrl = links.get(0).url;
            System.out.println("\n" + ANSI_BOLD + "QR Code for Primary Link (" + primaryUrl + "):" + ANSI_RESET);
            System.out.print(Shnet.qrAnsi(primaryUrl));
            System.out.println(ANSI_GRAY + "Scan with your phone camera or QR reader to connect directly." + ANSI_RESET);
        }

        if (saveQrPath != null && !saveQrPath.isEmpty() && !links.isEmpty()) {
            File qrFile = new File(saveQrPath);
            Shnet.qrPngFile(links.get(0).url, 400, qrFile);
            System.out.println(ANSI_GREEN + "Saved QR image to: " + qrFile.getAbsolutePath() + ANSI_RESET);
        }

        System.out.println("\nReady. Press Ctrl+C to stop.\n");

        CountDownLatch latch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutting down shnet node...");
            server.stop();
            latch.countDown();
            System.out.println("Node stopped.");
        }));

        latch.await();
    }

    private static void printBanner(int port, String bindMode, boolean tls, File staticDir) {
        System.out.println(ANSI_CYAN +
                " ____  _   _ _   _ _____ _____ \n" +
                "/ ___|| | | | \\ | | ____|_   _|\n" +
                "\\___ \\| |_| |  \\| |  _|   | |  \n" +
                " ___) |  _  | |\\  | |___  | |  \n" +
                "|____/|_| |_|_| \\_|_____| |_|  v" + Shnet.VERSION + ANSI_RESET);
        System.out.println("shnet node runtime (CLI)");
        System.out.println("Port:      " + port + (tls ? " (HTTPS/TLS)" : " (HTTP)"));
        System.out.println("Bind:      " + bindMode);
        if (staticDir != null) {
            System.out.println("Serving:   " + staticDir.getAbsolutePath());
        }
        System.out.println("--------------------------------------------------");
    }

    private static String colorForStatus(int statusCode) {
        if (statusCode >= 200 && statusCode < 300) return ANSI_GREEN;
        if (statusCode >= 300 && statusCode < 400) return ANSI_CYAN;
        if (statusCode >= 400 && statusCode < 500) return ANSI_YELLOW;
        return ANSI_RED;
    }
}
