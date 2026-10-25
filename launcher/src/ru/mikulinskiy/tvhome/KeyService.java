package ru.mikulinskiy.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

/** Перехватывает назначенную кнопку пульта и открывает домашний экран. */
public class KeyService extends AccessibilityService {

    private SharedPreferences prefs;
    private Remote remote;

    @Override
    protected void onServiceConnected() {
        prefs = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        remote = new Remote(this);
        remote.start();
        AdbSelf.get(this).warm();
        // Выключение/ожидание: гасим VPN, чтобы после включения не было ошибки Happ
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SHUTDOWN);
        f.addAction("android.intent.action.ACTION_SHUTDOWN");
        registerReceiver(offReceiver, f);
        // Сразу после загрузки ТВ: если Happ поднял туннель сам, выключаем его
        if (SystemClock.elapsedRealtime() < 5 * 60 * 1000) {
            new Handler().postDelayed(new Runnable() {
                @Override public void run() { vpnOff("boot"); }
            }, 15000);
        }
    }

    private final BroadcastReceiver offReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { Sleep.cancel(c); vpnOff(i.getAction()); }
    };

    private void vpnOff(String why) {
        try {
            if (Vpn.tunnelUp(this)) {
                Log.i("TvHome", "vpn off: " + why);
                Vpn.set(this, false);
            }
        } catch (Exception e) { Log.w("TvHome", "vpn off " + e); }
    }

    @Override
    public void onDestroy() {
        try { unregisterReceiver(offReceiver); } catch (Exception ignored) { }
        if (remote != null) remote.stop();
        super.onDestroy();
    }

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        if (prefs == null) return false;
        int code = e.getKeyCode();
        int scan = e.getScanCode();

        // Режим назначения кнопки
        if (System.currentTimeMillis() < prefs.getLong("learn_until", 0)) {
            if (code == KeyEvent.KEYCODE_HOME || code == KeyEvent.KEYCODE_BACK
                    || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                return false;
            }
            if (e.getAction() == KeyEvent.ACTION_UP) {
                prefs.edit().putInt("key_code", code).putInt("key_scan", scan)
                        .putLong("learn_until", 0).apply();
                Toast.makeText(this, "Кнопка назначена: " + KeyEvent.keyCodeToString(code)
                        + " (" + code + "/" + scan + ")", Toast.LENGTH_LONG).show();
            }
            return true;
        }

        int saved = prefs.getInt("key_code", KeyEvent.KEYCODE_VOICE_ASSIST);
        int savedScan = prefs.getInt("key_scan", -1);
        boolean match = saved != -1 && (saved != KeyEvent.KEYCODE_UNKNOWN ? code == saved : scan == savedScan);
        if (!match) return false;
        if (e.getAction() == KeyEvent.ACTION_UP) {
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
        }
        return true;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }

    static boolean isEnabled(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (s == null) return false;
        String me = new ComponentName(c, KeyService.class).flattenToString();
        String meShort = new ComponentName(c, KeyService.class).flattenToShortString();
        return s.contains(me) || s.contains(meShort);
    }
}
