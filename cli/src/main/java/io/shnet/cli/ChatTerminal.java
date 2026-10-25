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
    // 256-color ANSI definitions
    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_BOLD = "\u001B[1m";
    private static final String ANSI_DIM = "\u001B[2m";
    private static final String ANSI_ITALIC = "\u001B[3m";

    private static final String CLR_CYAN = "\u001B[38;5;45m";
    private static final String CLR_BLUE = "\u001B[38;5;75m";
    private static final String CLR_GREEN = "\u001B[38;5;48m";
    private static final String CLR_GOLD = "\u001B[38;5;220m";
    private static final String CLR_RED = "\u001B[38;5;203m";
    private static final String CLR_PURPLE = "\u001B[38;5;141m";
    private static final String CLR_ORANGE = "\u001B[38;5;208m";
    private static final String CLR_GRAY = "\u001B[38;5;244m";
    private static final String CLR_WHITE = "\u001B[38;5;255m";

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

        // If no peer specified, look for an active shnet/girchat chat host
        if (!hadExplicitPeer) {
            String existingHost = ChatClient.ping("http://127.0.0.1:" + localPort);
            if (existingHost != null) {
                activePeer = "http://127.0.0.1:" + localPort + "/";
                isHostMode = false;
            } else {
                String lanHost = ChatClient.discoverHost(localPort);
                if (lanHost != null) {
                    activePeer = lanHost;
                    isHostMode = false;
                }
            }
        } else {
            isHostMode = false;
        }

        // Port resolution: if client on localhost, use next available port
        if (activePeer != null && (activePeer.contains("127.0.0.1:" + localPort) || activePeer.contains("localhost:" + localPort))) {
            localPort = findAvailablePort(localPort + 1);
        } else {
            localPort = findAvailablePort(localPort);
        }

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
        } else {
            System.out.println(CLR_GOLD + "⏳ Waiting for peer to connect on port " + localPort + "..." + ANSI_RESET);
            System.out.println(CLR_GRAY + "   (Run 'girchat' in another terminal or device to mesh connect)\n" + ANSI_RESET);
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
            System.out.printf(Locale.US, "%s🟢 Peer connected: <%s> (%s)%s%n",
                    CLR_GREEN + ANSI_BOLD, formatPeerDisplayName(name), peerUrl, ANSI_RESET);
            printPrompt();
        }
    }

    private void onPeerDisconnected(String name) {
        System.out.print("\r\u001B[2K");
        System.out.printf(Locale.US, "%s🔴 <%s> disconnected.%s%n",
                CLR_RED, formatPeerDisplayName(name), ANSI_RESET);
        if (isHostMode) {
            this.activePeer = null;
            this.peerName = null;
        }
        printPrompt();
    }

    private void connectToPeer(String peerUrl) {
        System.out.print(CLR_GRAY + "📡 Connecting to " + peerUrl + "... " + ANSI_RESET);
        ChatClient.ConnectResult result = ChatClient.connect(peerUrl, localName, localPort);
        if (result.success) {
            this.activePeer = result.peerUrl;
            this.peerName = result.peerName;
            System.out.println(CLR_GREEN + "🟢 Connected to <" + formatPeerDisplayName(peerName) + ">" + ANSI_RESET);
        } else {
            String info = ChatClient.ping(peerUrl);
            if (info != null) {
                this.activePeer = ChatClient.formatPeerUrl(peerUrl);
                String n = ChatMessage.extractField(info, "name");
                this.peerName = (n != null && !n.isEmpty()) ? n : "peer";
                System.out.println(CLR_GREEN + "🟢 Online (<" + formatPeerDisplayName(peerName) + ">)" + ANSI_RESET);
            } else {
                System.out.println(CLR_GOLD + "⏳ Peer not yet reachable, messages will attempt delivery when sent." + ANSI_RESET);
            }
        }
    }

    private void onIncomingMessage(ChatMessage message) {
        String timeStr = timeFormat.format(new Date(message.timestamp));
        String displayName = formatPeerDisplayName(message.sender);
        System.out.print("\r\u001B[2K");

        if (message.text != null && message.text.startsWith("/me ")) {
            String action = message.text.substring(4).trim();
            System.out.printf(Locale.US, "%s[%s]%s %s✨ * %s %s%s%n",
                    CLR_GRAY, timeStr, ANSI_RESET,
                    CLR_PURPLE, displayName, action, ANSI_RESET);
        } else {
            System.out.printf(Locale.US, "%s[%s]%s %s💬 <%s>%s %s%n",
                    CLR_GRAY, timeStr, ANSI_RESET,
                    CLR_GREEN + ANSI_BOLD, displayName, ANSI_RESET,
                    message.text);
        }

        if (activePeer == null && message.peer != null && !message.peer.isEmpty() && !"unknown".equals(message.peer)) {
            activePeer = ChatClient.formatPeerUrl(message.peer);
            peerName = message.sender;
            System.out.println(CLR_GRAY + "   (Active peer auto-set to " + activePeer + ")" + ANSI_RESET);
        }

        printPrompt();
    }

    private void handleSendMessage(String text) {
        if (activePeer == null) {
            System.out.println(CLR_RED + "🔴 No peer connected yet. Waiting for a peer, or use /peer <host:port> to connect." + ANSI_RESET);
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
            System.out.printf(Locale.US, "%s[%s]%s %sYou%s: %s%n",
                    CLR_GRAY, timeStr, ANSI_RESET,
                    CLR_CYAN + ANSI_BOLD, ANSI_RESET,
                    text);
        } else {
            System.out.println(CLR_RED + "🔴 Failed to deliver to " + activePeer + ": " + result.error + ANSI_RESET);
        }
    }

    private void handleMeAction(String action) {
        if (activePeer == null) {
            System.out.println(CLR_RED + "🔴 No peer connected yet. Waiting for a peer, or use /peer <host:port> to connect." + ANSI_RESET);
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
            System.out.printf(Locale.US, "%s[%s]%s %s✨ * %s %s%s%n",
                    CLR_GRAY, timeStr, ANSI_RESET,
                    CLR_PURPLE, localName, action, ANSI_RESET);
        } else {
            System.out.println(CLR_RED + "🔴 Failed to deliver to " + activePeer + ": " + result.error + ANSI_RESET);
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
                    System.out.println(CLR_CYAN + "Current active peer: " + ANSI_RESET + (activePeer != null ? activePeer : "(none)"));
                } else {
                    connectToPeer(arg);
                    showRecentHistory(activePeer, 10);
                }
                break;

            case "/nick":
            case "/name":
                if (arg.isEmpty()) {
                    System.out.println(CLR_CYAN + "Current nickname: " + ANSI_BOLD + localName + ANSI_RESET);
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
                    System.out.printf(Locale.US, "%s✨ Nickname changed from <%s> to <%s>%s%n",
                            CLR_GOLD, oldName, localName, ANSI_RESET);
                }
                break;

            case "/me":
                if (arg.isEmpty()) {
                    System.out.println(CLR_GOLD + "Usage: /me <action description>" + ANSI_RESET);
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
                System.out.println(CLR_CYAN + ANSI_BOLD + "Recent Mesh Peers:" + ANSI_RESET);
                if (peers.isEmpty()) {
                    System.out.println("  (no message history found)");
                } else {
                    for (String p : peers) {
                        String mark = (activePeer != null && activePeer.contains(p)) ? CLR_GREEN + " (active)" + ANSI_RESET : "";
                        System.out.println("  • " + p + mark);
                    }
                }
                break;

            case "/info":
            case "/status":
                printInfo();
                break;

            case "/qr":
                List<Shnet.Link> links = Shnet.links(localPort);
                String url = links.isEmpty() ? ("http://127.0.0.1:" + localPort + "/") : links.get(0).url;
                System.out.println(CLR_CYAN + ANSI_BOLD + "QR Code for this Node (" + url + "):" + ANSI_RESET);
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
                System.out.println(CLR_RED + "Unknown command: " + cmd + ". Type /help for available commands." + ANSI_RESET);
                break;
        }
    }

    private void showRecentHistory(String peer, int limit) {
        if (peer == null) return;
        List<ChatMessage> history = storage.getHistory(peer, limit);
        if (history.isEmpty()) {
            return;
        }
        System.out.println(CLR_GRAY + "─── Recent History (" + history.size() + " messages) ───────────────────" + ANSI_RESET);
        for (ChatMessage m : history) {
            String timeStr = timeFormat.format(new Date(m.timestamp));
            if (m.direction == ChatMessage.Direction.OUT) {
                if (m.text != null && m.text.startsWith("/me ")) {
                    System.out.printf(Locale.US, "%s[%s]%s %s✨ * %s %s%s%n",
                            CLR_GRAY, timeStr, ANSI_RESET,
                            CLR_PURPLE, "You (" + localName + ")", m.text.substring(4).trim(), ANSI_RESET);
                } else {
                    System.out.printf(Locale.US, "%s[%s]%s %sYou%s: %s%n",
                            CLR_GRAY, timeStr, ANSI_RESET,
                            CLR_CYAN, ANSI_RESET,
                            m.text);
                }
            } else {
                String dName = formatPeerDisplayName(m.sender);
                if (m.text != null && m.text.startsWith("/me ")) {
                    System.out.printf(Locale.US, "%s[%s]%s %s✨ * %s %s%s%n",
                            CLR_GRAY, timeStr, ANSI_RESET,
                            CLR_PURPLE, dName, m.text.substring(4).trim(), ANSI_RESET);
                } else {
                    System.out.printf(Locale.US, "%s[%s]%s %s<%s>%s %s%n",
                            CLR_GRAY, timeStr, ANSI_RESET,
                            CLR_GREEN, dName, ANSI_RESET,
                            m.text);
                }
            }
        }
        System.out.println(CLR_GRAY + "────────────────────────────────────────────────────────" + ANSI_RESET);
    }

    private void printWelcomeHeader() {
        System.out.println(CLR_CYAN +
                "   ____ ___ ____   ____ _   _    _  _____ \n" +
                "  / ___|_ _|  _ \\ / ___| | | |  / \\|_   _|\n" +
                " | |  _ | || |_) | |   | |_| | / _ \\ | |  \n" +
                " | |_| || ||  _ <| |___|  _  |/ ___ \\| |  \n" +
                "  \\____|___|_| \\_\\\\____|_| |_/_/   \\_\\_|  v" + Shnet.VERSION + ANSI_RESET);
        System.out.println(CLR_BLUE + "  🌐 Dual-Stack IPv6 Peer Mesh Communicator" + ANSI_RESET);
        System.out.println();
        System.out.println("👤 Identity:    " + CLR_GOLD + ANSI_BOLD + localName + ANSI_RESET);
        System.out.println("🏷️  Role:        " + (isHostMode ? (CLR_GOLD + "Host (listening on port " + localPort + ")" + ANSI_RESET) : (CLR_GREEN + "Client & Host (listening on port " + localPort + ")" + ANSI_RESET)));
        System.out.println("📡 Local Port:  " + localPort + " (Dual-Stack IPv6/IPv4)");

        List<Shnet.Link> links = Shnet.links(localPort);
        System.out.println("🌐 Your Links:  " + (links.isEmpty() ? ("http://127.0.0.1:" + localPort + "/") : links.get(0).url));
        for (int i = 1; i < links.size(); i++) {
            System.out.println("                " + links.get(i).url);
        }

        if (activePeer != null) {
            System.out.println("🟢 Active Peer: " + ANSI_BOLD + (peerName != null ? ("<" + formatPeerDisplayName(peerName) + "> (" + activePeer + ")") : activePeer) + ANSI_RESET);
        }
        System.out.println("💾 History:     " + storage.getHistoryFile().getAbsolutePath());
        System.out.println(CLR_GRAY + "────────────────────────────────────────────────────────────────" + ANSI_RESET);
        System.out.println("Type your message and press " + ANSI_BOLD + "Enter" + ANSI_RESET + " to send.");
        System.out.println("Commands: " + CLR_CYAN + "/nick <name>" + ANSI_RESET + ", " +
                CLR_CYAN + "/me <action>" + ANSI_RESET + ", " +
                CLR_CYAN + "/whois" + ANSI_RESET + ", " +
                CLR_CYAN + "/peer <url>" + ANSI_RESET + ", " +
                CLR_CYAN + "/peers" + ANSI_RESET + ", " +
                CLR_CYAN + "/history" + ANSI_RESET + ", " +
                CLR_CYAN + "/qr" + ANSI_RESET + ", " +
                CLR_CYAN + "/exit" + ANSI_RESET);
        System.out.println(CLR_GRAY + "────────────────────────────────────────────────────────────────\n" + ANSI_RESET);
    }

    private void printPrompt() {
        String peerTag;
        if (peerName != null && !peerName.isEmpty()) {
            peerTag = formatPeerDisplayName(peerName);
        } else if (activePeer != null) {
            peerTag = ChatStorage.normalizePeer(activePeer);
        } else {
            peerTag = "waiting...";
        }
        String statusIcon = (activePeer != null) ? "🟢" : "⏳";
        System.out.print(CLR_GRAY + "[" + statusIcon + " " + CLR_GOLD + localName + CLR_GRAY + " ❯ " + CLR_GREEN + peerTag + CLR_GRAY + "] " + ANSI_RESET + CLR_CYAN + "❯ " + ANSI_RESET);
        System.out.flush();
    }

    private void printWhois() {
        System.out.println(CLR_CYAN + ANSI_BOLD + "WHOIS Information:" + ANSI_RESET);
        System.out.println("  Local Nick:   " + CLR_GOLD + localName + ANSI_RESET);
        System.out.println("  Local Port:   " + localPort);
        if (activePeer != null) {
            System.out.println("  Peer URL:     " + activePeer);
            System.out.println("  Peer Nick:    " + CLR_GREEN + (peerName != null ? peerName : "(unknown)") + ANSI_RESET);
            if (peerName != null && peerName.equalsIgnoreCase(localName)) {
                System.out.println("  Disambiguated: " + CLR_PURPLE + "<" + peerName + "#peer>" + ANSI_RESET + " (shares your nickname)");
            }
            String pingResult = ChatClient.ping(activePeer);
            System.out.println("  Peer Status:  " + (pingResult != null ? (CLR_GREEN + "Online" + ANSI_RESET) : (CLR_RED + "Unreachable" + ANSI_RESET)));
        } else {
            System.out.println("  Peer:         " + CLR_GRAY + "(no peer connected)" + ANSI_RESET);
        }
    }

    private void printInfo() {
        System.out.println(CLR_CYAN + ANSI_BOLD + "GIRCHAT Node Info:" + ANSI_RESET);
        System.out.println("  Identity: " + CLR_GOLD + localName + ANSI_RESET);
        System.out.println("  Role:     " + (isHostMode ? "Host" : "Client & Host"));
        System.out.println("  Port:     " + localPort);
        System.out.println("  Peer:     " + (peerName != null ? ("<" + formatPeerDisplayName(peerName) + "> (" + activePeer + ")") : (activePeer != null ? activePeer : "(waiting for peer)")));
        System.out.println("  Storage:  " + storage.getHistoryFile().getAbsolutePath());
        List<Shnet.Link> links = Shnet.links(localPort);
        System.out.println("  Mesh Network Links (IPv6 Prioritized):");
        for (Shnet.Link l : links) {
            String badge = l.isIpv6 ? (CLR_CYAN + "[IPv6]" + ANSI_RESET) : (CLR_BLUE + "[IPv4]" + ANSI_RESET);
            System.out.println("    • " + badge + " " + l.label + ": " + l.url);
        }
    }

    private void printHelp() {
        System.out.println(CLR_CYAN + ANSI_BOLD + "GIRCHAT / IRChat Commands:" + ANSI_RESET);
        System.out.println("  /nick <name>     Change your persistent nickname (saved to ~/.shnet/identity)");
        System.out.println("  /me <action>     Send IRC action (e.g. /me waves to peer)");
        System.out.println("  /whois           Display identity and connection details for active peer");
        System.out.println("  /peer <url>      Connect or change target peer (e.g. /peer [2409:...]:8723)");
        System.out.println("  /history [n]     Show the last [n] messages with the current peer");
        System.out.println("  /peers           List recent mesh peers from chat history");
        System.out.println("  /info, /status   Show your listening addresses, port, and IPv6/IPv4 links");
        System.out.println("  /qr              Display terminal ANSI QR code of your node");
        System.out.println("  /clear           Clear the terminal screen");
        System.out.println("  /help            Show this command reference");
        System.out.println("  /exit, /quit     Exit chat and stop local listener");
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
            System.out.println(CLR_GRAY + "\n💨 Stopping local chat node..." + ANSI_RESET);
            server.stop();
            server = null;
        }
        System.out.println(CLR_GREEN + "👋 Goodbye." + ANSI_RESET);
    }
}
