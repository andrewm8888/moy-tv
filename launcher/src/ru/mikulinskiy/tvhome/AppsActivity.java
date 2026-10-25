package ru.mikulinskiy.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Все установленные приложения сеткой. Долгое нажатие: на главную / убрать / удалить. */
public class AppsActivity extends Activity {

    private GridLayout grid;
    private String focusAfter;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Context c = this;
        ScrollView scroll = new ScrollView(c);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.BitmapDrawable(getResources(),
                Backdrop.render(this, Backdrop.current(this))));
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(c, 56), Ui.dp(c, 30), Ui.dp(c, 56), Ui.dp(c, 30));
        col.setClipChildren(false);
        col.setClipToPadding(false);
        scroll.addView(col);
        col.addView(Ui.label(c, "мой тв · библиотека"));
        col.addView(Ui.text(c, "Все приложения", 28, Ui.TEXT, "unbounded", 500));
        TextView hint = Ui.text(c, "Долгое нажатие: остановить, показать на главной, убрать, удалить. Зелёная точка: работает в фоне, можно остановить. ★ — уже на главной", 13, Ui.TEXT_DIM, "golos", 400);
        hint.setPadding(0, Ui.dp(c, 12), 0, 0);
        col.addView(hint);
        grid = new GridLayout(c);
        grid.setColumnCount(6);
        grid.setPadding(0, Ui.dp(c, 22), 0, 0);
        grid.setClipChildren(false);
        col.addView(grid);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        fill();
    }

    private void fill() {
        final Context c = this;
        final PackageManager pm = getPackageManager();
        grid.removeAllViews();
        badges.clear();
        List<ResolveInfo> apps = MainActivity.launchables(pm);
        // Встроенный YouTube Яндекса не имеет обычной иконки: добавим вручную
        Intent yt = MainActivity.launchIntent(pm, "com.yandex.tv.ytplayer");
        if (yt != null) {
            List<ResolveInfo> r = pm.queryIntentActivities(yt, 0);
            boolean have = false;
            for (ResolveInfo x : apps) if ("com.yandex.tv.ytplayer".equals(x.activityInfo.packageName)) have = true;
            if (!r.isEmpty() && !have) apps.add(r.get(0));
        }
        // Убираем повторы по названию (два «YouTube.com»)
        List<String> names = new java.util.ArrayList<>();
        for (java.util.Iterator<ResolveInfo> it = apps.iterator(); it.hasNext(); ) {
            String n = String.valueOf(it.next().loadLabel(pm)).trim().toLowerCase(Locale.ROOT);
            if (names.contains(n)) it.remove(); else names.add(n);
        }
        final Collator coll = Collator.getInstance(new Locale("ru", "RU"));
        Collections.sort(apps, new Comparator<ResolveInfo>() {
            @Override public int compare(ResolveInfo a, ResolveInfo b2) {
                return coll.compare(String.valueOf(a.loadLabel(pm)), String.valueOf(b2.loadLabel(pm)));
            }
        });
        View first = null, target = null;
        for (ResolveInfo r : apps) {
            final String pkg = r.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            final String label = String.valueOf(r.loadLabel(pm));
            android.widget.FrameLayout t = new android.widget.FrameLayout(c);
            LinearLayout in = new LinearLayout(c);
            in.setOrientation(LinearLayout.VERTICAL);
            in.setGravity(Gravity.CENTER);
            in.setPadding(Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8));
            t.addView(in, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            View badge = MainActivity.runBadge(c);
            android.widget.FrameLayout.LayoutParams bl = new android.widget.FrameLayout.LayoutParams(Ui.dp(c, 8), Ui.dp(c, 8));
            bl.gravity = Gravity.TOP | Gravity.END;
            bl.setMargins(0, Ui.dp(c, 8), Ui.dp(c, 8), 0);
            t.addView(badge, bl);
            badges.put(pkg, badge);
            ImageView img = new ImageView(c);
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            Drawable d = MainActivity.artwork(pm, pkg);
            if (d != null) img.setImageDrawable(d);
            in.addView(img, new LinearLayout.LayoutParams(Ui.dp(c, 104), Ui.dp(c, 54)));
            String shown = (Home.contains(c, pkg) ? "★ " : "") + label;
            TextView l = Ui.text(c, shown, 12, Ui.TEXT, "golos", 500);
            l.setGravity(Gravity.CENTER);
            l.setSingleLine(true);
            l.setPadding(0, Ui.dp(c, 6), 0, 0);
            in.addView(l, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = Ui.dp(c, 122);
            lp.height = Ui.dp(c, 100);
            lp.setMargins(Ui.dp(c, 4), Ui.dp(c, 4), Ui.dp(c, 10), Ui.dp(c, 10));
            t.setLayoutParams(lp);
            Ui.focusable(t, 18);
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Intent i = MainActivity.launchIntent(pm, pkg);
                    if (i == null) { Toast.makeText(c, "Не открывается", Toast.LENGTH_SHORT).show(); return; }
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                }
            });
            t.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) { menu(pkg, label); return true; }
            });
            grid.addView(t);
            if (first == null) first = t;
            if (pkg.equals(focusAfter)) target = t;
        }
        View f = target != null ? target : first;
        if (f != null) f.requestFocus();
        refreshRunning();
    }

    private final java.util.Map<String, View> badges = new java.util.HashMap<>();

    private void refreshRunning() {
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override public void run() {
                final java.util.Set<String> run = Procs.stoppable(AppsActivity.this);
                h.post(new Runnable() {
                    @Override public void run() {
                        for (java.util.Map.Entry<String, View> e : badges.entrySet())
                            e.getValue().setVisibility(run.contains(e.getKey()) ? View.VISIBLE : View.GONE);
                    }
                });
            }
        }).start();
    }

    private void menu(final String pkg, final String label) {
        final boolean onHome = Home.contains(this, pkg);
        String[] items = {"Остановить", onHome ? "Убрать с главной" : "Показать на главной", "Удалить приложение"};
        Ui.menu(this, label, items, new Ui.OnPick() {
            @Override public void pick(int w) {
                focusAfter = pkg;
                if (w == 0) {
                    Procs.stop(AppsActivity.this, pkg, label, new Runnable() { @Override public void run() { refreshRunning(); } });
                    return;
                }
                int which = w - 1;
                if (which == 0) {
                    if (onHome) Home.remove(AppsActivity.this, pkg); else Home.add(AppsActivity.this, pkg);
                    Toast.makeText(AppsActivity.this, onHome ? "Убрано с главной" : "Добавлено на главную",
                            Toast.LENGTH_SHORT).show();
                    fill();
                } else {
                    focusAfter = null;
                    Intent i = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg));
                    try {
                        startActivity(i);
                    } catch (Exception e) {
                        Toast.makeText(AppsActivity.this, "Система не дала удалить: " + e.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                }
            }
        });
    }
}
