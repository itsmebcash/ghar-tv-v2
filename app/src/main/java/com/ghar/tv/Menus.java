package com.ghar.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Small dialogs shared by the browser and the player: channel options and playlists. */
final class Menus {

    private Menus() {
    }

    private static AlertDialog.Builder dialog(Activity a) {
        return new AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert);
    }

    private static void toast(Activity a, String s) {
        Toast.makeText(a, s, Toast.LENGTH_SHORT).show();
    }

    /**
     * Hold OK / long-press on a channel.
     * @param browsing the category being looked at (offers "Remove from this playlist" there)
     */
    static void channel(Activity a, Channel c, String browsing, Runnable changed) {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        boolean fav = Store.favourites(a).contains(c.url);
        labels.add(a.getString(fav ? R.string.menu_unfavourite : R.string.menu_favourite));
        actions.add(() -> {
            boolean added = Store.toggleFavourite(a, c.url);
            toast(a, a.getString(added ? R.string.fav_added : R.string.fav_removed, c.name));
            changed.run();
        });
        labels.add(a.getString(R.string.menu_add_to_playlist));
        actions.add(() -> addToPlaylist(a, c, changed));
        if (Repo.isPlaylist(browsing)) {
            String id = browsing.substring(Repo.PLAYLIST.length());
            Store.UserPlaylist p = Store.playlist(a, id);
            if (p != null) {
                labels.add(a.getString(R.string.menu_remove_from, p.name));
                actions.add(() -> {
                    Store.removeFromPlaylist(a, id, c.url);
                    changed.run();
                });
            }
        }
        dialog(a).setTitle(c.name)
                .setItems(labels.toArray(new String[0]), (d, w) -> actions.get(w).run())
                .show();
    }

    static void addToPlaylist(Activity a, Channel c, Runnable changed) {
        List<Store.UserPlaylist> lists = Store.playlists(a);
        String[] labels = new String[lists.size() + 1];
        for (int i = 0; i < lists.size(); i++) labels[i] = lists.get(i).name;
        labels[lists.size()] = a.getString(R.string.new_playlist);
        dialog(a).setTitle(R.string.menu_add_to_playlist)
                .setItems(labels, (d, w) -> {
                    if (w == lists.size()) {
                        newPlaylist(a, c, changed);
                    } else {
                        Store.UserPlaylist p = lists.get(w);
                        boolean added = Store.addToPlaylist(a, p.id, c.url);
                        toast(a, a.getString(added ? R.string.added_to : R.string.already_in, c.name, p.name));
                        changed.run();
                    }
                })
                .show();
    }

    /** Create a playlist; if a channel is given, it goes straight in. */
    static void newPlaylist(Activity a, Channel first, Runnable changed) {
        askName(a, R.string.new_playlist_title, "", name -> {
            Store.UserPlaylist p = Store.createPlaylist(a, name);
            if (first != null) Store.addToPlaylist(a, p.id, first.url);
            toast(a, a.getString(R.string.playlist_created, name));
            changed.run();
        });
    }

    /** Hold OK / long-press on one of your playlists. */
    static void playlist(Activity a, String key, Runnable changed) {
        if (!Repo.isPlaylist(key)) return;
        String id = key.substring(Repo.PLAYLIST.length());
        Store.UserPlaylist p = Store.playlist(a, id);
        if (p == null) return;
        String[] labels = {a.getString(R.string.rename), a.getString(R.string.delete)};
        dialog(a).setTitle(p.name)
                .setItems(labels, (d, w) -> {
                    if (w == 0) {
                        askName(a, R.string.rename_playlist_title, p.name, name -> {
                            Store.renamePlaylist(a, id, name);
                            changed.run();
                        });
                    } else {
                        dialog(a).setTitle(a.getString(R.string.delete_playlist_title, p.name))
                                .setMessage(R.string.delete_playlist_message)
                                .setPositiveButton(R.string.delete, (d2, w2) -> {
                                    Store.deletePlaylist(a, id);
                                    changed.run();
                                })
                                .setNegativeButton(android.R.string.cancel, null)
                                .show();
                    }
                })
                .show();
    }

    interface NameListener {
        void onName(String name);
    }

    private static void askName(Activity a, int title, String current, NameListener done) {
        EditText input = new EditText(a);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setSingleLine(true);
        input.setText(current);
        input.setSelection(current.length());
        int pad = Math.round(a.getResources().getDisplayMetrics().density * 20);
        FrameLayout box = new FrameLayout(a);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);
        AlertDialog d = dialog(a).setTitle(title).setView(box)
                .setPositiveButton(android.R.string.ok, (dlg, w) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) done.onName(name);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        d.show();
        input.requestFocus();
    }
}
