package com.ghar.tv;

import java.nio.charset.Charset;

/**
 * Decides whether a channel really works, the way a player would:
 * playlist -> (variant playlist) -> first video segment, all within TIMEOUT_MS.
 * Channels that fail, are geo-blocked, or are too slow are rejected.
 */
final class Probe {

    static final long TIMEOUT_MS = 6000;
    private static final Charset LATIN1 = Charset.forName("ISO-8859-1");

    private Probe() {
    }

    static boolean check(Channel c) {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        try {
            Net.Result r = Net.get(c.url, c.userAgent, c.referrer, left(deadline), 256 * 1024);
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
                        left(deadline), 256 * 1024);
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
        } catch (Throwable e) {
            return false;
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
