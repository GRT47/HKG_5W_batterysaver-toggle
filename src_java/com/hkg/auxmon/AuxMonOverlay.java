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
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Interactive WindowManager Overlay Panel (TYPE_VOLUME_OVERLAY = 2020) for Gen5W (Android 4.4.2).
 *
 * Displays:
 *   1. N-hour parking configuration & Passive/Active-1h IG3 auto-charge mode controls
 *   2. 4 KPI summary cards (12V Voltage V, 12V SOC %, Charging Count, Elapsed/Target N hours)
 *   3. Real-time Canvas time-series voltage & IG3 charging timeline graph
 *   4. Charging event history table (#1, #2, ...) & recent voltage records
 *   5. One-click [데이터 내보내기 (USB 저장)] action button
 */
public final class AuxMonOverlay {

    private static final int MAX_SCREEN_X = 1840;
    private static final int MAX_SCREEN_Y = 680;
    private static final int CARD_WIDTH = 860;
    private static final int CHART_WIDTH = 828;
    private static final int CHART_HEIGHT = 156;

    private static volatile Object uiHandler = null;
    private static volatile Object sysContext = null;
    private static volatile Object windowManager = null;
    private static volatile Object rootOverlayView = null;
    private static volatile Object currentWmLp = null;

    // Views
    private static volatile Object compactRowView = null;
    private static volatile Object compactTextView = null;
    private static volatile Object expandedBoxView = null;

    private static volatile Object badgeTextView = null;
    private static volatile Object nHoursValueTv = null;
    private static volatile Object sampleIntervalBtnTv = null;
    private static volatile Object modeBtnTv = null;
    private static volatile Object sessionNavTv = null;

    // 4 KPI Cards
    private static volatile Object kpiVoltMainTv = null;
    private static volatile Object kpiVoltSubTv = null;
    private static volatile Object kpiSocMainTv = null;
    private static volatile Object kpiSocSubTv = null;
    private static volatile Object kpiChgMainTv = null;
    private static volatile Object kpiChgSubTv = null;
    private static volatile Object kpiTimeMainTv = null;
    private static volatile Object kpiTimeSubTv = null;

    // Canvas Chart ImageView & Bitmap
    private static volatile Object chartImageView = null;
    private static volatile Object chartBitmap = null;

    // Charge Event History & Log TextViews
    private static volatile Object chargeHistoryHeaderTv = null;
    private static volatile Object chargeHistoryBodyTv = null;

    // Bottom Action Buttons & Notice Banner
    private static volatile Object startStopParkingBtnTv = null;
    private static volatile Object manualIg3BtnTv = null;
    private static volatile Object exportUsbBtnTv = null;
    private static volatile Object bottomNoticeTv = null;

    private static volatile boolean isVisible = false;
    private static volatile boolean compactMode = false;
    private static volatile boolean hiddenForRearCam = false;
    private static volatile int winX = 480;
    private static volatile int winY = 24;
    private static volatile int lastBorderColor = 0;

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

    private AuxMonOverlay() {
    }

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

    public static void showOverlay() {
        postToUi(new Runnable() {
            @Override
            public void run() {
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
                    showOnUiThread();
                }
            }
        });
    }

    public static void requestRefresh() {
        postToUi(REFRESH_UI_TASK);
    }

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
        } catch (Throwable t) {
            sysContext = null;
            windowManager = null;
        }
        return sysContext != null && windowManager != null;
    }

    private static Object buildOverlayViewTree() throws Exception {
        Class<?> ctxCls = Class.forName("android.content.Context");
        Class<?> viewCls = Class.forName("android.view.View");
        Class<?> vgCls = Class.forName("android.view.ViewGroup");
        Class<?> vgLpCls = Class.forName("android.view.ViewGroup$LayoutParams");
        Class<?> llCls = Class.forName("android.widget.LinearLayout");
        Class<?> llLpCls = Class.forName("android.widget.LinearLayout$LayoutParams");
        Class<?> tvCls = Class.forName("android.widget.TextView");
        Class<?> ivCls = Class.forName("android.widget.ImageView");
        Class<?> scaleTypeCls = Class.forName("android.widget.ImageView$ScaleType");

        Constructor<?> llCtor = llCls.getConstructor(ctxCls);
        Constructor<?> tvCtor = tvCls.getConstructor(ctxCls);
        Constructor<?> ivCtor = ivCls.getConstructor(ctxCls);
        Constructor<?> llLpCtor2 = llLpCls.getConstructor(int.class, int.class);
        Constructor<?> llLpCtor3 = llLpCls.getConstructor(int.class, int.class, float.class);

        Method addView = vgCls.getMethod("addView", viewCls, vgLpCls);
        Method setPad = viewCls.getMethod("setPadding", int.class, int.class, int.class, int.class);
        Method setGravity = llCls.getMethod("setGravity", int.class);
        Method tvSetGravity = tvCls.getMethod("setGravity", int.class);

        final int MATCH_PARENT = -1;
        final int WRAP_CONTENT = -2;

        Object root = llCtor.newInstance(sysContext);
        llCls.getMethod("setOrientation", int.class).invoke(root, 1); // VERTICAL
        setRoundedBg(root, 0xF40B111E, 0xFF38BDF8, 2, 16f);
        lastBorderColor = 0xFF38BDF8;

        // --------------------------------------------------------------------
        // 1. Compact Pill View (when minimized)
        // --------------------------------------------------------------------
        Object compactRow = llCtor.newInstance(sysContext);
        llCls.getMethod("setOrientation", int.class).invoke(compactRow, 0);
        setGravity.invoke(compactRow, 16);
        setPad.invoke(compactRow, 18, 10, 18, 10);

        Object cTv = tvCtor.newInstance(sysContext);
        setTextViewStyle(cTv, "🔋 12V 보조배터리 주차 감시 중...", 14.5f, 0xFFF8FAFC, true);
        addView.invoke(compactRow, cTv, llLpCtor2.newInstance(WRAP_CONTENT, WRAP_CONTENT));
        attachButtonOrDragListener(compactRow, new Runnable() {
            @Override
            public void run() {
                compactMode = false;
                applyModeVisibilityOnUiThread();
                refreshOnUiThread();
            }
        });
        addView.invoke(root, compactRow, llLpCtor2.newInstance(WRAP_CONTENT, WRAP_CONTENT));

        // --------------------------------------------------------------------
        // 2. Expanded Panel Container
        // --------------------------------------------------------------------
        Object expBox = llCtor.newInstance(sysContext);
        llCls.getMethod("setOrientation", int.class).invoke(expBox, 1);
        setPad.invoke(expBox, 16, 12, 16, 12);

        // --- Row A: Top Title & Window Controls ---
        Object headerRow = llCtor.newInstance(sysContext);
        llCls.getMethod("setOrientation", int.class).invoke(headerRow, 0);
        setGravity.invoke(headerRow, 16);

        Object titleTv = tvCtor.newInstance(sysContext);
        setTextViewStyle(titleTv, "🔋 HKG 12V 보조배터리 주차 전압 & 자동충전(IG3) 기록기", 16.0f, 0xFFF8FAFC, true);
        attachDragOnlyListener(titleTv);
        addView.invoke(headerRow, titleTv, llLpCtor3.newInstance(0, WRAP_CONTENT, 1.0f));

        Object badgeTv = tvCtor.newInstance(sysContext);
        setTextViewStyle(badgeTv, "감시 대기", 12.0f, 0xFF0F172A, true);
        setPad.invoke(badgeTv, 12, 4, 12, 4);
        setRoundedBg(badgeTv, 0xFF38BDF8, 0xFF38BDF8, 0, 10f);
        Object badgeLp = llLpCtor2.newInstance(WRAP_CONTENT, WRAP_CONTENT);
        setMargins(badgeLp, 8, 0, 8, 0);
        addView.invoke(headerRow, badgeTv, badgeLp);

        Object minBtn = tvCtor.newInstance(sysContext);
        setTextViewStyle(minBtn, "접기 —", 12.5f, 0xFFCBD5E1, true);
        setPad.invoke(minBtn, 12, 4, 12, 4);
        setRoundedBg(minBtn, 0xFF1E293B, 0xFF334155, 1, 8f);
        attachButtonOrDragListener(minBtn, new Runnable() {
            @Override
            public void run() {
                compactMode = true;
                applyModeVisibilityOnUiThread();
                refreshOnUiThread();
            }
        });
        Object minLp = llLpCtor2.newInstance(WRAP_CONTENT, WRAP_CONTENT);
        setMargins(minLp, 0, 0, 8, 0);
        addView.invoke(headerRow, minBtn, minLp);

        Object closeBtn = tvCtor.newInstance(sysContext);
        setTextViewStyle(closeBtn, "닫기 ✕", 12.5f, 0xFFFCA5A5, true);
        setPad.invoke(closeBtn, 12, 4, 12, 4);
        setRoundedBg(closeBtn, 0xFF3B1219, 0xFFEF4444, 1, 8f);
        attachButtonOrDragListener(closeBtn, new Runnable() {
            @Override
            public void run() {
                hideOnUiThread();
            }
        });
        addView.invoke(headerRow, closeBtn, llLpCtor2.newInstance(WRAP_CONTENT, WRAP_CONTENT));
        addView.invoke(expBox, headerRow, llLpCtor2.newInstance(CARD_WIDTH, WRAP_CONTENT));

        // --- Row B: Configuration Bar (Parking N Hours + Auto-Charge Mode + Session Selector) ---
        Object ctrlRow = llCtor.newInstance(sysContext);
        llCls.getMethod("setOrientation", int.class).invoke(ctrlRow, 0);
        setGravity.invoke(ctrlRow, 16);
        setPad.invoke(ctrlRow, 10, 6, 10, 6);
        setRoundedBg(ctrlRow, 0xFF111C2D, 0xFF1E293B, 1, 10f);
        Object ctrlRowLp = llLpCtor2.newInstance(CARD_WIDTH, WRAP_CONTENT);
        setMargins(ctrlRowLp, 0, 8, 0, 8);

        Object nLabelTv = tvCtor.newInstance(sysContext);
        setTextViewStyle(nLabelTv, "절전유지:", 12.5f, 0xFF94A3B8, true);
        addView.invoke(ctrlRow, nLabelTv, llLpCtor2.newInstance(WRAP_CONTENT, WRAP_CONTENT));

        Object minusBtn = createChipButton(tvCtor, setPad, " - ", 0xFF1E293B, 0xFF475569, 0xFFF8FAFC, new Runnable() {
            @Override
            public void run() {
                AuxMonMain.adjustTargetParkingHours(-1);
                refreshOnUiThread();
            }
        });
        Object minusLp = llLpCtor2.newInstance(WRAP_CONTENT, WRAP_CONTENT);
        setMargins(minusLp, 6, 0, 4, 0);
        addView.invoke(ctrlRow, minusBtn, minusLp);

        Object nValTv = tvCtor.newInstance(sysContext);
        setTextViewStyle(nValTv, "상시(무제한)", 13.0f, 0xFF38BDF8, true);
        setPad.invoke(nValTv, 10, 3, 10, 3);
        setRoundedBg(nValTv, 0xFF0B1320, 0xFF0EA5E9, 1, 6f);
        attachButtonOrDragListener(nValTv, new Runnable() {
            @Override
            public void run() {
                AuxMonMain.cyclePresetParkingHours();
                refreshOnUiThread();
            }
        });
        addView.invoke(ctrlRow, nValTv, llLpCtor2.newInstance(WRAP_CONTENT, WRAP_CONTENT));

        Object plusBtn = createChipButton(tvCtor, setPad, " + ", 0xFF1E293B, 0xFF475569, 0xFFF8FAFC, new Runnable() {
            @Override
            public void run() {
                AuxMonMain.adjustTargetParkingHours(1);
                refreshOnUiThread();
            }
        });
        Object plusLp = llLpCtor2.newInstance(WRAP_CONTENT, WRAP_CONTENT);
        setMargins(plusLp, 4, 0, 8, 0);
        addView.invoke(ctrlRow, plusBtn, plusLp);

        Object smpBtn = createChipButton(tvCtor, setPad, "주기: 30s", 0xFF1E293B, 0xFF334155, 0xFFCBD5E1, new Runnable() {
            @Override
            public void run() {
                AuxMonMain.cycleSampleIntervalSec();
                refreshOnUiThread();
            }
        });
        Object smpLp = llLpCtor2.newInstance(WRAP_CONTENT, WRAP_CONTENT);
        setMargins(smpLp, 0, 0, 8, 0);
        addView.invoke(ctrlRow, smpBtn, smpLp);

        Object modeBtn = createChipButton(tvCtor, setPad, "모드: 순정 Aux.Saver+ 감시", 0xFF172554, 0xFF3B82F6, 0xFF93C5FD, new Runnable() {
            @Override
            public void run() {
                AuxMonMain.toggleAutoChargeMode();
                refreshOnUiThread();
            }
        });
        addView.invoke(ctrlRow, modeBtn, llLpCtor3.newInstance(0, WRAP_CONTENT, 1.0f));

        Object prevSessBtn = createChipButton(tvCtor, setPad, "◀", 0xFF1E293B, 0xFF334155, 0xFFCBD5E1, new Runnable() {
            @Override
            public void run() {
                AuxMonMain.selectAdjacentSession(-1);
                refreshOnUiThread();
            }
        });
        Object prevLp = llLpCtor2.newInstance(WRAP_CONTENT, WRAP_CONTENT);
        setMargins(prevLp, 10, 0, 4, 0);
        addView.invoke(ctrlRow, prevSessBtn, prevLp);

        Object sessTv = tvCtor.newInstance(sysContext);
        setTextViewStyle(sessTv, "현재 세션 (1/1)", 12.0f, 0xFFE2E8F0, true);
        setPad.invoke(sessTv, 6, 0, 6, 0);
        addView.invoke(ctrlRow, sessTv, llLpCtor2.newInstance(WRAP_CONTENT, WRAP_CONTENT));

        Object nextSessBtn = createChipButton(tvCtor, setPad, "▶", 0xFF1E293B, 0xFF334155, 0xFFCBD5E1, new Runnable() {
            @Override
            public void run() {
                AuxMonMain.selectAdjacentSession(1);
                refreshOnUiThread();
            }
        });
        Object nextLp = llLpCtor2.newInstance(WRAP_CONTENT, WRAP_CONTENT);
        setMargins(nextLp, 4, 0, 0, 0);
        addView.invoke(ctrlRow, nextSessBtn, nextLp);

        addView.invoke(expBox, ctrlRow, ctrlRowLp);

        // --- Row C: 4 KPI Metric Cards ---
        Object kpiRow = llCtor.newInstance(sysContext);
        llCls.getMethod("setOrientation", int.class).invoke(kpiRow, 0);
        Object kpiRowLp = llLpCtor2.newInstance(CARD_WIDTH, WRAP_CONTENT);
        setMargins(kpiRowLp, 0, 0, 0, 8);

        Object[] card1 = buildKpiCard(llCtor, tvCtor, addView, setPad, llLpCtor2,
                "12V 보조배터리 전압", "12.60 V", "최저 -V / 최고 -V", 0xFF38BDF8);
        Object[] card2 = buildKpiCard(llCtor, tvCtor, addView, setPad, llLpCtor2,
                "보조배터리 충전율 (SOC)", "85 %", "시작 85% → 현재 85%", 0xFFA7F3D0);
        Object[] card3 = buildKpiCard(llCtor, tvCtor, addView, setPad, llLpCtor2,
                "주차 중 충전 진행 횟수", "0 회 충전", "누적 충전: 00:00:00", 0xFF10B981);
        Object[] card4 = buildKpiCard(llCtor, tvCtor, addView, setPad, llLpCtor2,
                "주차 경과 / 목표 시간", "00:00:00", "목표: 6시간 (PwrKeep 대기)", 0xFFFBBF24);

        Object c1Lp = llLpCtor3.newInstance(0, WRAP_CONTENT, 1.0f);
        setMargins(c1Lp, 0, 0, 6, 0);
        Object c2Lp = llLpCtor3.newInstance(0, WRAP_CONTENT, 1.0f);
        setMargins(c2Lp, 0, 0, 6, 0);
        Object c3Lp = llLpCtor3.newInstance(0, WRAP_CONTENT, 1.0f);
        setMargins(c3Lp, 0, 0, 6, 0);
        Object c4Lp = llLpCtor3.newInstance(0, WRAP_CONTENT, 1.0f);

        addView.invoke(kpiRow, card1[0], c1Lp);
        addView.invoke(kpiRow, card2[0], c2Lp);
        addView.invoke(kpiRow, card3[0], c3Lp);
        addView.invoke(kpiRow, card4[0], c4Lp);
        addView.invoke(expBox, kpiRow, kpiRowLp);

        // --- Row D: Time-Series Voltage & IG3 Charging Graph Box ---
        Object chartBox = llCtor.newInstance(sysContext);
        llCls.getMethod("setOrientation", int.class).invoke(chartBox, 1);
        setPad.invoke(chartBox, 14, 8, 14, 8);
        setRoundedBg(chartBox, 0xFF0D1524, 0xFF1E293B, 1, 10f);
        Object chartBoxLp = llLpCtor2.newInstance(CARD_WIDTH, WRAP_CONTENT);
        setMargins(chartBoxLp, 0, 0, 0, 8);

        Object chartIv = ivCtor.newInstance(sysContext);
        Object fitXy = scaleTypeCls.getField("FIT_XY").get(null);
        ivCls.getMethod("setScaleType", scaleTypeCls).invoke(chartIv, fitXy);
        addView.invoke(chartBox, chartIv, llLpCtor2.newInstance(CHART_WIDTH, CHART_HEIGHT));
        addView.invoke(expBox, chartBox, chartBoxLp);

        // --- Row E: Charging Event History & Voltage Log Box ---
        Object logBox = llCtor.newInstance(sysContext);
        llCls.getMethod("setOrientation", int.class).invoke(logBox, 1);
        setPad.invoke(logBox, 12, 8, 12, 8);
        setRoundedBg(logBox, 0xFF0E1726, 0xFF1E293B, 1, 10f);
        Object logBoxLp = llLpCtor2.newInstance(CARD_WIDTH, WRAP_CONTENT);
        setMargins(logBoxLp, 0, 0, 0, 8);

        Object logHdrTv = tvCtor.newInstance(sysContext);
        setTextViewStyle(logHdrTv, "⚡ 주차 중 보조배터리 자동충전 이력 및 시간대별 전압 기록", 12.5f, 0xFF38BDF8, true);
        addView.invoke(logBox, logHdrTv, llLpCtor2.newInstance(MATCH_PARENT, WRAP_CONTENT));

        Object logBodyTv = tvCtor.newInstance(sysContext);
        setTextViewStyle(logBodyTv, "주차 감시가 시작되면 충전 이력(#1, #2...)과 전압 추이가 여기에 표시됩니다.", 11.5f, 0xFFCBD5E1, false);
        Object logBodyLp = llLpCtor2.newInstance(MATCH_PARENT, WRAP_CONTENT);
        setMargins(logBodyLp, 0, 4, 0, 0);
        addView.invoke(logBox, logBodyTv, logBodyLp);

        addView.invoke(expBox, logBox, logBoxLp);

        // --- Row F: Action Buttons (Start/Stop, Manual IG3 Test, Demo Sim, USB Export) ---
        Object actRow = llCtor.newInstance(sysContext);
        llCls.getMethod("setOrientation", int.class).invoke(actRow, 0);
        setGravity.invoke(actRow, 16);
        Object actRowLp = llLpCtor2.newInstance(CARD_WIDTH, WRAP_CONTENT);
        setMargins(actRowLp, 0, 0, 0, 6);

        Object startStopBtn = createChipButton(tvCtor, setPad, "▶ 주차기록 지금 시작", 0xFF0F2942, 0xFF0EA5E9, 0xFF38BDF8, new Runnable() {
            @Override
            public void run() {
                AuxMonMain.toggleManualParkingSession();
                refreshOnUiThread();
            }
        });
        tvSetGravity.invoke(startStopBtn, 17);
        Object b1Lp = llLpCtor2.newInstance(WRAP_CONTENT, WRAP_CONTENT);
        setMargins(b1Lp, 0, 0, 8, 0);
        addView.invoke(actRow, startStopBtn, b1Lp);

        Object ig3TestBtn = createChipButton(tvCtor, setPad, "⚡ IG3 수동충전 테스트: OFF", 0xFF1E293B, 0xFF475569, 0xFFE2E8F0, new Runnable() {
            @Override
            public void run() {
                AuxMonMain.toggleManualIg3Test();
                refreshOnUiThread();
            }
        });
        tvSetGravity.invoke(ig3TestBtn, 17);
        Object b2Lp = llLpCtor2.newInstance(WRAP_CONTENT, WRAP_CONTENT);
        setMargins(b2Lp, 0, 0, 8, 0);
        addView.invoke(actRow, ig3TestBtn, b2Lp);

        Object demoBtn = createChipButton(tvCtor, setPad, "🧪 시뮬레이션 기록 생성", 0xFF1E1B4B, 0xFF6366F1, 0xFFC7D2FE, new Runnable() {
            @Override
            public void run() {
                AuxMonMain.loadDemoParkingSession();
                refreshOnUiThread();
            }
        });
        tvSetGravity.invoke(demoBtn, 17);
        Object b3Lp = llLpCtor2.newInstance(WRAP_CONTENT, WRAP_CONTENT);
        setMargins(b3Lp, 0, 0, 8, 0);
        addView.invoke(actRow, demoBtn, b3Lp);

        Object exportBtn = createChipButton(tvCtor, setPad, "💾 데이터 내보내기 (USB 저장)", 0xFF064E3B, 0xFF10B981, 0xFFA7F3D0, new Runnable() {
            @Override
            public void run() {
                AuxMonMain.exportToUsbAsync();
                refreshOnUiThread();
            }
        });
        tvSetGravity.invoke(exportBtn, 17);
        addView.invoke(actRow, exportBtn, llLpCtor3.newInstance(0, WRAP_CONTENT, 1.0f));

        addView.invoke(expBox, actRow, actRowLp);

        // --- Row G: Bottom Status / Export Result Banner ---
        Object noticeTv = tvCtor.newInstance(sysContext);
        setTextViewStyle(noticeTv, "💡 시동이 꺼지면(ACC/IGN OFF) 자동으로 설정된 n시간 동안 헤드유닛 절전 전원을 유지하며 보조배터리 전압과 충전 횟수를 기록합니다.", 11.5f, 0xFFFBBF24, true);
        addView.invoke(expBox, noticeTv, llLpCtor2.newInstance(CARD_WIDTH, WRAP_CONTENT));

        attachDragOnlyListener(expBox);
        addView.invoke(root, expBox, llLpCtor2.newInstance(WRAP_CONTENT, WRAP_CONTENT));

        // Bind static references
        compactRowView = compactRow;
        compactTextView = cTv;
        expandedBoxView = expBox;
        badgeTextView = badgeTv;
        nHoursValueTv = nValTv;
        sampleIntervalBtnTv = smpBtn;
        modeBtnTv = modeBtn;
        sessionNavTv = sessTv;

        kpiVoltMainTv = card1[1];
        kpiVoltSubTv = card1[2];
        kpiSocMainTv = card2[1];
        kpiSocSubTv = card2[2];
        kpiChgMainTv = card3[1];
        kpiChgSubTv = card3[2];
        kpiTimeMainTv = card4[1];
        kpiTimeSubTv = card4[2];

        chartImageView = chartIv;
        chargeHistoryHeaderTv = logHdrTv;
        chargeHistoryBodyTv = logBodyTv;

        startStopParkingBtnTv = startStopBtn;
        manualIg3BtnTv = ig3TestBtn;
        exportUsbBtnTv = exportBtn;
        bottomNoticeTv = noticeTv;

        return root;
    }

    private static Object[] buildKpiCard(
            Constructor<?> llCtor,
            Constructor<?> tvCtor,
            Method addView,
            Method setPad,
            Constructor<?> llLpCtor2,
            String label,
            String mainVal,
            String subVal,
            int mainColor) throws Exception {
        final int MATCH_PARENT = -1;
        final int WRAP_CONTENT = -2;
        Class<?> llCls = Class.forName("android.widget.LinearLayout");

        Object card = llCtor.newInstance(sysContext);
        llCls.getMethod("setOrientation", int.class).invoke(card, 1);
        setPad.invoke(card, 12, 8, 12, 8);
        setRoundedBg(card, 0xFF111C2D, 0xFF1E293B, 1, 10f);

        Object lblTv = tvCtor.newInstance(sysContext);
        setTextViewStyle(lblTv, label, 11.5f, 0xFF94A3B8, true);
        addView.invoke(card, lblTv, llLpCtor2.newInstance(MATCH_PARENT, WRAP_CONTENT));

        Object mainTv = tvCtor.newInstance(sysContext);
        setTextViewStyle(mainTv, mainVal, 18.0f, mainColor, true);
        Object mainLp = llLpCtor2.newInstance(MATCH_PARENT, WRAP_CONTENT);
        setMargins(mainLp, 0, 2, 0, 2);
        addView.invoke(card, mainTv, mainLp);

        Object subTv = tvCtor.newInstance(sysContext);
        setTextViewStyle(subTv, subVal, 11.0f, 0xFFCBD5E1, false);
        addView.invoke(card, subTv, llLpCtor2.newInstance(MATCH_PARENT, WRAP_CONTENT));

        return new Object[]{card, mainTv, subTv};
    }

    private static Object createChipButton(
            Constructor<?> tvCtor,
            Method setPad,
            String text,
            int bgColor,
            int borderColor,
            int textColor,
            Runnable clickAction) throws Exception {
        Object btn = tvCtor.newInstance(sysContext);
        setTextViewStyle(btn, text, 12.0f, textColor, true);
        setPad.invoke(btn, 12, 6, 12, 6);
        setRoundedBg(btn, bgColor, borderColor, 1, 8f);
        attachButtonOrDragListener(btn, clickAction);
        return btn;
    }

    private static void applyModeVisibilityOnUiThread() {
        try {
            Class<?> viewCls = Class.forName("android.view.View");
            Method setVis = viewCls.getMethod("setVisibility", int.class);
            if (compactRowView != null) {
                setVis.invoke(compactRowView, compactMode ? 0 : 8);
            }
            if (expandedBoxView != null) {
                setVis.invoke(expandedBoxView, compactMode ? 8 : 0);
            }
        } catch (Throwable ignored) {
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
                applyModeVisibilityOnUiThread();
                refreshOnUiThread();
                return;
            }

            Class<?> viewCls = Class.forName("android.view.View");
            Class<?> vgLpCls = Class.forName("android.view.ViewGroup$LayoutParams");
            Class<?> wmLpCls = Class.forName("android.view.WindowManager$LayoutParams");
            Class<?> vmCls = Class.forName("android.view.ViewManager");

            int[] candidateTypes = new int[]{2020, 2008, 2009, 2005};
            int flags = 0x00000008 | 0x00000020; // FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL
            Constructor<?> wmLpCtor = wmLpCls.getConstructor(int.class, int.class, int.class, int.class, int.class);
            Method addViewMethod = vmCls.getMethod("addView", viewCls, vgLpCls);

            for (int i = 0; i < candidateTypes.length; i++) {
                int wtype = candidateTypes[i];
                Object candidateRoot = null;
                try {
                    candidateRoot = buildOverlayViewTree();
                    rootOverlayView = candidateRoot;
                    applyModeVisibilityOnUiThread();

                    Object wmLp = wmLpCtor.newInstance(-2, -2, wtype, flags, -3);
                    wmLpCls.getField("gravity").setInt(wmLp, 51); // TOP | LEFT
                    wmLpCls.getField("x").setInt(wmLp, winX);
                    wmLpCls.getField("y").setInt(wmLp, winY);
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
                    System.out.println("[AuxMon] Overlay displayed (wtype=" + wtype + ", x=" + winX + ", y=" + winY + ")");
                    refreshOnUiThread();
                    break;
                } catch (Throwable t) {
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
        try {
            if (oldRoot != null && windowManager != null) {
                Class<?> viewCls = Class.forName("android.view.View");
                Class<?> vmCls = Class.forName("android.view.ViewManager");
                try {
                    viewCls.getMethod("setVisibility", int.class).invoke(oldRoot, 8);
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
    }

    private static void refreshOnUiThread() {
        if (!isVisible || rootOverlayView == null) return;
        removeCallbacksFromUi(REFRESH_UI_TASK);

        try {
            boolean rearCamOpen = "1".equals(AuxMonMain.getSystemProperty("persist.sys.rearcam.open", "0"));
            Class<?> viewCls = Class.forName("android.view.View");
            if (rearCamOpen) {
                if (!hiddenForRearCam) {
                    hiddenForRearCam = true;
                    viewCls.getMethod("setVisibility", int.class).invoke(rootOverlayView, 8);
                }
                postDelayedToUi(REFRESH_UI_TASK, 500L);
                return;
            } else if (hiddenForRearCam) {
                hiddenForRearCam = false;
                viewCls.getMethod("setVisibility", int.class).invoke(rootOverlayView, 0);
            }

            ParkingSession sess = AuxMonMain.getDisplayedSession();
            LiveTelemetrySnapshot live = AuxMonMain.getLatestLiveSnapshot();
            int targetHours = AuxMonMain.getConfiguredTargetHours();
            int sampleSec = AuxMonMain.getConfiguredSampleIntervalSec();
            int autoMode = AuxMonMain.getConfiguredAutoChargeMode();

            boolean chargingNow = (live != null && live.isChargingActive())
                    || (sess != null && sess.getOngoingChargeEvent() != null);

            int borderColor = 0xFF38BDF8;
            int badgeBg = 0xFF38BDF8;
            String badgeStr = "주행/대기 중";

            if (chargingNow) {
                borderColor = 0xFF10B981;
                badgeBg = 0xFF10B981;
                badgeStr = "⚡ IG3 자동충전 중";
            } else if (sess != null && sess.status == AuxMonModels.SESSION_MONITORING_PARKING) {
                borderColor = 0xFF0EA5E9;
                badgeBg = 0xFF0EA5E9;
                badgeStr = "주차 절전유지 (" + AuxMonModels.formatTargetHoursLabel(sess.targetParkingHours) + ")";
            } else if (sess != null && sess.status == AuxMonModels.SESSION_COMPLETED_N_HOURS) {
                borderColor = 0xFF10B981;
                badgeBg = 0xFF10B981;
                badgeStr = AuxMonModels.formatTargetHoursLabel(sess.targetParkingHours) + " 완료 ✓";
            } else if (sess != null && sess.status == AuxMonModels.SESSION_SAFETY_CUTOFF) {
                borderColor = 0xFFEF4444;
                badgeBg = 0xFFEF4444;
                badgeStr = "방전보호 차단됨";
            }

            if (borderColor != lastBorderColor) {
                lastBorderColor = borderColor;
                setRoundedBg(rootOverlayView, 0xF40B111E, borderColor, 2, 16f);
            }

            Class<?> tvCls = Class.forName("android.widget.TextView");
            Method setText = tvCls.getMethod("setText", CharSequence.class);
            Method setTextColor = tvCls.getMethod("setTextColor", int.class);

            float dispVolt = (sess != null && !sess.samples.isEmpty()) ? sess.latestVoltageV : (live != null ? live.voltageV : 0.0f);
            int dispSoc = (sess != null && !sess.samples.isEmpty()) ? sess.latestSocPct : (live != null ? live.socPct : -1);
            int chgCount = (sess != null) ? sess.getChargeCount() : 0;
            long elapsedSec = (sess != null) ? sess.getElapsedParkingSec() : 0L;

            if (compactMode && compactTextView != null) {
                String voltTxt = dispVolt > 1.0f ? String.format(Locale.US, "%.2fV", Float.valueOf(dispVolt)) : "--V";
                String cText = String.format(Locale.US,
                        "🔋 12V %s (%s)  |  주차 %s (%s)  |  충전 %d회  %s  (터치하여 패널 열기)",
                        voltTxt,
                        AuxMonModels.formatSocPct(dispSoc),
                        AuxMonModels.formatDurationHms(elapsedSec),
                        AuxMonModels.formatTargetHoursLabel(sess != null ? sess.targetParkingHours : targetHours),
                        Integer.valueOf(chgCount),
                        chargingNow ? "• ⚡ IG3 충전중" : "");
                setText.invoke(compactTextView, cText);
            } else {
                setText.invoke(badgeTextView, badgeStr);
                setRoundedBg(badgeTextView, badgeBg, badgeBg, 0, 10f);

                setText.invoke(nHoursValueTv, AuxMonModels.formatTargetHoursLabel(targetHours));
                if (sampleIntervalBtnTv != null) {
                    setText.invoke(sampleIntervalBtnTv, "주기: " + sampleSec + "s");
                }
                if (autoMode == AuxMonModels.MODE_ACTIVE_1H_IG3) {
                    setText.invoke(modeBtnTv, "모드: 1시간 주기 IG3 강제충전");
                    setRoundedBg(modeBtnTv, 0xFF064E3B, 0xFF10B981, 1, 8f);
                    setTextColor.invoke(modeBtnTv, 0xFFA7F3D0);
                } else {
                    setText.invoke(modeBtnTv, "모드: 순정 Aux. Battery Saver+ 감시");
                    setRoundedBg(modeBtnTv, 0xFF172554, 0xFF3B82F6, 1, 8f);
                    setTextColor.invoke(modeBtnTv, 0xFF93C5FD);
                }

                setText.invoke(sessionNavTv, AuxMonMain.getSessionNavLabel());

                // KPI Card 1: Voltage (explicitly mark estimated vs measured)
                String vSrc = (live != null) ? live.voltageSource : "SOC_EST(추정)";
                boolean isEst = (live == null || !live.isDirectVoltageMeasured());
                if (dispVolt > 1.0f) {
                    setText.invoke(kpiVoltMainTv, String.format(Locale.US, "%.2f V%s",
                            Float.valueOf(dispVolt), isEst ? " (추정)" : ""));
                } else {
                    setText.invoke(kpiVoltMainTv, "--.-- V (미확인)");
                }
                if (sess != null && !sess.samples.isEmpty() && sess.maxVoltageV > 1.0f) {
                    float minV = sess.minVoltageV > 50f ? dispVolt : sess.minVoltageV;
                    setText.invoke(kpiVoltSubTv, String.format(Locale.US,
                            "최저 %.2fV / 최고 %.2fV (%s)",
                            Float.valueOf(minV), Float.valueOf(sess.maxVoltageV), vSrc));
                } else {
                    setText.invoke(kpiVoltSubTv, "전압 소스: " + vSrc);
                }

                // KPI Card 2: SOC & BatSocStatus_M (no fake 85%, no bogus Solar Roof LDC W)
                setText.invoke(kpiSocMainTv, AuxMonModels.formatSocPct(dispSoc));
                String socFlagStr = (live != null && live.batSocStatusFlag == 1) ? "비정상(UNNORMAL)" : "정상";
                if (sess != null && !sess.samples.isEmpty()) {
                    setText.invoke(kpiSocSubTv, String.format(Locale.US,
                            "시작 %s → 현재 %s | 상태:%s",
                            AuxMonModels.formatSocPct(sess.startSocPct),
                            AuxMonModels.formatSocPct(dispSoc),
                            socFlagStr));
                } else {
                    setText.invoke(kpiSocSubTv, "12V 배터리 SOC | 상태: " + socFlagStr);
                }

                // KPI Card 3: Charge Count & IG3 / Relay Mode
                setText.invoke(kpiChgMainTv, "총 " + chgCount + " 회 충전");
                long totalChgSec = (sess != null) ? sess.getTotalChargeDurationSec() : 0L;
                boolean ig3State = (live != null && live.ig3On);
                int rlyMode = (live != null) ? live.ig3RlyMode : 0;
                setText.invoke(kpiChgSubTv, String.format(Locale.US,
                        "누적 %s | IG3:%s (Rly:%d)",
                        AuxMonModels.formatDurationHms(totalChgSec),
                        ig3State ? "ON⚡" : "OFF",
                        Integer.valueOf(rlyMode)));

                // KPI Card 4: Parking Elapsed / Target Hours & Safety Status
                int sessHours = (sess != null) ? sess.targetParkingHours : targetHours;
                if (sessHours <= 0) {
                    setText.invoke(kpiTimeMainTv, AuxMonModels.formatDurationHms(elapsedSec) + " (상시)");
                } else {
                    int pct = (sess != null) ? sess.getProgressPercent() : 0;
                    setText.invoke(kpiTimeMainTv, AuxMonModels.formatDurationHms(elapsedSec) + " (" + pct + "%)");
                }
                String pwrKeepStr = HkmcVehicleBridge.isKeepAliveActive() ? "PwrKeep:ON(60s재전송)" : "PwrKeep:대기";
                String alarmStr = "보호:정상";
                if (live != null) {
                    if (live.autoCutPreWrng > 0 || live.batEvtAlarm > 0) {
                        alarmStr = "방전경보:" + live.batEvtAlarm + "/차단:" + live.autoCutPreWrng;
                    } else if (live.battMngAlert > 0) {
                        alarmStr = "등화경고(비상등/미등):" + live.battMngAlert;
                    }
                }
                setText.invoke(kpiTimeSubTv, "목표 " + AuxMonModels.formatTargetHoursLabel(sessHours) + " | " + pwrKeepStr + " | " + alarmStr);

                // Render Canvas Chart
                renderVoltageChartOnUiThread(sess, live, targetHours);

                // Update Charge Event History & Log Box
                updateChargeHistoryTextOnUiThread(setText, sess);

                // Update Action Buttons
                boolean activeMonitoring = AuxMonMain.isCurrentlyMonitoringParking();
                if (activeMonitoring) {
                    setText.invoke(startStopParkingBtnTv, "■ 주차기록 종료");
                    setRoundedBg(startStopParkingBtnTv, 0xFF3B1219, 0xFFEF4444, 1, 8f);
                    setTextColor.invoke(startStopParkingBtnTv, 0xFFFCA5A5);
                } else {
                    setText.invoke(startStopParkingBtnTv, "▶ 주차기록 지금 시작");
                    setRoundedBg(startStopParkingBtnTv, 0xFF0F2942, 0xFF0EA5E9, 1, 8f);
                    setTextColor.invoke(startStopParkingBtnTv, 0xFF38BDF8);
                }

                boolean manualIg3 = HkmcVehicleBridge.isManualIg3TestActive();
                if (manualIg3) {
                    setText.invoke(manualIg3BtnTv, "⚡ IG3 수동충전: ON (끄기)");
                    setRoundedBg(manualIg3BtnTv, 0xFF064E3B, 0xFF10B981, 1, 8f);
                    setTextColor.invoke(manualIg3BtnTv, 0xFFA7F3D0);
                } else {
                    setText.invoke(manualIg3BtnTv, "⚡ IG3 수동충전 테스트: OFF");
                    setRoundedBg(manualIg3BtnTv, 0xFF1E293B, 0xFF475569, 1, 8f);
                    setTextColor.invoke(manualIg3BtnTv, 0xFFE2E8F0);
                }

                setText.invoke(bottomNoticeTv, AuxMonMain.getStatusBannerMessage());
                setTextColor.invoke(bottomNoticeTv, AuxMonMain.getStatusBannerColor());
            }
        } catch (Throwable ignored) {
        }

        postDelayedToUi(REFRESH_UI_TASK, 1000L);
    }

    private static void updateChargeHistoryTextOnUiThread(Method setText, ParkingSession sess) throws Exception {
        if (chargeHistoryHeaderTv == null || chargeHistoryBodyTv == null) return;
        if (sess == null || sess.samples.isEmpty()) {
            setText.invoke(chargeHistoryHeaderTv, "⚡ 주차 중 보조배터리 자동충전 이력 (0회) 및 전압 기록");
            setText.invoke(chargeHistoryBodyTv,
                    "현재 대기 중입니다. 주행 종료(ACC/IGN OFF) 시 자동 기록되거나, 아래 [▶ 주차기록 지금 시작] 또는 [🧪 시뮬레이션 기록 생성]을 눌러 확인하세요.");
            return;
        }

        synchronized (sess) {
            int chgCnt = sess.chargeEvents.size();
            setText.invoke(chargeHistoryHeaderTv, String.format(Locale.US,
                    "⚡ 주차 세션 [%s] 충전 이력 (총 %d회 충전 / 샘플 %d건 / 시작 %s)",
                    sess.sessionId, Integer.valueOf(chgCnt), Integer.valueOf(sess.samples.size()),
                    AuxMonModels.formatTimestampIso(sess.driveEndEpochMs)));

            StringBuilder sb = new StringBuilder(512);
            long curElapsed = sess.getElapsedParkingSec();

            if (chgCnt == 0) {
                sb.append("• 자동충전(IG3) 이력: 아직 감지된 보조배터리 충전 이벤트가 없습니다.\n");
            } else {
                int startIdx = Math.max(0, chgCnt - 3);
                for (int i = startIdx; i < chgCnt; i++) {
                    ChargeEvent ce = sess.chargeEvents.get(i);
                    long dur = ce.getDurationSec(curElapsed);
                    sb.append(String.format(Locale.US,
                            "• [충전 #%d] 주차 +%s (%s ~ %s, 지속 %s)  |  전압 %.2fV(%s) → 최고 %.2fV → %.2fV(%s)  |  사유: %s\n",
                            Integer.valueOf(ce.chargeIndex),
                            AuxMonModels.formatDurationHms(ce.startElapsedSec),
                            AuxMonModels.formatTimeOnly(ce.startEpochMs),
                            ce.endEpochMs > 0L ? AuxMonModels.formatTimeOnly(ce.endEpochMs) : "진행중",
                            AuxMonModels.formatDurationHms(dur),
                            Float.valueOf(ce.startVoltageV),
                            AuxMonModels.formatSocPct(ce.startSocPct),
                            Float.valueOf(ce.peakVoltageV),
                            Float.valueOf(ce.endVoltageV),
                            AuxMonModels.formatSocPct(ce.endSocPct),
                            ce.triggerReason));
                }
            }

            // Append summary of recent voltage checkpoints
            int n = sess.samples.size();
            sb.append("• 전압 추이: ");
            int checkpoints = Math.min(6, n);
            for (int k = 0; k < checkpoints; k++) {
                int idx = (checkpoints == 1) ? 0 : (k * (n - 1)) / (checkpoints - 1);
                VoltageSample vs = sess.samples.get(idx);
                if (k > 0) sb.append("  →  ");
                sb.append(String.format(Locale.US, "+%dh%02dm: %.2fV(%s)",
                        Long.valueOf(vs.elapsedParkingSec / 3600L),
                        Long.valueOf((vs.elapsedParkingSec % 3600L) / 60L),
                        Float.valueOf(vs.voltageV),
                        AuxMonModels.formatSocPct(vs.socPct)));
            }
            setText.invoke(chargeHistoryBodyTv, sb.toString());
        }
    }

    /**
     * Draws the N-hour parking 12V Voltage (V), SOC (%), and IG3 Auto-Charging bands onto chartBitmap.
     */
    private static void renderVoltageChartOnUiThread(ParkingSession sess, LiveTelemetrySnapshot live, int defaultHours) {
        if (chartImageView == null) return;
        try {
            Class<?> bmpCls = Class.forName("android.graphics.Bitmap");
            Class<?> cfgCls = Class.forName("android.graphics.Bitmap$Config");
            Class<?> canvasCls = Class.forName("android.graphics.Canvas");
            Class<?> paintCls = Class.forName("android.graphics.Paint");
            Class<?> styleCls = Class.forName("android.graphics.Paint$Style");
            Class<?> ivCls = Class.forName("android.widget.ImageView");

            if (chartBitmap == null) {
                Object argb8888 = cfgCls.getField("ARGB_8888").get(null);
                chartBitmap = bmpCls.getMethod("createBitmap", int.class, int.class, cfgCls)
                        .invoke(null, Integer.valueOf(CHART_WIDTH), Integer.valueOf(CHART_HEIGHT), argb8888);
            }

            bmpCls.getMethod("eraseColor", int.class).invoke(chartBitmap, Integer.valueOf(0xFF090F1A));
            Object canvas = canvasCls.getConstructor(bmpCls).newInstance(chartBitmap);

            Object paint = paintCls.getConstructor(int.class).newInstance(Integer.valueOf(1)); // ANTI_ALIAS_FLAG
            Method setColor = paintCls.getMethod("setColor", int.class);
            Method setStrokeWidth = paintCls.getMethod("setStrokeWidth", float.class);
            Method setTextSize = paintCls.getMethod("setTextSize", float.class);
            Method setStyle = paintCls.getMethod("setStyle", styleCls);
            Object styleFill = styleCls.getField("FILL").get(null);
            Object styleStroke = styleCls.getField("STROKE").get(null);

            Method drawLine = canvasCls.getMethod("drawLine", float.class, float.class, float.class, float.class, paintCls);
            Method drawRect = canvasCls.getMethod("drawRect", float.class, float.class, float.class, float.class, paintCls);
            Method drawText = canvasCls.getMethod("drawText", String.class, float.class, float.class, paintCls);

            final float padLeft = 46f;
            final float padRight = 42f;
            final float padTop = 22f;
            final float padBottom = 22f;
            final float plotW = CHART_WIDTH - padLeft - padRight;
            final float plotH = CHART_HEIGHT - padTop - padBottom;

            final float minV = 11.0f;
            final float maxV = 15.0f;

            // 1. Title & Legend inside chart header
            setStyle.invoke(paint, styleFill);
            setTextSize.invoke(paint, Float.valueOf(11.5f));
            setColor.invoke(paint, Integer.valueOf(0xFF94A3B8));
            drawText.invoke(canvas, "주차 시간대별 보조배터리 전압(11.0V~15.0V) & 자동충전(IG3) 그래프", Float.valueOf(padLeft), Float.valueOf(14f), paint);

            setColor.invoke(paint, Integer.valueOf(0xFF38BDF8));
            drawText.invoke(canvas, "━ 전압(V)", Float.valueOf(CHART_WIDTH - 240f), Float.valueOf(14f), paint);
            setColor.invoke(paint, Integer.valueOf(0xFFFBBF24));
            drawText.invoke(canvas, "━ SOC(%)", Float.valueOf(CHART_WIDTH - 170f), Float.valueOf(14f), paint);
            setColor.invoke(paint, Integer.valueOf(0xFF10B981));
            drawText.invoke(canvas, "█ IG3 충전구간", Float.valueOf(CHART_WIDTH - 100f), Float.valueOf(14f), paint);

            // 2. Horizontal voltage grid lines (11V, 12V, 13V, 14V, 15V)
            setStrokeWidth.invoke(paint, Float.valueOf(1.0f));
            setTextSize.invoke(paint, Float.valueOf(10.5f));
            for (int v = 11; v <= 15; v++) {
                float ratio = (v - minV) / (maxV - minV);
                float y = padTop + plotH - (ratio * plotH);
                setColor.invoke(paint, Integer.valueOf(v == 12 ? 0xFF334155 : 0xFF1E293B));
                drawLine.invoke(canvas, Float.valueOf(padLeft), Float.valueOf(y), Float.valueOf(padLeft + plotW), Float.valueOf(y), paint);

                setColor.invoke(paint, Integer.valueOf(0xFF64748B));
                drawText.invoke(canvas, v + ".0V", Float.valueOf(4f), Float.valueOf(y + 4f), paint);

                int socAxis = (v - 11) * 25;
                drawText.invoke(canvas, socAxis + "%", Float.valueOf(padLeft + plotW + 5f), Float.valueOf(y + 4f), paint);
            }

            int hours = (sess != null && sess.targetParkingHours > 0) ? sess.targetParkingHours : defaultHours;
            long maxTimeSec = Math.max(3600L, (long) hours * 3600L);
            if (sess != null && sess.getElapsedParkingSec() > maxTimeSec) {
                maxTimeSec = sess.getElapsedParkingSec();
            }

            // 3. Vertical time grid lines (0h .. Nh)
            int gridSteps = Math.min(hours, 6);
            if (gridSteps < 1) gridSteps = 1;
            for (int g = 0; g <= gridSteps; g++) {
                float x = padLeft + ((plotW * g) / (float) gridSteps);
                setColor.invoke(paint, Integer.valueOf(0xFF1E293B));
                drawLine.invoke(canvas, Float.valueOf(x), Float.valueOf(padTop), Float.valueOf(x), Float.valueOf(padTop + plotH), paint);
                setColor.invoke(paint, Integer.valueOf(0xFF64748B));
                long labelSec = (maxTimeSec * g) / gridSteps;
                String tLbl = String.format(Locale.US, "+%.1fh", Float.valueOf(labelSec / 3600.0f));
                drawText.invoke(canvas, tLbl, Float.valueOf(x - 14f), Float.valueOf(CHART_HEIGHT - 5f), paint);
            }

            if (sess != null) {
                synchronized (sess) {
                    // 4. Shaded Green Bands for each IG3 Charging Event
                    for (int i = 0; i < sess.chargeEvents.size(); i++) {
                        ChargeEvent ce = sess.chargeEvents.get(i);
                        float x1 = padLeft + Math.min(1.0f, (float) ce.startElapsedSec / (float) maxTimeSec) * plotW;
                        long endEl = (ce.endElapsedSec >= ce.startElapsedSec && ce.endEpochMs > 0L)
                                ? ce.endElapsedSec : sess.getElapsedParkingSec();
                        float x2 = padLeft + Math.min(1.0f, (float) Math.max(ce.startElapsedSec + 60L, endEl) / (float) maxTimeSec) * plotW;
                        if (x2 - x1 < 6.0f) x2 = x1 + 6.0f;

                        setStyle.invoke(paint, styleFill);
                        setColor.invoke(paint, Integer.valueOf(0x4410B981));
                        drawRect.invoke(canvas, Float.valueOf(x1), Float.valueOf(padTop), Float.valueOf(x2), Float.valueOf(padTop + plotH), paint);

                        setColor.invoke(paint, Integer.valueOf(0xFF10B981));
                        setTextSize.invoke(paint, Float.valueOf(10.5f));
                        drawText.invoke(canvas, "⚡#" + ce.chargeIndex, Float.valueOf(x1 + 2f), Float.valueOf(padTop + 12f), paint);
                    }

                    // 5. Plot SOC (%) Curve (Amber) & Voltage (V) Curve (Cyan)
                    List<VoltageSample> pts = sess.samples;
                    int n = pts.size();
                    if (n >= 2) {
                        setStyle.invoke(paint, styleStroke);
                        // SOC line
                        setStrokeWidth.invoke(paint, Float.valueOf(1.5f));
                        setColor.invoke(paint, Integer.valueOf(0xAAFB923C));
                        float prevX = 0f;
                        float prevSocY = 0f;
                        for (int i = 0; i < n; i++) {
                            VoltageSample vs = pts.get(i);
                            float x = padLeft + Math.min(1.0f, (float) vs.elapsedParkingSec / (float) maxTimeSec) * plotW;
                            float socRatio = Math.max(0f, Math.min(1f, vs.socPct / 100.0f));
                            float y = padTop + plotH - (socRatio * plotH);
                            if (i > 0) {
                                drawLine.invoke(canvas, Float.valueOf(prevX), Float.valueOf(prevSocY), Float.valueOf(x), Float.valueOf(y), paint);
                            }
                            prevX = x;
                            prevSocY = y;
                        }

                        // Voltage line
                        setStrokeWidth.invoke(paint, Float.valueOf(2.4f));
                        setColor.invoke(paint, Integer.valueOf(0xFF38BDF8));
                        float prevVY = 0f;
                        for (int i = 0; i < n; i++) {
                            VoltageSample vs = pts.get(i);
                            float x = padLeft + Math.min(1.0f, (float) vs.elapsedParkingSec / (float) maxTimeSec) * plotW;
                            float vClamped = Math.max(minV, Math.min(maxV, vs.voltageV));
                            float vRatio = (vClamped - minV) / (maxV - minV);
                            float y = padTop + plotH - (vRatio * plotH);
                            if (i > 0) {
                                drawLine.invoke(canvas, Float.valueOf(prevX), Float.valueOf(prevVY), Float.valueOf(x), Float.valueOf(y), paint);
                            }
                            prevX = x;
                            prevVY = y;
                        }
                    } else if (n == 1) {
                        VoltageSample vs = pts.get(0);
                        float vClamped = Math.max(minV, Math.min(maxV, vs.voltageV));
                        float y = padTop + plotH - (((vClamped - minV) / (maxV - minV)) * plotH);
                        setColor.invoke(paint, Integer.valueOf(0xFF38BDF8));
                        setStrokeWidth.invoke(paint, Float.valueOf(3.0f));
                        drawLine.invoke(canvas, Float.valueOf(padLeft), Float.valueOf(y), Float.valueOf(padLeft + 12f), Float.valueOf(y), paint);
                    }
                }
            }

            ivCls.getMethod("setImageBitmap", bmpCls).invoke(chartImageView, chartBitmap);
            ivCls.getMethod("invalidate").invoke(chartImageView);
        } catch (Throwable ignored) {
        }
    }

    private static void updateWindowPositionOnUiThread(int newX, int newY) {
        Object root = rootOverlayView;
        Object lp = currentWmLp;
        if (root == null || lp == null || windowManager == null) return;
        try {
            int clampedX = Math.max(0, Math.min(MAX_SCREEN_X, newX));
            int clampedY = Math.max(0, Math.min(MAX_SCREEN_Y, newY));
            winX = clampedX;
            winY = clampedY;

            Class<?> viewCls = Class.forName("android.view.View");
            Class<?> wmLpCls = Class.forName("android.view.WindowManager$LayoutParams");
            wmLpCls.getField("x").setInt(lp, clampedX);
            wmLpCls.getField("y").setInt(lp, clampedY);

            Class<?> vgLpCls = Class.forName("android.view.ViewGroup$LayoutParams");
            Class<?> vmCls = Class.forName("android.view.ViewManager");
            vmCls.getMethod("updateViewLayout", viewCls, vgLpCls).invoke(windowManager, root, lp);
        } catch (Throwable ignored) {
        }
    }

    private static void attachDragOnlyListener(Object view) throws Exception {
        attachButtonOrDragListener(view, null);
    }

    private static void attachButtonOrDragListener(Object view, final Runnable clickAction) throws Exception {
        disableViewEffects(view);
        Class<?> touchListenerCls = Class.forName("android.view.View$OnTouchListener");
        Class<?> viewCls = Class.forName("android.view.View");
        viewCls.getMethod("setClickable", boolean.class).invoke(view, true);

        Object proxy = Proxy.newProxyInstance(
                touchListenerCls.getClassLoader(),
                new Class<?>[]{touchListenerCls},
                new InvocationHandler() {
                    private float downRawX = 0f;
                    private float downRawY = 0f;
                    private int startX = 0;
                    private int startY = 0;
                    private boolean dragging = false;

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

                                if (act == 0) {
                                    downRawX = rawX;
                                    downRawY = rawY;
                                    startX = winX;
                                    startY = winY;
                                    dragging = false;
                                } else if (act == 2) {
                                    int dx = Math.round(rawX - downRawX);
                                    int dy = Math.round(rawY - downRawY);
                                    if (!dragging && (Math.abs(dx) > 10 || Math.abs(dy) > 10)) {
                                        dragging = true;
                                    }
                                    if (dragging) {
                                        updateWindowPositionOnUiThread(startX + dx, startY + dy);
                                    }
                                } else if (act == 1 || act == 3) {
                                    if (!dragging && act == 1 && clickAction != null) {
                                        clickAction.run();
                                    }
                                    dragging = false;
                                }
                            } catch (Throwable ignored) {
                            }
                            return Boolean.TRUE;
                        }
                        return Boolean.TRUE;
                    }
                });
        viewCls.getMethod("setOnTouchListener", touchListenerCls).invoke(view, proxy);
    }

    private static void disableViewEffects(Object view) {
        try {
            Class<?> viewCls = Class.forName("android.view.View");
            viewCls.getMethod("setSoundEffectsEnabled", boolean.class).invoke(view, false);
            viewCls.getMethod("setHapticFeedbackEnabled", boolean.class).invoke(view, false);
        } catch (Throwable ignored) {
        }
    }

    private static void setTextViewStyle(Object tv, String text, float sizeSp, int color, boolean bold) throws Exception {
        Class<?> tvCls = Class.forName("android.widget.TextView");
        tvCls.getMethod("setText", CharSequence.class).invoke(tv, text);
        tvCls.getMethod("setTextSize", int.class, float.class).invoke(tv, 2, sizeSp);
        tvCls.getMethod("setTextColor", int.class).invoke(tv, color);
        if (bold) {
            Class<?> tfCls = Class.forName("android.graphics.Typeface");
            Object defaultBold = tfCls.getField("DEFAULT_BOLD").get(null);
            tvCls.getMethod("setTypeface", tfCls, int.class).invoke(tv, defaultBold, 1);
        }
    }

    private static void setRoundedBg(Object view, int fillColor, int strokeColor, int strokeWidthPx, float radiusPx) throws Exception {
        Class<?> gdCls = Class.forName("android.graphics.drawable.GradientDrawable");
        Class<?> drawableCls = Class.forName("android.graphics.drawable.Drawable");
        Class<?> viewCls = Class.forName("android.view.View");
        Object gd = gdCls.getConstructor().newInstance();
        gdCls.getMethod("setColor", int.class).invoke(gd, fillColor);
        gdCls.getMethod("setCornerRadius", float.class).invoke(gd, radiusPx);
        gdCls.getMethod("setStroke", int.class, int.class).invoke(gd, strokeWidthPx, strokeColor);
        viewCls.getMethod("setBackgroundDrawable", drawableCls).invoke(view, gd);
    }

    private static void setMargins(Object marginLp, int l, int t, int r, int b) throws Exception {
        Class<?> mlpCls = Class.forName("android.view.ViewGroup$MarginLayoutParams");
        mlpCls.getMethod("setMargins", int.class, int.class, int.class, int.class).invoke(marginLp, l, t, r, b);
    }
}
