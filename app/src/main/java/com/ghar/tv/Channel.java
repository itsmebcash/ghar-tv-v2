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
    String tvgId = "";        // iptv-org channel id, e.g. "Kantipur.np@SD" (may be empty)
    final List<String> categories = new ArrayList<>();
    boolean hls;              // set by the health check: stream is an HLS (.m3u8) playlist
    int rank;                 // 1, 2, 3… for the curated channels ("For You"), 0 for the rest

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
                .put("cats", cats)
                .put("tid", tvgId == null ? "" : tvgId);
    }

    /**
     * Stable identifier shared with Ghar Launcher instead of the stream address:
     * the iptv-org channel id when the playlist has one, otherwise a short fingerprint.
     */
    String id() {
        if (tvgId != null && !tvgId.isEmpty()) return tvgId;
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-1")
                    .digest(url.getBytes(java.nio.charset.Charset.forName("UTF-8")));
            StringBuilder sb = new StringBuilder("u-");
            for (int i = 0; i < 8; i++) sb.append(String.format(java.util.Locale.US, "%02x", h[i]));
            return sb.toString();
        } catch (Exception e) {
            return "u-" + Integer.toHexString(url.hashCode());
        }
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
        c.tvgId = o.optString("tid", "");
        return c;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    /** "News  ·  NP", or in Nepali "समाचार  ·  नेपाल" (see Lang). */
    String subtitle() {
        StringBuilder sb = new StringBuilder();
        if (!categories.isEmpty()) sb.append(nepali ? genreNe(categories.get(0)) : categories.get(0));
        if (!country.isEmpty()) {
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append(nepali ? countryNe(country) : country.toUpperCase(java.util.Locale.US));
        }
        if (quality != null) {
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append(quality);
        }
        return sb.toString();
    }

    /** Set once at start from the app's language (Lang). */
    static volatile boolean nepali;

    private static final java.util.Map<String, String> GENRE_NE = new java.util.HashMap<>();
    private static final java.util.Map<String, String> COUNTRY_NE = new java.util.HashMap<>();

    static {
        String[][] g = {{"News", "समाचार"}, {"Business", "व्यापार"}, {"Weather", "मौसम"}, {"Legislative", "संसद"},
                {"Entertainment", "मनोरञ्जन"}, {"Movies", "चलचित्र"}, {"Series", "धारावाहिक"}, {"Comedy", "हास्य"},
                {"Classic", "पुराना"}, {"Music", "गीत-संगीत"}, {"Sports", "खेलकुद"}, {"Kids", "बालबालिका"},
                {"Animation", "कार्टुन"}, {"Family", "परिवार"}, {"Education", "शिक्षा"}, {"Documentary", "वृत्तचित्र"},
                {"Science", "विज्ञान"}, {"Travel", "यात्रा"}, {"Lifestyle", "जीवनशैली"}, {"Cooking", "खाना"},
                {"Culture", "संस्कृति"}, {"Outdoor", "बाहिरी"}, {"Relax", "आराम"}, {"Shop", "किनमेल"}, {"Auto", "गाडी"},
                {"Religious", "धार्मिक"}, {"General", "सामान्य"}, {"Other", "अन्य"}, {"Public", "सार्वजनिक"},
                {"Undefined", "अन्य"}};
        for (String[] p : g) GENRE_NE.put(p[0], p[1]);
        String[][] c = {{"np", "नेपाल"}, {"in", "भारत"}, {"us", "अमेरिका"}, {"gb", "बेलायत"}, {"uk", "बेलायत"},
                {"pk", "पाकिस्तान"}, {"bd", "बंगलादेश"}, {"lk", "श्रीलंका"}, {"cn", "चीन"}, {"jp", "जापान"},
                {"kr", "कोरिया"}, {"au", "अस्ट्रेलिया"}, {"ae", "युएई"}, {"qa", "कतार"}, {"sa", "साउदी"},
                {"fr", "फ्रान्स"}, {"de", "जर्मनी"}, {"ca", "क्यानडा"}, {"bt", "भुटान"}, {"int", "अन्तर्राष्ट्रिय"}};
        for (String[] p : c) COUNTRY_NE.put(p[0], p[1]);
    }

    private static String genreNe(String g) {
        String n = GENRE_NE.get(g);
        return n != null ? n : g;
    }

    private static String countryNe(String code) {
        String n = COUNTRY_NE.get(code.toLowerCase(java.util.Locale.US));
        return n != null ? n : code.toUpperCase(java.util.Locale.US);
    }
}
