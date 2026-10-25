import javax.imageio.*;
import javax.imageio.stream.*;
import java.awt.*;
import java.awt.image.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;

/** Подбор сезонных фонов с Wikimedia Commons (избранные и качественные снимки), обрезка 1920x1080, притемнение. */
public class BgFetch {
    static final String UA = "MoyTV-launcher/1.0 (https://github.com/andrewm8888/moy-tv; TV wallpaper tool)";
    static final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
    static final String[][] SEASONS = {
        {"aut", "Осень", "autumn forest|autumn leaves|autumn fog|autumn sunset|fall foliage|autumn lake|autumn mountains"},
        {"win", "Зима", "winter night|snowy forest|winter landscape|winter sunset|snowy mountains|frozen lake|winter evening"},
        {"spr", "Весна", "spring blossom|spring meadow|cherry blossom|spring forest|tulip field|spring landscape|apple blossom"},
        {"sum", "Лето", "summer night|summer sunset field|lavender field|summer lake|milky way|summer evening|wheat field sunset"}};

    // не подходят по смыслу (отобраны по цвету, но это не пейзаж) — проверено глазами по списку
    static final String[] EXCLUDE = {"BMW", "Sepia", "Kirmes", "Breite Straße", "Restaurants", "Torminbrücke"};
    static final Set<String> used = new HashSet<>();

    public static void main(String[] a) throws Exception {
        Path out = Paths.get(a[0]);           // launcher/assets/bg
        Path cache = Paths.get(a[1]);         // bg/season
        Files.createDirectories(cache);
        Map<String, String> names = new LinkedHashMap<>();
        Path nj = out.resolve("names.json");
        // старые подписи космоса (массив) переносим
        String old = new String(Files.readAllBytes(nj), StandardCharsets.UTF_8).trim();
        Object o = new Json(old).parse();
        if (o instanceof List) { List<?> l = (List<?>) o; for (int i = 0; i < l.size(); i++) names.put(String.format("%02d.jpg", i + 1), (String) l.get(i)); }
        else for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) if (Character.isDigit(e.getKey().charAt(0))) names.put(e.getKey(), (String) e.getValue());
        for (String[] s : SEASONS) {
            System.out.println("######## " + s[1]);
            Map<String, Cand> found = new LinkedHashMap<>();
            for (String q : s[2].split("\\|")) {
                for (String cat : new String[]{"Featured pictures on Wikimedia Commons", "Quality images"}) {
                    try { search(q + " incategory:\"" + cat + "\"", found); } catch (Exception e) { System.out.println("поиск " + q + ": " + e); }
                }
            }
            System.out.println("кандидатов: " + found.size());
            List<Cand> ok = new ArrayList<>();
            for (Cand c : found.values()) {
                if (c.w < 1600 || (double) c.w / c.h < 1.3 || (double) c.w / c.h > 2.4) continue;
                try {
                    Path f = cache.resolve(s[0] + "_" + Integer.toHexString(c.title.hashCode()) + ".jpg");
                    if (!Files.exists(f)) {
                        HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create(c.url)).header("User-Agent", UA).build(), HttpResponse.BodyHandlers.ofByteArray());
                        if (r.statusCode() != 200) continue;
                        Files.write(f, r.body());
                        Thread.sleep(300);
                    }
                    c.file = f;
                    BufferedImage img = ImageIO.read(f.toFile());
                    if (img == null) continue;
                    c.score(img, s[0]);
                    ok.add(c);
                } catch (Exception e) { System.out.println("скачать " + c.title + ": " + e); }
                if (ok.size() >= 30) break;
            }
            ok.sort((x, y) -> Double.compare(y.score, x.score));
            for (Path p : Files.newDirectoryStream(out, s[0] + "*.jpg")) Files.delete(p);
            names.keySet().removeIf(k -> k.startsWith(s[0]));
            int n = 0;
            for (Cand c : ok) {
                if (n >= 10) break;
                boolean bad = used.contains(c.title);
                for (String x : EXCLUDE) if (c.title.contains(x)) bad = true;
                if (bad) continue;
                used.add(c.title);
                n++;
                String fn = String.format("%s%02d.jpg", s[0], n);
                save(ImageIO.read(c.file.toFile()), out.resolve(fn));
                String nm = s[1] + " · " + c.artist + " (" + c.license + ", Wikimedia Commons)";
                names.put(fn, nm);
                System.out.printf("%s  score %.0f lum %d warm %d blue %d green %d  %s%n", fn, c.score, c.lum, c.warm, c.blue, c.green, c.title);
            }
        }
        StringBuilder sb = new StringBuilder("{");
        for (Map.Entry<String, String> e : names.entrySet()) {
            if (sb.length() > 1) sb.append(",\n");
            sb.append(q(e.getKey())).append(":").append(q(e.getValue()));
        }
        Files.write(nj, (sb + "}").getBytes(StandardCharsets.UTF_8));
        long total = 0; for (Path p : Files.newDirectoryStream(out, "*.jpg")) total += Files.size(p);
        System.out.println("фонов: " + (names.size()) + ", всего " + total / 1024 + " КБ");
    }

    static String q(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }

    static class Cand {
        String title, url, license = "", artist = ""; int w, h; Path file; double score; int lum, warm, blue, green, bottom;
        void score(BufferedImage img, String season) {
            BufferedImage t = new BufferedImage(160, 90, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = t.createGraphics(); g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(img, 0, 0, 160, 90, null); g.dispose();
            long sum = 0, bsum = 0; int wn = 0, bn = 0, gn = 0, tot = 0; float[] hsv = new float[3];
            for (int y = 0; y < 90; y++) for (int x = 0; x < 160; x++) {
                int p = t.getRGB(x, y), r = (p >> 16) & 255, gg = (p >> 8) & 255, b = p & 255;
                int l = (int) (0.2126 * r + 0.7152 * gg + 0.0722 * b); sum += l; if (y >= 45) bsum += l;
                Color.RGBtoHSB(r, gg, b, hsv);
                if (hsv[2] < 0.15f || hsv[1] < 0.12f) continue;
                tot++; float d = hsv[0] * 360;
                if (d >= 15 && d < 55) wn++; else if (d >= 180 && d < 260) bn++; else if (d >= 70 && d < 170) gn++;
            }
            lum = (int) (sum / 14400); bottom = (int) (bsum / 7200);
            tot = Math.max(1, tot); warm = wn * 100 / tot; blue = bn * 100 / tot; green = gn * 100 / tot;
            // настроение лаунчера: скорее тёмные и мягкие кадры, в палитре оранжевого и голубого
            double s = 100 - Math.abs(lum - 75) * 0.8 - Math.max(0, bottom - 110) * 0.6;
            switch (season) {
                case "aut": s += warm * 0.8 - green * 0.5; break;
                case "win": s += blue * 0.7 + Math.min(warm, 25) * 0.3 - green * 0.6; break;
                case "spr": s += Math.min(green, 50) * 0.3 + warm * 0.3 + blue * 0.2; break;
                case "sum": s += warm * 0.5 + blue * 0.4 + Math.min(green, 40) * 0.2; break;
            }
            score = s;
        }
    }

    static void search(String query, Map<String, Cand> found) throws Exception {
        String url = "https://commons.wikimedia.org/w/api.php?action=query&format=json&formatversion=2&generator=search&gsrnamespace=6&gsrlimit=25"
                + "&gsrsearch=" + URLEncoder.encode(query + " filetype:bitmap", "UTF-8")
                + "&prop=imageinfo&iiprop=url%7Csize%7Cextmetadata&iiurlwidth=1920&iiextmetadatafilter=LicenseShortName%7CArtist";
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).header("User-Agent", UA).build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200 || !r.body().trim().startsWith("{")) throw new Exception("HTTP " + r.statusCode());
        Map<String, Object> root = (Map<String, Object>) new Json(r.body()).parse();
        Map<String, Object> qq = (Map<String, Object>) root.get("query");
        if (qq == null) return;
        for (Object po : (List<?>) qq.get("pages")) {
            Map<String, Object> p = (Map<String, Object>) po;
            List<?> ii = (List<?>) p.get("imageinfo");
            if (ii == null || ii.isEmpty()) continue;
            Map<String, Object> i = (Map<String, Object>) ii.get(0);
            Cand c = new Cand();
            c.title = (String) p.get("title");
            c.url = (String) i.get("thumburl");
            c.w = ((Number) i.get("width")).intValue(); c.h = ((Number) i.get("height")).intValue();
            Map<String, Object> em = (Map<String, Object>) i.get("extmetadata");
            if (em != null) {
                Map<String, Object> lic = (Map<String, Object>) em.get("LicenseShortName");
                Map<String, Object> art = (Map<String, Object>) em.get("Artist");
                if (lic != null) c.license = String.valueOf(lic.get("value"));
                if (art != null) c.artist = String.valueOf(art.get("value")).replaceAll("<[^>]*>", "").replaceAll("\\s+", " ").trim();
                if (c.artist.length() > 40) c.artist = c.artist.substring(0, 40) + "…";
            }
            if (c.url != null && !found.containsKey(c.title)) found.put(c.title, c);
        }
    }

    static void save(BufferedImage src, Path out) throws Exception {
        int W = 1920, H = 1080;
        double sc = Math.max((double) W / src.getWidth(), (double) H / src.getHeight());
        int sw = (int) Math.ceil(src.getWidth() * sc), sh = (int) Math.ceil(src.getHeight() * sc);
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, (W - sw) / 2, (H - sh) / 2, sw, sh, null);
        // общая яркость к уровню лаунчера
        long sum = 0; for (int y = 0; y < H; y += 8) for (int x = 0; x < W; x += 8) { int p = img.getRGB(x, y); sum += (int) (0.2126 * ((p >> 16) & 255) + 0.7152 * ((p >> 8) & 255) + 0.0722 * (p & 255)); }
        double mean = sum / (double) ((H / 8) * (W / 8));
        float k = (float) Math.max(0.42, Math.min(1.0, 52 / Math.max(1, mean)));
        g.setColor(new Color(6, 8, 12, Math.round((1 - k) * 255)));
        g.fillRect(0, 0, W, H);
        // плавное затемнение снизу (под плитки) и сверху (под шапку)
        for (int y = 0; y < H; y++) {
            int al = 0;
            if (y > 486) al = (int) (160 * Math.pow((y - 486) / 594.0, 1.2));
            if (y < 160) al = Math.max(al, (int) (80 * (1 - y / 160.0)));
            if (al > 0) { g.setColor(new Color(6, 8, 12, Math.min(255, al))); g.fillRect(0, y, W, 1); }
        }
        g.dispose();
        ImageWriter wr = ImageIO.getImageWritersByFormatName("jpg").next();
        ImageWriteParam pr = wr.getDefaultWriteParam(); pr.setCompressionMode(ImageWriteParam.MODE_EXPLICIT); pr.setCompressionQuality(0.8f);
        try (ImageOutputStream os = ImageIO.createImageOutputStream(out.toFile())) { wr.setOutput(os); wr.write(null, new IIOImage(img, null, null), pr); }
        wr.dispose();
    }

    /** Мини-разбор JSON. */
    static class Json {
        final String s; int i;
        Json(String s) { this.s = s; }
        Object parse() { ws(); char c = s.charAt(i);
            if (c == '{') { i++; Map<String, Object> m = new LinkedHashMap<>(); ws(); if (s.charAt(i) == '}') { i++; return m; }
                while (true) { ws(); String k = str(); ws(); i++; m.put(k, parse()); ws(); if (s.charAt(i++) == '}') return m; } }
            if (c == '[') { i++; List<Object> l = new ArrayList<>(); ws(); if (s.charAt(i) == ']') { i++; return l; }
                while (true) { l.add(parse()); ws(); if (s.charAt(i++) == ']') return l; } }
            if (c == '"') return str();
            if (s.startsWith("true", i)) { i += 4; return true; }
            if (s.startsWith("false", i)) { i += 5; return false; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int st = i; while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            return Double.parseDouble(s.substring(st, i)); }
        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        String str() { StringBuilder b = new StringBuilder(); i++;
            while (true) { char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c == '\\') { char e = s.charAt(i++);
                    switch (e) { case 'n': b.append('\n'); break; case 't': b.append('\t'); break; case 'r': break; case 'b': case 'f': break;
                        case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break; default: b.append(e); } }
                else b.append(c); } }
    }
}
