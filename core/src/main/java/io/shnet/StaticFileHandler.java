package io.shnet;

import java.io.File;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class StaticFileHandler implements Shnet.Handler {
    private static final Map<String, String> MIME_TYPES = new HashMap<>();

    static {
        MIME_TYPES.put("html", "text/html; charset=utf-8");
        MIME_TYPES.put("htm", "text/html; charset=utf-8");
        MIME_TYPES.put("css", "text/css; charset=utf-8");
        MIME_TYPES.put("js", "application/javascript; charset=utf-8");
        MIME_TYPES.put("mjs", "application/javascript; charset=utf-8");
        MIME_TYPES.put("json", "application/json; charset=utf-8");
        MIME_TYPES.put("xml", "application/xml; charset=utf-8");
        MIME_TYPES.put("txt", "text/plain; charset=utf-8");
        MIME_TYPES.put("md", "text/markdown; charset=utf-8");
        MIME_TYPES.put("png", "image/png");
        MIME_TYPES.put("jpg", "image/jpeg");
        MIME_TYPES.put("jpeg", "image/jpeg");
        MIME_TYPES.put("gif", "image/gif");
        MIME_TYPES.put("svg", "image/svg+xml");
        MIME_TYPES.put("webp", "image/webp");
        MIME_TYPES.put("ico", "image/x-icon");
        MIME_TYPES.put("pdf", "application/pdf");
        MIME_TYPES.put("zip", "application/zip");
        MIME_TYPES.put("tar", "application/x-tar");
        MIME_TYPES.put("gz", "application/gzip");
        MIME_TYPES.put("mp3", "audio/mpeg");
        MIME_TYPES.put("mp4", "video/mp4");
        MIME_TYPES.put("webm", "video/webm");
        MIME_TYPES.put("wasm", "application/wasm");
    }

    private final File rootDir;
    private final Shnet.Handler fallback;

    public StaticFileHandler(File rootDir) {
        this(rootDir, null);
    }

    public StaticFileHandler(File rootDir, Shnet.Handler fallback) {
        this.rootDir = rootDir != null ? rootDir.getAbsoluteFile() : new File(".").getAbsoluteFile();
        this.fallback = fallback;
    }

    @Override
    public Shnet.Response handle(Shnet.Request request) throws Exception {
        if (!"GET".equalsIgnoreCase(request.method) && !"HEAD".equalsIgnoreCase(request.method)) {
            return Shnet.Response.text(405, "text/plain; charset=utf-8", "Method Not Allowed");
        }

        String rawPath = request.path;
        if (rawPath == null || rawPath.isEmpty()) {
            rawPath = "/";
        }

        String decodedPath = URLDecoder.decode(rawPath, StandardCharsets.UTF_8.name());
        // Strip leading slash
        String relativePath = decodedPath.startsWith("/") ? decodedPath.substring(1) : decodedPath;

        Path targetPath = rootDir.toPath().resolve(relativePath).normalize();
        if (!targetPath.startsWith(rootDir.toPath())) {
            return Shnet.Response.text(403, "text/plain; charset=utf-8", "Forbidden");
        }

        File file = targetPath.toFile();
        if (!file.exists()) {
            if (fallback != null) {
                return fallback.handle(request);
            }
            return Shnet.Response.text(404, "text/plain; charset=utf-8", "Not Found");
        }

        if (file.isDirectory()) {
            File indexHtml = new File(file, "index.html");
            if (indexHtml.isFile() && indexHtml.canRead()) {
                return Shnet.Response.file(indexHtml, "text/html; charset=utf-8");
            }
            File indexHtm = new File(file, "index.htm");
            if (indexHtm.isFile() && indexHtm.canRead()) {
                return Shnet.Response.file(indexHtm, "text/html; charset=utf-8");
            }

            return renderDirectoryListing(file, decodedPath);
        }

        if (!file.canRead()) {
            return Shnet.Response.text(403, "text/plain; charset=utf-8", "Forbidden");
        }

        String mimeType = getMimeType(file.getName());
        return Shnet.Response.file(file, mimeType);
    }

    public static String getMimeType(String fileName) {
        if (fileName == null) {
            return "application/octet-stream";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot >= 0 && dot < fileName.length() - 1) {
            String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
            String mime = MIME_TYPES.get(ext);
            if (mime != null) {
                return mime;
            }
        }
        return "application/octet-stream";
    }

    private Shnet.Response renderDirectoryListing(File dir, String webPath) {
        if (!webPath.endsWith("/")) {
            return Shnet.Response.redirect(webPath + "/");
        }

        File[] files = dir.listFiles();
        if (files == null) {
            files = new File[0];
        }
        Arrays.sort(files, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) {
                return a.isDirectory() ? -1 : 1;
            }
            return a.getName().compareToIgnoreCase(b.getName());
        });

        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                .append("<title>Index of ").append(escapeHtml(webPath)).append(" - shnet</title>")
                .append("<style>")
                .append("body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; ")
                .append("  max-width: 900px; margin: 40px auto; padding: 0 20px; color: #222; background: #fafafa; }")
                .append("h1 { font-size: 1.4rem; border-bottom: 2px solid #eaeaea; padding-bottom: 10px; margin-bottom: 20px; }")
                .append(".brand { font-size: 0.85rem; color: #888; margin-bottom: 20px; }")
                .append("table { width: 100%; border-collapse: collapse; background: #fff; border-radius: 8px; box-shadow: 0 1px 3px rgba(0,0,0,0.06); }")
                .append("th, td { text-align: left; padding: 12px 16px; border-bottom: 1px solid #f0f0f0; }")
                .append("th { background: #f8f9fa; font-weight: 600; font-size: 0.85rem; color: #555; }")
                .append("tr:hover { background: #fbfbfb; }")
                .append("a { color: #0969da; text-decoration: none; }")
                .append("a:hover { text-decoration: underline; }")
                .append(".size, .date { color: #666; font-size: 0.85rem; white-space: nowrap; }")
                .append("</style></head><body>")
                .append("<div class=\"brand\">shnet node</div>")
                .append("<h1>Index of ").append(escapeHtml(webPath)).append("</h1>")
                .append("<table><thead><tr><th>Name</th><th>Size</th><th>Last Modified</th></tr></thead><tbody>");

        if (!"/".equals(webPath)) {
            html.append("<tr><td><a href=\"../\">../</a></td><td>-</td><td>-</td></tr>");
        }

        for (File f : files) {
            String name = f.getName();
            if (name.startsWith(".")) {
                continue;
            }
            String icon = f.isDirectory() ? "[dir] " : "";
            String href = f.isDirectory() ? name + "/" : name;
            String sizeStr = f.isDirectory() ? "-" : formatSize(f.length());
            String dateStr = dateFormat.format(new Date(f.lastModified()));

            html.append("<tr>")
                    .append("<td>").append(icon).append("<a href=\"").append(escapeHtml(href)).append("\">")
                    .append(escapeHtml(name)).append("</a></td>")
                    .append("<td class=\"size\">").append(sizeStr).append("</td>")
                    .append("<td class=\"date\">").append(dateStr).append("</td>")
                    .append("</tr>");
        }

        html.append("</tbody></table></body></html>");
        return Shnet.Response.html(html.toString());
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char pre = "KMGTPE".charAt(exp - 1);
        return String.format(Locale.US, "%.1f %cB", bytes / Math.pow(1024, exp), pre);
    }

    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
