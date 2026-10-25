package ru.mikulinskiy.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Город для погоды: хранится в настройках лаунчера, ищется через геокодер Open-Meteo. */
final class City {
    private City() {}

    static final String DEF_NAME = "Москва";
    static final double DEF_LAT = 55.7558, DEF_LON = 37.6173;

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
    }

    static boolean chosen(Context c) { return p(c).contains("city_name"); }
    static String name(Context c) { return p(c).getString("city_name", DEF_NAME); }

    /** Передаёт сохранённые координаты в WeatherData. */
    static void load(Context c) {
        SharedPreferences s = p(c);
        WeatherData.setPlace(Double.longBitsToDouble(s.getLong("city_lat", Double.doubleToLongBits(DEF_LAT))),
                Double.longBitsToDouble(s.getLong("city_lon", Double.doubleToLongBits(DEF_LON))));
    }

    private static void save(Context c, String name, double lat, double lon) {
        p(c).edit().putString("city_name", name)
                .putLong("city_lat", Double.doubleToLongBits(lat))
                .putLong("city_lon", Double.doubleToLongBits(lon))
                .remove("weather2").apply();
        WeatherData.setPlace(lat, lon);
    }

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    /** Окно поиска города. done вызывается после выбора. */
    static void pick(final Activity a, final Runnable done) {
        final Handler ui = new Handler(Looper.getMainLooper());
        final Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(a, 28), Ui.dp(a, 24), Ui.dp(a, 28), Ui.dp(a, 20));
        box.setBackground(Ui.shape(a, Ui.PANEL, Ui.HAIR, 1, 24));
        TextView t = Ui.text(a, "Город для погоды", 18, Ui.TEXT, "unbounded", 500);
        t.setPadding(Ui.dp(a, 4), 0, 0, Ui.dp(a, 6));
        box.addView(t);
        TextView now = Ui.text(a, "Сейчас: " + name(a), 13, Ui.TEXT_DIM, "golos", 400);
        now.setPadding(Ui.dp(a, 4), 0, 0, Ui.dp(a, 14));
        box.addView(now);

        final EditText q = new EditText(a);
        q.setHint("Название, например Тула");
        q.setHintTextColor(Ui.TEXT_DIM);
        q.setTextColor(Ui.TEXT);
        q.setTextSize(16);
        q.setSingleLine(true);
        q.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        q.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        q.setPadding(Ui.dp(a, 18), Ui.dp(a, 12), Ui.dp(a, 18), Ui.dp(a, 12));
        q.setBackground(Ui.shape(a, Ui.TILE, Ui.HAIR, 1, 14));
        box.addView(q, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final TextView find = Ui.text(a, "Найти", 15, Ui.TEXT, "golos", 500);
        find.setPadding(Ui.dp(a, 20), Ui.dp(a, 12), Ui.dp(a, 20), Ui.dp(a, 12));
        Ui.focusable(find, 999);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.setMargins(0, Ui.dp(a, 10), 0, Ui.dp(a, 6));
        box.addView(find, flp);

        final TextView status = Ui.text(a, "", 13, Ui.TEXT_DIM, "golos", 400);
        status.setPadding(Ui.dp(a, 4), Ui.dp(a, 4), 0, Ui.dp(a, 4));
        box.addView(status);
        final LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        box.addView(list);

        final Runnable search = new Runnable() {
            @Override public void run() {
                final String s = q.getText().toString().trim();
                if (s.length() < 2) { status.setText("Введите хотя бы две буквы"); return; }
                status.setText("Ищу…");
                list.removeAllViews();
                IO.execute(new Runnable() {
                    @Override public void run() {
                        JSONArray r = null;
                        String err = null;
                        try {
                            String url = "https://geocoding-api.open-meteo.com/v1/search?count=8&language=ru&format=json&name="
                                    + URLEncoder.encode(s, "UTF-8");
                            r = new JSONObject(Net.get(url, 6000)).optJSONArray("results");
                        } catch (Throwable e) { r = null; }
                        if (r == null || r.length() == 0) {
                            // Запасной поиск: OpenStreetMap Nominatim (Open-Meteo у части провайдеров недоступен)
                            try { r = nominatim(s); } catch (Throwable e) { err = "Нет связи с сервисом поиска"; }
                        }
                        final JSONArray res = r;
                        final String er = err;
                        ui.post(new Runnable() {
                            @Override public void run() { show(a, d, res, er, status, list, done); }
                        });
                    }
                });
            }
        };
        find.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { search.run(); } });
        q.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int id, KeyEvent e) {
                if (id == EditorInfo.IME_ACTION_SEARCH || id == EditorInfo.IME_ACTION_DONE
                        || (e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER)) { search.run(); return true; }
                return false;
            }
        });

        d.setContentView(box, new ViewGroup.LayoutParams(Ui.dp(a, 520), ViewGroup.LayoutParams.WRAP_CONTENT));
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setDimAmount(0.6f);
        }
        d.show();
        q.requestFocus();
    }

    /** Ответ Nominatim в том же виде, что у Open-Meteo: name, admin1, country, latitude, longitude. */
    private static JSONArray nominatim(String q) throws Exception {
        String url = "https://nominatim.openstreetmap.org/search?format=jsonv2&addressdetails=1&limit=8&accept-language=ru&q="
                + URLEncoder.encode(q, "UTF-8");
        JSONArray in = new JSONArray(Net.get(url, 8000));
        JSONArray out = new JSONArray();
        for (int i = 0; i < in.length(); i++) {
            JSONObject o = in.getJSONObject(i);
            String type = o.optString("addresstype", o.optString("type", ""));
            if (!type.matches("city|town|village|hamlet|municipality|suburb|administrative")) continue;
            JSONObject ad = o.optJSONObject("address");
            JSONObject r = new JSONObject();
            String nm = o.optString("name", "");
            if (nm.length() == 0) nm = o.optString("display_name").split(",")[0];
            r.put("name", nm);
            if (ad != null) { r.put("admin1", ad.optString("state", "")); r.put("country", ad.optString("country", "")); }
            r.put("latitude", Double.parseDouble(o.getString("lat")));
            r.put("longitude", Double.parseDouble(o.getString("lon")));
            out.put(r);
        }
        return out;
    }

    private static void show(final Activity a, final Dialog d, JSONArray res, String err,
                             TextView status, LinearLayout list, final Runnable done) {
        list.removeAllViews();
        if (err != null) { status.setText(err); return; }
        if (res == null || res.length() == 0) { status.setText("Ничего не нашлось. Попробуйте другое написание"); return; }
        status.setText("Выберите:");
        View first = null;
        for (int i = 0; i < res.length(); i++) {
            JSONObject o = res.optJSONObject(i);
            if (o == null) continue;
            final String nm = o.optString("name");
            final double lat = o.optDouble("latitude"), lon = o.optDouble("longitude");
            StringBuilder sub = new StringBuilder();
            for (String k : new String[]{"admin1", "country"}) {
                String v = o.optString(k, "");
                if (v.length() > 0 && !v.equals(nm)) { if (sub.length() > 0) sub.append(", "); sub.append(v); }
            }
            TextView b = Ui.text(a, nm + (sub.length() > 0 ? "  ·  " + sub : ""), 14, Ui.TEXT, "golos", 500);
            b.setPadding(Ui.dp(a, 20), Ui.dp(a, 10), Ui.dp(a, 20), Ui.dp(a, 10));
            b.setSingleLine(true);
            Ui.focusable(b, 999);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 0, Ui.dp(a, 6));
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    save(a, nm, lat, lon);
                    d.dismiss();
                    if (done != null) done.run();
                }
            });
            list.addView(b, lp);
            if (first == null) first = b;
        }
        if (first != null) first.requestFocus();
    }
}
