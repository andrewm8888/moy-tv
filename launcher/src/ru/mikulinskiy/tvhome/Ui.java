package ru.mikulinskiy.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Стиль в духе mikulinskiy.ru: почти чёрный фон, светлый текст, оранжевый и ледяной акценты. */
final class Ui {
    static final int BG = 0xFF06080C;
    static final int PANEL = 0xF20D1118;
    static final int TEXT = 0xFFE8ECF2;
    static final int TEXT_DIM = 0xFF8792A3;
    static final int ACCENT = 0xFFE7A35F;   // оранжевый
    static final int ICE = 0xFF9CC3E6;      // ледяной голубой
    static final int HAIR = 0x4D9CC3E6;     // тонкая рамка
    static final int TILE = 0x8C0B0F16;       // тёмная подложка 55 %: фото видно, текст читается
    static final int TILE_FOCUS = 0xB8231A12; // в фокусе темнее (72 %) и теплее
    static final int UP = 0xFF7ED3A6;
    static final int DOWN = 0xFFF08C8C;

    private static final Map<String, Typeface> FONTS = new HashMap<>();

    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    /** name: unbounded | golos | mono; weight 100..900. */
    static Typeface font(Context c, String name, int weight) {
        String key = name + weight;
        Typeface t = FONTS.get(key);
        if (t != null) return t;
        try {
            t = new Typeface.Builder(c.getAssets(), "fonts/" + name + ".ttf")
                    .setFontVariationSettings("'wght' " + weight).build();
        } catch (Throwable e) {
            t = null;
        }
        if (t == null) t = Typeface.DEFAULT;
        FONTS.put(key, t);
        return t;
    }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        return text(c, s, sp, color, "golos", bold ? 600 : 400);
    }

    static TextView text(Context c, String s, float sp, int color, String font, int weight) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setTypeface(font(c, font, weight));
        // мягкая тень: текст читается на светлых фотографиях
        t.setShadowLayer(dp(c, 5), 0, dp(c, 1), 0xB3000000);
        return t;
    }

    /** Надпись раздела: моноширинный, прописные, разрядка. */
    static TextView label(Context c, String s) {
        TextView t = text(c, s.toUpperCase(new Locale("ru")), 11, TEXT_DIM, "mono", 500);
        t.setLetterSpacing(0.18f);
        t.setPadding(dp(c, 4), 0, 0, dp(c, 10));
        return t;
    }

    static GradientDrawable shape(Context c, int fill, int stroke, float strokeDp, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radiusDp));
        if (strokeDp > 0) g.setStroke(Math.max(1, dp(c, strokeDp)), stroke);
        return g;
    }

    /** Плитка: в покое тонкая рамка, в фокусе оранжевая рамка и тёплая подсветка. */
    static void focusable(final View v, final float radiusDp) {
        final Context c = v.getContext();
        v.setFocusable(true);
        v.setFocusableInTouchMode(false);
        v.setClickable(true);
        v.setBackground(shape(c, TILE, HAIR, 1, radiusDp));
        v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View view, boolean has) {
                view.setBackground(has ? shape(c, TILE_FOCUS, ACCENT, 2, radiusDp)
                        : shape(c, TILE, HAIR, 1, radiusDp));
            }
        });
    }

    static void focusable(View v) { focusable(v, 20); }

    // ---------- Меню и подтверждения в том же стиле ----------

    interface OnPick { void pick(int which); }

    static Dialog menu(Activity a, String title, String[] items, final OnPick cb) {
        final Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(a, 28), dp(a, 24), dp(a, 28), dp(a, 20));
        box.setBackground(shape(a, PANEL, HAIR, 1, 24));
        TextView t = text(a, title, 18, TEXT, "unbounded", 500);
        t.setPadding(dp(a, 4), 0, 0, dp(a, 16));
        box.addView(t);
        View first = null;
        for (int i = 0; i < items.length; i++) {
            final int which = i;
            TextView b = text(a, items[i], 15, TEXT, "golos", 500);
            b.setPadding(dp(a, 20), dp(a, 12), dp(a, 20), dp(a, 12));
            focusable(b, 999);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 0, dp(a, 8));
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { d.dismiss(); cb.pick(which); }
            });
            box.addView(b, lp);
            if (first == null) first = b;
        }
        d.setContentView(box, new ViewGroup.LayoutParams(dp(a, 420), ViewGroup.LayoutParams.WRAP_CONTENT));
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setDimAmount(0.6f);
        }
        d.show();
        if (first != null) first.requestFocus();
        return d;
    }

    static void confirm(Activity a, String title, final Runnable yes) {
        menu(a, title, new String[]{"Нет", "Да"}, new OnPick() {
            @Override public void pick(int which) { if (which == 1) yes.run(); }
        });
    }
}
