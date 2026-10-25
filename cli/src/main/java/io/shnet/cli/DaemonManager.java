package io.shnet.cli;

import io.shnet.Shnet;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public final class DaemonManager {
    private static final File RUNTIME_DIR = new File(System.getProperty("user.home"), ".shnet");
    private static final File PID_FILE = new File(RUNTIME_DIR, "shnet.pid");
    private static final File STATE_FILE = new File(RUNTIME_DIR, "shnet.state");
    private static final File LOG_FILE = new File(RUNTIME_DIR, "shnet.log");

    private DaemonManager() {
    }

    public static File getRuntimeDir() {
        return RUNTIME_DIR;
    }

    public static File getLogFile() {
        return LOG_FILE;
    }

    public static Long getRunningPid() {
        if (!PID_FILE.exists()) {
            return null;
        }
        try {
            String pidStr = Files.readString(PID_FILE.toPath()).trim();
            if (pidStr.isEmpty()) {
                return null;
            }
            long pid = Long.parseLong(pidStr);
            Optional<ProcessHandle> ph = ProcessHandle.of(pid);
            if (ph.isPresent() && ph.get().isAlive()) {
                return pid;
            }
        } catch (Exception ignored) {
        }
        // Cleanup stale PID file
        PID_FILE.delete();
        STATE_FILE.delete();
        return null;
    }

    public static boolean isRunning() {
        return getRunningPid() != null;
    }

    public static void startDaemon(List<String> originalArgs, int port, String bindMode, boolean tls, String serveDir) throws Exception {
        Long existingPid = getRunningPid();
        if (existingPid != null) {
            System.out.println("shnet is already running in background (PID: " + existingPid + ")");
            System.out.println("Run 'shnet status' to inspect or 'shnet stop' to terminate.");
            return;
        }

        RUNTIME_DIR.mkdirs();

        // Find java executable and current jar or classpath
        String javaHome = System.getProperty("java.home");
        String javaBin = javaHome + File.separator + "bin" + File.separator + "java";

        String classPath = System.getProperty("java.class.path");
        List<String> cmd = new ArrayList<>();
        File setsidBin = new File("/usr/bin/setsid");
        if (setsidBin.exists()) {
            cmd.add(setsidBin.getAbsolutePath());
        }
        cmd.add(javaBin);
        cmd.add("-cp");
        cmd.add(classPath);
        cmd.add("io.shnet.cli.Main");
        cmd.add("run"); // Background runs "run" in detached mode

        // Forward options
        for (int i = 0; i < originalArgs.size(); i++) {
            String arg = originalArgs.get(i);
            if ("start".equals(arg)) continue;
            cmd.add(arg);
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        File devNull = new File("/dev/null");
        if (devNull.exists()) {
            pb.redirectInput(ProcessBuilder.Redirect.from(devNull));
        }
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(LOG_FILE));
        pb.redirectError(ProcessBuilder.Redirect.appendTo(LOG_FILE));

        Process process = pb.start();
        long pid = process.pid();

        // Brief check to make sure it started
        Thread.sleep(800);
        if (!process.isAlive()) {
            System.err.println("Failed to start shnet daemon. Process exited immediately.");
            System.err.println("Check logs at: " + LOG_FILE.getAbsolutePath());
            if (LOG_FILE.exists()) {
                printRecentLogs(10);
            }
            return;
        }

        Files.writeString(PID_FILE.toPath(), String.valueOf(pid));
        String state = String.format("port=%d\nbindMode=%s\ntls=%b\nserveDir=%s\nstartedAt=%d\n",
                port, bindMode, tls, serveDir != null ? serveDir : "", System.currentTimeMillis());
        Files.writeString(STATE_FILE.toPath(), state);

        System.out.println("[START] shnet node started in background (PID: " + pid + ")");
        System.out.println("port: " + port + (tls ? " (https)" : " (http)"));
        System.out.println("bind: " + bindMode);
        if (serveDir != null && !serveDir.isEmpty()) {
            System.out.println("serving: " + serveDir);
        }
        System.out.println("log: " + LOG_FILE.getAbsolutePath());

        List<Shnet.Link> links = Shnet.links(port, Shnet.LinkPreference.IPV6_FIRST, tls);
        System.out.println("links:");
        for (Shnet.Link link : links) {
            String badge = link.isIpv6 ? "ipv6" : "ipv4";
            System.out.println("  [" + badge + "] " + link.url);
        }
        System.out.println();
        System.out.println("Commands:");
        System.out.println("  shnet status  - view status & recent logs");
        System.out.println("  shnet stop    - stop the background node");
    }

    public static void stopDaemon() {
        Long pid = getRunningPid();
        if (pid == null) {
            System.out.println("shnet is not running.");
            return;
        }

        Optional<ProcessHandle> ph = ProcessHandle.of(pid);
        if (ph.isPresent()) {
            ProcessHandle handle = ph.get();
            handle.destroy();
            try {
                for (int i = 0; i < 20; i++) {
                    if (!handle.isAlive()) {
                        break;
                    }
                    Thread.sleep(150);
                }
                if (handle.isAlive()) {
                    handle.destroyForcibly();
                }
            } catch (InterruptedException ignored) {
            }
        }

        PID_FILE.delete();
        STATE_FILE.delete();
        System.out.println("shnet node (PID: " + pid + ") stopped.");
    }

    public static void printStatus() {
        Long pid = getRunningPid();
        if (pid == null) {
            System.out.println("shnet status: STOPPED (not running)");
            return;
        }

        System.out.println("[STATUS] running");
        System.out.println("PID: " + pid);

        int port = Shnet.DEFAULT_PORT;
        boolean tls = false;
        long startedAt = 0;
        String serveDir = "";

        if (STATE_FILE.exists()) {
            try {
                List<String> lines = Files.readAllLines(STATE_FILE.toPath());
                for (String line : lines) {
                    if (line.startsWith("port=")) port = Integer.parseInt(line.substring(5).trim());
                    else if (line.startsWith("tls=")) tls = Boolean.parseBoolean(line.substring(4).trim());
                    else if (line.startsWith("startedAt=")) startedAt = Long.parseLong(line.substring(10).trim());
                    else if (line.startsWith("serveDir=")) serveDir = line.substring(9).trim();
                }
            } catch (Exception ignored) {
            }
        }

        System.out.println("Port: " + port + (tls ? " (HTTPS)" : " (HTTP)"));
        if (!serveDir.isEmpty()) {
            System.out.println("Serving: " + serveDir);
        }
        if (startedAt > 0) {
            long uptime = (System.currentTimeMillis() - startedAt) / 1000;
            System.out.println("Uptime: " + formatUptime(uptime));
        }

        List<Shnet.Link> links = Shnet.links(port, Shnet.LinkPreference.IPV6_FIRST, tls);
        System.out.println("\nLinks:");
        for (Shnet.Link link : links) {
            String badge = link.isIpv6 ? "ipv6" : "ipv4";
            System.out.println("  [" + badge + "] " + link.url);
        }

        System.out.println("\nRecent Logs (" + LOG_FILE.getAbsolutePath() + "):");
        printRecentLogs(8);
        System.out.println("==================================================");
    }

    private static String formatUptime(long seconds) {
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        if (h > 0) {
            return String.format("%dh %dm %ds", h, m, s);
        }
        if (m > 0) {
            return String.format("%dm %ds", m, s);
        }
        return s + "s";
    }

    public static void printRecentLogs(int lines) {
        if (!LOG_FILE.exists()) {
            System.out.println("  (no log file found)");
            return;
        }
        try {
            List<String> allLines = Files.readAllLines(LOG_FILE.toPath());
            int start = Math.max(0, allLines.size() - lines);
            for (int i = start; i < allLines.size(); i++) {
                System.out.println("  " + allLines.get(i));
            }
        } catch (Exception e) {
            System.out.println("  Error reading logs: " + e.getMessage());
        }
    }
}
