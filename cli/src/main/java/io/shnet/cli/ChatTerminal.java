package io.shnet.cli;

import io.shnet.Shnet;
import io.shnet.ShnetServer;
import io.shnet.chat.ChatClient;
import io.shnet.chat.ChatHandler;
import io.shnet.chat.ChatMessage;
import io.shnet.chat.ChatStorage;
import io.shnet.chat.IdentityManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class ChatTerminal {
    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_BOLD = "\u001B[1m";
    private static final String ANSI_DIM = "\u001B[2m";

    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private final ChatStorage storage;
    private String localName;
    private String activePeer;
    private String peerName;
    private int localPort;
    private ShnetServer server;
    private ChatHandler chatHandler;
    private volatile boolean running = true;
    private boolean isHostMode = true;

    public ChatTerminal(ChatStorage storage, String localName, String initialPeer, int preferredPort) {
        this.storage = storage != null ? storage : new ChatStorage();
        this.localName = IdentityManager.resolveNickname(localName);
        this.activePeer = initialPeer != null && !initialPeer.trim().isEmpty() ? ChatClient.formatPeerUrl(initialPeer) : null;
        this.localPort = preferredPort > 0 ? preferredPort : Shnet.DEFAULT_PORT;
    }

    public void start() throws Exception {
        boolean hadExplicitPeer = (activePeer != null);

        if (!hadExplicitPeer) {
            isHostMode = true;
        } else {
            isHostMode = false;
        }

        localPort = findAvailablePort(localPort);

        chatHandler = new ChatHandler(storage, localName, new ChatHandler.ChatEventListener() {
            @Override
            public void onMessageReceived(ChatMessage message) {
                ChatTerminal.this.onIncomingMessage(message);
            }

            @Override
            public void onPeerConnected(String name, String peerUrl) {
                ChatTerminal.this.onPeerConnected(name, peerUrl);
            }

            @Override
            public void onPeerDisconnected(String name) {
                ChatTerminal.this.onPeerDisconnected(name);
            }
        });
        chatHandler.setLocalPort(localPort);

        Shnet.Config config = Shnet.Config.builder(localPort)
                .setBindMode(Shnet.BindMode.IPV6_PREFERRED)
                .build();

        server = new ShnetServer(config, chatHandler);
        server.start();

        printWelcomeHeader();

        if (activePeer != null) {
            connectToPeer(activePeer);
            showRecentHistory(activePeer, 10);
        }

        printPrompt();

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        while (running) {
            String line = reader.readLine();
            if (line == null) {
                break;
            }
            line = line.trim();
            if (line.isEmpty()) {
                printPrompt();
                continue;
            }

            if (line.startsWith("/")) {
                handleSlashCommand(line);
            } else {
                handleSendMessage(line);
            }
            printPrompt();
        }

        shutdown();
    }

    private String formatPeerDisplayName(String sender) {
        if (sender == null || sender.trim().isEmpty()) {
            return "peer";
        }
        String s = sender.trim();
        if (s.equalsIgnoreCase(localName)) {
            return s + "#peer";
        }
        return s;
    }

    private void onPeerConnected(String name, String peerUrl) {
        boolean wasNew = (this.activePeer == null || !this.activePeer.equals(peerUrl));
        this.activePeer = peerUrl;
        this.peerName = name;
        if (wasNew) {
            System.out.print("\r\u001B[2K");
            System.out.printf(Locale.US, "[CONNECT] %s <%s>%n", peerUrl, formatPeerDisplayName(name));
            printPrompt();
        }
    }

    private void onPeerDisconnected(String name) {
        System.out.print("\r\u001B[2K");
        System.out.printf(Locale.US, "[DISCONNECT] <%s>%n", formatPeerDisplayName(name));
        if (isHostMode) {
            this.activePeer = null;
            this.peerName = null;
        }
        printPrompt();
    }

    private void connectToPeer(String peerUrl) {
        ChatClient.ConnectResult result = ChatClient.connect(peerUrl, localName, localPort);
        if (result.success) {
            this.activePeer = result.peerUrl;
            this.peerName = result.peerName;
            System.out.printf(Locale.US, "[CONNECT] %s <%s>%n", activePeer, formatPeerDisplayName(peerName));
        } else {
            String info = ChatClient.ping(peerUrl);
            if (info != null) {
                this.activePeer = ChatClient.formatPeerUrl(peerUrl);
                String n = ChatMessage.extractField(info, "name");
                this.peerName = (n != null && !n.isEmpty()) ? n : "peer";
                System.out.printf(Locale.US, "[ONLINE] %s <%s>%n", activePeer, formatPeerDisplayName(peerName));
            } else {
                System.out.printf(Locale.US, "[OFFLINE] %s%n", peerUrl);
            }
        }
    }

    private void onIncomingMessage(ChatMessage message) {
        String timeStr = timeFormat.format(new Date(message.timestamp));
        String displayName = formatPeerDisplayName(message.sender);
        System.out.print("\r\u001B[2K");

        if (message.text != null && message.text.startsWith("/me ")) {
            String action = message.text.substring(4).trim();
            System.out.printf(Locale.US, "[ACTION] %s * %s %s%n", timeStr, displayName, action);
        } else {
            System.out.printf(Locale.US, "[RECV] %s <%s> %s%n", timeStr, displayName, message.text);
        }

        if (activePeer == null && message.peer != null && !message.peer.isEmpty() && !"unknown".equals(message.peer)) {
            activePeer = ChatClient.formatPeerUrl(message.peer);
            peerName = message.sender;
        }

        printPrompt();
    }

    private void handleSendMessage(String text) {
        if (activePeer == null) {
            System.out.println("[ERROR] no active peer (use /peer <url>)");
            return;
        }

        String timeStr = timeFormat.format(new Date());
        ChatMessage out = ChatMessage.createOutbound(localName, activePeer, text, localPort);

        ChatClient.SendResult result = ChatClient.sendMessage(activePeer, out);
        if (result.success) {
            try {
                storage.save(out);
            } catch (Exception ignored) {
            }
            System.out.printf(Locale.US, "[SEND] %s <%s> %s%n", timeStr, localName, text);
        } else {
            System.out.printf(Locale.US, "[ERROR] send failed: %s%n", result.error);
        }
    }

    private void handleMeAction(String action) {
        if (activePeer == null) {
            System.out.println("[ERROR] no active peer (use /peer <url>)");
            return;
        }

        String timeStr = timeFormat.format(new Date());
        String fullText = "/me " + action;
        ChatMessage out = ChatMessage.createOutbound(localName, activePeer, fullText, localPort);

        ChatClient.SendResult result = ChatClient.sendMessage(activePeer, out);
        if (result.success) {
            try {
                storage.save(out);
            } catch (Exception ignored) {
            }
            System.out.printf(Locale.US, "[ACTION] %s * %s %s%n", timeStr, localName, action);
        } else {
            System.out.printf(Locale.US, "[ERROR] send failed: %s%n", result.error);
        }
    }

    private void handleSlashCommand(String input) {
        String[] parts = input.split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1].trim() : "";

        switch (cmd) {
            case "/peer":
            case "/connect":
                if (arg.isEmpty()) {
                    System.out.println("[PEER] " + (activePeer != null ? activePeer : "none"));
                } else {
                    connectToPeer(arg);
                    showRecentHistory(activePeer, 10);
                }
                break;

            case "/nick":
            case "/name":
                if (arg.isEmpty()) {
                    System.out.println("[NICK] " + localName);
                } else {
                    String oldName = localName;
                    localName = arg;
                    IdentityManager.saveNickname(localName);
                    if (chatHandler != null) {
                        chatHandler.setNodeName(localName);
                    }
                    if (activePeer != null) {
                        ChatClient.connect(activePeer, localName, localPort);
                    }
                    System.out.printf(Locale.US, "[NICK] %s -> %s%n", oldName, localName);
                }
                break;

            case "/me":
                if (arg.isEmpty()) {
                    System.out.println("usage: /me <action>");
                } else {
                    handleMeAction(arg);
                }
                break;

            case "/whois":
                printWhois();
                break;

            case "/history":
                int limit = 20;
                if (!arg.isEmpty()) {
                    try {
                        limit = Integer.parseInt(arg);
                    } catch (NumberFormatException ignored) {
                    }
                }
                showRecentHistory(activePeer, limit);
                break;

            case "/peers":
                List<String> peers = storage.getRecentPeers();
                System.out.println("[PEERS]");
                if (peers.isEmpty()) {
                    System.out.println("  (none)");
                } else {
                    for (String p : peers) {
                        String mark = (activePeer != null && activePeer.contains(p)) ? " (active)" : "";
                        System.out.println("  " + p + mark);
                    }
                }
                break;

            case "/info":
            case "/status":
                printInfo();
                break;

            case "/qr":
                String url = io.shnet.ShnetLinks.getPrimaryUrl(localPort);
                System.out.println("[QR] " + url);
                System.out.print(Shnet.qrAnsi(url));
                break;

            case "/clear":
                System.out.print("\u001B[H\u001B[2J");
                System.out.flush();
                break;

            case "/help":
                printHelp();
                break;

            case "/quit":
            case "/exit":
                running = false;
                break;

            default:
                System.out.println("[ERROR] unknown command: " + cmd + " (try /help)");
                break;
        }
    }

    private void showRecentHistory(String peer, int limit) {
        if (peer == null) return;
        List<ChatMessage> history = storage.getHistory(peer, limit);
        if (history.isEmpty()) {
            return;
        }
        System.out.printf(Locale.US, "[HISTORY] %s (%d messages)%n", peer, history.size());
        for (ChatMessage m : history) {
            String timeStr = timeFormat.format(new Date(m.timestamp));
            if (m.direction == ChatMessage.Direction.OUT) {
                if (m.text != null && m.text.startsWith("/me ")) {
                    System.out.printf(Locale.US, "[ACTION] %s * %s %s%n", timeStr, localName, m.text.substring(4).trim());
                } else {
                    System.out.printf(Locale.US, "[SEND] %s <%s> %s%n", timeStr, localName, m.text);
                }
            } else {
                String dName = formatPeerDisplayName(m.sender);
                if (m.text != null && m.text.startsWith("/me ")) {
                    System.out.printf(Locale.US, "[ACTION] %s * %s %s%n", timeStr, dName, m.text.substring(4).trim());
                } else {
                    System.out.printf(Locale.US, "[RECV] %s <%s> %s%n", timeStr, dName, m.text);
                }
            }
        }
    }

    private void printWelcomeHeader() {
        System.out.println("girchat 1.0.0 [dual-stack ipv6]");
        System.out.println("node: " + localName + " | port: " + localPort);
        String primaryUrl = io.shnet.ShnetLinks.getPrimaryUrl(localPort);
        System.out.println("endpoint: " + primaryUrl);
        List<Shnet.Link> links = Shnet.links(localPort);
        for (Shnet.Link l : links) {
            String badge = l.label != null && l.label.contains("Internet IPv6") ? "internet-v6" : (l.isIpv6 ? "local-v6" : "local-v4");
            System.out.println("link [" + badge + "]: " + l.url);
        }
        if (activePeer != null) {
            System.out.println("peer: " + (peerName != null ? (formatPeerDisplayName(peerName) + " @ " + activePeer) : activePeer));
        } else {
            System.out.println("state: listening");
        }
        System.out.println("commands: /nick /me /whois /peer /peers /history /qr /exit");
        System.out.println();
    }

    private void printPrompt() {
        String promptTag;
        if (peerName != null && !peerName.isEmpty()) {
            promptTag = localName + "[" + formatPeerDisplayName(peerName) + "]> ";
        } else if (activePeer != null) {
            promptTag = localName + "[" + ChatStorage.normalizePeer(activePeer) + "]> ";
        } else {
            promptTag = localName + "> ";
        }
        System.out.print(promptTag);
        System.out.flush();
    }

    private void printWhois() {
        System.out.println("[WHOIS]");
        System.out.println("  local:    " + localName + " (port " + localPort + ")");
        System.out.println("  endpoint: " + io.shnet.ShnetLinks.getPrimaryUrl(localPort));
        if (activePeer != null) {
            System.out.println("  peer:     " + (peerName != null ? peerName : "unknown") + " (" + activePeer + ")");
            if (peerName != null && peerName.equalsIgnoreCase(localName)) {
                System.out.println("  alias:    " + peerName + "#peer");
            }
            String pingResult = ChatClient.ping(activePeer);
            System.out.println("  state:    " + (pingResult != null ? "online" : "offline"));
        } else {
            System.out.println("  peer:     none");
        }
    }

    private void printInfo() {
        System.out.println("[STATUS]");
        System.out.println("  node:     " + localName);
        System.out.println("  role:     " + (isHostMode ? "host" : "client/host"));
        System.out.println("  port:     " + localPort);
        System.out.println("  endpoint: " + io.shnet.ShnetLinks.getPrimaryUrl(localPort));
        System.out.println("  peer:     " + (peerName != null ? (formatPeerDisplayName(peerName) + " @ " + activePeer) : (activePeer != null ? activePeer : "none")));
        System.out.println("  links:");
        List<Shnet.Link> links = Shnet.links(localPort);
        for (Shnet.Link l : links) {
            String badge = l.label != null && l.label.contains("Internet IPv6") ? "internet-v6" : (l.isIpv6 ? "local-v6" : "local-v4");
            System.out.println("    [" + badge + "] " + l.url);
        }
    }

    private void printHelp() {
        System.out.println("commands:");
        System.out.println("  /nick <name>     set persistent nickname");
        System.out.println("  /me <action>     send action message");
        System.out.println("  /whois           show peer connection info");
        System.out.println("  /peer <url>      connect to target node");
        System.out.println("  /history [n]     show last [n] messages");
        System.out.println("  /peers           list known peers");
        System.out.println("  /info, /status   show node status & links");
        System.out.println("  /qr              show terminal QR code");
        System.out.println("  /clear           clear screen");
        System.out.println("  /help            show command help");
        System.out.println("  /exit, /quit     disconnect and exit");
    }

    private int findAvailablePort(int startPort) {
        for (int p = startPort; p < startPort + 50; p++) {
            try (ServerSocket ss = new ServerSocket(p)) {
                ss.setReuseAddress(true);
                return p;
            } catch (Exception ignored) {
            }
        }
        return startPort;
    }

    private void shutdown() {
        if (activePeer != null) {
            ChatClient.disconnect(activePeer, localName);
        }
        if (server != null) {
            server.stop();
            server = null;
        }
        System.out.println("[STOP] node closed");
    }
}
