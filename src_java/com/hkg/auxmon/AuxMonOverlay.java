package com.hkg.auxmon;

import com.hkg.auxmon.AuxMonModels.ChargeEvent;
import com.hkg.auxmon.AuxMonModels.LiveTelemetrySnapshot;
import com.hkg.auxmon.AuxMonModels.ParkingSession;
import com.hkg.auxmon.AuxMonModels.VoltageSample;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Full-screen WindowManager overlay panel (TYPE_VOLUME_OVERLAY = 2020 first) for Gen5W (Android 4.4.2).
 *
 * Layout (full screen, 1920x720 @ 240dpi on Gen5W, but sized from the real display at runtime):
 *   Header : title, [작게 보기] (switch to a small draggable pill), [닫기]
 *   Left   : current state (plain language), elapsed keep time, one large primary button
 *            (start keep / stop keep with a second-tap confirmation because stopping while parked
 *            powers the headunit off immediately)
 *   Right  : 4 tabs - 한눈에 보기 (4 tiles) / 전압 그래프 / 충전 기록 (+USB export) / 설정
 *   Bottom : status message banner
 *
 * Everything is accessed through reflection because the package is compiled against a minimal stub
 * classpath and executed with app_process (no APK, no resources).
 */
public final class AuxMonOverlay {

    // ------------------------------------------------------------------------
    // Palette
    // ------------------------------------------------------------------------
    private static final int C_BG = 0xFF0B1220;
    private static final int C_SURFACE = 0xFF121B2D;
    private static final int C_SURFACE_2 = 0xFF1A2540;
    private static final int C_BORDER = 0xFF27344F;
    private static final int C_TEXT = 0xFFF1F5F9;
    private static final int C_TEXT_SUB = 0xFF94A3B8;
    private static final int C_TEXT_DIM = 0xFF64748B;
    private static final int C_BLUE = 0xFF38BDF8;
    private static final int C_GREEN = 0xFF34D399;
    private static final int C_AMBER = 0xFFFBBF24;
    private static final int C_RED = 0xFFF87171;
    private static final int C_ON_ACCENT = 0xFF0B1220;

    // ------------------------------------------------------------------------
    // Android constants (avoid compile-time dependency on android.jar)
    // ------------------------------------------------------------------------
    private static final int MATCH_PARENT = -1;
    private static final int WRAP_CONTENT = -2;
    private static final int GRAVITY_CENTER = 17;
    private static final int GRAVITY_CENTER_VERTICAL = 16;
    private static final int GRAVITY_TOP_LEFT = 51;
    private static final int VISIBLE = 0;
    private static final int GONE = 8;
    private static final int FLAG_NOT_FOCUSABLE = 0x00000008;
    private static final int FLAG_NOT_TOUCH_MODAL = 0x00000020;
    private static final int FLAG_LAYOUT_IN_SCREEN = 0x00000100;
    private static final int FLAG_LAYOUT_NO_LIMITS = 0x00000200;
    private static final int FLAG_FULLSCREEN = 0x00000400;
    private static final int PIXEL_FORMAT_TRANSLUCENT = -3;

    private static final int TAB_OVERVIEW = 0;
    private static final int TAB_CHART = 1;
    private static final int TAB_HISTORY = 2;
    private static final int TAB_SETTINGS = 3;
    private static final String[] TAB_LABELS = new String[]{"한눈에 보기", "전압 그래프", "충전 기록", "설정"};
    private static final int[] HOUR_PRESETS = new int[]{0, 1, 3, 6, 12, 24, 48};
    private static final int[] INTERVAL_PRESETS = new int[]{10, 30, 60};
    private static final long STOP_CONFIRM_WINDOW_MS = 4000L;

    // ------------------------------------------------------------------------
    // Window / context
    // ------------------------------------------------------------------------
    private static volatile Object uiHandler = null;
    private static volatile Object sysContext = null;
    private static volatile Object windowManager = null;
    private static volatile Object rootOverlayView = null;
    private static volatile Object currentWmLp = null;
    private static volatile float density = 1.5f;

    // ------------------------------------------------------------------------
    // Views
    // ------------------------------------------------------------------------
    private static Object compactRowView = null;
    private static Object compactTextView = null;
    private static Object fullBoxView = null;

    private static Object heroTitleTv = null;
    private static Object heroDescTv = null;
    private static Object heroTimeCaptionTv = null;
    private static Object heroTimeTv = null;
    private static Object heroTargetTv = null;
    private static Object primaryBtnTv = null;
    private static Object primaryHintTv = null;

    private static final Object[] tabButtons = new Object[TAB_LABELS.length];
    private static final Object[] tabPages = new Object[TAB_LABELS.length];

    private static Object tileVoltValueTv = null;
    private static Object tileVoltSubTv = null;
    private static Object tileSocValueTv = null;
    private static Object tileSocSubTv = null;
    private static Object tileChgValueTv = null;
    private static Object tileChgSubTv = null;
    private static Object tileProtValueTv = null;
    private static Object tileProtSubTv = null;

    private static Object chartImageView = null;
    private static Object chartBitmap = null;
    private static int chartBmpW = 0;
    private static int chartBmpH = 0;
    private static Object chartNavTv = null;
    private static Object historyNavTv = null;
    private static Object historyBodyTv = null;

    private static final Object[] hourChips = new Object[HOUR_PRESETS.length];
    private static Object hourValueTv = null;
    private static final Object[] intervalChips = new Object[INTERVAL_PRESETS.length];
    private static final Object[] modeChips = new Object[2];
    private static Object modeDescTv = null;
    private static Object ig3TestBtnTv = null;

    private static Object bannerTv = null;

    // ------------------------------------------------------------------------
    // UI state
    // ------------------------------------------------------------------------
    private static volatile boolean isVisible = false;
    private static volatile boolean compactMode = false;
    private static volatile boolean hiddenForRearCam = false;
    private static volatile int winX = 480;
    private static volatile int winY = 24;
    private static volatile int currentTab = TAB_OVERVIEW;
    private static volatile long stopConfirmUntilRt = 0L;

    /** Last applied background per view, so the 1 s refresh does not allocate drawables needlessly. */
    private static final Map<Object, Long> bgCache = new IdentityHashMap<Object, Long>();

    private static final CountDownLatch uiLooperLatch = new CountDownLatch(1);

    private static final Runnable REFRESH_UI_TASK = new Runnable() {
        @Override
        public void run() {
            refreshOnUiThread();
        }
    };

    private static final Runnable RETRY_SHOW_TASK = new Runnable() {
        @Override
        public void run() {
            showOnUiThread();
        }
    };

    // ------------------------------------------------------------------------
    // Reflection cache
    // ------------------------------------------------------------------------
    private static boolean reflReady = false;
    private static Class<?> cView;
    private static Class<?> cLinear;
    private static Class<?> cTextView;
    private static Constructor<?> ctorLinear;
    private static Constructor<?> ctorText;
    private static Constructor<?> ctorImage;
    private static Constructor<?> ctorScroll;
    private static Constructor<?> ctorLlLp2;
    private static Constructor<?> ctorLlLp3;
    private static Constructor<?> ctorFlLp2;
    private static Method mAddView;
    private static Method mSetPadding;
    private static Method mSetVisibility;
    private static Method mSetAlpha;
    private static Method mSetOrientation;
    private static Method mLlSetGravity;
    private static Method mTvSetGravity;
    private static Method mSetText;
    private static Method mSetTextColor;
    private static Method mSetTextSize;
    private static Method mSetMargins;
    private static Method mSetMinHeight;

    private AuxMonOverlay() {
    }

    // ========================================================================
    // PUBLIC API (called from AuxMonMain)
    // ========================================================================

    public static boolean isUiLooperReady() {
        return uiHandler != null;
    }

    public static boolean isOverlayVisible() {
        return isVisible;
    }

    public static Object getSysContext() {
        return sysContext;
    }

    public static void init() {
        Thread uiThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Class<?> looperCls = Class.forName("android.os.Looper");
                    try {
                        looperCls.getMethod("prepareMainLooper").invoke(null);
                    } catch (Throwable ignored) {
                        looperCls.getMethod("prepare").invoke(null);
                    }
                    Object myLooper = looperCls.getMethod("myLooper").invoke(null);
                    Class<?> handlerCls = Class.forName("android.os.Handler");
                    uiHandler = handlerCls.getConstructor(looperCls).newInstance(myLooper);
                    ensureUiContextOnUiThread();
                    uiLooperLatch.countDown();

                    Method loopMethod = looperCls.getMethod("loop");
                    while (true) {
                        try {
                            loopMethod.invoke(null);
                            break;
                        } catch (Throwable loopErr) {
                            System.out.println("[AuxMon] UI Looper caught exception: " + loopErr);
                        }
                    }
                } catch (Throwable t) {
                    uiLooperLatch.countDown();
                    System.out.println("[AuxMon] UI Looper init failed: " + t);
                }
            }
        }, "AuxMonUiThread");
        uiThread.setDaemon(true);
        uiThread.start();
        try {
            uiLooperLatch.await(3000L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
    }

    /** Opens the full-screen panel (user-initiated: app icon, show/start/demo/export commands). */
    public static void showOverlay() {
        postToUi(new Runnable() {
            @Override
            public void run() {
                compactMode = false;
                showOnUiThread();
            }
        });
    }

    /**
     * Shows only the small pill (automatic pop-ups such as "driving resumed"), so the panel never
     * covers navigation while the vehicle is being driven. An already open full-screen panel is
     * shrunk to the pill as well.
     */
    public static void showOverlayCompact() {
        postToUi(new Runnable() {
            @Override
            public void run() {
                compactMode = true;
                showOnUiThread();
            }
        });
    }

    public static void hideOverlay() {
        postToUi(new Runnable() {
            @Override
            public void run() {
                hideOnUiThread();
            }
        });
    }

    public static void toggleOverlay() {
        postToUi(new Runnable() {
            @Override
            public void run() {
                if (isVisible) {
                    hideOnUiThread();
                } else {
                    compactMode = false;
                    showOnUiThread();
                }
            }
        });
    }

    public static void requestRefresh() {
        postToUi(REFRESH_UI_TASK);
    }

    // ========================================================================
    // HANDLER HELPERS
    // ========================================================================

    private static void postToUi(Runnable r) {
        Object h = uiHandler;
        if (h == null) return;
        try {
            h.getClass().getMethod("post", Runnable.class).invoke(h, r);
        } catch (Throwable ignored) {
        }
    }

    private static void postDelayedToUi(Runnable r, long delayMs) {
        Object h = uiHandler;
        if (h == null) return;
        try {
            h.getClass().getMethod("postDelayed", Runnable.class, long.class).invoke(h, r, delayMs);
        } catch (Throwable ignored) {
        }
    }

    private static void removeCallbacksFromUi(Runnable r) {
        Object h = uiHandler;
        if (h == null) return;
        try {
            h.getClass().getMethod("removeCallbacks", Runnable.class).invoke(h, r);
        } catch (Throwable ignored) {
        }
    }

    private static boolean ensureUiContextOnUiThread() {
        if (sysContext != null && windowManager != null) {
            return true;
        }
        try {
            Class<?> smCls = Class.forName("android.os.ServiceManager");
            Method getSvc = smCls.getMethod("getService", String.class);
            if (getSvc.invoke(null, "window") == null
                    || getSvc.invoke(null, "display") == null
                    || getSvc.invoke(null, "activity") == null) {
                return false;
            }
            Class<?> atCls = Class.forName("android.app.ActivityThread");
            Object at = null;
            try {
                at = atCls.getMethod("currentActivityThread").invoke(null);
            } catch (Throwable ignored) {
            }
            if (at == null) {
                at = atCls.getMethod("systemMain").invoke(null);
            }
            if (at != null && sysContext == null) {
                sysContext = atCls.getMethod("getSystemContext").invoke(at);
            }
            if (sysContext != null && windowManager == null) {
                Method getSysSvc = sysContext.getClass().getMethod("getSystemService", String.class);
                windowManager = getSysSvc.invoke(sysContext, "window");
            }
            if (sysContext != null) {
                try {
                    Object res = sysContext.getClass().getMethod("getResources").invoke(sysContext);
                    Object dm = res.getClass().getMethod("getDisplayMetrics").invoke(res);
                    float d = dm.getClass().getField("density").getFloat(dm);
                    if (d > 0.5f && d < 5.0f) {
                        density = d;
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            sysContext = null;
            windowManager = null;
        }
        return sysContext != null && windowManager != null;
    }

    // ========================================================================
    // REFLECTION HELPERS
    // ========================================================================

    private static void ensureReflection() throws Exception {
        if (reflReady) return;
        Class<?> ctxCls = Class.forName("android.content.Context");
        cView = Class.forName("android.view.View");
        Class<?> vgCls = Class.forName("android.view.ViewGroup");
        Class<?> vgLpCls = Class.forName("android.view.ViewGroup$LayoutParams");
        cLinear = Class.forName("android.widget.LinearLayout");
        Class<?> llLpCls = Class.forName("android.widget.LinearLayout$LayoutParams");
        Class<?> flLpCls = Class.forName("android.widget.FrameLayout$LayoutParams");
        cTextView = Class.forName("android.widget.TextView");
        Class<?> ivCls = Class.forName("android.widget.ImageView");
        Class<?> svCls = Class.forName("android.widget.ScrollView");
        Class<?> mlpCls = Class.forName("android.view.ViewGroup$MarginLayoutParams");

        ctorLinear = cLinear.getConstructor(ctxCls);
        ctorText = cTextView.getConstructor(ctxCls);
        ctorImage = ivCls.getConstructor(ctxCls);
        ctorScroll = svCls.getConstructor(ctxCls);
        ctorLlLp2 = llLpCls.getConstructor(int.class, int.class);
        ctorLlLp3 = llLpCls.getConstructor(int.class, int.class, float.class);
        ctorFlLp2 = flLpCls.getConstructor(int.class, int.class);

        mAddView = vgCls.getMethod("addView", cView, vgLpCls);
        mSetPadding = cView.getMethod("setPadding", int.class, int.class, int.class, int.class);
        mSetVisibility = cView.getMethod("setVisibility", int.class);
        mSetAlpha = cView.getMethod("setAlpha", float.class);
        mSetMinHeight = cView.getMethod("setMinimumHeight", int.class);
        mSetOrientation = cLinear.getMethod("setOrientation", int.class);
        mLlSetGravity = cLinear.getMethod("setGravity", int.class);
        mTvSetGravity = cTextView.getMethod("setGravity", int.class);
        mSetText = cTextView.getMethod("setText", CharSequence.class);
        mSetTextColor = cTextView.getMethod("setTextColor", int.class);
        mSetTextSize = cTextView.getMethod("setTextSize", int.class, float.class);
        mSetMargins = mlpCls.getMethod("setMargins", int.class, int.class, int.class, int.class);
        reflReady = true;
    }

    private static int dp(float v) {
        return Math.round(v * density);
    }

    private static Object vbox() throws Exception {
        Object ll = ctorLinear.newInstance(sysContext);
        mSetOrientation.invoke(ll, 1);
        return ll;
    }

    private static Object hbox() throws Exception {
        Object ll = ctorLinear.newInstance(sysContext);
        mSetOrientation.invoke(ll, 0);
        mLlSetGravity.invoke(ll, GRAVITY_CENTER_VERTICAL);
        return ll;
    }

    private static Object text(String s, float sp, int color, boolean bold) throws Exception {
        Object tv = ctorText.newInstance(sysContext);
        mSetText.invoke(tv, s);
        mSetTextSize.invoke(tv, 2, sp); // COMPLEX_UNIT_SP
        mSetTextColor.invoke(tv, color);
        if (bold) {
            Class<?> tfCls = Class.forName("android.graphics.Typeface");
            Object defaultBold = tfCls.getField("DEFAULT_BOLD").get(null);
            cTextView.getMethod("setTypeface", tfCls, int.class).invoke(tv, defaultBold, 1);
        }
        try {
            cTextView.getMethod("setLineSpacing", float.class, float.class).invoke(tv, 0f, 1.12f);
        } catch (Throwable ignored) {
        }
        return tv;
    }

    private static void maxLines(Object tv, int lines) {
        try {
            cTextView.getMethod("setMaxLines", int.class).invoke(tv, lines);
            Class<?> truncCls = Class.forName("android.text.TextUtils$TruncateAt");
            Object end = truncCls.getField("END").get(null);
            cTextView.getMethod("setEllipsize", truncCls).invoke(tv, end);
        } catch (Throwable ignored) {
        }
    }

    private static Object lp(int w, int h) throws Exception {
        return ctorLlLp2.newInstance(w, h);
    }

    private static Object lpw(int w, int h, float weight) throws Exception {
        return ctorLlLp3.newInstance(w, h, weight);
    }

    private static Object margins(Object lp, int l, int t, int r, int b) throws Exception {
        mSetMargins.invoke(lp, dp(l), dp(t), dp(r), dp(b));
        return lp;
    }

    private static void add(Object parent, Object child, Object lp) throws Exception {
        mAddView.invoke(parent, child, lp);
    }

    private static void pad(Object v, int l, int t, int r, int b) throws Exception {
        mSetPadding.invoke(v, dp(l), dp(t), dp(r), dp(b));
    }

    private static void vis(Object v, boolean visible) {
        if (v == null) return;
        try {
            mSetVisibility.invoke(v, visible ? VISIBLE : GONE);
        } catch (Throwable ignored) {
        }
    }

    private static void setText(Object tv, String s) {
        if (tv == null) return;
        try {
            mSetText.invoke(tv, s);
        } catch (Throwable ignored) {
        }
    }

    private static void setColor(Object tv, int color) {
        if (tv == null) return;
        try {
            mSetTextColor.invoke(tv, color);
        } catch (Throwable ignored) {
        }
    }

    /** Rounded background with stroke; skipped when the same colors are already applied to this view. */
    private static void bg(Object view, int fill, int stroke, float strokeDp, float radiusDp) {
        if (view == null) return;
        long key = (((long) fill) << 32) ^ (stroke & 0xFFFFFFFFL) ^ ((long) (radiusDp * 7f + strokeDp * 131f) << 20);
        Long prev = bgCache.get(view);
        if (prev != null && prev.longValue() == key) return;
        try {
            Class<?> gdCls = Class.forName("android.graphics.drawable.GradientDrawable");
            Class<?> drawableCls = Class.forName("android.graphics.drawable.Drawable");
            Object gd = gdCls.getConstructor().newInstance();
            gdCls.getMethod("setColor", int.class).invoke(gd, fill);
            gdCls.getMethod("setCornerRadius", float.class).invoke(gd, (float) dp(radiusDp));
            if (strokeDp > 0f) {
                gdCls.getMethod("setStroke", int.class, int.class).invoke(gd, Math.max(1, dp(strokeDp)), stroke);
            }
            cView.getMethod("setBackgroundDrawable", drawableCls).invoke(view, gd);
            bgCache.put(view, Long.valueOf(key));
        } catch (Throwable ignored) {
        }
    }

    // ---- Button styles ------------------------------------------------------

    private static Object button(String label, float sp, Runnable action) throws Exception {
        Object b = text(label, sp, C_TEXT, true);
        mTvSetGravity.invoke(b, GRAVITY_CENTER);
        pad(b, 18, 10, 18, 10);
        mSetMinHeight.invoke(b, dp(48));
        styleSecondary(b);
        attachTouch(b, action, false);
        return b;
    }

    private static void styleSecondary(Object b) {
        bg(b, C_SURFACE_2, C_BORDER, 1f, 10f);
        setColor(b, C_TEXT);
    }

    private static void styleChip(Object b, boolean selected) {
        if (selected) {
            bg(b, C_BLUE, C_BLUE, 1f, 10f);
            setColor(b, C_ON_ACCENT);
        } else {
            bg(b, C_SURFACE_2, C_BORDER, 1f, 10f);
            setColor(b, C_TEXT);
        }
    }

    // ========================================================================
    // VIEW TREE
    // ========================================================================

    private static Object buildOverlayViewTree() throws Exception {
        ensureReflection();
        bgCache.clear();

        Object root = vbox();

        // --------------------------------------------------------------------
        // A. Compact pill (draggable, tap to open)
        // --------------------------------------------------------------------
        Object compactRow = hbox();
        pad(compactRow, 16, 8, 8, 8);
        Object cTv = text("🔋 주차 절전 유지", 15f, C_TEXT, true);
        add(compactRow, cTv, lp(WRAP_CONTENT, WRAP_CONTENT));
        Object openChip = text("크게 보기", 14f, C_ON_ACCENT, true);
        mTvSetGravity.invoke(openChip, GRAVITY_CENTER);
        pad(openChip, 14, 8, 14, 8);
        bg(openChip, C_BLUE, C_BLUE, 1f, 10f);
        add(compactRow, openChip, margins(lp(WRAP_CONTENT, WRAP_CONTENT), 14, 0, 0, 0));
        attachTouch(compactRow, new Runnable() {
            @Override
            public void run() {
                setCompactModeOnUiThread(false);
            }
        }, true);
        add(root, compactRow, lp(WRAP_CONTENT, WRAP_CONTENT));

        // --------------------------------------------------------------------
        // B. Full-screen panel
        // --------------------------------------------------------------------
        Object full = vbox();
        pad(full, 20, 12, 20, 12);

        // B-1. Header
        Object header = hbox();
        Object title = text("🔋 주차 절전 유지", 22f, C_TEXT, true);
        add(header, title, lp(WRAP_CONTENT, WRAP_CONTENT));
        Object subtitle = text("시동을 꺼도 헤드유닛을 절전 상태로 유지하고 12V 보조배터리를 기록합니다", 14f, C_TEXT_SUB, false);
        maxLines(subtitle, 1);
        add(header, subtitle, margins(lpw(0, WRAP_CONTENT, 1f), 14, 0, 12, 0));
        Object minBtn = button("작게 보기", 15f, new Runnable() {
            @Override
            public void run() {
                setCompactModeOnUiThread(true);
            }
        });
        add(header, minBtn, margins(lp(WRAP_CONTENT, WRAP_CONTENT), 0, 0, 10, 0));
        Object closeBtn = button("닫기  ✕", 15f, new Runnable() {
            @Override
            public void run() {
                hideOnUiThread();
            }
        });
        add(header, closeBtn, lp(WRAP_CONTENT, WRAP_CONTENT));
        add(full, header, lp(MATCH_PARENT, WRAP_CONTENT));

        // B-2. Body
        Object body = hbox();
        mLlSetGravity.invoke(body, GRAVITY_TOP_LEFT);
        add(body, buildHeroColumn(), lp(dp(400), MATCH_PARENT));
        add(body, buildTabColumn(), margins(lpw(0, MATCH_PARENT, 1f), 14, 0, 0, 0));
        add(full, body, margins(lpw(MATCH_PARENT, 0, 1f), 0, 10, 0, 0));

        // B-3. Banner
        Object banner = text("", 14f, C_AMBER, false);
        maxLines(banner, 1);
        pad(banner, 14, 6, 14, 6);
        bg(banner, C_SURFACE, C_BORDER, 1f, 10f);
        add(full, banner, margins(lp(MATCH_PARENT, WRAP_CONTENT), 0, 10, 0, 0));
        bannerTv = banner;

        add(root, full, lp(MATCH_PARENT, MATCH_PARENT));

        compactRowView = compactRow;
        compactTextView = cTv;
        fullBoxView = full;
        selectTabViews(currentTab);
        return root;
    }

    private static Object buildHeroColumn() throws Exception {
        Object col = vbox();
        pad(col, 20, 12, 20, 12);
        bg(col, C_SURFACE, C_BORDER, 1f, 14f);

        add(col, text("현재 상태", 14f, C_TEXT_SUB, true), lp(MATCH_PARENT, WRAP_CONTENT));

        heroTitleTv = text("대기 중", 26f, C_BLUE, true);
        maxLines(heroTitleTv, 1);
        add(col, heroTitleTv, margins(lp(MATCH_PARENT, WRAP_CONTENT), 0, 2, 0, 0));

        heroDescTv = text("", 15f, C_TEXT, false);
        maxLines(heroDescTv, 3);
        add(col, heroDescTv, margins(lp(MATCH_PARENT, WRAP_CONTENT), 0, 4, 0, 0));

        heroTimeCaptionTv = text("절전 유지 경과 시간", 14f, C_TEXT_SUB, true);
        add(col, heroTimeCaptionTv, margins(lp(MATCH_PARENT, WRAP_CONTENT), 0, 10, 0, 0));

        heroTimeTv = text("--:--:--", 32f, C_TEXT, true);
        add(col, heroTimeTv, lp(MATCH_PARENT, WRAP_CONTENT));

        heroTargetTv = text("", 14f, C_TEXT_SUB, false);
        maxLines(heroTargetTv, 1);
        add(col, heroTargetTv, lp(MATCH_PARENT, WRAP_CONTENT));

        add(col, ctorLinear.newInstance(sysContext), lpw(MATCH_PARENT, 0, 1f)); // spacer

        primaryBtnTv = text("▶  지금 절전 유지 시작", 20f, C_TEXT, true);
        mTvSetGravity.invoke(primaryBtnTv, GRAVITY_CENTER);
        mSetMinHeight.invoke(primaryBtnTv, dp(60));
        pad(primaryBtnTv, 12, 10, 12, 10);
        attachTouch(primaryBtnTv, new Runnable() {
            @Override
            public void run() {
                onPrimaryButton();
            }
        }, false);
        add(col, primaryBtnTv, lp(MATCH_PARENT, WRAP_CONTENT));

        primaryHintTv = text("", 13f, C_TEXT_SUB, false);
        mTvSetGravity.invoke(primaryHintTv, GRAVITY_CENTER);
        maxLines(primaryHintTv, 2);
        add(col, primaryHintTv, margins(lp(MATCH_PARENT, WRAP_CONTENT), 0, 6, 0, 0));
        return col;
    }

    private static Object buildTabColumn() throws Exception {
        Object col = vbox();

        Object tabBar = hbox();
        for (int i = 0; i < TAB_LABELS.length; i++) {
            final int idx = i;
            Object tab = text(TAB_LABELS[i], 17f, C_TEXT, true);
            mTvSetGravity.invoke(tab, GRAVITY_CENTER);
            mSetMinHeight.invoke(tab, dp(52));
            attachTouch(tab, new Runnable() {
                @Override
                public void run() {
                    selectTabOnUiThread(idx);
                }
            }, false);
            add(tabBar, tab, margins(lpw(0, WRAP_CONTENT, 1f), i == 0 ? 0 : 8, 0, 0, 0));
            tabButtons[i] = tab;
        }
        add(col, tabBar, lp(MATCH_PARENT, WRAP_CONTENT));

        Object pages = vbox();
        tabPages[TAB_OVERVIEW] = buildOverviewPage();
        tabPages[TAB_CHART] = buildChartPage();
        tabPages[TAB_HISTORY] = buildHistoryPage();
        tabPages[TAB_SETTINGS] = buildSettingsPage();
        for (int i = 0; i < tabPages.length; i++) {
            add(pages, tabPages[i], lp(MATCH_PARENT, MATCH_PARENT));
        }
        add(col, pages, margins(lpw(MATCH_PARENT, 0, 1f), 0, 10, 0, 0));
        return col;
    }

    // ---- Tab 1: overview ----------------------------------------------------

    private static Object buildOverviewPage() throws Exception {
        Object page = vbox();
        Object row1 = hbox();
        Object row2 = hbox();
        Object[] t1 = buildTile("12V 보조배터리 전압");
        Object[] t2 = buildTile("보조배터리 충전율");
        Object[] t3 = buildTile("주차 중 자동 충전");
        Object[] t4 = buildTile("방전 보호");
        add(row1, t1[0], margins(lpw(0, MATCH_PARENT, 1f), 0, 0, 5, 0));
        add(row1, t2[0], margins(lpw(0, MATCH_PARENT, 1f), 5, 0, 0, 0));
        add(row2, t3[0], margins(lpw(0, MATCH_PARENT, 1f), 0, 0, 5, 0));
        add(row2, t4[0], margins(lpw(0, MATCH_PARENT, 1f), 5, 0, 0, 0));
        add(page, row1, margins(lpw(MATCH_PARENT, 0, 1f), 0, 0, 0, 5));
        add(page, row2, margins(lpw(MATCH_PARENT, 0, 1f), 0, 5, 0, 0));
        tileVoltValueTv = t1[1];
        tileVoltSubTv = t1[2];
        tileSocValueTv = t2[1];
        tileSocSubTv = t2[2];
        tileChgValueTv = t3[1];
        tileChgSubTv = t3[2];
        tileProtValueTv = t4[1];
        tileProtSubTv = t4[2];
        return page;
    }

    private static Object[] buildTile(String label) throws Exception {
        Object tile = vbox();
        mLlSetGravity.invoke(tile, GRAVITY_CENTER_VERTICAL);
        pad(tile, 18, 10, 18, 10);
        bg(tile, C_SURFACE, C_BORDER, 1f, 14f);
        add(tile, text(label, 15f, C_TEXT_SUB, true), lp(MATCH_PARENT, WRAP_CONTENT));
        Object value = text("--", 30f, C_TEXT, true);
        maxLines(value, 1);
        add(tile, value, margins(lp(MATCH_PARENT, WRAP_CONTENT), 0, 2, 0, 2));
        Object sub = text("", 14f, C_TEXT_SUB, false);
        maxLines(sub, 2);
        add(tile, sub, lp(MATCH_PARENT, WRAP_CONTENT));
        return new Object[]{tile, value, sub};
    }

    // ---- Tab 2: chart -------------------------------------------------------

    private static Object buildChartPage() throws Exception {
        Object page = vbox();
        chartNavTv = buildSessionNavRow(page, false);

        Object box = vbox();
        pad(box, 8, 8, 8, 8);
        bg(box, C_SURFACE, C_BORDER, 1f, 14f);
        Class<?> ivCls = Class.forName("android.widget.ImageView");
        Class<?> scaleTypeCls = Class.forName("android.widget.ImageView$ScaleType");
        Object iv = ctorImage.newInstance(sysContext);
        ivCls.getMethod("setScaleType", scaleTypeCls).invoke(iv, scaleTypeCls.getField("FIT_XY").get(null));
        add(box, iv, lp(MATCH_PARENT, MATCH_PARENT));
        add(page, box, margins(lpw(MATCH_PARENT, 0, 1f), 0, 8, 0, 0));
        chartImageView = iv;
        return page;
    }

    // ---- Tab 3: history -----------------------------------------------------

    private static Object buildHistoryPage() throws Exception {
        Object page = vbox();
        historyNavTv = buildSessionNavRow(page, true);

        Object scroll = ctorScroll.newInstance(sysContext);
        pad(scroll, 18, 12, 18, 12);
        bg(scroll, C_SURFACE, C_BORDER, 1f, 14f);
        Object bodyTv = text("", 15f, C_TEXT, false);
        add(scroll, bodyTv, ctorFlLp2.newInstance(MATCH_PARENT, WRAP_CONTENT));
        add(page, scroll, margins(lpw(MATCH_PARENT, 0, 1f), 0, 8, 0, 0));
        historyBodyTv = bodyTv;
        return page;
    }

    /** ◀ [session label] ▶ (+ USB export). Returns the label TextView. */
    private static Object buildSessionNavRow(Object parent, boolean withExport) throws Exception {
        Object row = hbox();
        Object prev = button("◀  이전", 15f, new Runnable() {
            @Override
            public void run() {
                AuxMonMain.selectAdjacentSession(-1);
                refreshOnUiThread();
            }
        });
        add(row, prev, lp(WRAP_CONTENT, WRAP_CONTENT));
        Object label = text("기록 없음", 15f, C_TEXT, true);
        mTvSetGravity.invoke(label, GRAVITY_CENTER);
        maxLines(label, 1);
        add(row, label, margins(lpw(0, WRAP_CONTENT, 1f), 10, 0, 10, 0));
        Object next = button("다음  ▶", 15f, new Runnable() {
            @Override
            public void run() {
                AuxMonMain.selectAdjacentSession(1);
                refreshOnUiThread();
            }
        });
        add(row, next, lp(WRAP_CONTENT, WRAP_CONTENT));
        if (withExport) {
            Object export = button("💾  USB로 내보내기", 15f, new Runnable() {
                @Override
                public void run() {
                    AuxMonMain.exportToUsbAsync();
                    refreshOnUiThread();
                }
            });
            add(row, export, margins(lp(WRAP_CONTENT, WRAP_CONTENT), 10, 0, 0, 0));
        }
        add(parent, row, lp(MATCH_PARENT, WRAP_CONTENT));
        return label;
    }

    // ---- Tab 4: settings ----------------------------------------------------

    private static Object buildSettingsPage() throws Exception {
        Object scroll = ctorScroll.newInstance(sysContext);
        Object list = vbox();

        // 1. Keep duration
        Object s1 = settingSection(list, "절전 유지 시간",
                "시동을 끈 뒤 헤드유닛 전원을 얼마나 유지할지 정합니다. 배터리 보호 조건에 걸리면 시간과 관계없이 꺼집니다.");
        Object hoursRow = hbox();
        for (int i = 0; i < HOUR_PRESETS.length; i++) {
            final int h = HOUR_PRESETS[i];
            Object chip = button(h == 0 ? "상시" : (h + "시간"), 15f, new Runnable() {
                @Override
                public void run() {
                    AuxMonMain.setTargetParkingHoursFromUi(h);
                    refreshOnUiThread();
                }
            });
            add(hoursRow, chip, margins(lp(WRAP_CONTENT, WRAP_CONTENT), 0, 0, 8, 0));
            hourChips[i] = chip;
        }
        add(s1, hoursRow, margins(lp(MATCH_PARENT, WRAP_CONTENT), 0, 10, 0, 0));
        Object fineRow = hbox();
        add(fineRow, text("세부 조절", 14f, C_TEXT_SUB, true), margins(lp(WRAP_CONTENT, WRAP_CONTENT), 0, 0, 12, 0));
        add(fineRow, button("−  1시간", 15f, new Runnable() {
            @Override
            public void run() {
                AuxMonMain.adjustTargetParkingHours(-1);
                refreshOnUiThread();
            }
        }), lp(WRAP_CONTENT, WRAP_CONTENT));
        hourValueTv = text("상시", 17f, C_BLUE, true);
        mTvSetGravity.invoke(hourValueTv, GRAVITY_CENTER);
        add(fineRow, hourValueTv, margins(lp(dp(120), WRAP_CONTENT), 8, 0, 8, 0));
        add(fineRow, button("+  1시간", 15f, new Runnable() {
            @Override
            public void run() {
                AuxMonMain.adjustTargetParkingHours(1);
                refreshOnUiThread();
            }
        }), lp(WRAP_CONTENT, WRAP_CONTENT));
        add(s1, fineRow, margins(lp(MATCH_PARENT, WRAP_CONTENT), 0, 10, 0, 0));

        // 2. Sample interval
        Object s2 = settingSection(list, "기록 간격",
                "주차 중 전압과 충전 상태를 저장하는 간격입니다. 짧을수록 자세하지만 차량 통신과 저장 공간을 더 씁니다.");
        Object intRow = hbox();
        for (int i = 0; i < INTERVAL_PRESETS.length; i++) {
            final int sec = INTERVAL_PRESETS[i];
            Object chip = button(sec + "초", 15f, new Runnable() {
                @Override
                public void run() {
                    AuxMonMain.setSampleIntervalSecFromUi(sec);
                    refreshOnUiThread();
                }
            });
            add(intRow, chip, margins(lp(dp(110), WRAP_CONTENT), 0, 0, 8, 0));
            intervalChips[i] = chip;
        }
        add(s2, intRow, margins(lp(MATCH_PARENT, WRAP_CONTENT), 0, 10, 0, 0));

        // 3. Auto-charge mode
        Object s3 = settingSection(list, "자동 충전 방식", null);
        modeDescTv = text("", 14f, C_TEXT_SUB, false);
        add(s3, modeDescTv, margins(lp(MATCH_PARENT, WRAP_CONTENT), 0, 2, 0, 0));
        Object modeRow = hbox();
        final int[] modes = new int[]{AuxMonModels.MODE_PASSIVE_OBSERVE, AuxMonModels.MODE_ACTIVE_1H_IG3};
        final String[] modeLabels = new String[]{"차량 기본 동작만 기록", "1시간마다 충전 요청"};
        for (int i = 0; i < modes.length; i++) {
            final int m = modes[i];
            Object chip = button(modeLabels[i], 15f, new Runnable() {
                @Override
                public void run() {
                    AuxMonMain.setAutoChargeModeFromUi(m);
                    refreshOnUiThread();
                }
            });
            add(modeRow, chip, margins(lp(WRAP_CONTENT, WRAP_CONTENT), 0, 0, 8, 0));
            modeChips[i] = chip;
        }
        add(s3, modeRow, margins(lp(MATCH_PARENT, WRAP_CONTENT), 0, 10, 0, 0));

        // 4. Test tools
        Object s4 = settingSection(list, "점검 도구",
                "실차 점검용 기능입니다. IG3 충전 테스트는 차량에 보조배터리 충전을 직접 요청합니다.");
        Object toolRow = hbox();
        ig3TestBtnTv = button("⚡  IG3 충전 테스트 시작", 15f, new Runnable() {
            @Override
            public void run() {
                AuxMonMain.toggleManualIg3Test();
                refreshOnUiThread();
            }
        });
        add(toolRow, ig3TestBtnTv, margins(lp(WRAP_CONTENT, WRAP_CONTENT), 0, 0, 8, 0));
        add(toolRow, button("📋  샘플 기록 만들기", 15f, new Runnable() {
            @Override
            public void run() {
                AuxMonMain.loadDemoParkingSession();
                refreshOnUiThread();
            }
        }), lp(WRAP_CONTENT, WRAP_CONTENT));
        add(s4, toolRow, margins(lp(MATCH_PARENT, WRAP_CONTENT), 0, 10, 0, 0));

        add(scroll, list, ctorFlLp2.newInstance(MATCH_PARENT, WRAP_CONTENT));
        return scroll;
    }

    private static Object settingSection(Object list, String title, String desc) throws Exception {
        Object sec = vbox();
        pad(sec, 18, 14, 18, 14);
        bg(sec, C_SURFACE, C_BORDER, 1f, 14f);
        add(sec, text(title, 18f, C_TEXT, true), lp(MATCH_PARENT, WRAP_CONTENT));
        if (desc != null) {
            add(sec, text(desc, 14f, C_TEXT_SUB, false), margins(lp(MATCH_PARENT, WRAP_CONTENT), 0, 2, 0, 0));
        }
        add(list, sec, margins(lp(MATCH_PARENT, WRAP_CONTENT), 0, 0, 0, 10));
        return sec;
    }

    // ========================================================================
    // ACTIONS
    // ========================================================================

    private static void onPrimaryButton() {
        long nowRt = AuxMonModels.elapsedRealtimeMs();
        if (AuxMonMain.isCurrentlyMonitoringParking()) {
            if (nowRt < stopConfirmUntilRt) {
                stopConfirmUntilRt = 0L;
                AuxMonMain.stopActiveParkingSessionManual();
            } else {
                // Stopping while parked powers the headunit off immediately -> require a second tap
                stopConfirmUntilRt = nowRt + STOP_CONFIRM_WINDOW_MS;
                postDelayedToUi(REFRESH_UI_TASK, STOP_CONFIRM_WINDOW_MS + 100L);
            }
        } else {
            stopConfirmUntilRt = 0L;
            AuxMonMain.toggleManualParkingSession();
        }
        refreshOnUiThread();
    }

    private static void selectTabOnUiThread(int idx) {
        currentTab = idx;
        selectTabViews(idx);
        refreshOnUiThread();
        // The chart is sized from the ImageView after layout; redraw once the new page is measured.
        postDelayedToUi(REFRESH_UI_TASK, 120L);
    }

    private static void selectTabViews(int idx) {
        for (int i = 0; i < tabPages.length; i++) {
            vis(tabPages[i], i == idx);
            Object tab = tabButtons[i];
            if (tab == null) continue;
            if (i == idx) {
                bg(tab, C_BLUE, C_BLUE, 1f, 12f);
                setColor(tab, C_ON_ACCENT);
            } else {
                bg(tab, C_SURFACE, C_BORDER, 1f, 12f);
                setColor(tab, C_TEXT_SUB);
            }
        }
    }

    // ========================================================================
    // WINDOW MANAGEMENT
    // ========================================================================

    private static int[] getDisplaySizePx() {
        try {
            Object display = windowManager.getClass().getMethod("getDefaultDisplay").invoke(windowManager);
            Class<?> pointCls = Class.forName("android.graphics.Point");
            Object pt = pointCls.getConstructor().newInstance();
            try {
                display.getClass().getMethod("getRealSize", pointCls).invoke(display, pt);
            } catch (Throwable t) {
                display.getClass().getMethod("getSize", pointCls).invoke(display, pt);
            }
            int w = pointCls.getField("x").getInt(pt);
            int h = pointCls.getField("y").getInt(pt);
            if (w > 0 && h > 0) {
                return new int[]{w, h};
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void configureWmLp(Object wmLp) throws Exception {
        Class<?> wmLpCls = Class.forName("android.view.WindowManager$LayoutParams");
        wmLpCls.getField("gravity").setInt(wmLp, GRAVITY_TOP_LEFT);
        if (compactMode) {
            wmLpCls.getField("width").setInt(wmLp, WRAP_CONTENT);
            wmLpCls.getField("height").setInt(wmLp, WRAP_CONTENT);
            wmLpCls.getField("flags").setInt(wmLp, FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL | FLAG_LAYOUT_IN_SCREEN);
            wmLpCls.getField("x").setInt(wmLp, winX);
            wmLpCls.getField("y").setInt(wmLp, winY);
        } else {
            int[] size = getDisplaySizePx();
            wmLpCls.getField("width").setInt(wmLp, size != null ? size[0] : MATCH_PARENT);
            wmLpCls.getField("height").setInt(wmLp, size != null ? size[1] : MATCH_PARENT);
            wmLpCls.getField("flags").setInt(wmLp,
                    FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS | FLAG_FULLSCREEN);
            wmLpCls.getField("x").setInt(wmLp, 0);
            wmLpCls.getField("y").setInt(wmLp, 0);
        }
    }

    private static void applyRootStyle() {
        if (compactMode) {
            bg(rootOverlayView, 0xF2121B2D, C_BLUE, 2f, 14f);
        } else {
            bg(rootOverlayView, C_BG, C_BG, 0f, 0f);
        }
    }

    private static void setCompactModeOnUiThread(boolean compact) {
        compactMode = compact;
        stopConfirmUntilRt = 0L;
        vis(compactRowView, compact);
        vis(fullBoxView, !compact);
        applyRootStyle();
        Object root = rootOverlayView;
        Object lp = currentWmLp;
        if (root != null && lp != null && windowManager != null) {
            try {
                configureWmLp(lp);
                Class<?> vgLpCls = Class.forName("android.view.ViewGroup$LayoutParams");
                Class<?> vmCls = Class.forName("android.view.ViewManager");
                vmCls.getMethod("updateViewLayout", cView, vgLpCls).invoke(windowManager, root, lp);
            } catch (Throwable t) {
                System.out.println("[AuxMon] updateViewLayout failed: " + t);
            }
        }
        refreshOnUiThread();
        if (!compact) {
            postDelayedToUi(REFRESH_UI_TASK, 120L);
        }
    }

    private static void showOnUiThread() {
        try {
            removeCallbacksFromUi(RETRY_SHOW_TASK);
            if (!ensureUiContextOnUiThread()) {
                postDelayedToUi(RETRY_SHOW_TASK, 1000L);
                return;
            }
            if (isVisible && rootOverlayView != null) {
                setCompactModeOnUiThread(compactMode);
                return;
            }

            Class<?> viewCls = Class.forName("android.view.View");
            Class<?> vgLpCls = Class.forName("android.view.ViewGroup$LayoutParams");
            Class<?> wmLpCls = Class.forName("android.view.WindowManager$LayoutParams");
            Class<?> vmCls = Class.forName("android.view.ViewManager");

            int[] candidateTypes = new int[]{2020, 2008, 2009, 2005};
            Constructor<?> wmLpCtor = wmLpCls.getConstructor(int.class, int.class, int.class, int.class, int.class);
            Method addViewMethod = vmCls.getMethod("addView", viewCls, vgLpCls);

            for (int i = 0; i < candidateTypes.length; i++) {
                int wtype = candidateTypes[i];
                Object candidateRoot = null;
                try {
                    candidateRoot = buildOverlayViewTree();
                    rootOverlayView = candidateRoot;
                    vis(compactRowView, compactMode);
                    vis(fullBoxView, !compactMode);
                    applyRootStyle();

                    Object wmLp = wmLpCtor.newInstance(WRAP_CONTENT, WRAP_CONTENT, wtype, FLAG_NOT_FOCUSABLE, PIXEL_FORMAT_TRANSLUCENT);
                    configureWmLp(wmLp);
                    wmLpCls.getMethod("setTitle", CharSequence.class).invoke(wmLp, "HKGAuxMonOverlay");

                    addViewMethod.invoke(windowManager, candidateRoot, wmLp);

                    try {
                        Object vri = viewCls.getMethod("getViewRootImpl").invoke(candidateRoot);
                        if (vri != null) {
                            Field fMedia = vri.getClass().getDeclaredField("mMediaDisabled");
                            fMedia.setAccessible(true);
                            fMedia.setBoolean(vri, true);
                        }
                    } catch (Throwable ignored) {
                    }

                    currentWmLp = wmLp;
                    hiddenForRearCam = false;
                    isVisible = true;
                    System.out.println("[AuxMon] Overlay displayed (wtype=" + wtype + ", compact=" + compactMode + ")");
                    refreshOnUiThread();
                    postDelayedToUi(REFRESH_UI_TASK, 150L);
                    break;
                } catch (Throwable t) {
                    rootOverlayView = null;
                    if (candidateRoot != null) {
                        try {
                            windowManager.getClass().getMethod("removeViewImmediate", viewCls).invoke(windowManager, candidateRoot);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        } catch (Throwable t) {
            System.out.println("[AuxMon] showOnUiThread error: " + t);
        }
    }

    private static void hideOnUiThread() {
        removeCallbacksFromUi(REFRESH_UI_TASK);
        removeCallbacksFromUi(RETRY_SHOW_TASK);
        Object oldRoot = rootOverlayView;
        rootOverlayView = null;
        currentWmLp = null;
        isVisible = false;
        stopConfirmUntilRt = 0L;
        try {
            if (oldRoot != null && windowManager != null) {
                Class<?> viewCls = Class.forName("android.view.View");
                Class<?> vmCls = Class.forName("android.view.ViewManager");
                try {
                    viewCls.getMethod("setVisibility", int.class).invoke(oldRoot, GONE);
                } catch (Throwable ignored) {
                }
                try {
                    vmCls.getMethod("removeView", viewCls).invoke(windowManager, oldRoot);
                } catch (Throwable t) {
                    windowManager.getClass().getMethod("removeViewImmediate", viewCls).invoke(windowManager, oldRoot);
                }
            }
        } catch (Throwable ignored) {
        }
        bgCache.clear();
    }

    private static void updateWindowPositionOnUiThread(int newX, int newY) {
        Object root = rootOverlayView;
        Object lp = currentWmLp;
        if (root == null || lp == null || windowManager == null || !compactMode) return;
        try {
            int[] size = getDisplaySizePx();
            int maxX = (size != null) ? Math.max(0, size[0] - dp(120)) : 1800;
            int maxY = (size != null) ? Math.max(0, size[1] - dp(48)) : 660;
            winX = Math.max(0, Math.min(maxX, newX));
            winY = Math.max(0, Math.min(maxY, newY));

            Class<?> wmLpCls = Class.forName("android.view.WindowManager$LayoutParams");
            wmLpCls.getField("x").setInt(lp, winX);
            wmLpCls.getField("y").setInt(lp, winY);
            Class<?> vgLpCls = Class.forName("android.view.ViewGroup$LayoutParams");
            Class<?> vmCls = Class.forName("android.view.ViewManager");
            vmCls.getMethod("updateViewLayout", cView, vgLpCls).invoke(windowManager, root, lp);
        } catch (Throwable ignored) {
        }
    }

    // ========================================================================
    // REFRESH
    // ========================================================================

    private static void refreshOnUiThread() {
        if (!isVisible || rootOverlayView == null) return;
        removeCallbacksFromUi(REFRESH_UI_TASK);

        try {
            boolean rearCamOpen = "1".equals(AuxMonMain.getSystemProperty("persist.sys.rearcam.open", "0"));
            if (rearCamOpen) {
                if (!hiddenForRearCam) {
                    hiddenForRearCam = true;
                    vis(rootOverlayView, false);
                }
                postDelayedToUi(REFRESH_UI_TASK, 500L);
                return;
            } else if (hiddenForRearCam) {
                hiddenForRearCam = false;
                vis(rootOverlayView, true);
            }

            UiState st = UiState.capture();
            if (compactMode) {
                refreshCompact(st);
            } else {
                refreshHero(st);
                switch (currentTab) {
                    case TAB_OVERVIEW:
                        refreshOverview(st);
                        break;
                    case TAB_CHART:
                        refreshSessionNav(chartNavTv, st.displayed);
                        renderVoltageChartOnUiThread(st.displayed, st.targetHours);
                        break;
                    case TAB_HISTORY:
                        refreshSessionNav(historyNavTv, st.displayed);
                        setText(historyBodyTv, buildHistoryText(st.displayed));
                        break;
                    case TAB_SETTINGS:
                        refreshSettings(st);
                        break;
                    default:
                        break;
                }
                String msg = AuxMonMain.getStatusBannerMessage();
                setText(bannerTv, msg != null ? msg : "");
                setColor(bannerTv, AuxMonMain.getStatusBannerColor());
            }
        } catch (Throwable t) {
            System.out.println("[AuxMon] refresh error: " + t);
        }

        postDelayedToUi(REFRESH_UI_TASK, 1000L);
    }

    /** One consistent snapshot of everything the panel shows for a single refresh pass. */
    private static final class UiState {
        ParkingSession active;
        ParkingSession displayed;
        LiveTelemetrySnapshot live;
        boolean monitoring;
        boolean driving;
        boolean charging;
        boolean tripped;
        boolean autoKeep;
        int targetHours;
        String stateTitle;
        String stateDesc;
        int stateColor;

        static UiState capture() {
            UiState s = new UiState();
            s.active = AuxMonMain.getActiveParkingSession();
            s.displayed = AuxMonMain.getDisplayedSession();
            s.live = AuxMonMain.getLatestLiveSnapshot();
            s.monitoring = AuxMonMain.isCurrentlyMonitoringParking();
            s.driving = s.live != null && s.live.isDrivingState();
            s.charging = s.monitoring && ((s.live != null && s.live.isChargingActive())
                    || (s.active != null && s.active.getOngoingChargeEvent() != null));
            s.tripped = HkmcVehicleBridge.isSafetyCutoffTripped();
            s.autoKeep = HkmcVehicleBridge.isAutoKeepOnShutdownEnabled();
            s.targetHours = AuxMonMain.getConfiguredTargetHours();

            if (s.charging) {
                s.stateTitle = "자동 충전 중";
                s.stateDesc = "차량이 12V 보조배터리를 충전하고 있습니다. 절전 유지는 계속됩니다.";
                s.stateColor = C_GREEN;
            } else if (s.monitoring) {
                s.stateTitle = "절전 유지 중";
                s.stateDesc = s.driving
                        ? "기록을 진행하고 있습니다. 시동을 끄면 헤드유닛 전원을 계속 유지합니다."
                        : "시동이 꺼져도 헤드유닛 전원을 유지하고 있습니다. 배터리가 부족해지면 자동으로 꺼집니다.";
                s.stateColor = C_BLUE;
            } else if (s.tripped) {
                s.stateTitle = "배터리 보호로 중지됨";
                s.stateDesc = "보조배터리를 지키기 위해 절전 유지를 멈췄습니다. 다음 시동 ON 때 자동으로 다시 켜집니다.";
                s.stateColor = C_RED;
            } else if (!s.autoKeep) {
                s.stateTitle = "자동 유지 꺼짐";
                s.stateDesc = "이번 주차에는 절전 유지를 하지 않습니다. 다음 시동 ON 때 자동으로 다시 켜집니다.";
                s.stateColor = C_AMBER;
            } else if (s.driving) {
                s.stateTitle = "대기 중 · 시동 ON";
                s.stateDesc = "시동을 끄면 자동으로 헤드유닛 전원을 유지하고 기록을 시작합니다.";
                s.stateColor = C_BLUE;
            } else {
                s.stateTitle = "대기 중";
                s.stateDesc = "시동을 끄면 자동으로 헤드유닛 전원을 유지하고 기록을 시작합니다.";
                s.stateColor = C_TEXT;
            }
            return s;
        }
    }

    private static void refreshCompact(UiState st) {
        StringBuilder sb = new StringBuilder(96);
        sb.append("🔋 ").append(st.stateTitle);
        if (st.live != null && st.live.voltageV > 1.0f) {
            sb.append("  ·  ").append(String.format(Locale.US, "%.1fV", Float.valueOf(st.live.voltageV)));
        }
        if (st.live != null && st.live.isSocValid()) {
            sb.append("  ·  ").append(st.live.socPct).append('%');
        }
        if (st.monitoring && st.active != null) {
            sb.append("  ·  ").append(AuxMonModels.formatDurationHms(st.active.getElapsedParkingSec()));
        }
        setText(compactTextView, sb.toString());
        setColor(compactTextView, st.stateColor);
    }

    private static void refreshHero(UiState st) {
        setText(heroTitleTv, st.stateTitle);
        setColor(heroTitleTv, st.stateColor);
        setText(heroDescTv, st.stateDesc);

        if (st.monitoring && st.active != null) {
            setText(heroTimeCaptionTv, st.driving ? "기록 경과 시간" : "시동 OFF 후 경과 시간");
            setText(heroTimeTv, AuxMonModels.formatDurationHms(st.active.getElapsedParkingSec()));
            setColor(heroTimeTv, C_TEXT);
            int h = st.active.targetParkingHours;
            setText(heroTargetTv, h <= 0
                    ? "목표: 상시 유지 (배터리 보호 조건까지)"
                    : ("목표: " + h + "시간  ·  " + st.active.getProgressPercent() + "% 진행"));
        } else {
            setText(heroTimeCaptionTv, "절전 유지 경과 시간");
            setText(heroTimeTv, "--:--:--");
            setColor(heroTimeTv, C_TEXT_DIM);
            setText(heroTargetTv, "설정된 유지 시간: " + (st.targetHours <= 0 ? "상시" : (st.targetHours + "시간")));
        }

        long nowRt = AuxMonModels.elapsedRealtimeMs();
        if (st.monitoring) {
            if (nowRt < stopConfirmUntilRt) {
                setText(primaryBtnTv, "한 번 더 누르면 중지합니다");
                bg(primaryBtnTv, 0xFFDC2626, 0xFFDC2626, 1f, 14f);
                setColor(primaryBtnTv, 0xFFFFFFFF);
            } else {
                setText(primaryBtnTv, "■  절전 유지 중지");
                bg(primaryBtnTv, 0xFF3B1219, C_RED, 1.5f, 14f);
                setColor(primaryBtnTv, 0xFFFECACA);
            }
            setText(primaryHintTv, st.driving
                    ? "주행 중에는 기록만 멈추고, 다음 시동 OFF 때 다시 자동으로 유지합니다."
                    : "중지하면 헤드유닛 전원이 바로 꺼집니다.");
        } else {
            setText(primaryBtnTv, "▶  지금 절전 유지 시작");
            bg(primaryBtnTv, 0xFF0369A1, 0xFF0EA5E9, 1.5f, 14f);
            setColor(primaryBtnTv, 0xFFFFFFFF);
            if (st.tripped) {
                setText(primaryHintTv, "직접 시작하면 배터리 보호 차단이 해제됩니다. 배터리 상태를 확인한 뒤 누르세요.");
            } else if (!st.autoKeep) {
                setText(primaryHintTv, "누르면 지금 바로 절전 유지를 다시 시작합니다.");
            } else {
                setText(primaryHintTv, "보통은 누르지 않아도 됩니다. 시동을 끄면 자동으로 시작됩니다.");
            }
        }
    }

    private static void refreshOverview(UiState st) {
        LiveTelemetrySnapshot live = st.live;
        ParkingSession sess = st.monitoring ? st.active : null;

        // Tile 1: voltage
        if (live != null && live.voltageV > 1.0f) {
            setText(tileVoltValueTv, String.format(Locale.US, "%.2f V", Float.valueOf(live.voltageV)));
            setColor(tileVoltValueTv, C_TEXT);
            StringBuilder sub = new StringBuilder(live.isDirectVoltageMeasured() ? "실측값" : "추정값 (충전율로 계산)");
            if (sess != null && !sess.samples.isEmpty() && sess.maxVoltageV > 1.0f && sess.minVoltageV < 50f) {
                sub.append(String.format(Locale.US, "\n이번 주차 최저 %.2f V · 최고 %.2f V",
                        Float.valueOf(sess.minVoltageV), Float.valueOf(sess.maxVoltageV)));
            }
            setText(tileVoltSubTv, sub.toString());
        } else {
            setText(tileVoltValueTv, "--");
            setColor(tileVoltValueTv, C_TEXT_DIM);
            setText(tileVoltSubTv, "아직 읽지 못했습니다");
        }

        // Tile 2: SOC
        if (live != null && live.isSocValid()) {
            setText(tileSocValueTv, live.socPct + " %");
            setColor(tileSocValueTv, live.socPct < AuxMonMain.SAFETY_MIN_SOC_PCT + 5 ? C_AMBER : C_TEXT);
            StringBuilder sub = new StringBuilder();
            if (sess != null && sess.startSocPct > 0 && sess.startSocPct <= 100) {
                sub.append("주차 시작 ").append(sess.startSocPct).append("% → 지금 ").append(live.socPct).append('%');
            } else {
                sub.append("12V 보조배터리 충전 상태");
            }
            if (live.batSocStatusFlag == 1) {
                sub.append("\n차량이 배터리 상태를 '비정상'으로 보고했습니다");
            }
            setText(tileSocSubTv, sub.toString());
        } else {
            setText(tileSocValueTv, "--");
            setColor(tileSocValueTv, C_TEXT_DIM);
            setText(tileSocSubTv, "차량에서 충전율을 아직 받지 못했습니다.\n30분 넘게 모르면 안전을 위해 꺼집니다");
        }

        // Tile 3: auto-charge count
        if (sess != null) {
            int cnt = sess.getChargeCount();
            setText(tileChgValueTv, cnt + " 회");
            setColor(tileChgValueTv, st.charging ? C_GREEN : C_TEXT);
            if (st.charging) {
                setText(tileChgSubTv, "지금 충전 중입니다");
            } else if (cnt > 0) {
                setText(tileChgSubTv, "누적 충전 시간 " + AuxMonModels.formatDurationHms(sess.getTotalChargeDurationSec()));
            } else {
                setText(tileChgSubTv, "아직 없습니다. 차량이 자동 충전하면 여기에 기록됩니다");
            }
        } else {
            setText(tileChgValueTv, "-");
            setColor(tileChgValueTv, C_TEXT_DIM);
            setText(tileChgSubTv, "절전 유지 중에 횟수를 셉니다");
        }

        // Tile 4: discharge protection
        if (st.tripped) {
            setText(tileProtValueTv, "차단됨");
            setColor(tileProtValueTv, C_RED);
            setText(tileProtSubTv, "배터리를 지키기 위해 절전 유지를 멈췄습니다");
        } else if (live != null && (live.autoCutPreWrng > 0 || live.batEvtAlarm > 0 || live.batSocStatusFlag == 1)) {
            setText(tileProtValueTv, "경고");
            setColor(tileProtValueTv, C_RED);
            setText(tileProtSubTv, "차량이 배터리 경고를 보냈습니다. 곧 자동으로 꺼집니다");
        } else if (live != null && live.isSocValid() && live.socPct < AuxMonMain.SAFETY_MIN_SOC_PCT + 5) {
            setText(tileProtValueTv, "주의");
            setColor(tileProtValueTv, C_AMBER);
            setText(tileProtSubTv, "충전율이 " + AuxMonMain.SAFETY_MIN_SOC_PCT + "% 아래로 내려가면 자동으로 꺼집니다");
        } else {
            setText(tileProtValueTv, "정상");
            setColor(tileProtValueTv, C_GREEN);
            setText(tileProtSubTv, "충전율 " + AuxMonMain.SAFETY_MIN_SOC_PCT + "% 미만, 저전압, 차량 경고 시 자동으로 꺼집니다");
        }
    }

    private static void refreshSessionNav(Object labelTv, ParkingSession sess) {
        if (labelTv == null) return;
        if (sess == null) {
            setText(labelTv, "기록 없음");
            return;
        }
        String nav = AuxMonMain.getSessionNavLabel();
        int p = (nav != null) ? nav.lastIndexOf('(') : -1;
        String pos = (p >= 0) ? ("  " + nav.substring(p)) : "";
        String when = AuxMonModels.formatTimestampIso(sess.driveEndEpochMs);
        if (when.length() >= 16) {
            when = when.substring(5, 16); // MM-dd HH:mm
        }
        setText(labelTv, when + " 주차  ·  " + sessionStatusLabel(sess.status) + pos);
    }

    private static String sessionStatusLabel(int status) {
        switch (status) {
            case AuxMonModels.SESSION_MONITORING_PARKING:
                return "기록 중";
            case AuxMonModels.SESSION_COMPLETED_N_HOURS:
                return "완료";
            case AuxMonModels.SESSION_SAFETY_CUTOFF:
                return "배터리 보호로 종료";
            case AuxMonModels.SESSION_ENDED_BY_DRIVE:
                return "시동 ON으로 종료";
            case AuxMonModels.SESSION_INTERRUPTED_REBOOT:
                return "재부팅으로 중단";
            default:
                return "대기";
        }
    }

    private static String buildHistoryText(ParkingSession sess) {
        if (sess == null || sess.samples.isEmpty()) {
            return "아직 기록이 없습니다.\n\n시동을 끄면 자동으로 기록이 시작됩니다.\n"
                    + "화면을 미리 보고 싶다면 [설정] 탭의 [샘플 기록 만들기]를 누르세요.";
        }
        StringBuilder sb = new StringBuilder(1024);
        synchronized (sess) {
            long curElapsed = sess.getElapsedParkingSec();
            sb.append("주차 시작   ").append(AuxMonModels.formatTimestampIso(sess.driveEndEpochMs)).append('\n');
            sb.append("유지 시간   ").append(AuxMonModels.formatDurationHms(curElapsed))
                    .append("   ·   ").append(sessionStatusLabel(sess.status))
                    .append("   ·   기록 ").append(sess.samples.size()).append("건\n");
            sb.append("충전율       ").append(AuxMonModels.formatSocPct(sess.startSocPct))
                    .append("  →  ").append(AuxMonModels.formatSocPct(sess.latestSocPct)).append("\n\n");

            int cnt = sess.chargeEvents.size();
            sb.append("■ 자동 충전 ").append(cnt).append("회");
            if (cnt > 0) {
                sb.append("  (누적 ").append(AuxMonModels.formatDurationHms(sess.getTotalChargeDurationSec())).append(')');
            }
            sb.append('\n');
            if (cnt == 0) {
                sb.append("   주차 중 자동 충전이 아직 없었습니다.\n");
            } else {
                int shown = 0;
                for (int i = cnt - 1; i >= 0 && shown < 20; i--, shown++) {
                    ChargeEvent ce = sess.chargeEvents.get(i);
                    boolean ongoing = ce.endEpochMs <= 0L;
                    sb.append(String.format(Locale.US, "   #%d   %s ~ %s   (%s%s)\n",
                            Integer.valueOf(ce.chargeIndex),
                            shortTime(ce.startEpochMs),
                            ongoing ? "진행 중" : shortTime(ce.endEpochMs),
                            AuxMonModels.formatDurationHms(ce.getDurationSec(curElapsed)),
                            ongoing ? ", 충전 중" : ""));
                    sb.append(String.format(Locale.US, "         전압 %.2f → 최고 %.2f → %.2f V   ·   충전율 %s → %s\n",
                            Float.valueOf(ce.startVoltageV),
                            Float.valueOf(ce.peakVoltageV),
                            Float.valueOf(ongoing ? sess.latestVoltageV : ce.endVoltageV),
                            AuxMonModels.formatSocPct(ce.startSocPct),
                            AuxMonModels.formatSocPct(ongoing ? sess.latestSocPct : ce.endSocPct)));
                }
                if (cnt > 20) {
                    sb.append("   … 이전 ").append(cnt - 20).append("회는 USB로 내보내면 모두 볼 수 있습니다\n");
                }
            }

            sb.append("\n■ 전압 변화\n");
            int n = sess.samples.size();
            int checkpoints = Math.min(8, n);
            for (int k = 0; k < checkpoints; k++) {
                int idx = (checkpoints == 1) ? 0 : (k * (n - 1)) / (checkpoints - 1);
                VoltageSample vs = sess.samples.get(idx);
                sb.append(String.format(Locale.US, "   +%dh %02dm     %.2f V     %s\n",
                        Long.valueOf(vs.elapsedParkingSec / 3600L),
                        Long.valueOf((vs.elapsedParkingSec % 3600L) / 60L),
                        Float.valueOf(vs.voltageV),
                        AuxMonModels.formatSocPct(vs.socPct)));
            }
        }
        return sb.toString();
    }

    private static String shortTime(long epochMs) {
        String t = AuxMonModels.formatTimeOnly(epochMs);
        return t.length() >= 5 ? t.substring(0, 5) : t;
    }

    private static void refreshSettings(UiState st) {
        int hours = st.targetHours;
        for (int i = 0; i < HOUR_PRESETS.length; i++) {
            styleChip(hourChips[i], HOUR_PRESETS[i] == hours);
        }
        setText(hourValueTv, hours <= 0 ? "상시" : (hours + "시간"));

        int interval = AuxMonMain.getConfiguredSampleIntervalSec();
        for (int i = 0; i < INTERVAL_PRESETS.length; i++) {
            styleChip(intervalChips[i], INTERVAL_PRESETS[i] == interval);
        }

        int mode = AuxMonMain.getConfiguredAutoChargeMode();
        styleChip(modeChips[0], mode == AuxMonModels.MODE_PASSIVE_OBSERVE);
        styleChip(modeChips[1], mode == AuxMonModels.MODE_ACTIVE_1H_IG3);
        setText(modeDescTv, mode == AuxMonModels.MODE_ACTIVE_1H_IG3
                ? "주차 중 1시간마다 차량에 보조배터리 충전(IG3)을 요청합니다. 충전 중에는 12V 전압이 올라갑니다."
                : "차량의 자체 보조배터리 충전 기능(Aux. Battery Saver+)이 언제 동작하는지 그대로 기록만 합니다.");

        if (HkmcVehicleBridge.isManualIg3TestActive()) {
            setText(ig3TestBtnTv, "⚡  IG3 충전 테스트 중지");
            bg(ig3TestBtnTv, 0xFF064E3B, C_GREEN, 1f, 10f);
            setColor(ig3TestBtnTv, 0xFFA7F3D0);
        } else {
            setText(ig3TestBtnTv, "⚡  IG3 충전 테스트 시작");
            styleSecondary(ig3TestBtnTv);
        }
    }

    // ========================================================================
    // CHART
    // ========================================================================

    /** Draws voltage (V), SOC (%) and auto-charge bands, sized to the ImageView's measured size. */
    private static void renderVoltageChartOnUiThread(ParkingSession sess, int defaultHours) {
        if (chartImageView == null) return;
        try {
            int w = ((Integer) cView.getMethod("getWidth").invoke(chartImageView)).intValue();
            int h = ((Integer) cView.getMethod("getHeight").invoke(chartImageView)).intValue();
            if (w <= 0 || h <= 0) return; // not laid out yet; next refresh will draw

            Class<?> bmpCls = Class.forName("android.graphics.Bitmap");
            Class<?> cfgCls = Class.forName("android.graphics.Bitmap$Config");
            Class<?> canvasCls = Class.forName("android.graphics.Canvas");
            Class<?> paintCls = Class.forName("android.graphics.Paint");
            Class<?> styleCls = Class.forName("android.graphics.Paint$Style");
            Class<?> ivCls = Class.forName("android.widget.ImageView");

            if (chartBitmap == null || chartBmpW != w || chartBmpH != h) {
                ivCls.getMethod("setImageBitmap", bmpCls).invoke(chartImageView, (Object) null);
                if (chartBitmap != null) {
                    try {
                        bmpCls.getMethod("recycle").invoke(chartBitmap);
                    } catch (Throwable ignored) {
                    }
                }
                Object argb8888 = cfgCls.getField("ARGB_8888").get(null);
                chartBitmap = bmpCls.getMethod("createBitmap", int.class, int.class, cfgCls)
                        .invoke(null, Integer.valueOf(w), Integer.valueOf(h), argb8888);
                chartBmpW = w;
                chartBmpH = h;
            }

            bmpCls.getMethod("eraseColor", int.class).invoke(chartBitmap, Integer.valueOf(C_SURFACE));
            Object canvas = canvasCls.getConstructor(bmpCls).newInstance(chartBitmap);
            Object paint = paintCls.getConstructor(int.class).newInstance(Integer.valueOf(1)); // ANTI_ALIAS_FLAG
            Method setColorM = paintCls.getMethod("setColor", int.class);
            Method setStrokeWidth = paintCls.getMethod("setStrokeWidth", float.class);
            Method setTextSize = paintCls.getMethod("setTextSize", float.class);
            Method setStyle = paintCls.getMethod("setStyle", styleCls);
            Method measureText = paintCls.getMethod("measureText", String.class);
            Object styleFill = styleCls.getField("FILL").get(null);
            Object styleStroke = styleCls.getField("STROKE").get(null);
            Method drawLine = canvasCls.getMethod("drawLine", float.class, float.class, float.class, float.class, paintCls);
            Method drawRect = canvasCls.getMethod("drawRect", float.class, float.class, float.class, float.class, paintCls);
            Method drawText = canvasCls.getMethod("drawText", String.class, float.class, float.class, paintCls);

            final float padLeft = dp(56);
            final float padRight = dp(48);
            final float padTop = dp(34);
            final float padBottom = dp(28);
            final float plotW = w - padLeft - padRight;
            final float plotH = h - padTop - padBottom;
            final float minV = 11.0f;
            final float maxV = 15.0f;
            final float fsLabel = 13f * density;
            final float fsAxis = 12f * density;

            // Title + legend
            setStyle.invoke(paint, styleFill);
            setTextSize.invoke(paint, Float.valueOf(fsLabel));
            setColorM.invoke(paint, Integer.valueOf(C_TEXT_SUB));
            drawText.invoke(canvas, "주차 중 12V 보조배터리 전압 · 충전율 · 자동 충전 구간", Float.valueOf(padLeft), Float.valueOf(dp(20)), paint);

            String[] legends = new String[]{"━ 전압 (왼쪽 축)", "━ 충전율 (오른쪽 축)", "█ 자동 충전"};
            int[] legendColors = new int[]{C_BLUE, 0xFFFB923C, C_GREEN};
            float lx = w - padRight;
            for (int i = legends.length - 1; i >= 0; i--) {
                float tw = ((Float) measureText.invoke(paint, legends[i])).floatValue();
                lx -= tw;
                setColorM.invoke(paint, Integer.valueOf(legendColors[i]));
                drawText.invoke(canvas, legends[i], Float.valueOf(lx), Float.valueOf(dp(20)), paint);
                lx -= dp(18);
            }

            // Horizontal grid (11..15 V / 0..100 %)
            setStrokeWidth.invoke(paint, Float.valueOf(Math.max(1f, density * 0.7f)));
            setTextSize.invoke(paint, Float.valueOf(fsAxis));
            for (int v = 11; v <= 15; v++) {
                float y = padTop + plotH - ((v - minV) / (maxV - minV)) * plotH;
                setColorM.invoke(paint, Integer.valueOf(v == 12 ? 0xFF3B4A66 : C_BORDER));
                drawLine.invoke(canvas, Float.valueOf(padLeft), Float.valueOf(y), Float.valueOf(padLeft + plotW), Float.valueOf(y), paint);
                setColorM.invoke(paint, Integer.valueOf(C_TEXT_DIM));
                drawText.invoke(canvas, v + "V", Float.valueOf(dp(10)), Float.valueOf(y + dp(4)), paint);
                drawText.invoke(canvas, ((v - 11) * 25) + "%", Float.valueOf(padLeft + plotW + dp(6)), Float.valueOf(y + dp(4)), paint);
            }

            int hours = (sess != null && sess.targetParkingHours > 0) ? sess.targetParkingHours : defaultHours;
            long maxTimeSec = Math.max(3600L, (long) Math.max(1, hours) * 3600L);
            if (sess != null && sess.getElapsedParkingSec() > maxTimeSec) {
                maxTimeSec = sess.getElapsedParkingSec();
            }
            if (sess != null && hours <= 0) {
                // 상시 mode: fit the axis to what has been recorded (at least 1 h)
                maxTimeSec = Math.max(3600L, sess.getElapsedParkingSec());
            }

            // Vertical time grid
            int gridSteps = 6;
            for (int g = 0; g <= gridSteps; g++) {
                float x = padLeft + (plotW * g) / gridSteps;
                setColorM.invoke(paint, Integer.valueOf(C_BORDER));
                drawLine.invoke(canvas, Float.valueOf(x), Float.valueOf(padTop), Float.valueOf(x), Float.valueOf(padTop + plotH), paint);
                setColorM.invoke(paint, Integer.valueOf(C_TEXT_DIM));
                long labelSec = (maxTimeSec * g) / gridSteps;
                String tLbl = String.format(Locale.US, "+%dh%02dm", Long.valueOf(labelSec / 3600L), Long.valueOf((labelSec % 3600L) / 60L));
                float tw = ((Float) measureText.invoke(paint, tLbl)).floatValue();
                drawText.invoke(canvas, tLbl, Float.valueOf(x - tw / 2f), Float.valueOf(h - dp(8)), paint);
            }

            if (sess == null || sess.samples.isEmpty()) {
                setTextSize.invoke(paint, Float.valueOf(16f * density));
                setColorM.invoke(paint, Integer.valueOf(C_TEXT_SUB));
                String msg = "표시할 기록이 없습니다. 시동을 끄면 자동으로 기록됩니다.";
                float tw = ((Float) measureText.invoke(paint, msg)).floatValue();
                drawText.invoke(canvas, msg, Float.valueOf(padLeft + (plotW - tw) / 2f), Float.valueOf(padTop + plotH / 2f), paint);
            } else {
                synchronized (sess) {
                    // Charge bands
                    for (int i = 0; i < sess.chargeEvents.size(); i++) {
                        ChargeEvent ce = sess.chargeEvents.get(i);
                        float x1 = padLeft + Math.min(1.0f, (float) ce.startElapsedSec / (float) maxTimeSec) * plotW;
                        long endEl = (ce.endElapsedSec >= ce.startElapsedSec && ce.endEpochMs > 0L)
                                ? ce.endElapsedSec : sess.getElapsedParkingSec();
                        float x2 = padLeft + Math.min(1.0f, (float) Math.max(ce.startElapsedSec + 60L, endEl) / (float) maxTimeSec) * plotW;
                        if (x2 - x1 < dp(4)) x2 = x1 + dp(4);
                        setStyle.invoke(paint, styleFill);
                        setColorM.invoke(paint, Integer.valueOf(0x4034D399));
                        drawRect.invoke(canvas, Float.valueOf(x1), Float.valueOf(padTop), Float.valueOf(x2), Float.valueOf(padTop + plotH), paint);
                        setColorM.invoke(paint, Integer.valueOf(C_GREEN));
                        setTextSize.invoke(paint, Float.valueOf(fsAxis));
                        drawText.invoke(canvas, "#" + ce.chargeIndex, Float.valueOf(x1 + dp(3)), Float.valueOf(padTop + dp(14)), paint);
                    }

                    List<VoltageSample> pts = sess.samples;
                    int n = pts.size();
                    setStyle.invoke(paint, styleStroke);
                    if (n >= 2) {
                        // SOC line
                        setStrokeWidth.invoke(paint, Float.valueOf(1.6f * density));
                        setColorM.invoke(paint, Integer.valueOf(0xCCFB923C));
                        float prevX = 0f;
                        float prevY = 0f;
                        boolean havePrev = false;
                        for (int i = 0; i < n; i++) {
                            VoltageSample vs = pts.get(i);
                            if (vs.socPct <= 0 || vs.socPct > 100) {
                                havePrev = false; // unknown SOC -> break the line instead of dropping to 0 %
                                continue;
                            }
                            float x = padLeft + Math.min(1.0f, (float) vs.elapsedParkingSec / (float) maxTimeSec) * plotW;
                            float y = padTop + plotH - (vs.socPct / 100.0f) * plotH;
                            if (havePrev) {
                                drawLine.invoke(canvas, Float.valueOf(prevX), Float.valueOf(prevY), Float.valueOf(x), Float.valueOf(y), paint);
                            }
                            prevX = x;
                            prevY = y;
                            havePrev = true;
                        }

                        // Voltage line
                        setStrokeWidth.invoke(paint, Float.valueOf(2.4f * density));
                        setColorM.invoke(paint, Integer.valueOf(C_BLUE));
                        havePrev = false;
                        for (int i = 0; i < n; i++) {
                            VoltageSample vs = pts.get(i);
                            if (vs.voltageV < 1.0f) {
                                havePrev = false;
                                continue;
                            }
                            float x = padLeft + Math.min(1.0f, (float) vs.elapsedParkingSec / (float) maxTimeSec) * plotW;
                            float vClamped = Math.max(minV, Math.min(maxV, vs.voltageV));
                            float y = padTop + plotH - ((vClamped - minV) / (maxV - minV)) * plotH;
                            if (havePrev) {
                                drawLine.invoke(canvas, Float.valueOf(prevX), Float.valueOf(prevY), Float.valueOf(x), Float.valueOf(y), paint);
                            }
                            prevX = x;
                            prevY = y;
                            havePrev = true;
                        }
                    } else {
                        VoltageSample vs = pts.get(0);
                        float vClamped = Math.max(minV, Math.min(maxV, vs.voltageV));
                        float y = padTop + plotH - ((vClamped - minV) / (maxV - minV)) * plotH;
                        setColorM.invoke(paint, Integer.valueOf(C_BLUE));
                        setStrokeWidth.invoke(paint, Float.valueOf(3.0f * density));
                        drawLine.invoke(canvas, Float.valueOf(padLeft), Float.valueOf(y), Float.valueOf(padLeft + dp(12)), Float.valueOf(y), paint);
                    }
                }
            }

            ivCls.getMethod("setImageBitmap", bmpCls).invoke(chartImageView, chartBitmap);
            ivCls.getMethod("invalidate").invoke(chartImageView);
        } catch (Throwable ignored) {
        }
    }

    // ========================================================================
    // TOUCH
    // ========================================================================

    /**
     * Touch handler with press feedback (alpha). draggable=true is used only by the compact pill:
     * a drag moves the window, a tap runs the action. Non-draggable views cancel the click when the
     * finger slides away (so scrolling a ScrollView over a button does not trigger it).
     */
    private static void attachTouch(final Object view, final Runnable clickAction, final boolean draggable) throws Exception {
        try {
            cView.getMethod("setSoundEffectsEnabled", boolean.class).invoke(view, false);
            cView.getMethod("setHapticFeedbackEnabled", boolean.class).invoke(view, false);
        } catch (Throwable ignored) {
        }
        Class<?> touchListenerCls = Class.forName("android.view.View$OnTouchListener");
        cView.getMethod("setClickable", boolean.class).invoke(view, true);
        final int slop = dp(12);
        final int cancelSlop = dp(28);

        Object proxy = Proxy.newProxyInstance(
                touchListenerCls.getClassLoader(),
                new Class<?>[]{touchListenerCls},
                new InvocationHandler() {
                    private float downRawX = 0f;
                    private float downRawY = 0f;
                    private int startX = 0;
                    private int startY = 0;
                    private boolean dragging = false;
                    private boolean canceled = false;

                    private void pressed(boolean on) {
                        try {
                            mSetAlpha.invoke(view, on ? 0.6f : 1.0f);
                        } catch (Throwable ignored) {
                        }
                    }

                    @Override
                    public Object invoke(Object proxyObj, Method method, Object[] args) {
                        String mName = method.getName();
                        if ("equals".equals(mName)) {
                            return Boolean.valueOf(args != null && args.length == 1 && proxyObj == args[0]);
                        }
                        if ("hashCode".equals(mName)) {
                            return Integer.valueOf(System.identityHashCode(proxyObj));
                        }
                        if ("toString".equals(mName)) {
                            return "AuxMonTouchListener";
                        }
                        if ("onTouch".equals(mName) && args != null && args.length >= 2 && args[1] != null) {
                            try {
                                Object me = args[1];
                                Class<?> meCls = me.getClass();
                                int act = ((Integer) meCls.getMethod("getAction").invoke(me)).intValue() & 0xFF;
                                float rawX = ((Float) meCls.getMethod("getRawX").invoke(me)).floatValue();
                                float rawY = ((Float) meCls.getMethod("getRawY").invoke(me)).floatValue();

                                if (act == 0) { // DOWN
                                    downRawX = rawX;
                                    downRawY = rawY;
                                    startX = winX;
                                    startY = winY;
                                    dragging = false;
                                    canceled = false;
                                    pressed(true);
                                } else if (act == 2) { // MOVE
                                    int dx = Math.round(rawX - downRawX);
                                    int dy = Math.round(rawY - downRawY);
                                    if (draggable) {
                                        if (!dragging && (Math.abs(dx) > slop || Math.abs(dy) > slop)) {
                                            dragging = true;
                                            pressed(false);
                                        }
                                        if (dragging) {
                                            updateWindowPositionOnUiThread(startX + dx, startY + dy);
                                        }
                                    } else if (!canceled && (Math.abs(dx) > cancelSlop || Math.abs(dy) > cancelSlop)) {
                                        canceled = true;
                                        pressed(false);
                                    }
                                } else if (act == 1) { // UP
                                    pressed(false);
                                    if (!dragging && !canceled && clickAction != null) {
                                        clickAction.run();
                                    }
                                    dragging = false;
                                } else if (act == 3) { // CANCEL
                                    pressed(false);
                                    dragging = false;
                                    canceled = true;
                                }
                            } catch (Throwable ignored) {
                            }
                            return Boolean.TRUE;
                        }
                        return Boolean.TRUE;
                    }
                });
        cView.getMethod("setOnTouchListener", touchListenerCls).invoke(view, proxy);
    }
}
