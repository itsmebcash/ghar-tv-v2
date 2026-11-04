package com.ghar.tv;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * How a channel's stream is fetched: one shared connection pool (reused connections, HTTP/2), more
 * retries for a dropped piece of video, and the fix for playlists that hide the sound (HlsCodecsFix).
 */
final class Streams {

    private static okhttp3.OkHttpClient client;

    private Streams() {
    }

    private static synchronized okhttp3.OkHttpClient client() {
        if (client == null) {
            client = new okhttp3.OkHttpClient.Builder()
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .retryOnConnectionFailure(true)
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .connectionPool(new okhttp3.ConnectionPool(6, 2, TimeUnit.MINUTES))
                    .build();
        }
        return client;
    }

    static MediaSource source(Channel c) {
        OkHttpDataSource.Factory data = new OkHttpDataSource.Factory(client())
                .setUserAgent(c.userAgent != null ? c.userAgent : Net.DEFAULT_UA);
        if (c.referrer != null) {
            Map<String, String> headers = new HashMap<>();
            headers.put("Referer", c.referrer);
            data.setDefaultRequestProperties(headers);
        }
        DefaultLoadErrorHandlingPolicy retries = new DefaultLoadErrorHandlingPolicy(6);
        MediaItem.Builder item = new MediaItem.Builder().setUri(c.url);
        boolean hls = c.hls || c.url.toLowerCase(Locale.ROOT).contains(".m3u8");
        if (hls) {
            item.setMimeType(MimeTypes.APPLICATION_M3U8);
            return new HlsMediaSource.Factory((DataSource.Factory) data)
                    .setPlaylistParserFactory(new HlsCodecsFix())
                    .setLoadErrorHandlingPolicy(retries)
                    .createMediaSource(item.build());
        }
        return new DefaultMediaSourceFactory(data).setLoadErrorHandlingPolicy(retries).createMediaSource(item.build());
    }
}
