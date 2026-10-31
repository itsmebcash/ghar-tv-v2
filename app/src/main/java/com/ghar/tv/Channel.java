package com.ghar.tv;

import java.util.ArrayList;
import java.util.List;

/** One entry from the M3U playlist. */
final class Channel {
    String name;
    String quality;           // e.g. "720p", from "(720p)" in the playlist name
    String url;
    String logo;
    String country = "";      // two-letter code from tvg-id, e.g. "np"
    String userAgent;
    String referrer;
    final List<String> categories = new ArrayList<>();
    boolean hls;              // set by the health check: stream is an HLS (.m3u8) playlist

    String subtitle() {
        StringBuilder sb = new StringBuilder();
        if (!categories.isEmpty()) sb.append(categories.get(0));
        if (!country.isEmpty()) {
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append(country.toUpperCase(java.util.Locale.US));
        }
        if (quality != null) {
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append(quality);
        }
        return sb.toString();
    }
}
