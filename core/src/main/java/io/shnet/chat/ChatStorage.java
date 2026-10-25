package io.shnet.chat;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ChatStorage {
    private static final File DEFAULT_CHAT_DIR = new File(System.getProperty("user.home"), ".shnet" + File.separator + "chats");
    private static final String HISTORY_FILENAME = "history.jsonl";

    private final File chatDir;
    private final File historyFile;
    private final Object lock = new Object();

    public ChatStorage() {
        this(DEFAULT_CHAT_DIR);
    }

    public ChatStorage(File chatDir) {
        this.chatDir = chatDir != null ? chatDir : DEFAULT_CHAT_DIR;
        this.historyFile = new File(this.chatDir, HISTORY_FILENAME);
        ensureDir();
    }

    private void ensureDir() {
        if (!chatDir.exists()) {
            chatDir.mkdirs();
        }
    }

    public File getHistoryFile() {
        return historyFile;
    }

    public void save(ChatMessage message) throws IOException {
        if (message == null) return;
        synchronized (lock) {
            ensureDir();
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(historyFile, StandardCharsets.UTF_8, true))) {
                writer.write(message.toJson());
                writer.newLine();
            }
        }
    }

    public List<ChatMessage> getHistory(String peer, int limit) {
        int max = limit > 0 ? limit : 50;
        List<ChatMessage> matches = new ArrayList<>();
        if (!historyFile.exists()) {
            return matches;
        }

        String normalizedPeer = peer != null ? normalizePeer(peer) : null;

        synchronized (lock) {
            try (BufferedReader reader = new BufferedReader(new FileReader(historyFile, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    ChatMessage msg = ChatMessage.fromJson(line);
                    if (msg == null) continue;
                    if (normalizedPeer == null || normalizedPeer.isEmpty() || normalizedPeer.equalsIgnoreCase(normalizePeer(msg.peer))) {
                        matches.add(msg);
                    }
                }
            } catch (IOException ignored) {
            }
        }

        if (matches.size() > max) {
            return matches.subList(matches.size() - max, matches.size());
        }
        return matches;
    }

    public List<ChatMessage> getAllHistory(int limit) {
        return getHistory(null, limit);
    }

    public List<String> getRecentPeers() {
        Set<String> peers = new LinkedHashSet<>();
        if (!historyFile.exists()) {
            return Collections.emptyList();
        }

        synchronized (lock) {
            try (BufferedReader reader = new BufferedReader(new FileReader(historyFile, StandardCharsets.UTF_8))) {
                String line;
                List<String> list = new ArrayList<>();
                while ((line = reader.readLine()) != null) {
                    ChatMessage msg = ChatMessage.fromJson(line);
                    if (msg != null && msg.peer != null && !msg.peer.isEmpty()) {
                        list.add(msg.peer);
                    }
                }
                // Reverse to get most recent first
                Collections.reverse(list);
                peers.addAll(list);
            } catch (IOException ignored) {
            }
        }
        return new ArrayList<>(peers);
    }

    public void clearHistory(String peer) throws IOException {
        if (!historyFile.exists()) return;
        synchronized (lock) {
            if (peer == null || peer.trim().isEmpty()) {
                historyFile.delete();
                return;
            }
            String norm = normalizePeer(peer);
            List<ChatMessage> keep = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new FileReader(historyFile, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    ChatMessage msg = ChatMessage.fromJson(line);
                    if (msg != null && !norm.equalsIgnoreCase(normalizePeer(msg.peer))) {
                        keep.add(msg);
                    }
                }
            }
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(historyFile, StandardCharsets.UTF_8, false))) {
                for (ChatMessage m : keep) {
                    writer.write(m.toJson());
                    writer.newLine();
                }
            }
        }
    }

    public static String normalizePeer(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.startsWith("http://")) s = s.substring(7);
        else if (s.startsWith("https://")) s = s.substring(8);
        if (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
