package ru.mikulinskiy.tvhome;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.content.Intent;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import android.util.Log;
import java.net.URL;

import java.util.Locale;

/** Состояние VPN: включён ли туннель, есть ли связь через него, страна выхода и задержка. */
final class Vpn {
    private Vpn() {}

    static final String HAPP = "su.happ.proxyutility";

    static final class Status {
        boolean on;        // в системе есть VPN-сеть
        boolean online;    // запрос через сеть прошёл
        String country = "";
        String flag = "";
        long ms = -1;
    }

    static Network vpnNetwork(Context c) {
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return null;
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return n;
        }
        return null;
    }

    static boolean tunnelUp(Context c) { return vpnNetwork(c) != null; }

    /** Включить/выключить VPN в Happ без открытия его интерфейса. */
    static void set(Context c, boolean on) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(on ? "happ://connect_without_ui" : "happ://disconnect_without_ui"));
        i.setPackage(HAPP);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        c.startActivity(i);
    }

    /** Локальные порты, которые слушает Happ (его SOCKS/HTTP-вход), из /proc/net/tcp. */
    static List<Integer> happPorts(Context c) {
        List<Integer> out = new ArrayList<>();
        int uid;
        try { uid = c.getPackageManager().getApplicationInfo(HAPP, 0).uid; } catch (Exception e) { return out; }
        for (String f : new String[]{"/proc/net/tcp", "/proc/net/tcp6"}) {
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                String line = r.readLine();
                while ((line = r.readLine()) != null) {
                    String[] p = line.trim().split("\\s+");
                    if (p.length < 8 || !"0A".equals(p[3])) continue;
                    if (Integer.parseInt(p[7]) != uid) continue;
                    String local = p[1];
                    int port = Integer.parseInt(local.substring(local.indexOf(':') + 1), 16);
                    if (!out.contains(port)) out.add(port);
                }
            } catch (Exception e) { Log.w("TvHome", "proc " + f + ": " + e); }
        }
        return out;
    }

    private static String trace(Network n) throws Exception { return trace(n, null); }

    private static String trace(Network n, Proxy proxy) throws Exception {
        URL u = new URL("https://www.cloudflare.com/cdn-cgi/trace");
        HttpURLConnection h = (HttpURLConnection) (proxy != null ? u.openConnection(proxy)
                : (n != null ? n.openConnection(u) : u.openConnection()));
        h.setConnectTimeout(5000);
        h.setReadTimeout(6000);
        h.setUseCaches(false);
        try {
            if (h.getResponseCode() != 200) throw new Exception("HTTP " + h.getResponseCode());
            InputStream in = h.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int k;
            while ((k = in.read(buf)) > 0) out.write(buf, 0, k);
            return out.toString("UTF-8");
        } finally {
            h.disconnect();
        }
    }

    static String flag(String code) {
        if (code == null || code.length() != 2) return "";
        int a = Character.codePointAt(code.toUpperCase(Locale.ROOT), 0) - 'A' + 0x1F1E6;
        int b = Character.codePointAt(code.toUpperCase(Locale.ROOT), 1) - 'A' + 0x1F1E6;
        return new String(Character.toChars(a)) + new String(Character.toChars(b));
    }

    /** Выполнять не в главном потоке. При включённом VPN проверяем именно через туннель. */
    static Status check(Context c) {
        Status s = new Status();
        Network vpn = vpnNetwork(c);
        s.on = vpn != null;
        String body = null;
        long t0 = System.nanoTime();
        if (vpn != null) {
            // 1) через локальный вход Happ: работает, даже если лаунчер исключён из туннеля
            for (int port : happPorts(c)) {
                for (Proxy.Type type : new Proxy.Type[]{Proxy.Type.SOCKS, Proxy.Type.HTTP}) {
                    t0 = System.nanoTime();
                    try {
                        body = trace(null, new Proxy(type, new InetSocketAddress("127.0.0.1", port)));
                        Log.i("TvHome", "vpn via " + type + " :" + port);
                        break;
                    } catch (Exception e) { Log.w("TvHome", "vpn " + type + " :" + port + " " + e); }
                }
                if (body != null) break;
            }
            // 2) через сам туннель
            if (body == null) {
                t0 = System.nanoTime();
                try { body = trace(vpn); } catch (Exception e) { Log.w("TvHome", "vpn net " + e); }
            }
            // 3) обычным путём
            if (body == null) {
                t0 = System.nanoTime();
                try { body = trace(null); } catch (Exception e) { Log.w("TvHome", "vpn default " + e); }
            }
        } else {
            try { body = trace(null); } catch (Exception e) { Log.w("TvHome", "direct " + e); }
        }
        if (body == null) { s.online = false; return s; }
        s.ms = (System.nanoTime() - t0) / 1_000_000;
        s.online = true;
        for (String line : body.split("\n")) {
            if (line.startsWith("loc=")) {
                String code = line.substring(4).trim();
                String name = new Locale("", code).getDisplayCountry(new Locale("ru"));
                s.country = name.length() > 0 ? name : code;
                s.flag = flag(code);
            }
        }
        return s;
    }
}
