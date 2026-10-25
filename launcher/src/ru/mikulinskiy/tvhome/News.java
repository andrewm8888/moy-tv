package ru.mikulinskiy.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.Html;
import android.util.Log;
import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.StringReader;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/** Новости: Мир и Россия (Интерфакс), ВЭД (Альта-Софт, Ведомости), Гаджеты, AI, IT (зарубежные, с переводом). */
final class News {
    static final String[] TABS = {"Мир", "Россия", "ВЭД", "Гаджеты", "AI", "IT"};
    static final int WORLD = 0, RUSSIA = 1, VED = 2;

    /** Вкладки с 3-й (Гаджеты, AI, IT): зарубежные, переводятся. */
    static boolean english(int tab) { return tab >= 3; }

    private static final String[][] GADGETS = {
            {"https://www.gsmarena.com/rss-news-reviews.php3", "GSMArena"},
            {"https://9to5mac.com/feed/", "9to5Mac"},
            {"https://www.androidauthority.com/feed/", "Android Authority"}};
    private static final String[][] AI = {
            {"https://arstechnica.com/ai/feed/", "Ars Technica"},
            {"https://techcrunch.com/category/artificial-intelligence/feed/", "TechCrunch"},
            {"https://www.technologyreview.com/topic/artificial-intelligence/feed", "MIT Tech Review"}};
    private static final String[] VED_WORDS = {"вэд", "экспорт", "импорт", "таможн", "пошлин", "фтс", "санкц",
            "внешнеторг", "внешней торгов", "товарооборот", "логист", "контейнер", "порт", "фрахт", "судоход",
            "еаэс", "параллельн", "валютн", "грузоперевоз", "морск"};
    private static final String IFX = "https://www.interfax.ru/rss";
    private static final String[][] IT = {
            {"https://feeds.arstechnica.com/arstechnica/index", "Ars Technica"},
            {"https://techcrunch.com/feed/", "TechCrunch"}};
    private static final long TTL = 15 * 60 * 1000;

    static final class Item {
        String title = "", desc = "", link = "", source = "", cat = "", full = "";
        long time;
        JSONObject json() throws Exception {
            return new JSONObject().put("t", title).put("d", desc).put("l", link).put("s", source).put("w", time);
        }
        static Item of(JSONObject o) {
            Item i = new Item();
            i.title = o.optString("t"); i.desc = o.optString("d"); i.link = o.optString("l");
            i.source = o.optString("s"); i.time = o.optLong("w");
            return i;
        }
    }

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
    }

    static long updated(Context c, int tab) { return p(c).getLong("n3_at" + tab, 0); }

    static boolean fresh(Context c, int tab) { return System.currentTimeMillis() - updated(c, tab) < TTL; }

    static List<Item> cached(Context c, int tab) {
        List<Item> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(p(c).getString("n3_" + tab, "[]"));
            for (int i = 0; i < a.length(); i++) out.add(Item.of(a.getJSONObject(i)));
        } catch (Exception ignored) { }
        return recentOnly(out);
    }

    private static void save(Context c, int tab, List<Item> l) {
        try {
            JSONArray a = new JSONArray();
            for (Item i : l) a.put(i.json());
            p(c).edit().putString("n3_" + tab, a.toString()).putLong("n3_at" + tab, System.currentTimeMillis()).apply();
        } catch (Exception ignored) { }
    }

    /** Начало вчерашнего дня по местному времени. */
    static long sinceYesterday() {
        java.util.Calendar k = java.util.Calendar.getInstance();
        k.set(java.util.Calendar.HOUR_OF_DAY, 0); k.set(java.util.Calendar.MINUTE, 0);
        k.set(java.util.Calendar.SECOND, 0); k.set(java.util.Calendar.MILLISECOND, 0);
        k.add(java.util.Calendar.DAY_OF_MONTH, -1);
        return k.getTimeInMillis();
    }

    static List<Item> recentOnly(List<Item> l) {
        long from = sinceYesterday();
        List<Item> out = new ArrayList<>();
        for (Item i : l) if (i.time >= from) out.add(i);
        return out;
    }

    /** Загрузить вкладку из сети (в фоновом потоке). */
    static List<Item> load(Context c, int tab) throws Exception {
        List<Item> out = new ArrayList<>();
        if (tab == WORLD || tab == RUSSIA) {
            for (Item i : parse(Net.get(IFX), "Интерфакс")) {
                boolean world = i.link.contains("/world/") || i.cat.contains("В мире");
                if (i.link.contains("/sport/") || i.cat.contains("Спорт")) continue;
                if ((tab == WORLD) == world) out.add(i);
            }
        } else if (tab == VED) {
            // Профильные ленты Альта-Софт берём целиком, общие — по словам ВЭД
            String[][] all = {{"https://www.alta.ru/rss/external_news/", "Альта-Софт"},
                    {"https://www.alta.ru/rss/laws_news/", "Альта-Софт"}};
            String[][] filtered = {{"https://www.vedomosti.ru/rss/rubric/economics", "Ведомости"},
                    {"https://www.vedomosti.ru/rss/rubric/business", "Ведомости"},
                    {IFX, "Интерфакс"}};
            for (String[] f : all) {
                try { out.addAll(parse(Net.get(f[0]), f[1])); }
                catch (Exception e) { Log.w("TvHome", "news " + f[0] + " " + e); }
            }
            for (String[] f : filtered) {
                try {
                    for (Item i : parse(Net.get(f[0]), f[1])) {
                        String t = (i.title + " " + i.desc).toLowerCase(new Locale("ru"));
                        for (String w : VED_WORDS) if (t.contains(w)) { out.add(i); break; }
                    }
                } catch (Exception e) { Log.w("TvHome", "news " + f[0] + " " + e); }
            }
            Log.i("TvHome", "news ved: " + out.size() + " всего, за 2 дня " + recentOnly(out).size());
            out = sortNew(dedup(recentOnly(out)));
            if (out.isEmpty()) throw new Exception("no news");
        } else {
            String[][] feeds = tab == 3 ? GADGETS : tab == 4 ? AI : IT;
            for (String[] f : feeds) {
                try { out.addAll(parse(Net.get(f[0]), f[1])); }
                catch (Exception e) { Log.w("TvHome", "news " + f[1] + " " + e); }
            }
            out = dedup(recentOnly(out));
            if (out.isEmpty()) throw new Exception("no news");
            Collections.sort(out, new Comparator<Item>() {
                @Override public int compare(Item a, Item b) { return Long.compare(b.time, a.time); }
            });
            if (out.size() > 30) out = new ArrayList<>(out.subList(0, 30));
            // полный текст из ленты (у Ars Technica он есть) сохраняем сразу, по-английски
            for (Item i : out) {
                if (i.full.length() > 400) {
                    String body = extract(i.full, true);
                    if (body.length() > 300) writeCache(c, "e", i.link, body);
                }
                i.full = "";
            }
            translate(c, out);
        }
        out = recentOnly(out);
        if (out.size() > 60) out = new ArrayList<>(out.subList(0, 60));
        if (!out.isEmpty()) save(c, tab, out);
        return out;
    }

    private static List<Item> dedup(List<Item> l) {
        List<Item> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Item i : l) if (seen.add(i.link.isEmpty() ? i.title : i.link)) out.add(i);
        return out;
    }

    private static List<Item> sortNew(List<Item> l) {
        Collections.sort(l, new Comparator<Item>() {
            @Override public int compare(Item a, Item b) { return Long.compare(b.time, a.time); }
        });
        return l;
    }

    private static List<Item> parse(String xml, String source) throws Exception {
        List<Item> out = new ArrayList<>();
        XmlPullParser x = Xml.newPullParser();
        x.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        x.setInput(new StringReader(xml));
        Item cur = null;
        String tag = null;
        SimpleDateFormat rfc = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US);
        for (int e = x.getEventType(); e != XmlPullParser.END_DOCUMENT; e = x.next()) {
            if (e == XmlPullParser.START_TAG) {
                tag = x.getName();
                if ("item".equals(tag)) { cur = new Item(); cur.source = source; }
            } else if (e == XmlPullParser.END_TAG) {
                if ("item".equals(x.getName()) && cur != null) {
                    cur.title = clean(cur.title);
                    cur.desc = cut(clean(cur.desc), 420);
                    if (!cur.title.isEmpty()) out.add(cur);
                    cur = null;
                }
                tag = null;
            } else if ((e == XmlPullParser.TEXT || e == XmlPullParser.CDSECT) && cur != null && tag != null) {
                String t = x.getText();
                switch (tag) {
                    case "title": cur.title += t; break;
                    case "description": cur.desc += t; break;
                    case "link": cur.link += t.trim(); break;
                    case "category": cur.cat += t.trim() + ";"; break;
                    case "content:encoded": cur.full += t; break;
                    case "pubDate":
                        try { cur.time = rfc.parse(t.trim()).getTime(); } catch (Exception ignored) { }
                        break;
                }
            }
        }
        return out;
    }

    private static String clean(String s) {
        String t = Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString();
        return t.replace('\uFFFC', ' ').replaceAll("\\s+", " ").trim();
    }

    private static String cut(String s, int max) {
        if (s.length() <= max) return s;
        int dot = s.lastIndexOf(". ", max);
        if (dot > max / 2) return s.substring(0, dot + 1);
        int sp = s.lastIndexOf(' ', max);
        return s.substring(0, sp > 0 ? sp : max) + "…";
    }

    // ---------- статья целиком ----------

    /** Полный текст статьи на русском. В фоновом потоке. null, если достать не удалось. */
    static String article(Context c, Item it, boolean english) {
        if (it.link.isEmpty()) return null;
        String done = readCache(c, "a", it.link);
        if (done != null) return done;
        try {
            String body = english ? readCache(c, "e", it.link) : null;
            if (body == null) body = extract(Net.page(it.link), false);
            if (body.length() < 200) return null;
            if (english) {
                String ru = googleLong(body);
                if (ru == null) return null;
                body = ru;
            }
            writeCache(c, "a", it.link, body);
            return body;
        } catch (Exception e) {
            Log.w("TvHome", "article " + it.link + " " + e);
            return null;
        }
    }

    private static final String[] JUNK = {"Читайте также", "Подпишитесь", "Telegram", "Фото:", "Источник фото",
            "Newsletter", "Sign up", "Subscribe", "Related", "Image:", "Credit:", "Getty Images", "Ars Technica",
            "TechCrunch Disrupt", "Register now", "Save up to", "Techcrunch event", "All rights reserved"};

    /** Достать абзацы основного текста из HTML страницы или из content:encoded. */
    static String extract(String html, boolean whole) {
        String region = html;
        if (!whole) {
            int a = -1;
            for (String mark : new String[]{"itemprop=\"articleBody\"", "entry-content", "post-content", "article-content", "<article"}) {
                a = html.indexOf(mark);
                if (a >= 0) break;
            }
            if (a >= 0) {
                int b = html.indexOf("</article>", a);
                region = html.substring(a, b > a ? b : html.length());
            }
        }
        region = region.replaceAll("(?is)<(script|style|figure|figcaption|aside|nav|footer)[^>]*>.*?</\\1>", " ");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?is)<p[^>]*>(.*?)</p>").matcher(region);
        StringBuilder sb = new StringBuilder();
        while (m.find() && sb.length() < 14000) {
            String t = clean(m.group(1));
            if (t.length() < 40) continue;
            boolean junk = false;
            for (String j : JUNK) if (t.startsWith(j) || (t.length() < 160 && t.contains(j))) { junk = true; break; }
            if (junk) continue;
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(t);
        }
        return sb.toString();
    }

    private static java.io.File cacheFile(Context c, String kind, String link) {
        java.io.File dir = new java.io.File(c.getCacheDir(), "news");
        dir.mkdirs();
        return new java.io.File(dir, kind + "_" + Integer.toHexString(link.hashCode()) + ".txt");
    }

    static String readCache(Context c, String kind, String link) {
        try {
            java.io.File f = cacheFile(c, kind, link);
            if (!f.exists()) return null;
            byte[] b = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int n = 0;
            while (n < b.length) { int r = in.read(b, n, b.length - n); if (r < 0) break; n += r; }
            in.close();
            return new String(b, 0, n, "UTF-8");
        } catch (Exception e) { return null; }
    }

    static void writeCache(Context c, String kind, String link, String text) {
        try {
            java.io.FileOutputStream o = new java.io.FileOutputStream(cacheFile(c, kind, link));
            o.write(text.getBytes("UTF-8"));
            o.close();
            // чистка: файлы старше трёх дней
            java.io.File[] all = cacheFile(c, kind, link).getParentFile().listFiles();
            long old = System.currentTimeMillis() - 3L * 24 * 3600 * 1000;
            if (all != null) for (java.io.File f : all) if (f.lastModified() < old) f.delete();
        } catch (Exception ignored) { }
    }

    /** Перевод длинного текста частями по абзацам. */
    private static String googleLong(String text) {
        String[] paras = text.split("\n\n");
        StringBuilder out = new StringBuilder(), chunk = new StringBuilder();
        for (int i = 0; i <= paras.length; i++) {
            boolean last = i == paras.length;
            if (!last && chunk.length() + paras[i].length() < 3500) {
                if (chunk.length() > 0) chunk.append("\n");
                chunk.append(paras[i]);
                continue;
            }
            if (chunk.length() > 0) {
                String tr = googlePost(chunk.toString());
                if (tr == null) return out.length() > 0 ? out.toString() : null;
                if (out.length() > 0) out.append("\n\n");
                out.append(tr.trim().replace("\n", "\n\n"));
                chunk.setLength(0);
            }
            if (!last) chunk.append(paras[i]);
        }
        return out.toString();
    }

    private static String googlePost(String text) {
        try {
            String r = null;
            for (int tryN = 0; r == null; tryN++) {
                try {
                    r = Net.post("https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=ru&dt=t",
                            "q=" + URLEncoder.encode(text, "UTF-8"), 10000);
                } catch (Exception e) {
                    if (tryN >= 2 || !String.valueOf(e.getMessage()).contains("429")) throw e;
                    Thread.sleep(2000L * (tryN + 1));
                }
            }
            JSONArray segs = new JSONArray(r).getJSONArray(0);
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < segs.length(); k++) {
                JSONArray s = segs.optJSONArray(k);
                if (s != null && !s.isNull(0)) sb.append(s.getString(0));
            }
            return sb.length() > 0 ? sb.toString() : null;
        } catch (Exception e) {
            Log.w("TvHome", "google post " + e);
            return null;
        }
    }

    // ---------- перевод ----------

    private static void translate(Context c, List<Item> items) {
        JSONObject cache;
        try { cache = new JSONObject(p(c).getString("news_tr", "{}")); } catch (Exception e) { cache = new JSONObject(); }
        JSONObject keep = cache.length() > 400 ? new JSONObject() : cache;
        for (Item i : items) {
            try {
                String key = i.link.isEmpty() ? i.title : i.link;
                String tr = cache.optString(key, null);
                if (tr == null) { tr = google(i.title + "\n" + i.desc); Thread.sleep(150); }
                if (tr == null) continue;
                keep.put(key, tr);
                String[] two = tr.split("\n", 2);
                i.title = two[0].trim();
                i.desc = two.length > 1 ? two[1].trim() : "";
            } catch (Exception e) { Log.w("TvHome", "translate " + e); }
        }
        p(c).edit().putString("news_tr", keep.toString()).apply();
    }

    private static String google(String text) {
        try {
            String url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=ru&dt=t&q="
                    + URLEncoder.encode(text, "UTF-8");
            String r = null;
            for (int tryN = 0; r == null; tryN++) {
                try { r = Net.get(url, 6000); }
                catch (Exception e) {
                    if (tryN >= 2 || !String.valueOf(e.getMessage()).contains("429")) throw e;
                    Thread.sleep(2000L * (tryN + 1));
                }
            }
            JSONArray segs = new JSONArray(r).getJSONArray(0);
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < segs.length(); k++) {
                JSONArray s = segs.optJSONArray(k);
                if (s != null && !s.isNull(0)) sb.append(s.getString(0));
            }
            return sb.length() > 0 ? sb.toString() : null;
        } catch (Exception e) {
            Log.w("TvHome", "google tr " + e);
            return null;
        }
    }

    static String time(long t) {
        if (t <= 0) return "";
        SimpleDateFormat today = new SimpleDateFormat("yyyyMMdd", Locale.US);
        java.util.Date d = new java.util.Date(t);
        boolean same = today.format(d).equals(today.format(new java.util.Date()));
        return new SimpleDateFormat(same ? "HH:mm" : "d MMM, HH:mm", new Locale("ru")).format(d);
    }
}
