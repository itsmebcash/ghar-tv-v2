package com.ghar.player.shared;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A very small HTTP/1.1 server for the home network (used by the TV for the remote-control API and by
 * the phone to serve songs). One request per connection, bodies up to {@link #MAX_BODY}.
 * Plain Java: no Android classes, so it can be tested on a computer.
 */
public final class MiniHttp {

    public static final Charset UTF8 = Charset.forName("UTF-8");
    public static final int MAX_BODY = 4 * 1024 * 1024;

    /** A request, as read. */
    public static final class Request {
        public String method = "";
        public String path = "";
        public final Map<String, String> query = new HashMap<>();
        public final Map<String, String> headers = new HashMap<>(); // lower-case names
        public byte[] body = new byte[0];
        public String remote = "";

        public String header(String name) {
            String v = headers.get(name.toLowerCase(Locale.ROOT));
            return v == null ? "" : v;
        }

        public String bodyText() {
            return new String(body, UTF8);
        }
    }

    /** What to send back. */
    public interface Response {
        void write(OutputStream out) throws IOException;
    }

    public interface Handler {
        Response handle(Request r) throws Exception;
    }

    private final Handler handler;
    private final ExecutorService workers = Executors.newFixedThreadPool(6);
    private ServerSocket server;
    private volatile boolean running;

    public MiniHttp(Handler handler) {
        this.handler = handler;
    }

    /** @return the port it listens on, or -1 */
    public int start(int[] ports) {
        for (int p : ports) {
            try {
                server = new ServerSocket(p, 16);
                break;
            } catch (IOException ignored) {
                // busy: try the next
            }
        }
        if (server == null) return -1;
        running = true;
        Thread t = new Thread(this::acceptLoop, "ghar-http");
        t.setDaemon(true);
        t.start();
        return server.getLocalPort();
    }

    public int port() {
        return server == null ? -1 : server.getLocalPort();
    }

    public boolean isRunning() {
        return running;
    }

    public void stop() {
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
                s.setSoTimeout(20_000);
                workers.execute(() -> serve(s));
            } catch (IOException e) {
                if (!running) return;
            } catch (java.util.concurrent.RejectedExecutionException e) {
                return;
            }
        }
    }

    private void serve(Socket s) {
        try (Socket sock = s) {
            InputStream in = sock.getInputStream();
            Request r = read(in);
            if (r == null) return;
            r.remote = sock.getInetAddress() == null ? "" : sock.getInetAddress().getHostAddress();
            OutputStream out = sock.getOutputStream();
            Response resp;
            try {
                resp = handler.handle(r);
            } catch (Exception e) {
                resp = json(500, "{\"ok\":false,\"error\":\"server\"}");
            }
            if (resp == null) resp = json(404, "{\"ok\":false,\"error\":\"not found\"}");
            resp.write(out);
            out.flush();
        } catch (IOException ignored) {
            // the other side went away
        }
    }

    private static Request read(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        int match = 0;
        byte[] end = {'\r', '\n', '\r', '\n'};
        while (true) {
            c = in.read();
            if (c == -1) return null;
            b.write(c);
            match = c == end[match] ? match + 1 : (c == '\r' ? 1 : 0);
            if (match == 4) break;
            if (b.size() > 32 * 1024) return null;
        }
        String[] lines = new String(b.toByteArray(), UTF8).split("\r\n");
        String[] first = lines[0].split(" ");
        if (first.length < 2) return null;
        Request r = new Request();
        r.method = first[0].toUpperCase(Locale.ROOT);
        String target = first[1];
        int q = target.indexOf('?');
        r.path = q >= 0 ? target.substring(0, q) : target;
        if (q >= 0) {
            for (String kv : target.substring(q + 1).split("&")) {
                int e = kv.indexOf('=');
                try {
                    if (e > 0) r.query.put(URLDecoder.decode(kv.substring(0, e), "UTF-8"),
                            URLDecoder.decode(kv.substring(e + 1), "UTF-8"));
                } catch (IllegalArgumentException ignored) {
                    // skip broken pairs
                }
            }
        }
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) {
                r.headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).trim());
            }
        }
        int length = 0;
        try {
            length = Integer.parseInt(r.header("content-length").isEmpty() ? "0" : r.header("content-length"));
        } catch (NumberFormatException ignored) {
            length = 0;
        }
        if (length < 0 || length > MAX_BODY) return null;
        byte[] body = new byte[length];
        int off = 0;
        while (off < length) {
            int n = in.read(body, off, length - off);
            if (n < 0) break;
            off += n;
        }
        r.body = body;
        return r;
    }

    // ------------------------------------------------------------ responses

    public static Response json(int status, String json) {
        byte[] b = json.getBytes(UTF8);
        return bytes(status, "application/json; charset=utf-8", b);
    }

    public static Response bytes(int status, String type, byte[] b) {
        return out -> {
            out.write(head(status, type, b.length, null).getBytes(UTF8));
            out.write(b);
        };
    }

    /** A file or stream with Range support (so the TV can seek in a song). */
    public interface Source {
        long length();

        InputStream open(long from) throws IOException;
    }

    public static Response range(Request r, String type, Source src) {
        return out -> {
            long total = src.length();
            long from = 0;
            long to = total - 1;
            boolean partial = false;
            String range = r.header("range");
            if (range.startsWith("bytes=") && total > 0) {
                String[] p = range.substring(6).split("-", 2);
                try {
                    if (!p[0].isEmpty()) from = Long.parseLong(p[0].trim());
                    if (p.length > 1 && !p[1].trim().isEmpty()) to = Math.min(total - 1, Long.parseLong(p[1].trim()));
                    if (p[0].isEmpty() && p.length > 1) { // "bytes=-500": the last 500 bytes
                        from = Math.max(0, total - Long.parseLong(p[1].trim()));
                        to = total - 1;
                    }
                    partial = true;
                } catch (NumberFormatException ignored) {
                    partial = false;
                }
            }
            if (from > to || from >= total) {
                out.write(head(416, "text/plain", 0, "Content-Range: bytes */" + total).getBytes(UTF8));
                return;
            }
            long len = to - from + 1;
            String extra = "Accept-Ranges: bytes" + (partial ? "\r\nContent-Range: bytes " + from + "-" + to + "/" + total : "");
            out.write(head(partial ? 206 : 200, type, len, extra).getBytes(UTF8));
            if ("HEAD".equals(r.method)) return;
            try (InputStream in = src.open(from)) {
                byte[] buf = new byte[64 * 1024];
                long left = len;
                while (left > 0) {
                    int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                    if (n < 0) break;
                    out.write(buf, 0, n);
                    left -= n;
                }
            }
        };
    }

    private static String head(int status, String type, long length, String extra) {
        return "HTTP/1.1 " + status + " " + reason(status) + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + length + "\r\n"
                + (extra == null ? "" : extra + "\r\n")
                + "Connection: close\r\n\r\n";
    }

    private static String reason(int s) {
        switch (s) {
            case 200: return "OK";
            case 206: return "Partial Content";
            case 400: return "Bad Request";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 416: return "Range Not Satisfiable";
            default: return "Error";
        }
    }

    // ---------------------------------------------------------------- utils

    public static String newSecret(int bytes) {
        byte[] b = new byte[bytes];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.US, "%02x", x));
        return sb.toString();
    }

    /** This device's address on the home network (Wi-Fi or Ethernet), or null. */
    public static String localIp() {
        try {
            String fallback = null;
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getName() == null ? "" : ni.getName();
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                    if (name.startsWith("wlan") || name.startsWith("eth")) return a.getHostAddress();
                    if (fallback == null) fallback = a.getHostAddress();
                }
            }
            return fallback;
        } catch (Exception e) {
            return null;
        }
    }
}
