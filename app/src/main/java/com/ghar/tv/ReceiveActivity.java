package com.ghar.tv;

import android.app.Activity;
import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * "Get from phone": receives favourites and playlists from Ghar TV Mobile on the same Wi-Fi.
 *
 * While this screen is open the TV announces itself on the home network (so the phone finds it
 * by itself) and shows a 4-digit code to type on the phone. Nothing is replaced: favourites are
 * added, and playlists are added or topped up. The receiver stops when you leave this screen.
 */
public class ReceiveActivity extends Activity {

    static final String SERVICE_TYPE = "_ghartv._tcp.";

    private ShareServer server;
    private NsdManager nsd;
    private NsdManager.RegistrationListener registration;
    private TextView status;
    private boolean received;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_receive);
        status = findViewById(R.id.receive_status);
    }

    @Override
    protected void onStart() {
        super.onStart();
        String ip = ShareServer.localIp();
        if (ip == null) {
            status.setText(R.string.receive_no_network);
            ((TextView) findViewById(R.id.receive_code)).setText("––––");
            return;
        }
        String code = ShareServer.newCode();
        String name = tvName();
        server = new ShareServer(code, name, this::importJson,
                () -> runOnUiThread(() -> status.setText(R.string.receive_locked)));
        int port = server.start();
        if (port < 0) {
            status.setText(R.string.receive_no_port);
            return;
        }
        ((TextView) findViewById(R.id.receive_code)).setText(code);
        ((TextView) findViewById(R.id.receive_name)).setText(name);
        ((TextView) findViewById(R.id.receive_address)).setText(getString(R.string.receive_address, ip, port));
        if (!received) status.setText(R.string.receive_waiting);
        announce(name, port);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (server != null) server.stop();
        server = null;
        if (nsd != null && registration != null) {
            try {
                nsd.unregisterService(registration);
            } catch (Exception ignored) {
                // already gone
            }
        }
        registration = null;
    }

    /** "Ghar TV · MiTV-4A" - what the phone lists. */
    private static String tvName() {
        String model = Build.MODEL == null ? "" : Build.MODEL.trim();
        return model.isEmpty() ? "Ghar TV" : "Ghar TV · " + model;
    }

    /** Lets phones on the Wi-Fi find this TV without typing an address (DNS-SD / Bonjour). */
    private void announce(String name, int port) {
        nsd = (NsdManager) getSystemService(Context.NSD_SERVICE);
        if (nsd == null) return;
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(name);
        info.setServiceType(SERVICE_TYPE);
        info.setPort(port);
        registration = new NsdManager.RegistrationListener() {
            @Override
            public void onServiceRegistered(NsdServiceInfo i) {
            }

            @Override
            public void onRegistrationFailed(NsdServiceInfo i, int error) {
                // the phone can still type the address shown on screen
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo i) {
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo i, int error) {
            }
        };
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration);
        } catch (Exception e) {
            registration = null;
        }
    }

    /** Server thread: merge what the phone sent, and say what changed. */
    private String importJson(String json) throws Exception {
        JSONObject o = new JSONObject(json);
        List<String> favs = new ArrayList<>();
        JSONArray f = o.optJSONArray("favourites");
        if (f != null) for (int i = 0; i < f.length() && i < 5000; i++) favs.add(f.getString(i));
        List<Store.UserPlaylist> lists = new ArrayList<>();
        JSONArray p = o.optJSONArray("playlists");
        if (p != null) {
            for (int i = 0; i < p.length() && i < 200; i++) {
                JSONObject po = p.getJSONObject(i);
                Store.UserPlaylist pl = new Store.UserPlaylist();
                pl.name = po.optString("name", "").trim();
                if (pl.name.length() > 60) pl.name = pl.name.substring(0, 60);
                JSONArray urls = po.optJSONArray("urls");
                if (urls != null) for (int j = 0; j < urls.length() && j < 2000; j++) pl.urls.add(urls.getString(j));
                lists.add(pl);
            }
        }
        int[] r = Store.importFromPhone(this, favs, lists);
        received = true;
        runOnUiThread(() -> {
            status.setText(getString(R.string.receive_done, r[0], r[1], r[2]));
            setResult(RESULT_OK);
        });
        return new JSONObject().put("ok", true).put("favourites", r[0]).put("playlistsCreated", r[1])
                .put("playlistsUpdated", r[2]).put("channels", r[3]).toString();
    }
}
