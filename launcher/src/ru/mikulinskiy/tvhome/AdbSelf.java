package ru.mikulinskiy.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import java.io.DataInputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;

/**
 * Клиент ADB к самому телевизору (127.0.0.1:5555). Один раз ТВ спрашивает
 * «Разрешить отладку?», дальше лаунчер держит канал и через Injector нажимает кнопки.
 */
final class AdbSelf {
    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f,
            OKAY = 0x59414b4f, CLSE = 0x45534c43, WRTE = 0x45545257;
    private static final byte[] SHA1_PREFIX = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    private static AdbSelf inst;
    static synchronized AdbSelf get(Context c) {
        if (inst == null) inst = new AdbSelf(c.getApplicationContext());
        return inst;
    }

    private final Context ctx;
    private Socket sock;
    private DataInputStream in;
    private OutputStream out;
    private final int localId = 7;
    private int remoteId;
    private volatile boolean ready;
    private volatile String state = "Нет связи с ADB телевизора";
    private final Semaphore canWrite = new Semaphore(0);

    private AdbSelf(Context c) { ctx = c; }

    boolean isReady() { return ready; }

    /** Подключиться заранее (при запуске службы), чтобы запрос разрешения появился сразу. */
    void warm() {
        new Thread(new Runnable() {
            @Override public void run() {
                synchronized (AdbSelf.this) {
                    try { if (!ready) connect(); } catch (Exception e) { Log.w("TvHome", "adb warm " + e); close(); }
                }
            }
        }, "adb-warm").start();
    }

    /** Нажать кнопку пульта. Возвращает "ok" или текст проблемы. */
    synchronized String key(int code) { return send("k " + code); }

    synchronized String longKey(int code) { return send("l " + code); }

    private String send(String cmd) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (!ready) connect();
                write((cmd + "\n").getBytes("UTF-8"));
                return "ok";
            } catch (Exception e) {
                Log.w("TvHome", "adb key " + e);
                close();
            }
        }
        return state;
    }

    // ---------- протокол ----------

    private void connect() throws Exception {
        close();
        state = "Подключаюсь к ADB телевизора…";
        sock = new Socket();
        sock.connect(new InetSocketAddress("127.0.0.1", 5555), 3000);
        sock.setTcpNoDelay(true);
        sock.setSoTimeout(90_000); // ждём, пока на ТВ нажмут «Разрешить»
        in = new DataInputStream(sock.getInputStream());
        out = sock.getOutputStream();
        send(CNXN, 0x01000000, 4096, "host::\0".getBytes("UTF-8"));
        boolean sentSig = false, sentPub = false;
        while (true) {
            Msg m = read();
            if (m.cmd == CNXN) break;
            if (m.cmd == AUTH && m.a0 == 1) {
                if (!sentSig) { send(AUTH, 2, 0, sign(m.data)); sentSig = true; }
                else if (!sentPub) {
                    send(AUTH, 3, 0, pubKey());
                    sentPub = true;
                    state = "На ТВ разрешите отладку: отметьте «Всегда разрешать» и нажмите ОК";
                    Log.i("TvHome", "adb: waiting for user approval");
                } else throw new Exception("auth rejected");
            }
        }
        String path = ctx.getApplicationInfo().sourceDir;
        send(OPEN, localId, 0, ("shell:CLASSPATH=" + path
                + " app_process /system/bin ru.mikulinskiy.tvhome.Injector\0").getBytes("UTF-8"));
        sock.setSoTimeout(5000);
        while (true) {
            Msg m = read();
            if (m.cmd == OKAY) { remoteId = m.a0; break; }
            if (m.cmd == CLSE) throw new Exception("shell refused");
        }
        sock.setSoTimeout(0);
        canWrite.drainPermits();
        canWrite.release();
        ready = true;
        state = "ok";
        Log.i("TvHome", "adb: ready");
        final Socket mine = sock;
        new Thread(new Runnable() { @Override public void run() { pump(mine); } }, "adb-read").start();
    }

    private void pump(Socket mine) {
        try {
            while (sock == mine) {
                Msg m = read();
                if (m.cmd == WRTE) {
                    if (m.data.length > 0) Log.i("TvHome", "injector: " + new String(m.data, "UTF-8").trim());
                    synchronized (out) { send(OKAY, localId, remoteId, new byte[0]); }
                } else if (m.cmd == OKAY) {
                    canWrite.release();
                } else if (m.cmd == CLSE) {
                    Log.w("TvHome", "adb: stream closed");
                    break;
                }
            }
        } catch (Exception e) {
            if (sock == mine) Log.w("TvHome", "adb read " + e);
        }
        if (sock == mine) { ready = false; state = "Связь с ADB телевизора прервалась"; }
    }

    private void write(byte[] d) throws Exception {
        if (!canWrite.tryAcquire(3, TimeUnit.SECONDS)) throw new Exception("write timeout");
        synchronized (out) { send(WRTE, localId, remoteId, d); }
    }

    private void close() {
        ready = false;
        try { if (sock != null) sock.close(); } catch (Exception ignored) { }
        sock = null;
    }

    private static final class Msg { int cmd, a0, a1; byte[] data; }

    private Msg read() throws Exception {
        byte[] h = new byte[24];
        in.readFully(h);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = b.getInt(); m.a0 = b.getInt(); m.a1 = b.getInt();
        int len = b.getInt();
        if (len < 0 || len > 1 << 20) throw new Exception("bad len " + len);
        m.data = new byte[len];
        in.readFully(m.data);
        return m;
    }

    private void send(int cmd, int a0, int a1, byte[] d) throws Exception {
        ByteBuffer b = ByteBuffer.allocate(24 + d.length).order(ByteOrder.LITTLE_ENDIAN);
        int sum = 0;
        for (byte x : d) sum += x & 0xff;
        b.putInt(cmd).putInt(a0).putInt(a1).putInt(d.length).putInt(sum).putInt(~cmd).put(d);
        out.write(b.array());
        out.flush();
    }

    // ---------- ключ ----------

    private KeyPair keys() throws Exception {
        SharedPreferences p = ctx.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        String pr = p.getString("adb_priv", null), pu = p.getString("adb_pub", null);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (pr != null && pu != null) {
            return new KeyPair(
                    kf.generatePublic(new X509EncodedKeySpec(Base64.decode(pu, Base64.NO_WRAP))),
                    kf.generatePrivate(new PKCS8EncodedKeySpec(Base64.decode(pr, Base64.NO_WRAP))));
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        p.edit().putString("adb_priv", Base64.encodeToString(kp.getPrivate().getEncoded(), Base64.NO_WRAP))
                .putString("adb_pub", Base64.encodeToString(kp.getPublic().getEncoded(), Base64.NO_WRAP)).commit();
        return kp;
    }

    private byte[] sign(byte[] token) throws Exception {
        PrivateKey k = keys().getPrivate();
        Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        c.init(Cipher.ENCRYPT_MODE, k);
        c.update(SHA1_PREFIX);
        return c.doFinal(token);
    }

    /** Открытый ключ в формате Android (RSAPublicKey из mincrypt), base64 + имя. */
    private byte[] pubKey() throws Exception {
        RSAPublicKey pk = (RSAPublicKey) keys().getPublic();
        BigInteger n = pk.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = n.mod(r32).modInverse(r32).negate().mod(r32);
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(n);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(64);
        b.putInt(n0inv.intValue());
        for (int i = 0; i < 64; i++) b.putInt(n.shiftRight(32 * i).mod(r32).intValue());
        for (int i = 0; i < 64; i++) b.putInt(rr.shiftRight(32 * i).mod(r32).intValue());
        b.putInt(pk.getPublicExponent().intValue());
        String s = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " MoyTV@launcher\0";
        return s.getBytes("UTF-8");
    }
}
