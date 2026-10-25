package ru.mikulinskiy.tvhome;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Погода в едином виде. Два источника: Open-Meteo и MET Norway (yr.no). */
final class WeatherData {
    // Координаты задаёт City.load(); часовой пояс берём из настроек телевизора
    static volatile double LAT = City.DEF_LAT, LON = City.DEF_LON;
    static TimeZone tz() { return TimeZone.getDefault(); }

    static void setPlace(double lat, double lon) { LAT = lat; LON = lon; }

    String source;
    int temp, code, max, min;
    boolean night;
    final List<String> hTime = new ArrayList<>();
    final List<Integer> hTemp = new ArrayList<>(), hCode = new ArrayList<>();
    final List<Boolean> hNight = new ArrayList<>();

    // ---------- Источник 1: Open-Meteo ----------
    static String openMeteoUrl() {
        String z;
        try { z = java.net.URLEncoder.encode(tz().getID(), "UTF-8"); } catch (Exception e) { z = "auto"; }
        return "https://api.open-meteo.com/v1/forecast"
            + "?latitude=" + LAT + "&longitude=" + LON
            + "&current=temperature_2m,weather_code,is_day"
            + "&hourly=temperature_2m,weather_code,is_day"
            + "&daily=temperature_2m_max,temperature_2m_min"
            + "&timezone=" + z + "&forecast_days=2";
    }

    static WeatherData fromOpenMeteo(String json) throws Exception {
        JSONObject o = new JSONObject(json);
        WeatherData w = new WeatherData();
        w.source = "Open-Meteo";
        JSONObject cur = o.getJSONObject("current");
        w.temp = (int) Math.round(cur.getDouble("temperature_2m"));
        w.code = cur.getInt("weather_code");
        w.night = cur.optInt("is_day", 1) == 0;
        JSONObject d = o.getJSONObject("daily");
        w.max = (int) Math.round(d.getJSONArray("temperature_2m_max").getDouble(0));
        w.min = (int) Math.round(d.getJSONArray("temperature_2m_min").getDouble(0));
        JSONObject h = o.getJSONObject("hourly");
        JSONArray t = h.getJSONArray("time");
        SimpleDateFormat key = new SimpleDateFormat("yyyy-MM-dd'T'HH", Locale.US);
        key.setTimeZone(tz());
        String now = key.format(new Date());
        int start = -1;
        for (int i = 0; i < t.length(); i++) if (t.getString(i).startsWith(now)) { start = i + 1; break; }
        if (start < 0) start = 0;
        for (int i = start; i < Math.min(start + 6, t.length()); i++) {
            w.hTime.add(t.getString(i).substring(11, 16));
            w.hTemp.add((int) Math.round(h.getJSONArray("temperature_2m").getDouble(i)));
            w.hCode.add(h.getJSONArray("weather_code").getInt(i));
            w.hNight.add(h.getJSONArray("is_day").getInt(i) == 0);
        }
        return w;
    }

    // ---------- Источник 2: MET Norway ----------
    static String metNoUrl() {
        return String.format(Locale.US, "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=%.4f&lon=%.4f", LAT, LON);
    }

    static WeatherData fromMetNo(String json) throws Exception {
        JSONArray ts = new JSONObject(json).getJSONObject("properties").getJSONArray("timeseries");
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.US);
        hm.setTimeZone(tz());
        SimpleDateFormat day = new SimpleDateFormat("yyyyMMdd", Locale.US);
        day.setTimeZone(tz());
        long now = System.currentTimeMillis();
        String today = day.format(new Date(now));

        WeatherData w = new WeatherData();
        w.source = "MET Norway";
        w.max = -999; w.min = 999;
        boolean currentSet = false;
        for (int i = 0; i < ts.length(); i++) {
            JSONObject e = ts.getJSONObject(i);
            Date t = iso.parse(e.getString("time"));
            JSONObject data = e.getJSONObject("data");
            int temp = (int) Math.round(data.getJSONObject("instant").getJSONObject("details").getDouble("air_temperature"));
            String sym = "";
            JSONObject n1 = data.optJSONObject("next_1_hours");
            if (n1 == null) n1 = data.optJSONObject("next_6_hours");
            if (n1 != null) sym = n1.getJSONObject("summary").optString("symbol_code", "");
            if (day.format(t).equals(today)) { w.max = Math.max(w.max, temp); w.min = Math.min(w.min, temp); }
            if (!currentSet && t.getTime() > now - 3600 * 1000L) {
                w.temp = temp; w.code = metToWmo(sym); w.night = sym.endsWith("_night"); currentSet = true;
                continue;
            }
            if (currentSet && w.hTime.size() < 6 && t.getTime() > now) {
                w.hTime.add(hm.format(t));
                w.hTemp.add(temp);
                w.hCode.add(metToWmo(sym));
                w.hNight.add(sym.endsWith("_night"));
            }
        }
        if (w.max == -999) { w.max = w.temp; w.min = w.temp; }
        if (!currentSet) throw new Exception("no data");
        return w;
    }

    static int metToWmo(String s) {
        if (s.contains("thunder")) return 95;
        if (s.contains("snowshowers")) return 85;
        if (s.contains("snow")) return s.contains("heavy") ? 75 : 71;
        if (s.contains("sleet")) return 66;
        if (s.contains("rainshowers")) return 80;
        if (s.contains("rain")) return s.contains("heavy") ? 65 : (s.contains("light") ? 61 : 63);
        if (s.contains("fog")) return 45;
        if (s.startsWith("partlycloudy")) return 2;
        if (s.startsWith("cloudy")) return 3;
        if (s.startsWith("fair")) return 1;
        if (s.startsWith("clearsky")) return 0;
        return 3;
    }

    // ---------- Восход и закат: считаем сами (формулы NOAA) ----------
    static String[] sun() {
        Calendar c = Calendar.getInstance(tz());
        int n = c.get(Calendar.DAY_OF_YEAR);
        double g = 2 * Math.PI / 365 * (n - 1);
        double eq = 229.18 * (0.000075 + 0.001868 * Math.cos(g) - 0.032077 * Math.sin(g)
                - 0.014615 * Math.cos(2 * g) - 0.040849 * Math.sin(2 * g));
        double decl = 0.006918 - 0.399912 * Math.cos(g) + 0.070257 * Math.sin(g) - 0.006758 * Math.cos(2 * g)
                + 0.000907 * Math.sin(2 * g) - 0.002697 * Math.cos(3 * g) + 0.00148 * Math.sin(3 * g);
        double lat = Math.toRadians(LAT);  // широта выбранного города
        double cosHa = Math.cos(Math.toRadians(90.833)) / (Math.cos(lat) * Math.cos(decl)) - Math.tan(lat) * Math.tan(decl);
        cosHa = Math.max(-1, Math.min(1, cosHa));
        double ha = Math.toDegrees(Math.acos(cosHa));
        int offset = tz().getOffset(c.getTimeInMillis()) / 60000;
        double rise = 720 - 4 * (LON + ha) - eq + offset;
        double set = 720 - 4 * (LON - ha) - eq + offset;
        return new String[]{hhmm(rise), hhmm(set)};
    }

    private static String hhmm(double minutes) {
        int m = (int) Math.round(minutes);
        m = ((m % 1440) + 1440) % 1440;
        return String.format(Locale.US, "%02d:%02d", m / 60, m % 60);
    }

    // ---------- Кэш ----------
    String toJson() throws Exception {
        JSONObject o = new JSONObject();
        o.put("source", source).put("temp", temp).put("code", code).put("night", night)
                .put("max", max).put("min", min);
        o.put("hTime", new JSONArray(hTime)).put("hTemp", new JSONArray(hTemp))
                .put("hCode", new JSONArray(hCode)).put("hNight", new JSONArray(hNight));
        return o.toString();
    }

    static WeatherData fromJson(String s) throws Exception {
        JSONObject o = new JSONObject(s);
        WeatherData w = new WeatherData();
        w.source = o.optString("source");
        w.temp = o.getInt("temp"); w.code = o.getInt("code"); w.night = o.getBoolean("night");
        w.max = o.getInt("max"); w.min = o.getInt("min");
        JSONArray a = o.getJSONArray("hTime"), b = o.getJSONArray("hTemp"), c = o.getJSONArray("hCode"), d = o.getJSONArray("hNight");
        for (int i = 0; i < a.length(); i++) {
            w.hTime.add(a.getString(i)); w.hTemp.add(b.getInt(i)); w.hCode.add(c.getInt(i)); w.hNight.add(d.getBoolean(i));
        }
        return w;
    }
}
