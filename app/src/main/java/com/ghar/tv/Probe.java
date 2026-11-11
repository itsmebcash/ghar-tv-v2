package com.ghar.tv;

import java.nio.charset.Charset;

/**
 * Decides whether a channel really works, the way a player would:
 * playlist -> (variant playlist) -> first video segment, all within TIMEOUT_MS.
 * Channels that fail, are geo-blocked, or are not a stream are rejected.
 *
 * Like Ghar TV Mobile, a check that only timed out or lost the connection is "unsure", not
 * "dead": on home Wi-Fi a good channel can be slow to answer while many are checked at once.
 */
final class Probe {

    static final long TIMEOUT_MS = 6000;
    /** A second, more patient try for channels that were slow the first time. */
    static final long SLOW_TIMEOUT_MS = 14000;

    /** Works; definitely broken (error, not a stream); couldn't tell (timed out / connection trouble). */
    static final int OK = 0, DEAD = 1, UNSURE = 2;
    private static final Charset LATIN1 = Charset.forName("ISO-8859-1");

    private Probe() {
    }

    static int check(Channel c, long timeoutMs) {
        try {
            return run(c, timeoutMs) ? OK : DEAD;
        } catch (java.io.InterruptedIOException | java.net.UnknownHostException | java.net.SocketException e) {
            return UNSURE; // the connection, not necessarily the channel
        } catch (Throwable e) {
            return DEAD;
        }
    }

    private static boolean run(Channel c, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        try {
            Net.Result r = Net.get(c.url, c.userAgent, c.referrer, left(deadline), 96 * 1024);
            String body = new String(r.data, LATIN1).trim();

            if (!body.startsWith("#EXTM3U")) {
                // Not HLS: a direct stream (MPEG-TS, MP4...). It sent data, so accept it —
                // but not a DASH manifest or a web page: the app cannot play either.
                c.hls = false;
                if (r.data.length <= 1024) return false;
                String head = body.substring(0, Math.min(400, body.length())).toLowerCase(java.util.Locale.ROOT);
                if (c.url.toLowerCase(java.util.Locale.ROOT).contains(".mpd")) return false;
                if (head.startsWith("<?xml") || head.startsWith("<mpd") || head.startsWith("<!doctype")
                        || head.startsWith("<html") || head.contains("<mpd")) return false;
                return true;
            }
            c.hls = true;

            String baseUrl = r.finalUrl;
            if (body.contains("#EXT-X-STREAM-INF")) {
                // Master playlist: check the first quality level.
                String variant = firstUri(body);
                if (variant == null) return false;
                Net.Result v = Net.get(Net.resolve(baseUrl, variant), c.userAgent, c.referrer,
                        left(deadline), 96 * 1024);
                body = new String(v.data, LATIN1);
                baseUrl = v.finalUrl;
            }
            if (!body.contains("#EXTINF")) return false;

            // Finally fetch the start of the first video segment.
            String segment = firstUri(body);
            if (segment == null) return false;
            Net.Result s = Net.get(Net.resolve(baseUrl, segment), c.userAgent, c.referrer,
                    left(deadline), 16 * 1024);
            return s.data.length > 0;
        } catch (java.io.IOException e) {
            String m = e.getMessage();
            if (m != null && m.startsWith("HTTP ")) return false; // the server said no
            throw e;
        }
    }

    private static int left(long deadline) {
        return (int) Math.max(1, deadline - System.currentTimeMillis());
    }

    /** First line that is a URI (not a tag or comment). */
    private static String firstUri(String playlist) {
        for (String raw : playlist.split("\n")) {
            String line = raw.trim();
            if (!line.isEmpty() && !line.startsWith("#")) return line;
        }
        return null;
    }
}
