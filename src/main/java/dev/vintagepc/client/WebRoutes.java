package dev.vintagepc.client;

import java.io.*;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Карта URL локального сервера:
 *   /                    → оболочка (index.html)
 *   /web/**              → оболочка из jar мода
 *   /engines/**          → движки: сначала .minecraft/vintagepc/engines/ (обновления, свои сборки), затем jar
 *   /carts/&lt;id&gt;/**       → файлы картриджа с диска (Range — для больших образов)
 *   /carts/&lt;id&gt;/__bundle.jsdos → автосборка DOS-бандла из папки
 *   /music/**            → mp3/ogg игрока для Winamp
 *   /api/carts, /api/music → списки в JSON
 */
final class WebRoutes implements LocalHttpServer.Router {
    private final Path root;
    private final Cartridges carts;

    WebRoutes(Path root) {
        this.root = root;
        this.carts = new Cartridges(root.resolve("cartridges"), root.resolve("cache"));
    }

    @Override
    public LocalHttpServer.Response route(String path, Map<String, String> q) throws IOException {
        if (path.equals("/") || path.equals("/index.html")) return jar("web/index.html");
        if (path.startsWith("/web/")) return jar("web/" + path.substring(5));
        if (path.startsWith("/engines/")) {
            String rel = path.substring(9);
            Path p = Cartridges.safeResolve(root.resolve("engines"), rel);
            if (p != null && Files.isRegularFile(p)) return file(p);
            return jar("engines/" + rel);
        }
        if (path.equals("/api/carts")) return json(carts.listJson());
        if (path.equals("/api/music")) return json(musicJson());
        if (path.startsWith("/music/")) return disk(root.resolve("music"), path.substring(7));
        if (path.startsWith("/carts/")) {
            String rest = path.substring(7);
            int slash = rest.indexOf('/');
            if (slash < 0) return null;
            String id = rest.substring(0, slash), rel = rest.substring(slash + 1);
            Path dir = carts.dir(id);
            if (dir == null) return null;
            if (rel.equals("__bundle.jsdos")) {
                Path b = carts.jsdosBundle(id);
                return b != null && Files.isRegularFile(b) ? file(b) : null;
            }
            return disk(dir, rel.isEmpty() ? "index.html" : rel);
        }
        return null;
    }

    private static LocalHttpServer.Response json(String s) {
        return LocalHttpServer.Response.of("application/json; charset=utf-8", s.getBytes(StandardCharsets.UTF_8));
    }

    private static LocalHttpServer.Response disk(Path base, String rel) {
        Path p = Cartridges.safeResolve(base, rel);
        return p != null && Files.isRegularFile(p) ? file(p) : null;
    }

    private static LocalHttpServer.Response jar(String rel) throws IOException {
        if (rel.contains("..")) return null;
        try (InputStream in = WebRoutes.class.getResourceAsStream("/assets/vintagepc/" + rel)) {
            if (in == null) return null;
            return LocalHttpServer.Response.of(LocalHttpServer.mime(rel), in.readAllBytes());
        }
    }

    static LocalHttpServer.Response file(Path p) {
        return new LocalHttpServer.Response(200, LocalHttpServer.mime(p.toString()), new LocalHttpServer.Body() {
            public long length() { try { return Files.size(p); } catch (IOException e) { return 0; } }
            public boolean rangeable() { return true; }
            public void write(OutputStream out, long from, long count) throws IOException {
                try (FileChannel ch = FileChannel.open(p, StandardOpenOption.READ)) {
                    ch.transferTo(from, count, Channels.newChannel(out));
                }
            }
        }, Map.of());
    }

    private String musicJson() throws IOException {
        Path dir = root.resolve("music");
        StringBuilder sb = new StringBuilder("[");
        if (Files.isDirectory(dir)) {
            try (Stream<Path> s = Files.walk(dir, 3)) {
                for (Path p : s.filter(Files::isRegularFile).sorted().toList()) {
                    String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (!(n.endsWith(".mp3") || n.endsWith(".ogg") || n.endsWith(".wav") || n.endsWith(".flac"))) continue;
                    String rel = dir.relativize(p).toString().replace('\\', '/');
                    if (sb.length() > 1) sb.append(',');
                    sb.append(new com.google.gson.JsonPrimitive(rel));
                }
            }
        }
        return sb.append(']').toString();
    }
}
