package ru.mikulinskiy.tvhome;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Блок новостей на главной (под погодой): вкладки Россия · Мир · IT,
 * слева узкий список заголовков, справа статья целиком (вдвое шире списка).
 * Стрелка вправо из списка — в статью, там вверх/вниз листают текст, влево — обратно.
 */
final class NewsBlock {
    private static final Locale RU = new Locale("ru", "RU");
    private final Activity a;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService art = Executors.newSingleThreadExecutor();
    private final TextView[] tabs = new TextView[News.TABS.length];
    private ScrollView page;
    private View barThumb, barTrack;
    private LinearLayout list;
    private ScrollView listScroll, textScroll;
    private android.widget.FrameLayout textFrame;
    private android.animation.ValueAnimator scrollAnim;
    private int scrollTarget;
    private TextView status, rSource, rTitle, rBody;
    private int tab = 0, loadToken = 0, artToken = 0;
    private News.Item current;
    private View root;

    NewsBlock(Activity act) { a = act; }

    View build(ScrollView pageScroll) {
        page = pageScroll;
        final Context c = a;
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);

        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setClipChildren(false);
        TextView lbl = Ui.label(c, "Новости");
        lbl.setPadding(Ui.dp(c, 4), 0, Ui.dp(c, 18), 0);
        head.addView(lbl);
        for (int i = 0; i < tabs.length; i++) {
            final int k = i;
            TextView t = Ui.text(c, News.TABS[i], 15, Ui.TEXT, "golos", 600);
            t.setPadding(Ui.dp(c, 18), Ui.dp(c, 9), Ui.dp(c, 18), Ui.dp(c, 9));
            t.setId(View.generateViewId());
            Ui.focusable(t, 999);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, Ui.dp(c, 10), 0);
            t.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override public void onFocusChange(View v, boolean has) {
                    paint(v, has, 999);
                    if (has) fillScreen();
                    if (has && k != tab) select(k, false);
                }
            });
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { fillScreen(); select(k, true); }
            });
            tabs[i] = t;
            head.addView(t, lp);
        }
        View flex = new View(c);
        head.addView(flex, new LinearLayout.LayoutParams(0, 1, 1));
        status = Ui.text(c, "", 11, Ui.TEXT_DIM, "mono", 500);
        status.setLetterSpacing(0.12f);
        head.addView(status);
        col.addView(head);


        LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setPadding(0, Ui.dp(c, 14), 0, 0);
        body.setClipChildren(false);

        listScroll = new ScrollView(c);
        listScroll.setVerticalScrollBarEnabled(false);
        listScroll.setClipChildren(false);
        listScroll.setClipToPadding(false);
        list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setClipChildren(false);
        list.setPadding(Ui.dp(c, 4), Ui.dp(c, 4), Ui.dp(c, 6), Ui.dp(c, 4));
        listScroll.addView(list);
        body.addView(listScroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.8f));

        // Рамка отдельно, текст листается внутри с отступом и тает к верхнему и нижнему краю
        textFrame = new android.widget.FrameLayout(c);
        textFrame.setBackground(Ui.shape(c, Ui.PANEL, Ui.HAIR, 1, 22));
        textScroll = new ScrollView(c);
        textScroll.setVerticalScrollBarEnabled(false);
        textScroll.setVerticalFadingEdgeEnabled(true);
        textScroll.setFadingEdgeLength(Ui.dp(c, 56));
        textScroll.setFocusable(true);
        textScroll.setId(View.generateViewId());
        textScroll.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                textFrame.setBackground(has ? Ui.shape(a, Ui.PANEL, Ui.ACCENT, 2, 22) : Ui.shape(a, Ui.PANEL, Ui.HAIR, 1, 22));
            }
        });
        textScroll.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View v, int code, KeyEvent e) {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                int step = textScroll.getHeight() / 3;
                if (code == KeyEvent.KEYCODE_DPAD_DOWN) { scrollText(step); return true; }
                if (code == KeyEvent.KEYCODE_DPAD_UP) {
                    if (textScroll.getScrollY() == 0) return false;
                    scrollText(-step); return true;
                }
                if (code == KeyEvent.KEYCODE_DPAD_LEFT) { focusCurrentRow(); return true; }
                return false;
            }
        });
        LinearLayout right = new LinearLayout(c);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(Ui.dp(c, 15), Ui.dp(c, 20), Ui.dp(c, 15), Ui.dp(c, 40));
        rSource = Ui.label(c, "");
        rTitle = Ui.text(c, "", 22, Ui.TEXT, "golos", 600);
        rTitle.setLineSpacing(0, 1.15f);
        rBody = Ui.text(c, "", 17, Ui.TEXT, "golos", 400);
        rBody.setLineSpacing(0, 1.35f);
        rBody.setPadding(0, Ui.dp(c, 16), 0, 0);
        right.addView(rSource);
        right.addView(rTitle);
        right.addView(rBody);
        textScroll.addView(right);
        android.widget.FrameLayout.LayoutParams sl = new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        sl.setMargins(Ui.dp(c, 3), Ui.dp(c, 14), Ui.dp(c, 3), Ui.dp(c, 14));
        textFrame.addView(textScroll, sl);
        barTrack = new View(c);
        barTrack.setBackgroundColor(Ui.HAIR);
        android.widget.FrameLayout.LayoutParams tl = new android.widget.FrameLayout.LayoutParams(Ui.dp(c, 2), ViewGroup.LayoutParams.MATCH_PARENT);
        tl.gravity = Gravity.END | Gravity.TOP;
        tl.setMargins(0, Ui.dp(c, 18), Ui.dp(c, 7), Ui.dp(c, 18));
        textFrame.addView(barTrack, tl);
        barThumb = new View(c);
        barThumb.setBackground(Ui.shape(c, Ui.ACCENT, 0, 0, 999));
        android.widget.FrameLayout.LayoutParams bl = new android.widget.FrameLayout.LayoutParams(Ui.dp(c, 4), Ui.dp(c, 40));
        bl.gravity = Gravity.END | Gravity.TOP;
        bl.setMargins(0, Ui.dp(c, 18), Ui.dp(c, 6), 0);
        textFrame.addView(barThumb, bl);
        textScroll.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override public void onScrollChange(View v, int x, int y, int ox, int oy) { updateBar(); }
        });
        right.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                ui.post(new Runnable() { @Override public void run() { updateBar(); } });
            }
        });
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2.2f);
        rl.setMargins(Ui.dp(c, 16), Ui.dp(c, 4), 0, Ui.dp(c, 4));
        body.addView(textFrame, rl);

        col.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        // Блок ровно в высоту экрана: при выборе вкладки страница прокручивается так, что он занимает весь экран
        int screenH = c.getResources().getDisplayMetrics().heightPixels;
        col.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, screenH - Ui.dp(c, 36)));
        root = col;
        select(0, false);
        return col;
    }

    /** Вызывать из onResume главного экрана. */
    void refreshIfStale() {
        if (root != null && !News.fresh(a, tab)) select(tab, false);
    }

    private void select(final int k, final boolean force) {
        tab = k;
        for (int i = 0; i < tabs.length; i++) tabs[i].setTextColor(i == k ? Ui.ACCENT : Ui.TEXT);
        List<News.Item> cached = News.cached(a, k);
        show(cached);
        if (!force && News.fresh(a, k) && !cached.isEmpty()) { stamp(k, null); return; }
        stamp(k, News.english(k) ? "загружаю и перевожу…" : "загружаю…");
        final int token = ++loadToken;
        io.execute(new Runnable() {
            @Override public void run() {
                List<News.Item> fresh = null;
                String err = null;
                try { fresh = News.load(a, k); } catch (Exception e) { err = "no news".equals(e.getMessage()) ? "за сегодня и вчера новостей нет" : "нет связи"; }
                final List<News.Item> f = fresh;
                final String er = err;
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (token != loadToken || tab != k) return;
                        if (f != null) show(f);
                        stamp(k, er);
                    }
                });
            }
        });
    }

    private void stamp(int k, String msg) {
        long at = News.updated(a, k);
        String s = msg != null ? msg : (at > 0 ? "обновлено " + News.time(at) : "");
        if (News.english(k) && msg == null) s += " · перевод Google";
        status.setText(s.toUpperCase(RU));
    }

    private void show(List<News.Item> items) {
        Context c = a;
        boolean hadFocus = list.findFocus() != null;
        list.removeAllViews();
        listScroll.scrollTo(0, 0);
        long yStart = News.sinceYesterday() + 24L * 3600 * 1000;
        boolean yHeader = false;
        View first = null;
        for (final News.Item it : items) {
            if (!yHeader && it.time < yStart) {
                yHeader = true;
                TextView y = Ui.label(c, "Вчера");
                y.setPadding(Ui.dp(c, 6), Ui.dp(c, 14), 0, Ui.dp(c, 8));
                list.addView(y);
            }
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(Ui.dp(c, 16), Ui.dp(c, 10), Ui.dp(c, 16), Ui.dp(c, 10));
            row.setId(View.generateViewId());
            TextView t = Ui.text(c, (News.time(it.time) + (tab >= News.VED ? " · " + it.source : "")).toUpperCase(RU), 10, Ui.TEXT_DIM, "mono", 500);
            t.setLetterSpacing(0.1f);
            TextView h = Ui.text(c, it.title, 14, Ui.TEXT, "golos", 500);
            h.setMaxLines(4);
            h.setLineSpacing(0, 1.1f);
            h.setEllipsize(android.text.TextUtils.TruncateAt.END);
            h.setPadding(0, Ui.dp(c, 5), 0, 0);
            row.addView(t);
            row.addView(h);
            row.setTag(it);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 0, Ui.dp(c, 6));
            Ui.focusable(row, 16);
            row.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override public void onFocusChange(View v, boolean has) {
                    paint(v, has, 16);
                    if (has) detail(it);
                }
            });
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { textScroll.requestFocus(); }
            });
            row.setNextFocusRightId(textScroll.getId());
            if (first == null) { first = row; row.setNextFocusUpId(tabs[tab].getId()); }
            list.addView(row, lp);
        }
        // Вниз с любой вкладки — в первую новость списка, а не в статью
        for (TextView t : tabs) t.setNextFocusDownId(first != null ? first.getId() : t.getId());
        if (items.isEmpty()) {
            current = null;
            rSource.setText("");
            rTitle.setText("За сегодня и вчера новостей нет");
            rBody.setText("");
        } else {
            detail(items.get(0));
            if (hadFocus && first != null) first.requestFocus();
        }
    }

    private void focusCurrentRow() {
        for (int i = 0; i < list.getChildCount(); i++) {
            View v = list.getChildAt(i);
            if (v.getTag() == current) { v.requestFocus(); return; }
        }
        if (list.getChildCount() > 0) list.getChildAt(0).requestFocus();
    }

    private void detail(final News.Item it) {
        if (it == current) return;
        current = it;
        rSource.setText((it.source + "  ·  " + News.time(it.time)).toUpperCase(RU));
        rTitle.setText(it.title);
        rBody.setText(it.desc.isEmpty() ? "" : it.desc);
        rBody.setTextColor(Ui.TEXT_DIM);
        textScroll.scrollTo(0, 0);
        final int token = ++artToken;
        final boolean english = News.english(tab);
        // небольшая пауза, чтобы при быстрой прокрутке списка не качать каждую статью
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (token != artToken) return;
                art.execute(new Runnable() {
                    @Override public void run() {
                        if (token != artToken) return;
                        final String text = News.article(a, it, english);
                        ui.post(new Runnable() {
                            @Override public void run() {
                                if (token != artToken || current != it) return;
                                if (text != null) { rBody.setText(text); rBody.setTextColor(Ui.TEXT); }
                                else rBody.setTextColor(Ui.TEXT);
                            }
                        });
                    }
                });
            }
        }, 450);
    }

    /** Прокрутить главную так, чтобы блок новостей занял весь экран. */
    private void fillScreen() {
        if (page == null || root == null) return;
        final int y = Math.max(0, root.getTop() - Ui.dp(a, 14));
        if (Math.abs(page.getScrollY() - y) > 4) page.smoothScrollTo(0, y);
    }

    /** Полоса прочитанного справа в рамке статьи. */
    private void updateBar() {
        View child = textScroll.getChildAt(0);
        int h = textScroll.getHeight();
        if (child == null || h == 0) return;
        int ch = child.getHeight();
        if (ch <= h + Ui.dp(a, 8)) {
            barThumb.setVisibility(View.INVISIBLE);
            barTrack.setVisibility(View.INVISIBLE);
            return;
        }
        int track = textFrame.getHeight() - Ui.dp(a, 36);
        int thumb = Math.max(Ui.dp(a, 24), track * h / ch);
        int top = (track - thumb) * textScroll.getScrollY() / (ch - h);
        android.widget.FrameLayout.LayoutParams lp = (android.widget.FrameLayout.LayoutParams) barThumb.getLayoutParams();
        if (lp.height != thumb || lp.topMargin != Ui.dp(a, 18) + top) {
            lp.height = thumb;
            lp.topMargin = Ui.dp(a, 18) + top;
            barThumb.setLayoutParams(lp);
        }
        barThumb.setVisibility(View.VISIBLE);
        barTrack.setVisibility(View.VISIBLE);
    }

    /** Плавная прокрутка статьи: шаг треть экрана, медленнее стандартной. */
    private void scrollText(int dy) {
        View child = textScroll.getChildAt(0);
        if (child == null) return;
        int max = Math.max(0, child.getHeight() - textScroll.getHeight());
        int from = textScroll.getScrollY();
        if (scrollAnim != null && scrollAnim.isRunning()) {
            from = (Integer) scrollAnim.getAnimatedValue();
            dy += scrollTarget - from;
            scrollAnim.cancel();
        }
        final int to = Math.max(0, Math.min(max, from + dy));
        scrollAnim = android.animation.ValueAnimator.ofInt(from, to);
        scrollTarget = to;
        scrollAnim.setDuration(450);
        scrollAnim.setInterpolator(new android.view.animation.DecelerateInterpolator());
        scrollAnim.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(android.animation.ValueAnimator va) {
                textScroll.scrollTo(0, (Integer) va.getAnimatedValue());
            }
        });
        scrollAnim.start();
    }

    private static void paint(View v, boolean has, float r) {
        Context c = v.getContext();
        v.setBackground(has ? Ui.shape(c, Ui.TILE_FOCUS, Ui.ACCENT, 2, r) : Ui.shape(c, Ui.TILE, Ui.HAIR, 1, r));
    }
}
