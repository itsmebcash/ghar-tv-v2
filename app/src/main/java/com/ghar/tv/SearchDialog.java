package com.ghar.tv;

import android.app.Activity;
import android.app.Dialog;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Live search, opened by holding Back (or Menu → Search): the list updates while you type, and
 * the spelling doesn't have to be exact ("kisan" finds DD Kishan). Down moves from the box into
 * the results; OK plays one. Before you type, recently watched channels are shown.
 */
final class SearchDialog {

    interface Picked {
        /** @param results what was listed (becomes the channel up/down order), {@code index} = the one picked */
        void picked(List<Channel> results, int index, String query);
    }

    private SearchDialog() {
    }

    static void show(Activity a, Repo repo, Picked done) {
        Dialog d = new Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        d.setContentView(R.layout.dialog_search);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        EditText input = d.findViewById(R.id.search_input);
        ListView list = d.findViewById(R.id.search_results);
        TextView count = d.findViewById(R.id.search_count);
        TextView section = d.findViewById(R.id.search_section);
        TextView empty = d.findViewById(R.id.search_empty);
        // Keep the results visible above the on-screen keyboard.
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_FULLSCREEN);

        Set<String> favs = Store.favourites(a);
        List<Channel> results = new ArrayList<>();
        String[] query = {""};
        Adapter adapter = new Adapter(a, results, favs);
        list.setAdapter(adapter);

        Handler handler = new Handler(Looper.getMainLooper());
        Runnable update = () -> {
            String q = input.getText().toString().trim();
            query[0] = q;
            results.clear();
            if (q.isEmpty()) {
                results.addAll(Session.recent(a));
                section.setText(R.string.search_recent);
                count.setText("");
                empty.setText(R.string.search_start);
            } else {
                results.addAll(repo.search(q));
                section.setText(a.getString(R.string.search_results_for, q));
                count.setText(results.isEmpty() ? "" : String.valueOf(results.size()));
                empty.setText(a.getString(R.string.search_none, q));
            }
            adapter.notifyDataSetChanged();
            boolean none = results.isEmpty();
            list.setVisibility(none ? View.GONE : View.VISIBLE);
            empty.setVisibility(none ? View.VISIBLE : View.GONE);
            section.setVisibility(none && q.isEmpty() ? View.GONE : View.VISIBLE);
        };
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int af) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
                handler.removeCallbacks(update);
                handler.postDelayed(update, 140); // as you type, without lagging the keyboard
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        // Down from the box (or the keyboard's Search key) goes into the results.
        input.setOnKeyListener((v, keyCode, e) -> {
            if (e.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN && !results.isEmpty()) {
                list.requestFocus();
                list.setSelection(0);
                return true;
            }
            return false;
        });
        input.setOnEditorActionListener((v, actionId, e) -> {
            handler.removeCallbacks(update);
            update.run();
            if (!results.isEmpty()) {
                list.requestFocus();
                list.setSelection(0);
            }
            return true;
        });
        list.setOnKeyListener((v, keyCode, e) -> {
            if (e.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP
                    && list.getSelectedItemPosition() <= 0) {
                input.requestFocus(); // back up to the box to keep typing
                return true;
            }
            return false;
        });
        list.setOnItemClickListener((p, v, position, id) -> {
            d.dismiss();
            done.picked(new ArrayList<>(results), position, query[0]);
        });
        d.setOnDismissListener(x -> handler.removeCallbacksAndMessages(null));
        update.run();
        d.show();
        input.requestFocus();
        // Some TV keyboards only open when asked directly.
        input.postDelayed(() -> showKeyboard(a, input), 250);
        input.setOnClickListener(v -> showKeyboard(a, input)); // OK on the box: keyboard again
    }

    private static void showKeyboard(Activity a, EditText input) {
        android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
        if (imm != null && input.hasFocus()) imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
    }

    private static final class Adapter extends BaseAdapter {
        private final Activity a;
        private final List<Channel> items;
        private final Set<String> favs;

        Adapter(Activity a, List<Channel> items, Set<String> favs) {
            this.a = a;
            this.items = items;
            this.favs = favs;
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Channel getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView != null ? convertView
                    : LayoutInflater.from(a).inflate(R.layout.item_panel_channel, parent, false);
            Channel c = getItem(position);
            ((TextView) row.findViewById(R.id.row_number)).setText(String.valueOf(position + 1));
            ((TextView) row.findViewById(R.id.row_name)).setText(c.name);
            ((TextView) row.findViewById(R.id.row_sub)).setText(c.subtitle());
            row.findViewById(R.id.row_fav).setVisibility(favs.contains(c.url) ? View.VISIBLE : View.GONE);
            Logos.load(a, (ImageView) row.findViewById(R.id.row_logo), c.logo, null);
            return row;
        }
    }
}
