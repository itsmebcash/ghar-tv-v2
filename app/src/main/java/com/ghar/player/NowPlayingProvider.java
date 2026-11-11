package com.ghar.player;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Tells Ghar Launcher what's playing, so the home screen can show the music while it plays in the
 * background. Read-only, and only for apps signed with the same Ghar key (signature permission).
 *
 * content://com.ghar.tv.nowplaying/now  one row: state, title, artist, album, song_id, position, duration
 * content://com.ghar.tv.nowplaying/art  the artwork (JPEG), when there is one
 */
public class NowPlayingProvider extends ContentProvider {

    static final String AUTHORITY = "com.ghar.tv.nowplaying";
    static final Uri NOW = Uri.parse("content://" + AUTHORITY + "/now");

    /** What's playing, kept by {@link PlaybackService} on the main thread. */
    static final class Snapshot {
        String state = "idle"; // idle, playing, paused
        String title = "";
        String artist = "";
        String album = "";
        String songId = "";
        long position;
        long positionAt;
        long duration;
        float speed = 1f;
    }

    static volatile Snapshot now = new Snapshot();

    /** The song playing now, or null (for Ghar TV's start screen). */
    public static String playingTitle() {
        Snapshot s = now;
        return "playing".equals(s.state) && !s.title.isEmpty() ? s.title : null;
    }

    static File artFile(Context c) {
        return new File(c.getCacheDir(), "now_art.jpg");
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        MatrixCursor c = new MatrixCursor(new String[]{"state", "title", "artist", "album", "song_id", "position",
                "duration", "has_art"});
        Snapshot s = now;
        long pos = s.position;
        if ("playing".equals(s.state)) pos += (long) ((SystemClock.elapsedRealtime() - s.positionAt) * s.speed);
        if (s.duration > 0) pos = Math.min(pos, s.duration);
        Context ctx = getContext();
        boolean art = ctx != null && artFile(ctx).exists() && !s.songId.isEmpty();
        c.addRow(new Object[]{s.state, s.title, s.artist, s.album, s.songId, pos, s.duration, art ? 1 : 0});
        return c;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        Context ctx = getContext();
        if (ctx == null || !"r".equals(mode) || !"/art".equals(uri.getPath())) throw new FileNotFoundException();
        File f = artFile(ctx);
        if (!f.exists()) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "/art".equals(uri.getPath()) ? "image/jpeg" : "vnd.android.cursor.item/vnd.ghar.nowplaying";
    }

    @Override
    public Uri insert(Uri uri, ContentValues v) {
        return null;
    }

    @Override
    public int delete(Uri uri, String s, String[] a) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues v, String s, String[] a) {
        return 0;
    }
}
