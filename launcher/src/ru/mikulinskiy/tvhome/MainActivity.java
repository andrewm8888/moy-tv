package ru.mikulinskiy.tvhome;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    static final String PREFS = "tvhome";
    private static final Locale RU = new Locale("ru", "RU");
    private static final String RATES_URL = "https://www.cbr-xml-daily.ru/daily_json.js";
    private static final String HDMI_PREFIX = "com.mediatek.tvinput/.hdmi.HDMIInputService/HW";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService net2 = Executors.newSingleThreadExecutor();
    private final java.util.Map<String, View> badges = new java.util.HashMap<>();
    private boolean lastOnline = true;
    private SharedPreferences prefs;

    private TextView clock, date, weatherLine, usd, eur, cny, ratesTitle, sleepSub, appsTitle;
    private TextView wTemp, wDesc, wMinMax, wSun, wCity;
    private TextView vpnText, vpnCountry, vpnMs;
    private View vpnDot, vpnTrack, vpnThumb;
    private boolean vpnOn;
    private View vpnPillView;
    private long vpnBusyUntil = 0;
    private LinearLayout hourly, appsRow;
    private View firstApp;
    private long lastFetch = 0;
    private int bgVariant = -1;
    private NewsBlock news;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        City.load(this);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        setContentView(build());
        renderWeather(prefs.getString("weather2", null));
        renderRates(prefs.getString("rates", null));
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyBackdrop();
        buildApps();
        if (vpnPillView != null)
            vpnPillView.setVisibility(launchIntent(getPackageManager(), Vpn.HAPP) != null ? View.VISIBLE : View.GONE);
        if (news != null) news.refreshIfStale();
        tick.run();
        vpnTick.run();
        runTick.run();
        if (System.currentTimeMillis() - lastFetch > 20 * 60 * 1000) fetch();
        ui.post(new Runnable() { @Override public void run() { if (firstApp != null) firstApp.requestFocus(); } });
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(tick);
        ui.removeCallbacks(vpnTick);
        ui.removeCallbacks(runTick);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (firstApp != null) firstApp.requestFocus();
    }

    @Override
    public void onBackPressed() { /* домашний экран: назад некуда */ }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (getBacklight() == 0) {
            if (e.getAction() == KeyEvent.ACTION_UP) setBacklight(100, false);
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    // ---------- Фон ----------

    private void applyBackdrop() {
        final int v = Backdrop.current(this);
        if (v == bgVariant) return;
        bgVariant = v;
        io.execute(new Runnable() {
            @Override public void run() {
                final Bitmap bmp = Backdrop.render(MainActivity.this, v);
                ui.post(new Runnable() {
                    @Override public void run() {
                        BitmapDrawable d = new BitmapDrawable(getResources(), bmp);
                        d.setFilterBitmap(true);
                        getWindow().setBackgroundDrawable(d);
                    }
                });
            }
        });
    }

    // ---------- Вёрстка ----------

    private View build() {
        final Context c = this;
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Ui.BG));
        ScrollView scroll = new ScrollView(c);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(c, 56), Ui.dp(c, 26), Ui.dp(c, 56), Ui.dp(c, 32));
        col.setClipChildren(false);
        col.setClipToPadding(false);
        scroll.addView(col);

        // Верхняя строка: бренд | VPN
        LinearLayout top = new LinearLayout(c);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView dot = Ui.text(c, "●", 12, Ui.ACCENT, "golos", 400);
        TextView brand = Ui.text(c, "  мой тв", 15, Ui.TEXT, "unbounded", 500);
        top.addView(dot);
        top.addView(brand);
        View flex = new View(c);
        top.addView(flex, new LinearLayout.LayoutParams(0, 1, 1));
        vpnPillView = vpnPill(c);
        top.addView(vpnPillView);
        col.addView(top);

        View hair = new View(c);
        hair.setBackgroundColor(Ui.HAIR);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
        hlp.setMargins(0, Ui.dp(c, 14), 0, Ui.dp(c, 18));
        col.addView(hair, hlp);

        // Шапка: дата, часы, погода | курсы
        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.BOTTOM);
        LinearLayout left = new LinearLayout(c);
        left.setOrientation(LinearLayout.VERTICAL);
        date = Ui.label(c, "");
        clock = Ui.text(c, "", 88, Ui.TEXT, "unbounded", 500);
        clock.setLetterSpacing(-0.02f);
        weatherLine = Ui.text(c, "", 17, Ui.TEXT, "golos", 400);
        weatherLine.setPadding(0, Ui.dp(c, 12), 0, 0);
        weatherLine.setVisibility(View.GONE);
        left.addView(date);
        left.addView(clock);
        left.addView(weatherLine);
        head.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        LinearLayout right = new LinearLayout(c);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        ratesTitle = Ui.label(c, "Курс ЦБ");
        right.addView(ratesTitle);
        usd = Ui.text(c, "$ …", 22, Ui.TEXT, "golos", 500);
        eur = Ui.text(c, "€ …", 22, Ui.TEXT, "golos", 500);
        cny = Ui.text(c, "¥ …", 22, Ui.TEXT, "golos", 500);
        eur.setPadding(0, Ui.dp(c, 6), 0, 0);
        cny.setPadding(0, Ui.dp(c, 6), 0, Ui.dp(c, 4));
        right.addView(usd);
        right.addView(eur);
        right.addView(cny);
        head.addView(right);
        col.addView(head);

        // Приложения
        col.addView(spacer(c, 26));
        appsTitle = Ui.label(c, "Приложения");
        col.addView(appsTitle);
        HorizontalScrollView hs = new HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipChildren(false);
        hs.setClipToPadding(false);
        appsRow = new LinearLayout(c);
        appsRow.setOrientation(LinearLayout.HORIZONTAL);
        appsRow.setClipChildren(false);
        appsRow.setPadding(Ui.dp(c, 4), Ui.dp(c, 8), Ui.dp(c, 4), Ui.dp(c, 8));
        hs.addView(appsRow);
        col.addView(hs);

        // Входы и ярлыки
        col.addView(spacer(c, 16));
        HorizontalScrollView hs2 = new HorizontalScrollView(c);
        hs2.setHorizontalScrollBarEnabled(false);
        hs2.setClipChildren(false);
        hs2.setClipToPadding(false);
        LinearLayout bottom = new LinearLayout(c);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setClipChildren(false);
        bottom.setPadding(Ui.dp(c, 2), 0, Ui.dp(c, 2), 0);
        hs2.addView(bottom);

        LinearLayout inputs = section(c, "Входы");
        LinearLayout inRow = row(c);
        for (int hw = 6; hw <= 8; hw++) {
            final String id = HDMI_PREFIX + hw;
            String custom = customLabel(id);
            String title = custom != null ? custom : "HDMI " + (hw - 5);
            String sub = custom != null ? "HDMI " + (hw - 5) : "вход";
            inRow.addView(shortcut(c, title, sub, new Runnable() { @Override public void run() { openInput(id); } }));
        }
        inputs.addView(inRow);
        bottom.addView(inputs);
        bottom.addView(spacer(c, 28));

        LinearLayout shortcuts = section(c, "Ярлыки");
        LinearLayout scRow = row(c);
        scRow.addView(shortcut(c, "Все", "приложения", new Runnable() { @Override public void run() {
            startActivity(new Intent(MainActivity.this, AppsActivity.class)); } }));
        scRow.addView(shortcut(c, "Очистка", "память", new Runnable() { @Override public void run() { clean(); } }));
        scRow.addView(shortcut(c, "100%", "подсветка", new Runnable() { @Override public void run() { setBacklight(100, true); } }));
        scRow.addView(shortcut(c, "50%", "подсветка", new Runnable() { @Override public void run() { setBacklight(50, true); } }));
        scRow.addView(shortcut(c, "0%", "экран тёмный", new Runnable() { @Override public void run() { setBacklight(0, true); } }));
        scRow.addView(shortcut(c, "Фон", "сменить", new Runnable() { @Override public void run() { backdropMenu(); } }));
        View sleepTile = shortcut(c, "Сон", "таймер", new Runnable() { @Override public void run() { sleepMenu(); } });
        sleepSub = (TextView) ((ViewGroup) sleepTile).getChildAt(1);
        scRow.addView(sleepTile);
        scRow.addView(shortcut(c, "Настройки", "телевизор", new Runnable() { @Override public void run() { openSettings(); } }));
        View last = shortcut(c, "Телефон", "пульт и ввод", new Runnable() { @Override public void run() { phoneInfo(); } });
        scRow.addView(last);
        stopRight(last);
        shortcuts.addView(scRow);
        bottom.addView(shortcuts);
        col.addView(hs2);

        // Погода
        col.addView(spacer(c, 20));
        col.addView(weatherCard(c));

        // Новости
        col.addView(spacer(c, 26));
        news = new NewsBlock(this);
        col.addView(news.build(scroll));
        return scroll;
    }

    private View vpnPill(Context c) {
        LinearLayout p = new LinearLayout(c);
        p.setOrientation(LinearLayout.HORIZONTAL);
        p.setGravity(Gravity.CENTER_VERTICAL);
        p.setPadding(Ui.dp(c, 16), Ui.dp(c, 8), Ui.dp(c, 10), Ui.dp(c, 8));
        vpnDot = new View(c);
        vpnDot.setBackground(Ui.shape(c, Ui.TEXT_DIM, 0, 0, 999));
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(Ui.dp(c, 9), Ui.dp(c, 9));
        dl.setMargins(0, 0, Ui.dp(c, 8), 0);
        p.addView(vpnDot, dl);
        // Колонки фиксированной ширины: статус | страна | задержка | тумблер
        vpnText = Ui.text(c, "VPN", 13, Ui.TEXT, "mono", 600);
        vpnText.setSingleLine(true);
        p.addView(vpnText, new LinearLayout.LayoutParams(Ui.dp(c, 36), ViewGroup.LayoutParams.WRAP_CONTENT));
        p.addView(vSep(c));
        vpnCountry = Ui.text(c, "…", 14, Ui.TEXT, "golos", 500);
        vpnCountry.setSingleLine(true);
        vpnCountry.setEllipsize(android.text.TextUtils.TruncateAt.END);
        p.addView(vpnCountry, new LinearLayout.LayoutParams(Ui.dp(c, 128), ViewGroup.LayoutParams.WRAP_CONTENT));
        p.addView(vSep(c));
        vpnMs = Ui.text(c, "", 13, Ui.TEXT_DIM, "mono", 500);
        vpnMs.setSingleLine(true);
        vpnMs.setGravity(Gravity.END);
        p.addView(vpnMs, new LinearLayout.LayoutParams(Ui.dp(c, 66), ViewGroup.LayoutParams.WRAP_CONTENT));
        // Тумблер
        android.widget.FrameLayout sw = new android.widget.FrameLayout(c);
        vpnTrack = new View(c);
        vpnTrack.setBackground(Ui.shape(c, 0x33FFFFFF, Ui.HAIR, 1, 999));
        sw.addView(vpnTrack, new android.widget.FrameLayout.LayoutParams(Ui.dp(c, 40), Ui.dp(c, 22)));
        vpnThumb = new View(c);
        vpnThumb.setBackground(Ui.shape(c, Ui.TEXT, 0, 0, 999));
        android.widget.FrameLayout.LayoutParams tl = new android.widget.FrameLayout.LayoutParams(Ui.dp(c, 16), Ui.dp(c, 16));
        tl.gravity = Gravity.CENTER_VERTICAL;
        tl.leftMargin = Ui.dp(c, 3);
        sw.addView(vpnThumb, tl);
        LinearLayout.LayoutParams swl = new LinearLayout.LayoutParams(Ui.dp(c, 40), Ui.dp(c, 22));
        swl.setMargins(Ui.dp(c, 12), 0, 0, 0);
        p.addView(sw, swl);

        Ui.focusable(p, 999);
        p.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleVpn(); }
        });
        p.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { launch(Vpn.HAPP); return true; }
        });
        return p;
    }

    private View vSep(Context c) {
        View v = new View(c);
        v.setBackgroundColor(Ui.HAIR);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(1, Ui.dp(c, 16));
        lp.setMargins(Ui.dp(c, 10), 0, Ui.dp(c, 10), 0);
        v.setLayoutParams(lp);
        return v;
    }

    private void toggleVpn() {
        final boolean target = !vpnOn;
        try {
            Vpn.set(this, target);
        } catch (Exception e) {
            toast("Happ не ответил, открываю его");
            launch(Vpn.HAPP);
            return;
        }
        setSwitch(target);
        vpnBusyUntil = System.currentTimeMillis() + 12000;
        vpnText.setText("VPN");
        vpnCountry.setText(target ? "подключаюсь" : "отключаю");
        vpnCountry.setTextColor(Ui.TEXT_DIM);
        vpnMs.setText("");
        ui.postDelayed(new Runnable() { @Override public void run() { checkVpn(); } }, 2500);
        ui.postDelayed(new Runnable() { @Override public void run() { checkVpn(); } }, 6000);
        ui.postDelayed(new Runnable() { @Override public void run() { checkVpn(); } }, 12500);
    }

    private void setSwitch(boolean on) {
        vpnOn = on;
        vpnTrack.setBackground(Ui.shape(this, on ? Ui.ACCENT : 0x33FFFFFF, on ? Ui.ACCENT : Ui.HAIR, 1, 999));
        vpnThumb.animate().translationX(on ? Ui.dp(this, 18) : 0).setDuration(150).start();
    }

    private View weatherCard(Context c) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(Ui.dp(c, 26), Ui.dp(c, 18), Ui.dp(c, 26), Ui.dp(c, 18));
        LinearLayout now = new LinearLayout(c);
        now.setOrientation(LinearLayout.VERTICAL);
        wCity = Ui.label(c, City.chosen(c) ? City.name(c) : City.name(c) + " · ОК — выбрать город");
        now.addView(wCity);
        wTemp = Ui.text(c, "…", 36, Ui.TEXT, "unbounded", 500);
        wDesc = Ui.text(c, "", 15, Ui.TEXT, "golos", 400);
        wDesc.setPadding(0, Ui.dp(c, 8), 0, Ui.dp(c, 4));
        wMinMax = Ui.text(c, "", 13, Ui.TEXT_DIM, "golos", 400);
        now.addView(wTemp);
        now.addView(wDesc);
        now.addView(wMinMax);
        card.addView(now, new LinearLayout.LayoutParams(Ui.dp(c, 230), ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout right = new LinearLayout(c);
        right.setOrientation(LinearLayout.VERTICAL);
        hourly = new LinearLayout(c);
        hourly.setOrientation(LinearLayout.HORIZONTAL);
        right.addView(hourly, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        wSun = Ui.text(c, "", 12, Ui.TEXT_DIM, "mono", 400);
        wSun.setPadding(Ui.dp(c, 8), Ui.dp(c, 12), 0, 0);
        right.addView(wSun);
        card.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Ui.focusable(card, 24);
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { weatherMenu(); }
        });
        return card;
    }

    private LinearLayout section(Context c, String title) {
        LinearLayout s = new LinearLayout(c);
        s.setOrientation(LinearLayout.VERTICAL);
        s.setClipChildren(false);
        s.addView(Ui.label(c, title));
        return s;
    }

    private LinearLayout row(Context c) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setClipChildren(false);
        r.setPadding(Ui.dp(c, 4), Ui.dp(c, 8), Ui.dp(c, 4), Ui.dp(c, 8));
        return r;
    }

    private View spacer(Context c, int dp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(c, dp), Ui.dp(c, dp)));
        return v;
    }

    /** Ярлык: крупное слово + подпись моноширинным. */
    private View shortcut(Context c, String title, String sub, final Runnable action) {
        LinearLayout t = new LinearLayout(c);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(Ui.dp(c, 16), 0, Ui.dp(c, 12), 0);
        TextView a = Ui.text(c, title, 15, Ui.TEXT, "golos", 600);
        a.setSingleLine(true);
        TextView b = Ui.text(c, sub.toUpperCase(RU), 9, Ui.TEXT_DIM, "mono", 500);
        b.setLetterSpacing(0.12f);
        b.setSingleLine(true);
        b.setPadding(0, Ui.dp(c, 6), 0, 0);
        t.addView(a);
        t.addView(b);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(c, 112), Ui.dp(c, 64));
        lp.setMargins(0, 0, Ui.dp(c, 10), 0);
        t.setLayoutParams(lp);
        Ui.focusable(t, 18);
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { action.run(); }
        });
        return t;
    }

    // ---------- Приложения ----------

    private void buildApps() {
        appsRow.removeAllViews();
        badges.clear();
        firstApp = null;
        PackageManager pm = getPackageManager();
        for (String pkg : Home.list(this)) {
            if (launchIntent(pm, pkg) == null) continue;
            View tile = appTile(pm, Home.label(pm, pkg), pkg);
            appsRow.addView(tile);
            if (firstApp == null) firstApp = tile;
        }
        int n = appsRow.getChildCount();
        if (n > 0) stopRight(appsRow.getChildAt(n - 1));
        appsTitle.setText(("Приложения · " + n).toUpperCase(RU));
    }

    private static void stopRight(View v) {
        if (v.getId() == View.NO_ID) v.setId(View.generateViewId());
        v.setNextFocusRightId(v.getId());
    }

    private void appMenu(final String pkg, String label) {
        final String lb = label;
        Ui.menu(this, label, new String[]{"Остановить", "Скрыть с главной", "Сдвинуть влево", "Сдвинуть вправо"}, new Ui.OnPick() {
            @Override public void pick(int w) {
                if (w == 0) {
                    Procs.stop(MainActivity.this, pkg, lb, new Runnable() { @Override public void run() { refreshRunning(); } });
                    return;
                }
                int which = w - 1;
                if (which == 0) { Home.remove(MainActivity.this, pkg); toast("Скрыто. Вернуть: Ярлыки → Все приложения"); }
                if (which == 1) Home.move(MainActivity.this, pkg, -1);
                if (which == 2) Home.move(MainActivity.this, pkg, 1);
                buildApps();
                focusPkg(pkg);
            }
        });
    }

    private void focusPkg(String pkg) {
        for (int i = 0; i < appsRow.getChildCount(); i++) {
            View v = appsRow.getChildAt(i);
            if (pkg.equals(v.getTag())) { v.requestFocus(); return; }
        }
        if (firstApp != null) firstApp.requestFocus();
    }

    static List<ResolveInfo> launchables(PackageManager pm) {
        List<ResolveInfo> out = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            for (ResolveInfo r : pm.queryIntentActivities(i, 0)) {
                String p = r.activityInfo.packageName;
                if (seen.contains(p)) continue;
                seen.add(p);
                out.add(r);
            }
        }
        return out;
    }

    static Intent launchIntent(PackageManager pm, String pkg) {
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) {
            Intent m = new Intent(Intent.ACTION_MAIN).setPackage(pkg);
            List<ResolveInfo> r = pm.queryIntentActivities(m, 0);
            for (ResolveInfo ri : r) if (ri.activityInfo.exported) {
                i = new Intent(Intent.ACTION_MAIN).setClassName(pkg, ri.activityInfo.name); break;
            }
        }
        if (i == null) {
            Intent v = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/")).setPackage(pkg);
            if (!pm.queryIntentActivities(v, 0).isEmpty()) i = v;
        }
        return i;
    }

    static Drawable artwork(PackageManager pm, String pkg) {
        try {
            Drawable d = pm.getApplicationBanner(pkg);
            if (d == null) {
                Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
                if (i != null) d = pm.getActivityBanner(i);
            }
            if (d == null) d = pm.getApplicationIcon(pkg);
            return d;
        } catch (Exception e) {
            return null;
        }
    }

    private View appTile(PackageManager pm, String label, final String pkg) {
        Context c = this;
        android.widget.FrameLayout t = new android.widget.FrameLayout(c);
        LinearLayout in = new LinearLayout(c);
        in.setOrientation(LinearLayout.VERTICAL);
        in.setGravity(Gravity.CENTER);
        in.setPadding(Ui.dp(c, 10), Ui.dp(c, 10), Ui.dp(c, 10), Ui.dp(c, 8));
        ImageView img = new ImageView(c);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        img.setClipToOutline(true);
        img.setBackground(Ui.shape(c, 0x00000000, 0, 0, 12));
        Drawable d = artwork(pm, pkg);
        if (d != null) img.setImageDrawable(d);
        in.addView(img, new LinearLayout.LayoutParams(Ui.dp(c, 140), Ui.dp(c, 70)));
        TextView l = Ui.text(c, label, 12, Ui.TEXT, "golos", 500);
        l.setGravity(Gravity.CENTER);
        l.setPadding(0, Ui.dp(c, 8), 0, 0);
        in.addView(l);
        t.addView(in, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View badge = runBadge(c);
        android.widget.FrameLayout.LayoutParams bl = new android.widget.FrameLayout.LayoutParams(Ui.dp(c, 9), Ui.dp(c, 9));
        bl.gravity = Gravity.TOP | Gravity.END;
        bl.setMargins(0, Ui.dp(c, 9), Ui.dp(c, 9), 0);
        t.addView(badge, bl);
        badges.put(pkg, badge);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(c, 164), Ui.dp(c, 116));
        lp.setMargins(0, 0, Ui.dp(c, 12), 0);
        t.setLayoutParams(lp);
        Ui.focusable(t, 20);
        t.setTag(pkg);
        final String lbl = label;
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { launch(pkg); }
        });
        t.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { appMenu(pkg, lbl); return true; }
        });
        return t;
    }

    /** Значок «запущено»: зелёная точка с тёмной обводкой, по умолчанию скрыт. */
    static View runBadge(Context c) {
        View b = new View(c);
        b.setBackground(Ui.shape(c, 0xFF3DDC84, 0xFF06080C, 1.5f, 999));
        b.setElevation(Ui.dp(c, 4));
        b.setVisibility(View.GONE);
        return b;
    }

    private void refreshRunning() {
        io.execute(new Runnable() {
            @Override public void run() {
                final java.util.Set<String> run = Procs.stoppable(MainActivity.this);
                ui.post(new Runnable() {
                    @Override public void run() {
                        for (java.util.Map.Entry<String, View> e : badges.entrySet())
                            e.getValue().setVisibility(run.contains(e.getKey()) ? View.VISIBLE : View.GONE);
                    }
                });
            }
        });
    }

    private final Runnable runTick = new Runnable() {
        @Override public void run() {
            refreshRunning();
            ui.removeCallbacks(this);
            ui.postDelayed(this, 8 * 1000);
        }
    };

    void launch(String pkg) {
        Intent i = launchIntent(getPackageManager(), pkg);
        if (i == null) { toast("Приложение не найдено"); return; }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); } catch (Exception e) { toast("Не удалось открыть: " + e.getMessage()); }
    }

    // ---------- VPN ----------

    private final Runnable vpnTick = new Runnable() {
        @Override public void run() {
            checkVpn();
            ui.removeCallbacks(this);
            ui.postDelayed(this, lastOnline ? 60 * 1000 : 10 * 1000);
        }
    };

    private void checkVpn() {
        final boolean up = Vpn.tunnelUp(this);
        showVpn(up, null);
        net2.execute(new Runnable() {
            @Override public void run() {
                final Vpn.Status s = Vpn.check(MainActivity.this);
                lastOnline = s.online;
                ui.post(new Runnable() { @Override public void run() { showVpn(s.on, s); } });
            }
        });
    }

    private void showVpn(boolean on, Vpn.Status s) {
        boolean busy = System.currentTimeMillis() < vpnBusyUntil;
        if (busy && (s == null || !s.online || s.on != vpnOn)) return;
        vpnBusyUntil = 0;
        setSwitch(on);
        int color;
        vpnText.setText("VPN");
        vpnText.setTextColor(on ? Ui.TEXT : Ui.TEXT_DIM);
        if (s == null) {
            color = on ? Ui.ICE : Ui.TEXT_DIM;
            vpnCountry.setText("проверяю");
            vpnCountry.setTextColor(Ui.TEXT_DIM);
            vpnMs.setText("");
        } else if (!s.online) {
            color = Ui.DOWN;
            vpnCountry.setText(on ? "нет связи" : (Net.hasNetwork(this) ? "жду интернет…" : "нет сети"));
            vpnCountry.setTextColor(Ui.DOWN);
            vpnMs.setText("—");
        } else {
            color = on ? Ui.UP : Ui.TEXT_DIM;
            vpnCountry.setText(s.flag + "  " + s.country);
            vpnCountry.setTextColor(Ui.TEXT);
            vpnMs.setText(Math.min(s.ms, 9999) + " мс");
            vpnMs.setTextColor(s.ms > 9999 ? 0xFFB0303C : s.ms < 150 ? Ui.UP : (s.ms < 400 ? Ui.ACCENT : Ui.DOWN));
        }
        vpnDot.setBackground(Ui.shape(this, color, 0, 0, 999));
    }

    // ---------- Входы ----------

    private String customLabel(String inputId) {
        try {
            String s = Settings.Secure.getString(getContentResolver(), "tv_input_custom_labels");
            if (s == null) return null;
            for (String part : s.split(":")) {
                String[] kv = part.split(",", 2);
                if (kv.length == 2 && Uri.decode(kv[0]).equals(inputId) && kv[1].length() > 0) return kv[1];
            }
        } catch (Exception ignored) { }
        return null;
    }

    private void openInput(String inputId) {
        String enc = Uri.encode(inputId);
        String[] uris = {"live-tv://android.media.tv/passthrough/" + enc, "content://android.media.tv/passthrough/" + enc};
        for (String u : uris) {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(u));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { startActivity(i); return; } catch (Exception e) { Log.w("TvHome", "input " + u, e); }
        }
        try {
            startActivity(new Intent("yandex.intent.action.TV_SOURCE").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            toast("Не удалось переключить вход");
        }
    }

    private void phoneInfo() {
        String u = Remote.url(this);
        String warn = KeyService.isEnabled(this) ? "" : "  (служба «Мой ТВ» выключена, ввод не сработает)";
        Ui.menu(this, "Откройте на телефоне в той же Wi-Fi сети:", new String[]{u + warn, "Закрыть"}, new Ui.OnPick() {
            @Override public void pick(int which) { }
        });
    }

    // ---------- Фон ----------

    private android.app.Dialog bgDialog;

    /** Меню «Фон»: справа, почти без затемнения, чтобы видеть снимок. Группы отдельными кнопками. */
    private void backdropMenu() { backdropMenu(0); }

    private void backdropMenu(final int focusIndex) {
        final Context c = this;
        if (bgDialog != null && bgDialog.isShowing()) bgDialog.dismiss();
        final android.app.Dialog d = new android.app.Dialog(this);
        bgDialog = d;
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(c, 24), Ui.dp(c, 20), Ui.dp(c, 24), Ui.dp(c, 18));
        box.setBackground(Ui.shape(c, Ui.PANEL, Ui.HAIR, 1, 22));

        final int cur = Backdrop.current(this);
        final boolean daily = Backdrop.daily(this);
        final String grp = Backdrop.group(this);
        TextView t = Ui.text(c, Backdrop.name(this, cur), 14, Ui.TEXT, "golos", 600);
        t.setMaxLines(3);
        box.addView(t);
        TextView st = Ui.text(c, ("группа: " + Backdrop.groupName(grp)
                + (grp.equals("season") ? " (" + Backdrop.groupName(Backdrop.season()) + ")" : "")
                + " · " + (daily ? "меняется каждый день" : "закреплён")).toUpperCase(RU), 10, Ui.TEXT_DIM, "mono", 500);
        st.setLetterSpacing(0.1f);
        st.setPadding(0, Ui.dp(c, 6), 0, Ui.dp(c, 12));
        box.addView(st);

        final List<View> all = new ArrayList<>();
        // ряд 1: листать
        box.addView(chipRow(c, all, new String[]{"◀ Предыдущий", "Следующий ▶"}, null, new Ui.OnPick() {
            @Override public void pick(int w) { Backdrop.step(c, w == 0 ? -1 : 1); bgApply(w); }
        }));
        // ряды 2–3: группы
        box.addView(Ui.label(c, "Группа"));
        String[] g1 = new String[4], g2 = new String[3];
        for (int i = 0; i < 4; i++) g1[i] = Backdrop.GROUPS[i][1] + " " + Backdrop.count(c, Backdrop.GROUPS[i][0]);
        for (int i = 0; i < 3; i++) g2[i] = Backdrop.GROUPS[4 + i][1] + " " + Backdrop.count(c, Backdrop.GROUPS[4 + i][0]);
        g1[1] = "По сезону";
        box.addView(chipRow(c, all, g1, grp, new Ui.OnPick() {
            @Override public void pick(int w) { Backdrop.setGroup(c, Backdrop.GROUPS[w][0]); bgApply(2 + w); }
        }));
        box.addView(chipRow(c, all, g2, grp, new Ui.OnPick() {
            @Override public void pick(int w) { Backdrop.setGroup(c, Backdrop.GROUPS[4 + w][0]); bgApply(6 + w); }
        }));
        // ряд 4: прочее
        final int hid = Backdrop.hiddenCount(c);
        String[] r4 = hid > 0
                ? new String[]{daily ? "Закрепить этот" : "Каждый день", "Убрать этот", "Тёмный", "Вернуть " + hid}
                : new String[]{daily ? "Закрепить этот" : "Каждый день", "Убрать этот", "Тёмный"};
        LinearLayout row4 = chipRow(c, all, r4, null, new Ui.OnPick() {
            @Override public void pick(int w) {
                if (w == 0) { if (daily) Backdrop.fix(c, cur); else Backdrop.setDaily(c); }
                if (w == 1) Backdrop.hide(c, cur);
                if (w == 2) Backdrop.fix(c, Backdrop.DARK);
                if (w == 3) Backdrop.unhideAll(c);
                bgApply(9 + w);
            }
        });
        row4.setPadding(0, Ui.dp(c, 10), 0, 0);
        box.addView(row4);

        d.setContentView(box, new ViewGroup.LayoutParams(Ui.dp(c, 470), ViewGroup.LayoutParams.WRAP_CONTENT));
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            w.setDimAmount(0.1f);
            w.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        }
        d.show();
        all.get(Math.min(focusIndex, all.size() - 1)).requestFocus();
    }

    private void bgApply(int focusIndex) {
        bgVariant = Integer.MIN_VALUE;
        applyBackdrop();
        backdropMenu(focusIndex);
    }

    /** Ряд кнопок-«таблеток»; активная группа подсвечена оранжевым. */
    private LinearLayout chipRow(Context c, List<View> all, String[] labels, String activeGroup, final Ui.OnPick cb) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 0, 0, Ui.dp(c, 8));
        for (int i = 0; i < labels.length; i++) {
            final int k = i;
            TextView b = Ui.text(c, labels[i], 13, Ui.TEXT, "golos", 500);
            b.setGravity(Gravity.CENTER);
            b.setSingleLine(true);
            b.setPadding(Ui.dp(c, 8), Ui.dp(c, 10), Ui.dp(c, 8), Ui.dp(c, 10));
            Ui.focusable(b, 999);
            if (activeGroup != null) {
                int gi = -1;
                for (int j = 0; j < Backdrop.GROUPS.length; j++) if (labels[i].startsWith(Backdrop.GROUPS[j][1])) gi = j;
                if (gi >= 0 && Backdrop.GROUPS[gi][0].equals(activeGroup)) b.setTextColor(Ui.ACCENT);
            }
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { cb.pick(k); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            lp.setMargins(0, 0, i < labels.length - 1 ? Ui.dp(c, 8) : 0, 0);
            row.addView(b, lp);
            all.add(b);
        }
        return row;
    }

    // ---------- Таймер сна ----------

    private void sleepMenu() {
        final boolean on = Sleep.active(this);
        String title = on ? "Таймер сна: осталось " + Sleep.left(this) + " мин" : "Таймер сна";
        String[] items = on
                ? new String[]{"30 минут", "60 минут", "90 минут", "Отменить таймер", "Выключить сейчас"}
                : new String[]{"30 минут", "60 минут", "90 минут", "Выключить сейчас"};
        Ui.menu(this, title, items, new Ui.OnPick() {
            @Override public void pick(int w) {
                if (w <= 2) {
                    int m = (w + 1) * 30;
                    Sleep.set(MainActivity.this, m);
                    toast("ТВ выключится через " + m + " мин");
                } else if (on && w == 3) {
                    Sleep.cancel(MainActivity.this);
                    toast("Таймер сна отменён");
                } else {
                    Sleep.powerOff(MainActivity.this);
                }
                showSleep();
            }
        });
    }

    private void showSleep() {
        if (sleepSub == null) return;
        int m = Sleep.left(this);
        sleepSub.setText((m > 0 ? "через " + m + " мин" : "таймер").toUpperCase(RU));
        sleepSub.setTextColor(m > 0 ? Ui.ACCENT : Ui.TEXT_DIM);
    }

    private void openSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            toast("Настройки не открылись");
        }
    }

    // ---------- Подсветка ----------

    private int getBacklight() {
        return Settings.Global.getInt(getContentResolver(), "picture_backlight", 100);
    }

    private void setBacklight(int v, boolean notify) {
        try {
            Settings.Global.putInt(getContentResolver(), "picture_backlight", v);
            if (notify && v == 0) toast("Экран погашен. Любая кнопка включит его обратно");
        } catch (SecurityException e) {
            toast("Нет права менять подсветку. Запусти на Mac скрипт установки ещё раз");
        }
    }

    // ---------- Очистка ----------

    private static final String[] KEEP = {
            "android", "com.android.", "com.mediatek.", "mediatek.", "com.ctv.", "com.cultraview.",
            "com.yandex.tv.services", "com.yandex.io.", "com.yandex.tv.settings", "com.yandex.tv.live",
            "com.yandex.tv.input", "com.yandex.server.", "com.yandex.tv.vendor", "com.yandex.prebuiltstub",
            "ru.yandex.androidkeyboard", "su.happ.", "ru.yourok.torrserve", "ru.mikulinskiy.tvhome"
    };

    private void clean() {
        toast("Чищу память…");
        io.execute(new Runnable() {
            @Override public void run() {
                ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
                long before = availMb(am);
                for (ApplicationInfo ai : getPackageManager().getInstalledApplications(0)) {
                    boolean keep = false;
                    for (String k : KEEP) if (ai.packageName.equals(k) || ai.packageName.startsWith(k)) { keep = true; break; }
                    if (!keep) am.killBackgroundProcesses(ai.packageName);
                }
                try { Thread.sleep(1500); } catch (InterruptedException ignored) { }
                final long after = availMb(am);
                final long freed = Math.max(0, after - before);
                ui.post(new Runnable() {
                    @Override public void run() { toast("Освобождено " + freed + " МБ, свободно " + after + " МБ"); }
                });
            }
        });
    }

    private static long availMb(ActivityManager am) {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.availMem / (1024 * 1024);
    }

    // ---------- Часы ----------

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            Date now = new Date();
            // После включения часы ТВ стоят на 1970 годе, пока не придёт время из сети
            if (now.getTime() < 1577836800000L) {
                clock.getPaint().setShader(null);
                clock.setText("--:--");
                date.setText("ЖДУ ТОЧНОЕ ВРЕМЯ ИЗ СЕТИ…");
                ui.removeCallbacks(this);
                ui.postDelayed(this, 2000);
                return;
            }
            String t = new SimpleDateFormat("HH:mm", RU).format(now);
            clock.setText(t);
            float w = clock.getPaint().measureText(t);
            clock.getPaint().setShader(new LinearGradient(0, 0, w, 0,
                    new int[]{Ui.TEXT, Ui.TEXT, Ui.ACCENT, Ui.ICE, Ui.TEXT},
                    new float[]{0f, 0.42f, 0.62f, 0.82f, 1f}, Shader.TileMode.CLAMP));
            clock.invalidate();
            date.setText(new SimpleDateFormat("EEEE · d MMMM", RU).format(now).toUpperCase(RU));
            showSleep();
            applyBackdrop();
            ui.removeCallbacks(this);
            ui.postDelayed(this, 1000 * (60 - (now.getTime() / 1000) % 60));
            if (System.currentTimeMillis() - lastFetch > 30 * 60 * 1000) fetch();
        }
    };

    // ---------- Погода и курсы ----------

    private void weatherMenu() {
        Ui.menu(this, "Погода · " + City.name(this), new String[]{"Обновить погоду и курсы", "Сменить город"}, new Ui.OnPick() {
            @Override public void pick(int w) {
                if (w == 0) { toast("Обновляю погоду и курсы"); fetch(); return; }
                City.pick(MainActivity.this, new Runnable() {
                    @Override public void run() {
                        if (wCity != null) wCity.setText(City.name(MainActivity.this).toUpperCase(RU));
                        wTemp.setText("…"); wDesc.setText(""); wMinMax.setText(""); hourly.removeAllViews();
                        toast("Город: " + City.name(MainActivity.this) + ". Загружаю погоду");
                        fetch();
                    }
                });
            }
        });
    }

    private void fetch() {
        lastFetch = System.currentTimeMillis();
        io.execute(new Runnable() {
            @Override public void run() {
                WeatherData w = null;
                try { w = WeatherData.fromMetNo(Net.get(WeatherData.metNoUrl())); }
                catch (Throwable e) { Log.w("TvHome", "met.no", e); }
                if (w == null) {
                    try { w = WeatherData.fromOpenMeteo(Net.get(WeatherData.openMeteoUrl(), 5000)); }
                    catch (Throwable e) { Log.w("TvHome", "open-meteo", e); }
                }
                if (w != null) {
                    try {
                        final String js = w.toJson();
                        prefs.edit().putString("weather2", js).apply();
                        ui.post(new Runnable() { @Override public void run() { renderWeather(js); } });
                    } catch (Throwable e) { Log.w("TvHome", "weather save", e); }
                }
                try {
                    final String r = Net.get(RATES_URL);
                    prefs.edit().putString("rates", r).apply();
                    ui.post(new Runnable() { @Override public void run() { renderRates(r); } });
                } catch (Throwable e) { Log.w("TvHome", "rates fetch", e); }
            }
        });
    }

    private void renderWeather(String json) {
        if (json == null) return;
        try {
            WeatherData w = WeatherData.fromJson(json);
            String[] sun = WeatherData.sun();
            weatherLine.setText(Weather.icon(w.code, w.night) + "  " + signed(w.temp) + "  " + Weather.text(w.code)
                    + "     макс. " + signed(w.max) + " · мин. " + signed(w.min));
            weatherLine.setVisibility(View.VISIBLE);
            wTemp.setText(Weather.icon(w.code, w.night) + " " + signed(w.temp));
            wDesc.setText(Weather.text(w.code));
            wMinMax.setText("макс. " + signed(w.max) + " · мин. " + signed(w.min));
            wSun.setText(("восход " + sun[0] + "   ·   закат " + sun[1] + "   ·   " + w.source).toUpperCase(RU));
            hourly.removeAllViews();
            Context c = this;
            for (int i = 0; i < w.hTime.size(); i++) {
                LinearLayout cell = new LinearLayout(c);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER);
                TextView hh = Ui.text(c, w.hTime.get(i), 12, Ui.TEXT_DIM, "mono", 400);
                TextView ic = Ui.text(c, Weather.icon(w.hCode.get(i), w.hNight.get(i)), 22, Ui.TEXT, "golos", 400);
                ic.setPadding(0, Ui.dp(c, 8), 0, Ui.dp(c, 8));
                TextView tt = Ui.text(c, signed(w.hTemp.get(i)), 15, Ui.TEXT, "golos", 500);
                cell.addView(hh);
                cell.addView(ic);
                cell.addView(tt);
                hourly.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            }
        } catch (Throwable e) { Log.w("TvHome", "weather render", e); }
    }

    private void renderRates(String json) {
        if (json == null) return;
        try {
            JSONObject v = new JSONObject(json).getJSONObject("Valute");
            rate(usd, "$", v.getJSONObject("USD"));
            rate(eur, "€", v.getJSONObject("EUR"));
            JSONObject y = v.getJSONObject("CNY");
            rate(cny, "¥", y.getDouble("Value") / y.getInt("Nominal"), y.getDouble("Previous") / y.getInt("Nominal"));
            String d = new JSONObject(json).optString("Date", "");
            if (d.length() >= 10) ratesTitle.setText(("Курс ЦБ · на " + d.substring(8, 10) + "." + d.substring(5, 7)).toUpperCase(RU));
        } catch (Exception ignored) { }
    }

    private void rate(TextView tv, String sym, JSONObject o) throws Exception {
        rate(tv, sym, o.getDouble("Value"), o.getDouble("Previous"));
    }

    private void rate(TextView tv, String sym, double val, double prev) {
        double diff = val - prev;
        String arrow = diff >= 0 ? "▲ +" : "▼ ";
        String s = sym + " " + String.format(RU, "%.2f", val) + "   " + arrow + String.format(RU, "%.2f", diff);
        SpannableString sp = new SpannableString(s);
        int from = s.indexOf(arrow.charAt(0));
        sp.setSpan(new ForegroundColorSpan(diff >= 0 ? Ui.UP : Ui.DOWN), from, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sp.setSpan(new RelativeSizeSpan(0.62f), from, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        tv.setText(sp);
    }

    private static String signed(int t) { return (t > 0 ? "+" : "") + t + "°"; }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
}
