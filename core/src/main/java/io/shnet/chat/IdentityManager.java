package io.shnet.chat;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class IdentityManager {
    private static final File RUNTIME_DIR = new File(System.getProperty("user.home", "/root"), ".shnet");
    private static final File IDENTITY_FILE = new File(RUNTIME_DIR, "identity");
    public static final String DEFAULT_NAME = "rei";

    private IdentityManager() {
    }

    public static File getIdentityFile() {
        return IDENTITY_FILE;
    }

    public static String getSavedNickname() {
        if (!IDENTITY_FILE.exists()) {
            return null;
        }
        try {
            String content = Files.readString(IDENTITY_FILE.toPath(), StandardCharsets.UTF_8).trim();
            if (!content.isEmpty()) {
                return content;
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    public static void saveNickname(String nick) {
        if (nick == null || nick.trim().isEmpty()) {
            return;
        }
        try {
            if (!RUNTIME_DIR.exists()) {
                RUNTIME_DIR.mkdirs();
            }
            Files.writeString(IDENTITY_FILE.toPath(), nick.trim(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    public static String resolveNickname(String cliArg) {
        // 1. Explicit CLI flag: always save and use
        if (cliArg != null && !cliArg.trim().isEmpty()) {
            String trimmed = cliArg.trim();
            saveNickname(trimmed);
            return trimmed;
        }

        // 2. Saved nickname from previous session
        String saved = getSavedNickname();
        if (saved != null && !saved.isEmpty()) {
            return saved;
        }

        // 3. If running interactively in terminal (TTY), ask the user
        if (System.console() != null) {
            try {
                System.out.print("\u001B[38;5;220m✨ Welcome to girchat! Enter your nickname [default: " + DEFAULT_NAME + "]: \u001B[0m");
                System.out.flush();
                BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
                String input = reader.readLine();
                if (input != null && !input.trim().isEmpty()) {
                    String chosen = input.trim();
                    saveNickname(chosen);
                    return chosen;
                }
            } catch (Exception ignored) {
            }
        }

        // 4. Default fallback
        saveNickname(DEFAULT_NAME);
        return DEFAULT_NAME;
    }
}
