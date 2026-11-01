package com.ghar.tv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Receives favourites and playlists from Ghar TV Mobile on the home Wi-Fi, only while the
 * "Get from phone" screen is open.
 *
 *   GET  /ghar/hello                      -> {"app":"ghartv","name":...}   (lets the phone find the TV)
 *   POST /ghar/import  X-Ghar-Code: 1234  -> body {"favourites":[...],"playlists":[{"name","urls"}]}
 *
 * Safety: the 4-digit code shown on the TV is required; 5 wrong codes and the receiver closes.
 * Bodies are limited to 512 KB, connections time out, and it stops after 15 minutes.
 * Plain Java (no Android classes), so it can be tested on its own.
 */
final class ShareServer {

    interface Importer {
        /** Called on a server thread with the phone's JSON; returns the JSON reply. */
        String onImport(String json) throws Exception;
    }

    static final long MAX_LIFETIME_MS = 15 * 60 * 1000L;
    static final int[] PORTS = {8790, 8791, 8792, 8793, 0};
    private static final int MAX_BODY = 512 * 1024;
    private static final int MAX_WRONG_CODES = 5;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final String code;
    private final String name;
    private final Importer importer;
    private final Runnable onLockedOut;
    private final ExecutorService workers = Executors.newFixedThreadPool(2);
    private final AtomicInteger wrong = new AtomicInteger();
    private ServerSocket server;
    private volatile boolean running;
    private long startedAt;

    ShareServer(String code, String name, Importer importer, Runnable onLockedOut) {
        this.code = code;
        this.name = name;
        this.importer = importer;
        this.onLockedOut = onLockedOut;
    }

    static String newCode() {
        return String.format(Locale.US, "%04d", new SecureRandom().nextInt(10000));
    }

    /** @return the port, or -1 */
    int start() {
        for (int p : PORTS) {
            try {
                server = new ServerSocket(p, 8);
                break;
            } catch (IOException ignored) {
                // busy: next
            }
        }
        if (server == null) return -1;
        running = true;
        startedAt = System.currentTimeMillis();
        Thread t = new Thread(this::acceptLoop, "ghar-share-server");
        t.setDaemon(true);
        t.start();
        return server.getLocalPort();
    }

    void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
            // closing anyway
        }
        workers.shutdownNow();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = server.accept();
                if (System.currentTimeMillis() - startedAt > MAX_LIFETIME_MS) {
                    s.close();
                    stop();
                    return;
                }
                s.setSoTimeout(10_000);
                workers.execute(() -> handle(s));
            } catch (IOException e) {
                if (!running) return;
            } catch (java.util.concurrent.RejectedExecutionException e) {
                return;
            }
        }
    }

    private void handle(Socket s) {
        try (Socket sock = s) {
            InputStream in = sock.getInputStream();
            String head = readHead(in);
            if (head == null) return;
            String[] lines = head.split("\r\n");
            String[] req = lines[0].split(" ");
            if (req.length < 2) return;
            String method = req[0];
            String path = req[1];
            int length = 0;
            String given = "";
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon < 0) continue;
                String key = lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String value = lines[i].substring(colon + 1).trim();
                if (key.equals("content-length")) {
                    try {
                        length = Integer.parseInt(value);
                    } catch (NumberFormatException ignored) {
                        length = -1;
                    }
                } else if (key.equals("x-ghar-code")) {
                    given = value;
                }
            }
            OutputStream out = sock.getOutputStream();
            if ("GET".equals(method) && path.startsWith("/ghar/hello")) {
                send(out, 200, "{\"app\":\"ghartv\",\"v\":1,\"name\":" + quote(name) + "}");
                return;
            }
            if (!"POST".equals(method) || !path.startsWith("/ghar/import")) {
                send(out, 404, "{\"ok\":false,\"error\":\"not found\"}");
                return;
            }
            if (!code.equals(given)) {
                send(out, 403, "{\"ok\":false,\"error\":\"code\"}");
                if (wrong.incrementAndGet() >= MAX_WRONG_CODES) {
                    stop();
                    if (onLockedOut != null) onLockedOut.run();
                }
                return;
            }
            if (length < 0 || length > MAX_BODY) {
                send(out, 413, "{\"ok\":false,\"error\":\"too big\"}");
                return;
            }
            byte[] body = readBody(in, length);
            String reply;
            try {
                reply = importer.onImport(new String(body, UTF8));
            } catch (Exception e) {
                send(out, 400, "{\"ok\":false,\"error\":\"bad data\"}");
                return;
            }
            send(out, 200, reply);
        } catch (IOException ignored) {
            // phone went away
        }
    }

    /** Headers up to the blank line (max 16 KB). */
    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        int match = 0;
        byte[] end = {'\r', '\n', '\r', '\n'};
        while ((c = in.read()) != -1) {
            b.write(c);
            match = c == end[match] ? match + 1 : (c == '\r' ? 1 : 0);
            if (match == 4) return new String(b.toByteArray(), UTF8);
            if (b.size() > 16 * 1024) return null;
        }
        return null;
    }

    private static byte[] readBody(InputStream in, int length) throws IOException {
        byte[] body = new byte[length];
        int off = 0;
        while (off < length) {
            int n = in.read(body, off, length - off);
            if (n < 0) break;
            off += n;
        }
        if (off == length) return body;
        byte[] cut = new byte[off];
        System.arraycopy(body, 0, cut, 0, off);
        return cut;
    }

    private static void send(OutputStream out, int status, String json) throws IOException {
        byte[] b = json.getBytes(UTF8);
        String reason = status == 200 ? "OK" : status == 403 ? "Forbidden" : status == 404 ? "Not Found"
                : status == 413 ? "Payload Too Large" : "Bad Request";
        String head = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + b.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(UTF8));
        out.write(b);
        out.flush();
    }

    static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            if (ch == '"' || ch == '\\') sb.append('\\').append(ch);
            else if (ch < 0x20) sb.append(' ');
            else sb.append(ch);
        }
        return sb.append('"').toString();
    }

    /** The TV's address on the home network (Wi-Fi or Ethernet), or null. */
    static String localIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
            // no network
        }
        return null;
    }
}
