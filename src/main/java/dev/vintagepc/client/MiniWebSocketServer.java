package dev.vintagepc.client;

import dev.vintagepc.VintagePC;

import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Минимальный WebSocket-сервер (RFC 6455) только на 127.0.0.1 — без внешних зависимостей.
 * Через него страница в MCEF (и WASM-порты игр с эмуляцией сокетов) общается с модом.
 * Доступ защищён случайным токеном в URL.
 */
final class MiniWebSocketServer {
    interface Handler {
        void onOpen(Conn c);
        void onText(Conn c, String text);
        void onBinary(Conn c, byte[] data);
        void onClose(Conn c);
    }

    static final class Conn {
        private final Socket socket;
        private final OutputStream out;
        final Map<String, Object> attrs = new HashMap<>();
        volatile boolean open = true;

        Conn(Socket s) throws IOException { socket = s; out = new BufferedOutputStream(s.getOutputStream()); }

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

        void close() {
            open = false;
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private static final int MAX_FRAME = 1 << 20;
    private final ServerSocket server;
    private final String token;
    private final Handler handler;

    MiniWebSocketServer(String token, Handler handler) throws IOException {
        this.token = token;
        this.handler = handler;
        this.server = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
        Thread t = new Thread(this::acceptLoop, "VintagePC-WS-accept");
        t.setDaemon(true);
        t.start();
    }

    int port() { return server.getLocalPort(); }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket s = server.accept();
                s.setTcpNoDelay(true);
                Thread t = new Thread(() -> serve(s), "VintagePC-WS-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (!server.isClosed()) VintagePC.LOG.warn("WS accept failed", e);
            }
        }
    }

    private void serve(Socket s) {
        Conn c = null;
        try (s) {
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            String request = readLine(in);
            Map<String, String> headers = new HashMap<>();
            for (String line; !(line = readLine(in)).isEmpty(); ) {
                int i = line.indexOf(':');
                if (i > 0) headers.put(line.substring(0, i).trim().toLowerCase(), line.substring(i + 1).trim());
            }
            String key = headers.get("sec-websocket-key");
            if (key == null || request.indexOf("token=" + token) < 0) {
                s.getOutputStream().write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                return;
            }
            String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
            c = new Conn(s);
            c.attrs.put("path", request.split(" ")[1]);
            synchronized (c) {
                c.out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                        + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                c.out.flush();
            }
            handler.onOpen(c);

            ByteArrayOutputStream message = new ByteArrayOutputStream();
            int messageOp = 0;
            while (c.open) {
                int b0 = in.readUnsignedByte(), b1 = in.readUnsignedByte();
                boolean fin = (b0 & 0x80) != 0;
                int op = b0 & 0x0F;
                long len = b1 & 0x7F;
                if (len == 126) len = in.readUnsignedShort();
                else if (len == 127) len = in.readLong();
                if (len > MAX_FRAME || (b1 & 0x80) == 0) break; // клиент обязан маскировать
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
                            if (messageOp == 0x1) handler.onText(c, new String(full, StandardCharsets.UTF_8));
                            else if (messageOp == 0x2) handler.onBinary(c, full);
                        }
                    }
                }
            }
        } catch (EOFException ignored) {
        } catch (Exception e) {
            VintagePC.LOG.debug("WS connection closed: {}", e.toString());
        } finally {
            if (c != null) { c.close(); handler.onClose(c); }
        }
    }

    private static String readLine(DataInputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int ch; (ch = in.read()) != -1; ) {
            if (ch == '\n') break;
            if (ch != '\r') sb.append((char) ch);
            if (sb.length() > 8192) throw new IOException("header too long");
        }
        return sb.toString();
    }

}
