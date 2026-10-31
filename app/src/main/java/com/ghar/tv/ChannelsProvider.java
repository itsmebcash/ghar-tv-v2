package com.ghar.tv;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import java.util.List;

/**
 * Read-only window into Ghar TV for Ghar Launcher.
 *
 * Protected by the signature permission com.ghar.tv.permission.CHANNELS: only apps signed
 * with the same key as Ghar TV (i.e. Ghar Launcher) can read it. Stream addresses are never
 * returned; channels are identified by stable ids.
 *
 *   content://com.ghar.tv.channels/favourites   your favourite channels
 *   content://com.ghar.tv.channels/last         the last channel you watched (0 or 1 row)
 *
 * Columns: id, name, logo, category, available (1 = working, 0 = failed, -1 = not checked yet),
 *          last_ok (ms, 0 = unknown), startup_ms (-1 = unknown), last_watched (ms, "last" only)
 */
public class ChannelsProvider extends ContentProvider {

    static final String AUTHORITY = "com.ghar.tv.channels";
    private static final int FAVOURITES = 1;
    private static final int LAST = 2;
    private static final UriMatcher MATCHER = new UriMatcher(UriMatcher.NO_MATCH);

    static {
        MATCHER.addURI(AUTHORITY, "favourites", FAVOURITES);
        MATCHER.addURI(AUTHORITY, "last", LAST);
    }

    private static final String[] COLUMNS = {
            "id", "name", "logo", "category", "available", "last_ok", "startup_ms", "last_watched"};

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        MatrixCursor out = new MatrixCursor(COLUMNS);
        android.content.Context c = getContext();
        if (c == null) return out;
        int match = MATCHER.match(uri);
        if (match != FAVOURITES && match != LAST) return out;

        Store.ScanCache scan = Store.loadScan(c);
        if (match == FAVOURITES) {
            List<Snapshot.Entry> favs = Snapshot.read(c);
            for (Snapshot.Entry e : favs) addRow(out, c, scan, e, 0);
        } else {
            Channel last = Session.lastChannel(c);
            if (last != null) addRow(out, c, scan, Snapshot.entry(last), Session.lastWatchedTime(c));
        }
        return out;
    }

    private static void addRow(MatrixCursor out, android.content.Context c, Store.ScanCache scan,
                               Snapshot.Entry e, long lastWatched) {
        int available;
        if (scan.working.containsKey(e.url)) available = 1;
        else if (scan.checked.contains(e.url)) available = 0;
        else available = -1;
        Stats.Info s = Stats.get(c, e.url);
        Long scanOk = scan.okTime.get(e.url);
        long lastOk = Math.max(s.lastOk, scanOk == null ? 0 : scanOk);
        if (s.lastOk > 0 && available == 0 && s.lastOk > scan.time) available = 1; // played since the check
        out.addRow(new Object[]{e.id, e.name, e.logo, e.category, available, lastOk, s.startupMs, lastWatched});
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.dir/vnd.com.ghar.tv.channel";
    }

    // Read-only: nothing can be changed from outside.

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only");
    }
}
