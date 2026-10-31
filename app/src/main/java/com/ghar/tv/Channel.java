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

    /** For saving the last-watched / recent channels. */
    org.json.JSONObject toJson() throws org.json.JSONException {
        org.json.JSONArray cats = new org.json.JSONArray();
        for (String c : categories) cats.put(c);
        return new org.json.JSONObject()
                .put("name", name).put("url", url).put("hls", hls)
                .put("logo", logo == null ? "" : logo)
                .put("country", country == null ? "" : country)
                .put("quality", quality == null ? "" : quality)
                .put("ua", userAgent == null ? "" : userAgent)
                .put("ref", referrer == null ? "" : referrer)
                .put("cats", cats);
    }

    static Channel fromJson(org.json.JSONObject o) throws org.json.JSONException {
        Channel c = new Channel();
        c.name = o.getString("name");
        c.url = o.getString("url");
        c.hls = o.optBoolean("hls", false);
        c.logo = emptyToNull(o.optString("logo", ""));
        c.country = o.optString("country", "");
        c.quality = emptyToNull(o.optString("quality", ""));
        c.userAgent = emptyToNull(o.optString("ua", ""));
        c.referrer = emptyToNull(o.optString("ref", ""));
        org.json.JSONArray cats = o.optJSONArray("cats");
        if (cats != null) for (int i = 0; i < cats.length(); i++) c.categories.add(cats.getString(i));
        return c;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

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
