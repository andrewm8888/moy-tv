package ru.mikulinskiy.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Ввод с телефона: маленький веб-сервер в домашней сети, текст вставляется в поле ввода на экране ТВ. */
final class Remote {
    static final int PORT = 8080;
    private final AccessibilityService svc;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private ServerSocket server;
    private volatile boolean running;

    Remote(AccessibilityService s) { svc = s; }

    static String key(Context c) {
        SharedPreferences p = c.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        String k = p.getString("remote_key", null);
        if (k == null) {
            k = String.format("%04d", new Random().nextInt(10000));
            p.edit().putString("remote_key", k).apply();
        }
        return k;
    }

    static String ip() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.getName().startsWith("tun")) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses()))
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
            }
        } catch (Exception ignored) { }
        return "IP-телевизора";
    }

    static String url(Context c) { return "http://" + ip() + ":" + PORT + "/?k=" + key(c); }

    void start() {
        if (running) return;
        running = true;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    server = new ServerSocket(PORT);
                    while (running) {
                        final Socket s = server.accept();
                        new Thread(new Runnable() { @Override public void run() { handle(s); } }).start();
                    }
                } catch (Exception e) { Log.w("TvHome", "remote " + e); }
            }
        }, "remote").start();
    }

    void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) { }
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(8000);
            InputStream in = s.getInputStream();
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int b, state = 0;
            while ((b = in.read()) != -1) {
                head.write(b);
                state = (b == '\r' || b == '\n') ? state + 1 : 0;
                if (state == 4 || head.size() > 16000) break;
            }
            String h = head.toString("UTF-8");
            String first = h.split("\r\n", 2)[0];
            String[] parts = first.split(" ");
            String path = parts.length > 1 ? parts[1] : "/";
            int len = 0;
            for (String line : h.split("\r\n"))
                if (line.toLowerCase().startsWith("content-length:")) len = Integer.parseInt(line.substring(15).trim());
            byte[] body = new byte[Math.min(len, 20000)];
            int got = 0;
            while (got < body.length) { int n = in.read(body, got, body.length - got); if (n < 0) break; got += n; }
            String text = new String(body, 0, got, "UTF-8");

            String k = key(svc);
            String status;
            String type = "text/plain; charset=utf-8";
            String out;
            if (!path.contains("k=" + k)) {
                out = "Неверный код. Откройте ссылку с экрана ТВ: Ярлыки → Телефон";
                status = "403 Forbidden";
            } else if (path.startsWith("/text")) {
                out = setText(text); status = "200 OK";
            } else if (path.startsWith("/long")) {
                out = AdbSelf.get(svc).longKey(param(path, "c")); status = "200 OK";
            } else if (path.startsWith("/key")) {
                out = AdbSelf.get(svc).key(param(path, "c")); status = "200 OK";
            } else if (path.startsWith("/back")) {
                out = "ok"; status = "200 OK";
                boolean viaAdb = AdbSelf.get(svc).isReady() && "ok".equals(AdbSelf.get(svc).key(4));
                if (!viaAdb) svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
            } else if (path.startsWith("/home")) {
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME); out = "ok"; status = "200 OK";
            } else {
                out = PAGE.replace("%K%", k); status = "200 OK"; type = "text/html; charset=utf-8";
            }
            byte[] data = out.getBytes("UTF-8");
            OutputStream o = s.getOutputStream();
            o.write(("HTTP/1.1 " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: " + data.length
                    + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
            o.write(data);
            o.flush();
        } catch (Exception e) {
            Log.w("TvHome", "remote req " + e);
        } finally {
            try { s.close(); } catch (Exception ignored) { }
        }
    }

    private static int param(String path, String name) {
        int q = path.indexOf('?');
        if (q < 0) return 0;
        for (String kv : path.substring(q + 1).split("&")) {
            if (kv.startsWith(name + "=")) {
                try { return Integer.parseInt(kv.substring(name.length() + 1)); } catch (Exception ignored) { }
            }
        }
        return 0;
    }

    /** Вставить текст в поле ввода, которое сейчас в фокусе на ТВ. */
    private String setText(final String text) {
        final String[] res = {"Нет поля ввода на экране ТВ. Выберите поле поиска пультом"};
        final CountDownLatch done = new CountDownLatch(1);
        ui.post(new Runnable() {
            @Override public void run() {
                try {
                    AccessibilityNodeInfo root = svc.getRootInActiveWindow();
                    AccessibilityNodeInfo n = root != null ? root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) : null;
                    if (n == null || !n.isEditable()) n = findEditable(root);
                    if (n != null) {
                        Bundle a = new Bundle();
                        a.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                        boolean ok = n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, a);
                        if (ok) {
                            Bundle sel = new Bundle();
                            sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, text.length());
                            sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, text.length());
                            n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel);
                        }
                        res[0] = ok ? "ok" : "Поле не принимает текст (" + n.getPackageName() + ")";
                    }
                } catch (Exception e) { res[0] = "Ошибка: " + e; }
                done.countDown();
            }
        });
        try { done.await(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        return res[0];
    }

    private static AccessibilityNodeInfo findEditable(AccessibilityNodeInfo n) {
        if (n == null) return null;
        if (n.isEditable() && (n.isFocused() || n.isAccessibilityFocused())) return n;
        AccessibilityNodeInfo any = null;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = findEditable(n.getChild(i));
            if (r != null && (r.isFocused() || r.isAccessibilityFocused())) return r;
            if (r != null && any == null) any = r;
        }
        if (any == null && n.isEditable()) any = n;
        return any;
    }

    private static final String PAGE = "<!doctype html><html lang=ru><head><meta charset=utf-8>"
            + "<meta name=viewport content='width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no'>"
            + "<meta name=apple-mobile-web-app-capable content=yes><title>Мой ТВ</title><style>"
            + "*{-webkit-tap-highlight-color:transparent;-webkit-user-select:none;user-select:none}"
            + "body{margin:0;background:#06080C;color:#E8ECF2;font:16px -apple-system,system-ui,sans-serif;padding:18px 18px 40px}"
            + "h1{font-size:13px;font-weight:600;letter-spacing:.14em;text-transform:uppercase;color:#8792A3;margin:0 0 14px}"
            + "button{background:#0D1118;color:#E8ECF2;border:1px solid #2a3a4a;font-size:17px;touch-action:manipulation;padding:0}"
            + "button:active{border-color:#E7A35F;color:#E7A35F;background:#1a1510}"
            + ".pad{display:grid;grid-template-columns:repeat(3,1fr);gap:10px;max-width:330px;margin:0 auto}"
            + ".pad button{height:78px;border-radius:22px;font-size:26px}.pad .ok{border-radius:50%;border-color:#E7A35F;color:#E7A35F;font-size:18px;font-weight:600}"
            + ".row{display:flex;gap:10px;margin-top:12px;max-width:330px;margin-left:auto;margin-right:auto}"
            + ".row button{flex:1;height:56px;border-radius:999px}"
            + "textarea{width:100%;box-sizing:border-box;min-height:90px;background:#0D1118;color:#E8ECF2;border:1px solid #2a3a4a;"
            + "border-radius:14px;padding:14px;font-size:18px;outline:none;-webkit-user-select:text;user-select:text}textarea:focus{border-color:#E7A35F}"
            + ".txt{max-width:330px;margin:26px auto 0}.txt .row button{height:48px;font-size:15px}"
            + "#st{margin:14px auto 0;max-width:330px;font-size:14px;color:#8792A3;min-height:20px;text-align:center}</style></head><body>"
            + "<h1>Мой ТВ · пульт</h1>"
            + "<div class=pad><span></span><button data-c=19>↑</button><span></span>"
            + "<button data-c=21>←</button><button class=ok data-c=23>ОК</button><button data-c=22>→</button>"
            + "<span></span><button data-c=20>↓</button><span></span></div>"
            + "<div class=row><button onclick='lng(23)'>Долгое ОК</button></div>"
            + "<div class=row><button data-c=4>Назад</button><button onclick=\"go('home')\">Домой</button><button data-c=85>⏯</button></div>"
            + "<div class=row><button data-c=25 data-r=1>Громк −</button><button data-c=164>Без звука</button><button data-c=24 data-r=1>Громк +</button></div>"
            + "<div id=st></div>"
            + "<div class=txt><h1>Ввод текста</h1><textarea id=t placeholder='Выберите поле на ТВ и печатайте здесь'></textarea>"
            + "<div class=row><button onclick='send(t.value)'>Отправить</button><button onclick=\"t.value='';send('')\">Очистить</button></div></div>"
            + "<script>"
            + "const K='%K%',t=document.getElementById('t'),st=document.getElementById('st');let tm,rp,rt;"
            + "function say(x){st.textContent=x=='ok'?'':x}"
            + "function lng(c){if(navigator.vibrate)navigator.vibrate(20);fetch('/long?k='+K+'&c='+c).then(r=>r.text()).then(say).catch(e=>say('Нет связи с ТВ'))}"
            + "function key(c){fetch('/key?k='+K+'&c='+c).then(r=>r.text()).then(say).catch(e=>say('Нет связи с ТВ'))}"
            + "document.querySelectorAll('[data-c]').forEach(b=>{const c=b.dataset.c;"
            + "const isArrow=['19','20','21','22','24','25'].includes(c);"
            + "b.addEventListener('pointerdown',e=>{e.preventDefault();if(navigator.vibrate)navigator.vibrate(8);key(c);"
            + "if(isArrow){rt=setTimeout(()=>{rp=setInterval(()=>key(c),160)},450)}});"
            + "['pointerup','pointerleave','pointercancel'].forEach(ev=>b.addEventListener(ev,()=>{clearTimeout(rt);clearInterval(rp)}))});"
            + "function send(v){fetch('/text?k='+K,{method:'POST',headers:{'Content-Type':'text/plain;charset=utf-8'},body:v})"
            + ".then(r=>r.text()).then(x=>st.textContent=x=='ok'?'✓ текст на экране':x).catch(e=>say('Нет связи с ТВ'))}"
            + "function go(a){fetch('/'+a+'?k='+K).then(()=>say('ok')).catch(e=>say('Нет связи с ТВ'))}"
            + "t.addEventListener('input',()=>{clearTimeout(tm);tm=setTimeout(()=>send(t.value),350)});"
            + "</script></body></html>";
}
