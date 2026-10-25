package ru.mikulinskiy.tvhome;

import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

/**
 * Запускается не как приложение, а через app_process от имени shell (ADB самого ТВ).
 * Читает строки «k КОД» из stdin и нажимает кнопки пульта от имени системы.
 */
public final class Injector {
    private static Object im;
    private static Method inj;

    public static void main(String[] args) {
        try { Looper.prepareMainLooper(); } catch (Throwable ignored) { }
        try {
            Class<?> c = Class.forName("android.hardware.input.InputManager");
            im = c.getDeclaredMethod("getInstance").invoke(null);
            inj = c.getMethod("injectInputEvent", InputEvent.class, int.class);
            System.out.println("ready");
            System.out.flush();
            BufferedReader r = new BufferedReader(new InputStreamReader(System.in));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("k ")) press(Integer.parseInt(line.substring(2).trim()));
                else if (line.startsWith("l ")) longPress(Integer.parseInt(line.substring(2).trim()));
                else if (line.equals("q")) break;
            }
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
        System.exit(0);
    }

    /** Долгое нажатие: как удержание кнопки на пульте около секунды. */
    private static void longPress(int code) throws Exception {
        long down = SystemClock.uptimeMillis();
        inj.invoke(im, ev(down, down, KeyEvent.ACTION_DOWN, code, 0, 0), 0);
        for (int r = 1; r <= 12; r++) {
            Thread.sleep(70);
            long t = SystemClock.uptimeMillis();
            inj.invoke(im, ev(down, t, KeyEvent.ACTION_DOWN, code, r, r == 1 ? KeyEvent.FLAG_LONG_PRESS : 0), 0);
        }
        long t = SystemClock.uptimeMillis();
        inj.invoke(im, ev(down, t, KeyEvent.ACTION_UP, code, 0, 0), 0);
    }

    private static KeyEvent ev(long down, long t, int action, int code, int repeat, int flags) {
        return new KeyEvent(down, t, action, code, repeat, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                flags, InputDevice.SOURCE_KEYBOARD);
    }

    private static void press(int code) throws Exception {
        long now = SystemClock.uptimeMillis();
        KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD);
        KeyEvent up = new KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD);
        inj.invoke(im, down, 0);
        inj.invoke(im, up, 0);
    }
}
