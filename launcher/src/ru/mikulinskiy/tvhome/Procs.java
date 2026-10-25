package ru.mikulinskiy.tvhome;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Какие приложения сейчас в памяти и как их остановить. Нужно право DUMP (выдаётся через adb). */
final class Procs {
    private Procs() {}

    private static boolean logged;
    private static final Pattern REC = Pattern.compile("ProcessRecord\\{[0-9a-f]+ \\d+:([A-Za-z0-9_.]+)(?::[^/\\s}]+)?/");

    /** Пакеты, у которых есть живой процесс. Вызывать не в главном потоке. */
    static Set<String> running() {
        Set<String> out = new HashSet<>();
        Process p = null;
        try {
            p = new ProcessBuilder("/system/bin/dumpsys", "activity", "processes").redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line, firstLines = "";
            int n = 0;
            while ((line = r.readLine()) != null) {
                if (n++ < 3) firstLines += line + " | ";
                Matcher m = REC.matcher(line);
                while (m.find()) out.add(m.group(1));
            }
            if (!logged || out.isEmpty()) {
                logged = true;
                Log.i("TvHome", "procs lines=" + n + " pkgs=" + out.size() + " head: " + firstLines);
            }
        } catch (Exception e) {
            Log.w("TvHome", "procs " + e);
        } finally {
            if (p != null) p.destroy();
        }
        return out;
    }

    private static final Pattern SVC = Pattern.compile("ServiceRecord\\{[0-9a-f]+ u\\d+ ([A-Za-z0-9_.]+)/");

    /** Пакеты с фоновой службой на переднем плане (VPN, торрент-сервер): их кнопкой не остановить. */
    static Set<String> foregroundServices() {
        Set<String> out = new HashSet<>();
        Process p = null;
        try {
            p = new ProcessBuilder("/system/bin/dumpsys", "activity", "services").redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line, cur = null;
            while ((line = r.readLine()) != null) {
                Matcher m = SVC.matcher(line);
                if (m.find()) cur = m.group(1);
                else if (cur != null && line.contains("isForeground=true")) out.add(cur);
            }
        } catch (Exception e) {
            Log.w("TvHome", "fgs " + e);
        } finally {
            if (p != null) p.destroy();
        }
        return out;
    }

    /** Что можно остановить кнопкой: процесс жив, нет службы на переднем плане, не системное постоянное. */
    static Set<String> stoppable(Context c) {
        Set<String> run = running();
        run.removeAll(foregroundServices());
        run.remove(c.getPackageName());
        android.content.pm.PackageManager pm = c.getPackageManager();
        for (java.util.Iterator<String> it = run.iterator(); it.hasNext(); ) {
            try {
                int f = pm.getApplicationInfo(it.next(), 0).flags;
                if ((f & android.content.pm.ApplicationInfo.FLAG_PERSISTENT) != 0) it.remove();
            } catch (Exception e) { it.remove(); }
        }
        return run;
    }

    /** Остановить приложение. Если держит службу и не останавливается, открыть «О приложении». */
    static void stop(final Activity a, final String pkg, final String label, final Runnable after) {
        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    ActivityManager am = (ActivityManager) a.getSystemService(Context.ACTIVITY_SERVICE);
                    am.killBackgroundProcesses(pkg);
                    Thread.sleep(700);
                    am.killBackgroundProcesses(pkg);
                    Thread.sleep(500);
                } catch (Exception e) { Log.w("TvHome", "stop " + e); }
                final boolean alive = running().contains(pkg);
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (!alive) {
                            Toast.makeText(a, label + ": остановлено", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(a, label + " держит фоновую службу. Нажмите «Остановить» на следующем экране",
                                    Toast.LENGTH_LONG).show();
                            try {
                                a.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:" + pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                            } catch (Exception e) { Log.w("TvHome", "details " + e); }
                        }
                        if (after != null) after.run();
                    }
                });
            }
        }).start();
    }
}
