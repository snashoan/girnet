package io.shnet.cli;

import io.shnet.Shnet;
import io.shnet.StaticFileHandler;
import io.shnet.chat.ChatClient;
import io.shnet.chat.ChatMessage;
import io.shnet.chat.ChatStorage;
import io.shnet.chat.IdentityManager;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class Main {

    public static void main(String[] args) {
        if (args == null || args.length == 0) {
            printHelp();
            return;
        }

        String command = args[0].toLowerCase();
        List<String> restArgs = new ArrayList<>(Arrays.asList(args).subList(1, args.length));

        try {
            switch (command) {
                case "run":
                    handleRun(restArgs);
                    break;
                case "start":
                    handleStart(restArgs);
                    break;
                case "stop":
                    DaemonManager.stopDaemon();
                    break;
                case "status":
                    DaemonManager.printStatus();
                    break;
                case "restart":
                    DaemonManager.stopDaemon();
                    Thread.sleep(500);
                    handleStart(restArgs);
                    break;
                case "chat":
                case "talk":
                    handleChat(restArgs);
                    break;
                case "send":
                case "msg":
                    handleSend(restArgs);
                    break;
                case "history":
                    handleHistory(restArgs);
                    break;
                case "serve":
                    handleServe(restArgs);
                    break;
                case "demo":
                    handleDemo(restArgs);
                    break;
                case "links":
                    handleLinks(restArgs);
                    break;
                case "qr":
                    handleQr(restArgs);
                    break;
                case "version":
                case "--version":
                case "-v":
                    printVersion();
                    break;
                case "help":
                case "--help":
                case "-h":
                    printHelp();
                    break;
                default:
                    if (command.startsWith("-")) {
                        printHelp();
                    } else {
                        System.err.println("Unknown command: " + command);
                        System.err.println("Run 'shnet --help' for usage instructions.");
                        System.exit(1);
                    }
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            if (Boolean.getBoolean("shnet.debug")) {
                e.printStackTrace();
            }
            System.exit(1);
        }
    }

    private static void handleRun(List<String> args) throws Exception {
        CliOptions opts = parseOptions(args);
        Shnet.Config config = opts.toConfig();
        ChatStorage storage = new ChatStorage();
        Shnet.Handler baseHandler = opts.dir != null
                ? new StaticFileHandler(opts.dir)
                : new DemoHandler();
        String nodeName = IdentityManager.resolveNickname(null);
        Shnet.Handler handler = new io.shnet.chat.ChatHandler(storage, nodeName, (msg) -> {
            System.out.printf(Locale.US, "\n💬 [Message from <%s>]: %s\n", msg.sender, msg.text);
        }, baseHandler);
        ForegroundRunner.run(config, handler, opts.showQr, opts.saveQrPath);
    }

    private static void handleStart(List<String> args) throws Exception {
        CliOptions opts = parseOptions(args);
        if (opts.foreground) {
            handleRun(args);
            return;
        }
        DaemonManager.startDaemon(args, opts.port, opts.bindMode.name(), opts.isTls(), opts.dir != null ? opts.dir.getAbsolutePath() : null);
    }

    private static void handleChat(List<String> args) throws Exception {
        String peer = null;
        int port = Shnet.DEFAULT_PORT;
        String explicitName = null;

        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (("-p".equals(arg) || "--port".equals(arg)) && i + 1 < args.size()) {
                port = Integer.parseInt(args.get(++i));
            } else if (("--name".equals(arg) || "-n".equals(arg)) && i + 1 < args.size()) {
                explicitName = args.get(++i);
            } else if (!arg.startsWith("-") && peer == null) {
                peer = arg;
            }
        }

        String name = IdentityManager.resolveNickname(explicitName);
        ChatStorage storage = new ChatStorage();
        ChatTerminal terminal = new ChatTerminal(storage, name, peer, port);
        terminal.start();
    }

    private static void handleSend(List<String> args) throws Exception {
        String explicitName = null;

        List<String> positional = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (("--name".equals(arg) || "-n".equals(arg)) && i + 1 < args.size()) {
                explicitName = args.get(++i);
            } else if (!arg.startsWith("-")) {
                positional.add(arg);
            }
        }

        String name = IdentityManager.resolveNickname(explicitName);

        if (positional.size() < 2) {
            System.err.println("Usage: shnet send <peer-host:port> <message> [--name <name>]");
            System.err.println("Example: shnet send 192.168.1.50:8723 \"Hello from cli\"");
            System.exit(1);
        }

        String peer = positional.get(0);
        String message = positional.get(1);

        String peerUrl = ChatClient.formatPeerUrl(peer);
        ChatMessage msg = ChatMessage.createOutbound(name, peerUrl, message);

        ChatClient.SendResult result = ChatClient.sendMessage(peerUrl, msg);
        if (result.success) {
            ChatStorage storage = new ChatStorage();
            storage.save(msg);
            System.out.println("✓ Sent to " + peerUrl + " (id: " + msg.id + ")");
        } else {
            System.err.println("✗ Delivery failed to " + peerUrl + ": " + result.error);
            System.exit(1);
        }
    }

    private static void handleHistory(List<String> args) throws Exception {
        String peer = null;
        int limit = 50;
        boolean clear = false;

        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (("-n".equals(arg) || "--limit".equals(arg)) && i + 1 < args.size()) {
                limit = Integer.parseInt(args.get(++i));
            } else if ("--clear".equals(arg)) {
                clear = true;
            } else if (!arg.startsWith("-") && peer == null) {
                peer = arg;
            }
        }

        ChatStorage storage = new ChatStorage();
        if (clear) {
            storage.clearHistory(peer);
            System.out.println("Cleared chat history" + (peer != null ? " for " + peer : "") + ".");
            return;
        }

        List<ChatMessage> history = storage.getHistory(peer, limit);
        if (history.isEmpty()) {
            System.out.println("No chat messages recorded" + (peer != null ? " for " + peer : "") + ".");
            return;
        }

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        System.out.println("Stored Chat History (" + history.size() + " messages):");
        for (ChatMessage m : history) {
            String ts = sdf.format(new Date(m.timestamp));
            String dir = m.direction == ChatMessage.Direction.OUT ? "OUT -> " + m.peer : "IN  <- " + m.sender;
            System.out.printf("[%s] [%-22s] %s%n", ts, dir, m.text);
        }
    }

    private static void handleServe(List<String> args) throws Exception {
        List<String> remaining = new ArrayList<>();
        File targetDir = null;
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (!arg.startsWith("-") && targetDir == null) {
                targetDir = new File(arg);
            } else {
                remaining.add(arg);
            }
        }
        if (targetDir == null) {
            targetDir = new File(".");
        }
        remaining.add("-d");
        remaining.add(targetDir.getAbsolutePath());
        handleRun(remaining);
    }

    private static void handleDemo(List<String> args) throws Exception {
        handleRun(args);
    }

    private static void handleLinks(List<String> args) {
        int port = Shnet.DEFAULT_PORT;
        Shnet.LinkPreference pref = Shnet.LinkPreference.ALL;
        boolean tls = false;

        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (("-p".equals(arg) || "--port".equals(arg)) && i + 1 < args.size()) {
                port = Integer.parseInt(args.get(++i));
            } else if ("--tls".equals(arg)) {
                tls = true;
            } else if (("--pref".equals(arg) || "--preference".equals(arg)) && i + 1 < args.size()) {
                String p = args.get(++i).toLowerCase();
                if ("ipv6".equals(p)) pref = Shnet.LinkPreference.IPV6_FIRST;
                else if ("ipv4".equals(p)) pref = Shnet.LinkPreference.IPV4_FIRST;
            }
        }

        List<Shnet.Link> links = Shnet.links(port, pref, tls);
        System.out.println("Discovered links on port " + port + ":");
        for (Shnet.Link link : links) {
            String tag = link.isIpv6 ? "[IPv6]" : "[IPv4]";
            System.out.printf("  %-7s %-20s %s%n", tag, link.label + ":", link.url);
        }
    }

    private static void handleQr(List<String> args) throws Exception {
        String text = null;
        String savePath = null;

        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (("--save".equals(arg) || "-o".equals(arg)) && i + 1 < args.size()) {
                savePath = args.get(++i);
            } else if (!arg.startsWith("-") && text == null) {
                text = arg;
            }
        }

        if (text == null || text.trim().isEmpty()) {
            List<Shnet.Link> links = Shnet.links(Shnet.DEFAULT_PORT);
            text = links.isEmpty() ? "http://127.0.0.1:" + Shnet.DEFAULT_PORT + "/" : links.get(0).url;
        }

        System.out.println("QR Code for: " + text);
        System.out.print(Shnet.qrAnsi(text));

        if (savePath != null) {
            File outFile = new File(savePath);
            Shnet.qrPngFile(text, 400, outFile);
            System.out.println("Saved QR code to: " + outFile.getAbsolutePath());
        }
    }

    private static void printVersion() {
        System.out.println("shnet version " + Shnet.VERSION + " (Java " + System.getProperty("java.version") + ")");
        System.out.println("Self-contained node runtime for device-owned apps.");
    }

    private static void printHelp() {
        System.out.println("shnet - Self-contained node runtime for device-owned apps (CLI)");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  shnet <command> [options]");
        System.out.println();
        System.out.println("Commands:");
        System.out.println("  chat [peer]               Interactive CLI-to-CLI peer messaging with local persistence");
        System.out.println("  send <peer> <message>     Send a one-shot message to peer host");
        System.out.println("  history [peer]            Display locally stored chat history (--clear to wipe)");
        System.out.println("  run                       Run the node in foreground with live request logs & links");
        System.out.println("  start                     Run the node in background (daemon mode)");
        System.out.println("  stop                      Stop the background node daemon");
        System.out.println("  status                    Display current node status and recent logs");
        System.out.println("  restart                   Restart the background node");
        System.out.println("  serve [dir]               Serve static files from a directory (default: current directory)");
        System.out.println("  demo                      Run the demo node server");
        System.out.println("  links                     Discover and print all active LAN links (IPv6 + IPv4)");
        System.out.println("  qr [url]                  Print an ANSI QR code in the terminal (+ optional --save)");
        System.out.println("  version                   Show version information");
        System.out.println("  help                      Show this help message");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -p, --port <port>         Port to listen on (default: " + Shnet.DEFAULT_PORT + ")");
        System.out.println("  -n, --name <name>         Your username/identity in chat (default: rei)");
        System.out.println("  -b, --bind <mode>         Bind mode: ipv6-preferred (default), dual, ipv6-only, ipv4-only");
        System.out.println("  -d, --dir <path>          Directory to serve static files from");
        System.out.println("  -w, --workers <count>     Worker thread pool size (default: 4)");
        System.out.println("  -t, --timeout <ms>        Socket read timeout in milliseconds (default: 4000)");
        System.out.println("  -m, --max-body <bytes>    Maximum request body bytes (default: 262144 / 256KB)");
        System.out.println("  -q, --qr                  Display terminal QR code upon startup");
        System.out.println("  --save-qr <path>          Save startup QR code as a PNG image to <path>");
        System.out.println("  -f, --foreground          Run in foreground (when using 'start')");
        System.out.println("  -v, --verbose             Verbose request logging");
        System.out.println();
        System.out.println("TLS/HTTPS Options:");
        System.out.println("  --tls-keystore <path>     Path to PKCS12 / JKS keystore file");
        System.out.println("  --tls-pass <password>     Keystore password");
        System.out.println("  --tls-key-pass <pass>     Private key password (if different)");
        System.out.println("  --tls-cert <cert.pem>     Path to X.509 certificate PEM file");
        System.out.println("  --tls-key <key.pem>       Path to PKCS#8 private key PEM file");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  shnet chat");
        System.out.println("  shnet chat 192.168.1.50:8723 --name rei");
        System.out.println("  shnet send 192.168.1.50:8723 \"Deploy complete\"");
        System.out.println("  shnet history");
        System.out.println("  shnet run");
        System.out.println("  shnet serve ./public -p 8080 --qr");
        System.out.println("  shnet start -p 8723");
        System.out.println("  shnet links");
    }

    private static CliOptions parseOptions(List<String> args) {
        CliOptions opts = new CliOptions();
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (("-p".equals(arg) || "--port".equals(arg)) && i + 1 < args.size()) {
                opts.port = Integer.parseInt(args.get(++i));
            } else if (("-b".equals(arg) || "--bind".equals(arg)) && i + 1 < args.size()) {
                String val = args.get(++i).toLowerCase().replace('-', '_');
                if ("dual".equals(val)) opts.bindMode = Shnet.BindMode.DUAL;
                else if ("ipv6_only".equals(val)) opts.bindMode = Shnet.BindMode.IPV6_ONLY;
                else if ("ipv4_only".equals(val)) opts.bindMode = Shnet.BindMode.IPV4_ONLY;
                else opts.bindMode = Shnet.BindMode.IPV6_PREFERRED;
            } else if (("-d".equals(arg) || "--dir".equals(arg)) && i + 1 < args.size()) {
                opts.dir = new File(args.get(++i));
            } else if (("-w".equals(arg) || "--workers".equals(arg)) && i + 1 < args.size()) {
                opts.workers = Integer.parseInt(args.get(++i));
            } else if (("-t".equals(arg) || "--timeout".equals(arg)) && i + 1 < args.size()) {
                opts.timeoutMs = Integer.parseInt(args.get(++i));
            } else if (("-m".equals(arg) || "--max-body".equals(arg)) && i + 1 < args.size()) {
                opts.maxBodyBytes = Integer.parseInt(args.get(++i));
            } else if ("--tls-keystore".equals(arg) && i + 1 < args.size()) {
                opts.keystorePath = args.get(++i);
            } else if ("--tls-pass".equals(arg) && i + 1 < args.size()) {
                opts.keystorePass = args.get(++i);
            } else if ("--tls-key-pass".equals(arg) && i + 1 < args.size()) {
                opts.keyPass = args.get(++i);
            } else if ("--tls-cert".equals(arg) && i + 1 < args.size()) {
                opts.certPath = args.get(++i);
            } else if ("--tls-key".equals(arg) && i + 1 < args.size()) {
                opts.privateKeyPath = args.get(++i);
            } else if ("-q".equals(arg) || "--qr".equals(arg)) {
                opts.showQr = true;
            } else if ("--save-qr".equals(arg) && i + 1 < args.size()) {
                opts.saveQrPath = args.get(++i);
            } else if ("-f".equals(arg) || "--foreground".equals(arg)) {
                opts.foreground = true;
            } else if ("-v".equals(arg) || "--verbose".equals(arg)) {
                opts.verbose = true;
            }
        }
        return opts;
    }

    private static class CliOptions {
        int port = Shnet.DEFAULT_PORT;
        Shnet.BindMode bindMode = Shnet.BindMode.IPV6_PREFERRED;
        File dir = null;
        int workers = 4;
        int timeoutMs = 4000;
        int maxBodyBytes = 256 * 1024;
        String keystorePath;
        String keystorePass;
        String keyPass;
        String certPath;
        String privateKeyPath;
        boolean showQr = true;
        String saveQrPath;
        boolean foreground = false;
        boolean verbose = false;

        boolean isTls() {
            return keystorePath != null || (certPath != null && privateKeyPath != null);
        }

        Shnet.Config toConfig() {
            Shnet.Config.Builder b = Shnet.Config.builder(port)
                    .setBindMode(bindMode)
                    .setWorkerThreads(workers)
                    .setReadTimeoutMs(timeoutMs)
                    .setMaxBodyBytes(maxBodyBytes)
                    .setStaticDir(dir)
                    .setVerboseLogging(verbose);

            if (certPath != null && privateKeyPath != null) {
                b.setTlsConfig(Shnet.TlsConfig.fromPemFiles(certPath, privateKeyPath));
            } else if (keystorePath != null) {
                b.setTlsConfig(Shnet.TlsConfig.fromFile(keystorePath, keystorePass, keyPass, null));
            }

            return b.build();
        }
    }
}
