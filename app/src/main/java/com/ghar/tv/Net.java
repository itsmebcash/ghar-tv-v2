package com.ghar.tv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;

/** Minimal HTTP GET with a hard time limit, manual redirects (incl. http↔https) and a size cap. */
final class Net {

    static final String DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 10; Android TV) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    static final class Result {
        String finalUrl;
        byte[] data;
    }

    private Net() {
    }

    /**
     * @param timeoutMs total time allowed, including redirects and reading
     * @param maxBytes  stop reading after this many bytes (enough to inspect the start of a stream)
     */
    static Result get(String url, String userAgent, String referrer, long timeoutMs, int maxBytes)
            throws IOException {
        return get(url, userAgent, referrer, timeoutMs, maxBytes, null);
    }

    /** Same, with extra request headers (e.g. Accept). */
    static Result get(String url, String userAgent, String referrer, long timeoutMs, int maxBytes,
                      java.util.Map<String, String> headers) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String current = url;
        for (int hop = 0; hop < 6; hop++) {
            int remaining = (int) (deadline - System.currentTimeMillis());
            if (remaining <= 0) throw new SocketTimeoutException("deadline");
            HttpURLConnection c = (HttpURLConnection) new URL(current).openConnection();
            try {
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(remaining);
                c.setReadTimeout(remaining);
                c.setRequestProperty("User-Agent", userAgent != null ? userAgent : DEFAULT_UA);
                if (referrer != null) c.setRequestProperty("Referer", referrer);
                if (headers != null) {
                    for (java.util.Map.Entry<String, String> h : headers.entrySet()) {
                        c.setRequestProperty(h.getKey(), h.getValue());
                    }
                }
                int code = c.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null) throw new IOException("redirect without location");
                    current = new URL(new URL(current), loc).toString();
                    continue;
                }
                if (code != 200 && code != 206) throw new IOException("HTTP " + code);

                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (InputStream in = c.getInputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while (out.size() < maxBytes && (n = in.read(buf)) != -1) {
                        out.write(buf, 0, Math.min(n, maxBytes - out.size()));
                        if (System.currentTimeMillis() > deadline) throw new SocketTimeoutException("slow");
                    }
                }
                Result r = new Result();
                r.finalUrl = current;
                r.data = out.toByteArray();
                return r;
            } finally {
                c.disconnect();
            }
        }
        throw new IOException("too many redirects");
    }

    static String resolve(String base, String ref) {
        try {
            return new URL(new URL(base), ref.trim()).toString();
        } catch (Exception e) {
            return ref;
        }
    }
}
