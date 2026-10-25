package ru.mikulinskiy.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Список приложений на главном экране: порядок, скрытие, добавление. */
final class Home {
    private Home() {}

    /** Подпись, пакеты-кандидаты, запасной поиск по имени. */
    static final String[][] DEFAULTS = {
            {"YouTube", "org.smarttube.stable", ""},
            {"YouTube 2", "org.smarttube.beta", ""},
            {"YouTube Яндекс", "com.yandex.tv.ytplayer", ""},
            {"Lampa", "top.rootu.lampa", ""},
            {"Кинопоиск", "ru.kinopoisk.yandex.tv", ""},
            {"Okko", "ru.more.play", "okko"},
            {"VK Видео", "com.vk.tv", ""},
            {"Filmix", "net.filmix.filmix", ""},
            {"Rutube", "ru.rutube.app", ""},
            {"Zona", "mobi.zona", ""},
            {"Happ", "su.happ.proxyutility", ""},
            {"Aptoide", "cm.aptoidetv.pt", ""},
    };

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
    }

    static List<String> list(Context c) {
        String s = prefs(c).getString("home", null);
        List<String> out = new ArrayList<>();
        if (s == null) {
            PackageManager pm = c.getPackageManager();
            List<ResolveInfo> all = MainActivity.launchables(pm);
            for (String[] a : DEFAULTS) {
                String pkg = null;
                for (String p : a[1].split(",")) if (MainActivity.launchIntent(pm, p) != null) { pkg = p; break; }
                if (pkg == null && a[2].length() > 0) {
                    for (ResolveInfo r : all) {
                        if (String.valueOf(r.loadLabel(pm)).toLowerCase(Locale.ROOT).contains(a[2])) {
                            pkg = r.activityInfo.packageName; break;
                        }
                    }
                }
                if (pkg != null) out.add(pkg);
            }
            save(c, out);
            return out;
        }
        for (String p : s.split(",")) if (p.length() > 0) out.add(p);
        if (prefs(c).getInt("defaults_v", 1) < 2) {
            String yt = "com.yandex.tv.ytplayer";
            if (!out.contains(yt) && MainActivity.launchIntent(c.getPackageManager(), yt) != null) {
                out.add(Math.min(1, out.size()), yt);
                save(c, out);
            }
            prefs(c).edit().putInt("defaults_v", 2).apply();
        }
        return out;
    }

    static void save(Context c, List<String> l) {
        StringBuilder b = new StringBuilder();
        for (String p : l) { if (b.length() > 0) b.append(','); b.append(p); }
        prefs(c).edit().putString("home", b.toString()).apply();
    }

    static boolean contains(Context c, String pkg) { return list(c).contains(pkg); }

    static void add(Context c, String pkg) {
        List<String> l = list(c);
        if (!l.contains(pkg)) { l.add(pkg); save(c, l); }
    }

    static void remove(Context c, String pkg) {
        List<String> l = list(c);
        if (l.remove(pkg)) save(c, l);
    }

    static void move(Context c, String pkg, int delta) {
        List<String> l = list(c);
        int i = l.indexOf(pkg);
        int j = i + delta;
        if (i < 0 || j < 0 || j >= l.size()) return;
        l.remove(i);
        l.add(j, pkg);
        save(c, l);
    }

    static String label(PackageManager pm, String pkg) {
        for (String[] a : DEFAULTS) for (String p : a[1].split(",")) if (p.equals(pkg)) return a[0];
        try {
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            return pkg;
        }
    }
}
