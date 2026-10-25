package ru.mikulinskiy.tvhome;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

/**
 * Таймер сна. Срабатывание: широковещательное сообщение приёмнику Яндекса
 * com.yandex.tv.settings/.timer.SleepBroadcastReceiver. Он работает от системного
 * пользователя и на любое сообщение, кроме BOOT_COMPLETED, нажимает POWER.
 * VPN гасит KeyService по выключению экрана.
 */
public class Sleep extends BroadcastReceiver {

    static final String FIRE = "ru.mikulinskiy.tvhome.SLEEP_FIRE";
    static final String WARN = "ru.mikulinskiy.tvhome.SLEEP_WARN";
    private static final String KEY = "sleep_at"; // elapsedRealtime срабатывания

    private static PendingIntent pi(Context c, String action) {
        Intent i = new Intent(c, Sleep.class).setAction(action);
        return PendingIntent.getBroadcast(c, action.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
    }

    static void set(Context c, int minutes) {
        cancel(c);
        long at = SystemClock.elapsedRealtime() + minutes * 60_000L;
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi(c, FIRE));
        if (minutes > 1) am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at - 60_000L, pi(c, WARN));
        p(c).edit().putLong(KEY, at).apply();
        Log.i("TvHome", "sleep in " + minutes + " min");
    }

    static void cancel(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        am.cancel(pi(c, FIRE));
        am.cancel(pi(c, WARN));
        p(c).edit().remove(KEY).apply();
    }

    /** Минут до выключения, 0 если таймер не стоит. */
    static int left(Context c) {
        long at = p(c).getLong(KEY, 0);
        long ms = at - SystemClock.elapsedRealtime();
        if (at == 0 || ms <= 0) return 0;
        return (int) ((ms + 59_999) / 60_000);
    }

    static boolean active(Context c) { return left(c) > 0; }

    /** Выключить ТВ сейчас (в режим ожидания, как кнопкой). */
    static void powerOff(Context c) {
        p(c).edit().remove(KEY).apply();
        try {
            Intent i = new Intent("ru.mikulinskiy.tvhome.SLEEP");
            i.setComponent(new ComponentName("com.yandex.tv.settings",
                    "com.yandex.tv.settings.timer.SleepBroadcastReceiver"));
            i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            c.sendBroadcast(i);
            Log.i("TvHome", "sleep: power sent");
        } catch (Exception e) { Log.w("TvHome", "sleep power " + e); }
    }

    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (WARN.equals(a)) {
            Toast.makeText(c, "Таймер сна: ТВ выключится через минуту. Продлить: Ярлыки → Сон", Toast.LENGTH_LONG).show();
        } else if (FIRE.equals(a)) {
            powerOff(c);
        }
    }
}
