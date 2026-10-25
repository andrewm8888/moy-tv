package ru.mikulinskiy.tvhome;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Простая загрузка текста по HTTPS. */
final class Net {
    private Net() {}

    static String get(String url) throws Exception { return get(url, 10000); }

    static String get(String url, int timeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs + 5000);
        c.setUseCaches(false);
        c.setRequestProperty("User-Agent", "MoyTV/1.0 (+https://github.com/andrewm8888/moy-tv)");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    static final String BROWSER_UA = "Mozilla/5.0 (Linux; Android 9; Smart TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    /** Веб-страница: следует переадресациям, кодировку берёт из заголовка или meta. */
    static String page(String url) throws Exception {
        for (int hop = 0; hop < 4; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", BROWSER_UA);
            c.setRequestProperty("Accept-Language", "ru,en;q=0.8");
            try {
                int code = c.getResponseCode();
                if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                    url = new URL(new URL(url), c.getHeaderField("Location")).toString();
                    continue;
                }
                if (code != 200) throw new Exception("HTTP " + code);
                InputStream in = c.getInputStream();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0 && out.size() < 3_000_000) out.write(buf, 0, n);
                byte[] b = out.toByteArray();
                String cs = charset(c.getContentType());
                if (cs == null) {
                    String head = new String(b, 0, Math.min(b.length, 4096), "ISO-8859-1");
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("charset=[\"']?([\\w-]+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(head);
                    cs = m.find() ? m.group(1) : "UTF-8";
                }
                try { return new String(b, cs); } catch (Exception e) { return new String(b, "UTF-8"); }
            } finally {
                c.disconnect();
            }
        }
        throw new Exception("too many redirects");
    }

    private static String charset(String ct) {
        if (ct == null) return null;
        int i = ct.toLowerCase().indexOf("charset=");
        return i < 0 ? null : ct.substring(i + 8).replace("\"", "").trim();
    }

    static String post(String url, String form, int timeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs + 5000);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("User-Agent", BROWSER_UA);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8");
        try {
            c.getOutputStream().write(form.getBytes("UTF-8"));
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    static boolean hasNetwork(android.content.Context c) {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) c.getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
            android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Exception e) { return false; }
    }
}
