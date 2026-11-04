package com.ghar.tv;

import android.net.Uri;

import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory;
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory;
import androidx.media3.exoplayer.upstream.ParsingLoadable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fixes "picture but no sound" on channels whose HLS playlist lists only the video codec
 * (CODECS="avc1...") although the stream carries sound: the player believes that and drops the audio.
 * For those quality levels the CODECS attribute is removed, so the player looks at the stream itself.
 */
final class HlsCodecsFix implements HlsPlaylistParserFactory {

    private static final Charset LATIN1 = Charset.forName("ISO-8859-1");
    private static final Pattern CODECS = Pattern.compile(",?CODECS=\"([^\"]*)\"");
    private static final String[] AUDIO = {"mp4a", "ac-3", "ec-3", "opus", "mp3", "mp2", "flac", "dts", "ac-4", "alac"};

    private final HlsPlaylistParserFactory base = new DefaultHlsPlaylistParserFactory();

    @Override
    public ParsingLoadable.Parser<HlsPlaylist> createPlaylistParser() {
        return wrap(base.createPlaylistParser());
    }

    @Override
    public ParsingLoadable.Parser<HlsPlaylist> createPlaylistParser(HlsMultivariantPlaylist multivariant,
                                                                     HlsMediaPlaylist previous) {
        return wrap(base.createPlaylistParser(multivariant, previous));
    }

    private static ParsingLoadable.Parser<HlsPlaylist> wrap(ParsingLoadable.Parser<HlsPlaylist> parser) {
        return (Uri uri, InputStream in) -> parser.parse(uri, fix(in));
    }

    static InputStream fix(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        byte[] data = out.toByteArray();
        String text = new String(data, LATIN1);
        // Only quality-level lists without separate audio renditions need it.
        if (!text.contains("#EXT-X-STREAM-INF") || text.contains("TYPE=AUDIO")) return new ByteArrayInputStream(data);
        String fixed = fixText(text);
        return new ByteArrayInputStream(fixed == null ? data : fixed.getBytes(LATIN1));
    }

    /** The playlist with video-only CODECS removed, or null when nothing changed. */
    static String fixText(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        boolean changed = false;
        for (String line : text.split("\n", -1)) {
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                Matcher m = CODECS.matcher(line);
                if (m.find() && !hasAudio(m.group(1))) {
                    line = line.substring(0, m.start()) + line.substring(m.end());
                    line = line.replace("#EXT-X-STREAM-INF:,", "#EXT-X-STREAM-INF:");
                    changed = true;
                }
            }
            sb.append(line).append('\n');
        }
        if (!changed) return null;
        sb.setLength(sb.length() - 1); // split kept a trailing empty part
        return sb.toString();
    }

    private static boolean hasAudio(String codecs) {
        String c = codecs.toLowerCase(Locale.ROOT);
        for (String a : AUDIO) if (c.contains(a)) return true;
        return false;
    }
}
