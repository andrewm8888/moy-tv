package ru.mikulinskiy.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Фоны: группы «Космос» (01..20.jpg: NASA, ESA/Hubble, ESA/Webb, ESO), «Осень» (aut), «Зима» (win),
 * «Весна» (spr), «Лето» (sum) — снимки Wikimedia Commons; все 1920x1080, уже притемнены под текст.
 * Плюс тёмный фон без рисунка. Можно выбрать группу (или «по сезону»), менять каждый день, закрепить, убрать.
 */
final class Backdrop {
    private Backdrop() {}

    static final int DARK = -2;
    private static List<String> files, names;

    private static synchronized void load(Context c) {
        if (files != null) return;
        files = new ArrayList<>();
        names = new ArrayList<>();
        try {
            String[] all = c.getAssets().list("bg");
            java.util.Arrays.sort(all);
            for (String f : all) if (f.endsWith(".jpg")) files.add(f);
            InputStream in = c.getAssets().open("bg/names.json");
            byte[] b = new byte[in.available()];
            int n = 0; while (n < b.length) { int r = in.read(b, n, b.length - n); if (r < 0) break; n += r; }
            in.close();
            String js = new String(b, 0, n, "UTF-8").trim();
            if (js.startsWith("[")) {
                JSONArray a = new JSONArray(js);
                for (int i = 0; i < a.length(); i++) names.add(a.getString(i));
            } else {
                JSONObject m = new JSONObject(js);
                for (String f : files) names.add(m.optString(f, "Фон"));
            }
        } catch (Exception ignored) { }
    }

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
    }

    private static Set<String> hidden(Context c) {
        return new HashSet<>(p(c).getStringSet("bg_hidden", new HashSet<String>()));
    }

    // Группы: код, название, префикс файла
    static final String[][] GROUPS = {{"all", "Все"}, {"season", "По сезону"}, {"space", "Космос"},
            {"aut", "Осень"}, {"win", "Зима"}, {"spr", "Весна"}, {"sum", "Лето"}};

    static String group(Context c) { return p(c).getString("bg_group", "all"); }

    static void setGroup(Context c, String g) { p(c).edit().putString("bg_group", g).remove("bg_fix2").apply(); }

    static String groupName(String g) {
        for (String[] x : GROUPS) if (x[0].equals(g)) return x[1];
        return "Все";
    }

    /** Время года сейчас: aut, win, spr, sum. */
    static String season() {
        int m = Calendar.getInstance().get(Calendar.MONTH);
        return m <= 1 || m == 11 ? "win" : m <= 4 ? "spr" : m <= 7 ? "sum" : "aut";
    }

    private static String groupOf(String file) {
        return Character.isDigit(file.charAt(0)) ? "space" : file.substring(0, 3);
    }

    private static boolean inGroup(String file, String g) {
        if (g.equals("all")) return true;
        if (g.equals("season")) return groupOf(file).equals(season());
        return groupOf(file).equals(g);
    }

    /** Номера фонов выбранной группы, которые не убраны. */
    static List<Integer> active(Context c) {
        load(c);
        Set<String> h = hidden(c);
        String g = group(c);
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) if (!h.contains(files.get(i)) && inGroup(files.get(i), g)) out.add(i);
        return out;
    }

    /** Сколько фонов в группе (без убранных). */
    static int count(Context c, String g) {
        load(c);
        Set<String> h = hidden(c);
        int n = 0;
        for (String f : files) if (!h.contains(f) && inGroup(f, g)) n++;
        return n;
    }

    private static int dayNumber() {
        Calendar k = Calendar.getInstance(WeatherData.tz());
        return (int) ((k.getTimeInMillis() + k.get(Calendar.ZONE_OFFSET) + k.get(Calendar.DST_OFFSET)) / 86400000L);
    }

    /** Текущий фон: закреплённый или свой на каждый день. */
    static int current(Context c) {
        List<Integer> act = active(c);
        int fixed = p(c).getInt("bg_fix2", -1);
        if (fixed == DARK || act.isEmpty()) return DARK;
        if (fixed >= 0 && act.contains(fixed)) return fixed;
        return act.get(Math.abs(dayNumber()) % act.size());
    }

    static boolean daily(Context c) { return p(c).getInt("bg_fix2", -1) == -1; }

    static void fix(Context c, int v) { p(c).edit().putInt("bg_fix2", v).apply(); }

    static void setDaily(Context c) { p(c).edit().remove("bg_fix2").apply(); }

    /** Следующий (+1) или предыдущий (-1) среди оставленных; после тёмного идёт первый. */
    static void step(Context c, int dir) {
        List<Integer> act = active(c);
        if (act.isEmpty()) { fix(c, DARK); return; }
        int cur = current(c);
        int i = act.indexOf(cur);
        int n = i < 0 ? (dir > 0 ? 0 : act.size() - 1) : ((i + dir) % act.size() + act.size()) % act.size();
        fix(c, act.get(n));
    }

    /** Убрать фон из показа. */
    static void hide(Context c, int v) {
        load(c);
        if (v < 0 || v >= files.size()) return;
        Set<String> h = hidden(c);
        h.add(files.get(v));
        p(c).edit().putStringSet("bg_hidden", h).apply();
        if (p(c).getInt("bg_fix2", -1) == v) step(c, 1);
    }

    static void unhideAll(Context c) { p(c).edit().remove("bg_hidden").apply(); }

    static final int[] SHADES = {0, 15, 28};

    /** Тень на фото в процентах, по умолчанию 28. */
    static int shade(Context c) { return p(c).getInt("bg_shade", 28); }
    static void setShade(Context c, int pct) { p(c).edit().putInt("bg_shade", pct).apply(); }

    static int hiddenCount(Context c) { return hidden(c).size(); }

    static String name(Context c, int v) {
        load(c);
        if (v == DARK || v < 0 || v >= files.size()) return "Тёмный, без рисунка";
        String n = v < names.size() ? names.get(v) : "Фон";
        List<Integer> act = active(c);
        int pos = act.indexOf(v);
        return (pos >= 0 ? (pos + 1) + " из " + act.size() + ". " : "") + n;
    }

    static Bitmap render(Context c, int v) {
        load(c);
        if (v != DARK && v >= 0 && v < files.size()) {
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                InputStream in = c.getAssets().open("bg/" + files.get(v));
                o.inMutable = true;
                Bitmap b = BitmapFactory.decodeStream(in, null, o);
                in.close();
                if (b != null) {
                    // «Тень»: приглушаем фото (0, 15 или 28 %), чтобы часы, курсы и подписи не терялись
                    int sh = shade(c);
                    if (sh > 0) new Canvas(b).drawColor(Math.round(sh * 255f / 100f) << 24);
                    return b;
                }
            } catch (Exception ignored) { }
        }
        Bitmap b = Bitmap.createBitmap(16, 9, Bitmap.Config.RGB_565);
        new Canvas(b).drawColor(Ui.BG);
        return b;
    }
}
