package dev.vintagepc.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Локальный HTTP/1.1 + WebSocket сервер только на 127.0.0.1 (без внешних зависимостей).
 *
 * Зачем вместо file://:
 *  - Chromium запрещает fetch()/WASM-streaming с file:// — js-dos, v86 и Emscripten-порты без HTTP не стартуют;
 *  - заголовки COOP/COEP включают crossOriginIsolated → SharedArrayBuffer → многопоточные WASM-порты (reVC, Xash3D);
 *  - Range-запросы: образы дисков на гигабайты читаются кусками, а не целиком в память;
 *  - движки отдаются прямо из jar мода, без распаковки на диск.
 *
 * Защита: привязка к loopback, проверка заголовка Host (от DNS-rebinding) и секретный токен
 * (в первой ссылке ?t=..., дальше — HttpOnly cookie).
 */
final class LocalHttpServer {
    static final Logger LOG = LoggerFactory.getLogger("vintagepc/http");

    /** Тело ответа. length = -1 — длина неизвестна (поток до закрытия соединения). */
    interface Body {
        long length();
        default boolean rangeable() { return false; }
        void write(OutputStream out, long from, long count) throws IOException;
    }

    record Response(int status, String mime, Body body, Map<String, String> headers) {
        static Response of(String mime, byte[] data) {
            return new Response(200, mime, new Body() {
                public long length() { return data.length; }
                public boolean rangeable() { return true; }
                public void write(OutputStream o, long from, long n) throws IOException { o.write(data, (int) from, (int) n); }
            }, Map.of());
        }
        static Response text(int status, String s) {
            Response r = of("text/plain; charset=utf-8", s.getBytes(StandardCharsets.UTF_8));
            return new Response(status, r.mime, r.body, Map.of());
        }
    }

    interface Router { Response route(String path, Map<String, String> query) throws IOException; }

    interface WsHandler {
        void onOpen(Conn c);
        void onText(Conn c, String text);
        void onBinary(Conn c, byte[] data);
        void onClose(Conn c);
    }

    static final class Conn {
        private final Socket socket;
        private final OutputStream out;
        final Map<String, Object> attrs = new HashMap<>();
        final String path;
        volatile boolean open = true;

        Conn(Socket s, OutputStream out, String path) { socket = s; this.out = out; this.path = path; }

        void sendText(String s) { send(0x1, s.getBytes(StandardCharsets.UTF_8)); }
        void sendBinary(byte[] b) { send(0x2, b); }

        synchronized void send(int opcode, byte[] payload) {
            if (!open) return;
            try {
                out.write(0x80 | opcode);
                int n = payload.length;
                if (n < 126) out.write(n);
                else if (n < 65536) { out.write(126); out.write(n >>> 8); out.write(n); }
                else { out.write(127); for (int i = 7; i >= 0; i--) out.write((int) ((long) n >>> (8 * i))); }
                out.write(payload);
                out.flush();
            } catch (IOException e) { close(); }
        }

        void close() { open = false; try { socket.close(); } catch (IOException ignored) {} }
    }

    private static final int MAX_FRAME = 1 << 20;
    private final ServerSocket server;
    private final String token;
    private final Router router;
    private final WsHandler ws;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "VintagePC-HTTP");
        t.setDaemon(true);
        return t;
    });

    LocalHttpServer(String token, Router router, WsHandler ws) throws IOException {
        this.token = token;
        this.router = router;
        this.ws = ws;
        this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        pool.execute(this::acceptLoop);
    }

    int port() { return server.getLocalPort(); }
    String origin() { return "http://127.0.0.1:" + port(); }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket s = server.accept();
                s.setTcpNoDelay(true);
                pool.execute(() -> serve(s));
            } catch (IOException e) {
                if (!server.isClosed()) LOG.warn("accept failed", e);
            }
        }
    }

    private void serve(Socket s) {
        try (s) {
            s.setSoTimeout(120_000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = new BufferedOutputStream(s.getOutputStream(), 64 * 1024);
            while (true) {
                String request = readLine(in);
                if (request == null || request.isEmpty()) return;
                Map<String, String> h = new HashMap<>();
                for (String line; (line = readLine(in)) != null && !line.isEmpty(); ) {
                    int i = line.indexOf(':');
                    if (i > 0) h.put(line.substring(0, i).trim().toLowerCase(Locale.ROOT), line.substring(i + 1).trim());
                }
                String[] parts = request.split(" ");
                if (parts.length < 3) return;
                String method = parts[0], target = parts[1];
                String rawPath = target.contains("?") ? target.substring(0, target.indexOf('?')) : target;
                Map<String, String> query = parseQuery(target.contains("?") ? target.substring(target.indexOf('?') + 1) : "");
                String path = URLDecoder.decode(rawPath, StandardCharsets.UTF_8);

                if (!("127.0.0.1:" + port()).equals(h.get("host"))) { write(out, Response.text(421, "bad host"), method, h, false); return; }
                boolean viaQuery = token.equals(query.get("t"));
                if (!viaQuery && !token.equals(cookie(h.get("cookie"), "vpc_t"))) {
                    write(out, Response.text(403, "forbidden"), method, h, false);
                    return;
                }
                if ("websocket".equalsIgnoreCase(h.get("upgrade"))) { websocket(s, in, out, h, path); return; }
                if (!method.equals("GET") && !method.equals("HEAD")) { write(out, Response.text(405, "method"), method, h, false); return; }

                Response r;
                try {
                    r = router.route(path, query);
                } catch (Exception e) {
                    LOG.warn("route {} failed", path, e);
                    r = Response.text(500, String.valueOf(e));
                }
                if (r == null) r = Response.text(404, "not found: " + path);
                boolean keep = write(out, r, method, h, viaQuery);
                if (!keep || "close".equalsIgnoreCase(h.get("connection"))) return;
            }
        } catch (IOException ignored) {
        } catch (Exception e) {
            LOG.debug("connection error", e);
        }
    }

    /** @return можно ли держать соединение (keep-alive) */
    private boolean write(OutputStream out, Response r, String method, Map<String, String> h, boolean setCookie) throws IOException {
        long len = r.body().length(), from = 0, count = len;
        int status = r.status();
        StringBuilder sb = new StringBuilder();
        String range = h.get("range");
        if (status == 200 && len >= 0 && r.body().rangeable() && range != null && range.startsWith("bytes=")) {
            String[] se = range.substring(6).split(",")[0].split("-", 2);
            try {
                if (se[0].isEmpty()) { long n = Long.parseLong(se[1]); from = Math.max(0, len - n); }
                else from = Long.parseLong(se[0]);
                long to = se.length > 1 && !se[1].isEmpty() && !se[0].isEmpty() ? Math.min(len - 1, Long.parseLong(se[1])) : len - 1;
                if (from > to || from >= len) {
                    status = 416; count = 0;
                    sb.append("Content-Range: bytes */").append(len).append("\r\n");
                } else {
                    status = 206; count = to - from + 1;
                    sb.append("Content-Range: bytes ").append(from).append('-').append(to).append('/').append(len).append("\r\n");
                }
            } catch (NumberFormatException ignored) {}
        }
        boolean keep = len >= 0;
        String head = "HTTP/1.1 " + status + " " + reason(status) + "\r\n"
                + "Content-Type: " + r.mime() + "\r\n"
                + (keep ? "Content-Length: " + count + "\r\n" : "")
                + "Connection: " + (keep ? "keep-alive" : "close") + "\r\n"
                + (r.body().rangeable() ? "Accept-Ranges: bytes\r\n" : "")
                + "Cache-Control: no-cache\r\n"
                + "Cross-Origin-Opener-Policy: same-origin\r\n"
                + "Cross-Origin-Embedder-Policy: require-corp\r\n"
                + "Cross-Origin-Resource-Policy: same-origin\r\n"
                + "X-Content-Type-Options: nosniff\r\n"
                + (setCookie ? "Set-Cookie: vpc_t=" + token + "; Path=/; HttpOnly; SameSite=Strict\r\n" : "");
        StringBuilder all = new StringBuilder(head).append(sb);
        r.headers().forEach((k, v) -> all.append(k).append(": ").append(v).append("\r\n"));
        all.append("\r\n");
        out.write(all.toString().getBytes(StandardCharsets.UTF_8));
        if (!method.equals("HEAD") && status != 416) r.body().write(out, from, keep ? count : -1);
        out.flush();
        return keep;
    }

    private void websocket(Socket s, InputStream rawIn, OutputStream out, Map<String, String> h, String path) throws Exception {
        String key = h.get("sec-websocket-key");
        if (key == null) return;
        String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
        Conn c = new Conn(s, out, path);
        synchronized (c) {
            out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }
        s.setSoTimeout(0);
        DataInputStream in = new DataInputStream(rawIn);
        ws.onOpen(c);
        try {
            ByteArrayOutputStream message = new ByteArrayOutputStream();
            int messageOp = 0;
            while (c.open) {
                int b0 = in.readUnsignedByte(), b1 = in.readUnsignedByte();
                boolean fin = (b0 & 0x80) != 0;
                int op = b0 & 0x0F;
                long len = b1 & 0x7F;
                if (len == 126) len = in.readUnsignedShort();
                else if (len == 127) len = in.readLong();
                if (len > MAX_FRAME || (b1 & 0x80) == 0) break; // клиент обязан маскировать кадры
                byte[] mask = new byte[4];
                in.readFully(mask);
                byte[] data = new byte[(int) len];
                in.readFully(data);
                for (int i = 0; i < data.length; i++) data[i] ^= mask[i & 3];
                switch (op) {
                    case 0x8 -> { c.send(0x8, new byte[0]); c.open = false; }
                    case 0x9 -> c.send(0xA, data);
                    case 0xA -> {}
                    default -> {
                        if (op != 0) { messageOp = op; message.reset(); }
                        if (message.size() + data.length > MAX_FRAME) { c.open = false; break; }
                        message.write(data);
                        if (fin) {
                            byte[] full = message.toByteArray();
                            message.reset();
                            if (messageOp == 0x1) ws.onText(c, new String(full, StandardCharsets.UTF_8));
                            else if (messageOp == 0x2) ws.onBinary(c, full);
                        }
                    }
                }
            }
        } catch (EOFException ignored) {
        } finally {
            c.close();
            ws.onClose(c);
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int ch;
        while ((ch = in.read()) != -1) {
            if (ch == '\n') return sb.toString();
            if (ch != '\r') sb.append((char) ch);
            if (sb.length() > 16384) throw new IOException("line too long");
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private static Map<String, String> parseQuery(String q) {
        Map<String, String> m = new HashMap<>();
        for (String kv : q.split("&")) {
            if (kv.isEmpty()) continue;
            int i = kv.indexOf('=');
            String k = URLDecoder.decode(i < 0 ? kv : kv.substring(0, i), StandardCharsets.UTF_8);
            m.put(k, i < 0 ? "" : URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private static String cookie(String header, String name) {
        if (header == null) return null;
        for (String part : header.split(";")) {
            String p = part.trim();
            if (p.startsWith(name + "=")) return p.substring(name.length() + 1);
        }
        return null;
    }

    private static String reason(int s) {
        return switch (s) {
            case 200 -> "OK"; case 206 -> "Partial Content"; case 403 -> "Forbidden"; case 404 -> "Not Found";
            case 405 -> "Method Not Allowed"; case 416 -> "Range Not Satisfiable"; case 421 -> "Misdirected Request";
            default -> "Error";
        };
    }

    static String mime(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        int dot = p.lastIndexOf('.');
        String ext = dot < 0 ? "" : p.substring(dot + 1);
        return switch (ext) {
            case "html", "htm" -> "text/html; charset=utf-8";
            case "js", "mjs" -> "text/javascript; charset=utf-8";
            case "css" -> "text/css; charset=utf-8";
            case "json" -> "application/json; charset=utf-8";
            case "wasm" -> "application/wasm";
            case "png" -> "image/png"; case "jpg", "jpeg" -> "image/jpeg"; case "gif" -> "image/gif";
            case "svg" -> "image/svg+xml"; case "ico" -> "image/x-icon"; case "bmp" -> "image/bmp";
            case "woff2" -> "font/woff2"; case "woff" -> "font/woff"; case "ttf" -> "font/ttf";
            case "mp3" -> "audio/mpeg"; case "ogg" -> "audio/ogg"; case "wav" -> "audio/wav"; case "flac" -> "audio/flac";
            case "mp4" -> "video/mp4"; case "webm" -> "video/webm";
            case "txt", "md", "cfg", "conf", "ini" -> "text/plain; charset=utf-8";
            default -> "application/octet-stream";
        };
    }
}
