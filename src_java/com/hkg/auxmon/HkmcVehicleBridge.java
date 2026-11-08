package com.hkg.auxmon;

import com.hkg.auxmon.AuxMonModels.LiveTelemetrySnapshot;
import com.hkmc.bcm.BCMBattListener;
import com.hkmc.bcm.BCMVehicleStatusListener;
import com.hkmc.hev.HEVListener;
import com.hkmc.power.MicomPowerStatusListener;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Hardware & Framework Bridge for Hyundai/Kia Standard Gen5W Headunit (Android 4.4.2).
 *
 * Interfaces with:
 *   - com.hkmc.power.MicomPowerManager     (Power status, PwrKeepStart(2)/Stop(2), SystemOffResponder, setScreenOn)
 *   - com.hkmc.bcm.BCMManager              (12V Battery SOC %, IGN status, Discharge/AutoCut/Light alerts)
 *   - com.hkmc.hev.HEVManager              (IG3 status, reqEVIG3Change(boolean))
 *   - com.hkmc.datc.DATCManager            (isIGN3Status())
 *   - com.hkmc.telematics.hscan.tm_interface.TM_HSCANAdapter (getBatSoc, getIG3RlyMode, sendIG3RlyMode(byte))
 */
public final class HkmcVehicleBridge {

    public interface EventCallback {
        void onVehicleEvent(String eventName);
        void onAccOffShutdownIntercepted(String triggerSource);
        void onDischargeDanger(String reason);
    }

    private static final String POWER_LATCH_FILE_PATH = "/data/local/tmp/hkg_auxmon/power_latch.txt";
    private static final String IG3_REQ_FLAG_PATH = "/data/local/tmp/hkg_auxmon/ig3_requested.flag";

    private static volatile Object sysContext = null;
    private static volatile Object micomPowerMgr = null;
    private static volatile Object bcmMgr = null;
    private static volatile Object hevMgr = null;
    private static volatile Object datcMgr = null;
    private static volatile Object hscanAdapter = null;
    private static volatile Object wakeLock = null;

    private static volatile boolean listenersRegistered = false;
    private static volatile boolean autoKeepOnShutdownEnabled = true;
    private static volatile boolean safetyCutoffTripped = false;
    private static volatile boolean keepAliveActive = false;
    private static volatile long pwrKeepActivatedRealtimeMs = 0L;
    private static volatile long lastPwrKeepSentRealtimeMs = 0L;
    private static volatile boolean weRequestedIg3On = false;
    private static volatile EventCallback eventCallback = null;

    // Cached asynchronous values from HKMC listeners
    private static volatile int cachedPowerStatus = -1;
    private static volatile int cachedTeleAccStatus = -1;   // 2=ACCON, 1=ACCOFF, 255=INVALID
    private static volatile Boolean cachedIg3Status = null;
    private static volatile int cachedSocM = -1;            // 1..100 valid, -1 unknown
    private static volatile int cachedBatSocStatusFlag = 0; // BatSocStatus_M: 0=NORMAL, 1=UNNORMAL
    private static volatile int cachedBatEvtAlarm = 0;
    private static volatile int cachedBattMngAlert = 0;     // 1=HAZARDON, 2=TAILON
    private static volatile int cachedAutoCutPreWrng = 0;
    private static volatile boolean manualIg3TestActive = false;

    private HkmcVehicleBridge() {
    }

    public static void setEventCallback(EventCallback cb) {
        eventCallback = cb;
    }

    public static boolean isKeepAliveActive() {
        return keepAliveActive;
    }

    public static long getPwrKeepActivatedRealtimeMs() {
        return pwrKeepActivatedRealtimeMs;
    }

    public static boolean isAutoKeepOnShutdownEnabled() {
        return autoKeepOnShutdownEnabled;
    }

    public static synchronized void setAutoKeepOnShutdownEnabled(boolean enabled) {
        autoKeepOnShutdownEnabled = enabled;
        savePowerLatchState();
    }

    public static boolean isSafetyCutoffTripped() {
        return safetyCutoffTripped;
    }

    public static synchronized void resetSafetyCutoff() {
        safetyCutoffTripped = false;
        autoKeepOnShutdownEnabled = true;
        savePowerLatchState();
    }

    public static boolean isManualIg3TestActive() {
        return manualIg3TestActive;
    }

    public static boolean didWeRequestIg3On() {
        return weRequestedIg3On || new File(IG3_REQ_FLAG_PATH).isFile();
    }

    public static synchronized void loadPowerLatchState() {
        try {
            File f = new File(POWER_LATCH_FILE_PATH);
            if (!f.isFile()) return;
            String txt = AuxMonModels.readFileText(f);
            String[] lines = txt.split("\n");
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.startsWith("safety_cutoff=")) {
                    safetyCutoffTripped = "1".equals(line.substring(14).trim());
                } else if (line.startsWith("auto_keep=")) {
                    autoKeepOnShutdownEnabled = !"0".equals(line.substring(10).trim());
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static synchronized void savePowerLatchState() {
        try {
            File f = new File(POWER_LATCH_FILE_PATH);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            String content = "safety_cutoff=" + (safetyCutoffTripped ? "1" : "0")
                    + "\nauto_keep=" + (autoKeepOnShutdownEnabled ? "1" : "0") + "\n";
            AuxMonModels.writeBytesAtomic(f, content.getBytes(AuxMonModels.UTF8));
        } catch (Throwable ignored) {
        }
    }

    /**
     * Ensures the calling thread has a prepared Android Looper before invoking ActivityThread.systemMain(),
     * preventing "Can't create handler inside thread that has not called Looper.prepare()" in one-shot CLI mode.
     */
    public static void ensureThreadLooperPrepared() {
        try {
            Class<?> looperCls = Class.forName("android.os.Looper");
            Object cur = looperCls.getMethod("myLooper").invoke(null);
            if (cur == null) {
                try {
                    looperCls.getMethod("prepareMainLooper").invoke(null);
                } catch (Throwable ignored) {
                    looperCls.getMethod("prepare").invoke(null);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public static synchronized boolean initBridge(Object contextObj) {
        loadPowerLatchState();
        try {
            if (contextObj != null) {
                sysContext = contextObj;
            }
            if (sysContext == null) {
                ensureThreadLooperPrepared();
                Class<?> atCls = Class.forName("android.app.ActivityThread");
                Object at = null;
                try {
                    at = atCls.getMethod("currentActivityThread").invoke(null);
                } catch (Throwable ignored) {
                }
                if (at == null) {
                    at = atCls.getMethod("systemMain").invoke(null);
                }
                if (at != null) {
                    sysContext = atCls.getMethod("getSystemContext").invoke(at);
                }
            }
            if (sysContext == null) {
                return false;
            }

            Class<?> ctxCls = Class.forName("android.content.Context");

            if (micomPowerMgr == null) {
                try {
                    Class<?> cls = Class.forName("com.hkmc.power.MicomPowerManager");
                    Constructor<?> ctor = cls.getConstructor(ctxCls);
                    micomPowerMgr = ctor.newInstance(sysContext);
                } catch (Throwable t) {
                    System.out.println("[AuxMon] MicomPowerManager init note: " + t);
                }
            }

            if (bcmMgr == null) {
                try {
                    Class<?> cls = Class.forName("com.hkmc.bcm.BCMManager");
                    Constructor<?> ctor = cls.getConstructor(ctxCls);
                    bcmMgr = ctor.newInstance(sysContext);
                } catch (Throwable t) {
                    System.out.println("[AuxMon] BCMManager init note: " + t);
                }
            }

            if (hevMgr == null) {
                try {
                    Class<?> cls = Class.forName("com.hkmc.hev.HEVManager");
                    Constructor<?> ctor = cls.getConstructor(ctxCls);
                    hevMgr = ctor.newInstance(sysContext);
                } catch (Throwable t) {
                    System.out.println("[AuxMon] HEVManager init note: " + t);
                }
            }

            if (datcMgr == null) {
                try {
                    Class<?> cls = Class.forName("com.hkmc.datc.DATCManager");
                    Constructor<?> ctor = cls.getConstructor(ctxCls);
                    datcMgr = ctor.newInstance(sysContext);
                } catch (Throwable t) {
                    System.out.println("[AuxMon] DATCManager init note: " + t);
                }
            }

            if (hscanAdapter == null) {
                try {
                    Class<?> cls = Class.forName("com.hkmc.telematics.hscan.tm_interface.TM_HSCANAdapter");
                    Method m = cls.getMethod("getDefaultAdapter", ctxCls);
                    hscanAdapter = m.invoke(null, sysContext);
                } catch (Throwable t) {
                    System.out.println("[AuxMon] TM_HSCANAdapter init note: " + t);
                }
            }

            if (!listenersRegistered) {
                try {
                    ListenerRegistrar.registerAll(micomPowerMgr, bcmMgr, hevMgr);
                    listenersRegistered = true;
                } catch (Throwable t) {
                    System.out.println("[AuxMon] ListenerRegistrar note: " + t);
                }
            }
            return micomPowerMgr != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Polls current vehicle telemetry without fabricating values or triggering unnecessary CAN traffic.
     */
    public static LiveTelemetrySnapshot pollSnapshot() {
        if (sysContext == null) {
            initBridge(null);
        }
        LiveTelemetrySnapshot snap = new LiveTelemetrySnapshot();
        snap.timestampMs = System.currentTimeMillis();
        snap.elapsedRealtimeMs = AuxMonModels.elapsedRealtimeMs();

        // 1. Power Status & ACC/IGN (Micom powerStatus & TeleAcc are authoritative)
        int pwrSt = cachedPowerStatus;
        if (micomPowerMgr != null) {
            try {
                Object val = micomPowerMgr.getClass().getMethod("getPowerStatus").invoke(micomPowerMgr);
                if (val instanceof Integer) {
                    pwrSt = ((Integer) val).intValue();
                    cachedPowerStatus = pwrSt;
                }
            } catch (Throwable ignored) {
            }
        }
        if (pwrSt < 0) {
            pwrSt = 0;
        }
        snap.powerStatus = pwrSt;
        snap.teleAccStatus = cachedTeleAccStatus;

        // Micom powerStatus: 0=ACCON, 1=LOGICON, 2=ADM, 5=SYSTEMREADY, 6=DELAYMODE, 7=PRESYSTEMOFF, 255=OFF
        boolean nonDrivingMicom = (cachedTeleAccStatus == 1)
                || (pwrSt == 1 || pwrSt == 2 || pwrSt == 6 || pwrSt == 7 || pwrSt == 255);

        boolean ign = false;
        boolean acc = false;
        if (!nonDrivingMicom) {
            ign = (pwrSt == 0);
            acc = (pwrSt == 0 || cachedTeleAccStatus == 2);
            if (bcmMgr != null) {
                try {
                    Object ignVal = bcmMgr.getClass().getMethod("getIGNStatus").invoke(bcmMgr);
                    if (ignVal instanceof Boolean) {
                        ign = ((Boolean) ignVal).booleanValue();
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        snap.ignOn = ign;
        snap.accOn = acc;

        // 2. IG3 (Aux. Battery Saver+ / LDC 12V Charging Rail) Status
        boolean ig3 = (cachedIg3Status != null) ? cachedIg3Status.booleanValue() : false;
        if (hevMgr != null) {
            try {
                Object val = hevMgr.getClass().getMethod("getLastIGN3Status").invoke(hevMgr);
                if (val instanceof Boolean) {
                    ig3 = ((Boolean) val).booleanValue();
                    cachedIg3Status = Boolean.valueOf(ig3);
                }
            } catch (Throwable ignored) {
            }
        }
        if (!ig3 && datcMgr != null) {
            try {
                Object val = datcMgr.getClass().getMethod("isIGN3Status").invoke(datcMgr);
                if (val instanceof Boolean && ((Boolean) val).booleanValue()) {
                    ig3 = true;
                }
            } catch (Throwable ignored) {
            }
        }
        snap.ig3On = ig3;

        // 3. IG3 Relay Mode & HSCAN SOC
        int rlyMode = 0;
        int hscanSoc = -1;
        int auxOpt = 1;
        if (hscanAdapter != null) {
            try {
                Object v = hscanAdapter.getClass().getMethod("getIG3RlyMode").invoke(hscanAdapter);
                if (v instanceof Number) rlyMode = ((Number) v).intValue();
            } catch (Throwable ignored) {
            }
            try {
                Object v = hscanAdapter.getClass().getMethod("getBatSoc").invoke(hscanAdapter);
                if (v instanceof Number) {
                    int hs = ((Number) v).intValue();
                    // Treat 0 as uninitialized/unknown; valid SOC is 1..100
                    if (hs > 0 && hs <= 100) {
                        hscanSoc = hs;
                    }
                }
            } catch (Throwable ignored) {
            }
            try {
                Object v = hscanAdapter.getClass().getMethod("get_BAT_AuxBattOpt").invoke(hscanAdapter);
                if (v instanceof Number) auxOpt = ((Number) v).intValue();
            } catch (Throwable ignored) {
            }
        }
        snap.ig3RlyMode = rlyMode;
        snap.auxBattOpt = auxOpt;

        // 4. BCM 12V SOC (%) and Discharge / Auto-Cut Warnings
        int bcmSoc = cachedSocM;
        int socSeg = 0;
        int evtAlarm = cachedBatEvtAlarm;
        int mngAlert = cachedBattMngAlert;
        int autoCut = cachedAutoCutPreWrng;

        if (bcmMgr != null) {
            try {
                Object v = bcmMgr.getClass().getMethod("getBatSoc_M").invoke(bcmMgr);
                if (v instanceof Number) {
                    int val = ((Number) v).intValue();
                    // Treat 0, 255, and -1 as unknown; valid SOC is 1..100
                    if (val > 0 && val <= 100) {
                        bcmSoc = val;
                        cachedSocM = val;
                    }
                }
            } catch (Throwable ignored) {
            }
            try {
                Object v = bcmMgr.getClass().getMethod("getBatSocSegment").invoke(bcmMgr);
                if (v instanceof Number) socSeg = ((Number) v).intValue();
            } catch (Throwable ignored) {
            }
            try {
                Object v = bcmMgr.getClass().getMethod("getBatEvtAlramState").invoke(bcmMgr);
                if (v instanceof Number) evtAlarm = ((Number) v).intValue();
            } catch (Throwable ignored) {
            }
            try {
                Object v = bcmMgr.getClass().getMethod("getBattMngAlert").invoke(bcmMgr);
                if (v instanceof Number) mngAlert = ((Number) v).intValue();
            } catch (Throwable ignored) {
            }
            try {
                Object v = bcmMgr.getClass().getMethod("getBattAutoCutPreWrng").invoke(bcmMgr);
                if (v instanceof Number) autoCut = ((Number) v).intValue();
            } catch (Throwable ignored) {
            }
        }

        // Keep -1 when SOC is unknown (never substitute 85% or 0%)
        int finalSoc = (bcmSoc > 0 && bcmSoc <= 100) ? bcmSoc : ((hscanSoc > 0 && hscanSoc <= 100) ? hscanSoc : -1);
        snap.socPct = finalSoc;
        snap.batSocStatusFlag = cachedBatSocStatusFlag;
        snap.socSegment = socSeg;
        snap.batEvtAlarm = evtAlarm;
        snap.battMngAlert = mngAlert;
        snap.autoCutPreWrng = autoCut;

        // 5. Direct 12V Voltage (V) Probe (Sysfs / Prop) + Explicit Estimate Labeling
        float directVolts = 0.0f;
        String voltSrc = "UNKNOWN";

        float sysfsV = probeSysfs12vVoltage();
        if (sysfsV >= 8.0f && sysfsV <= 16.5f) {
            directVolts = sysfsV;
            voltSrc = "SYSFS_ADC";
        } else {
            float propV = probeProperty12vVoltage();
            if (propV >= 8.0f && propV <= 16.5f) {
                directVolts = propV;
                voltSrc = "SYS_PROP";
            }
        }

        if (directVolts < 8.0f) {
            if (snap.ig3On || snap.ig3RlyMode >= 2) {
                voltSrc = "IG3_EST(추정)";
            } else if (finalSoc > 0 && finalSoc <= 100) {
                voltSrc = "SOC_EST(추정)";
            } else {
                voltSrc = "UNKNOWN";
            }
        }

        snap.voltageV = AuxMonModels.synthesize12vVoltage(
                directVolts,
                finalSoc,
                snap.ig3On,
                snap.ig3RlyMode);
        snap.voltageSource = voltSrc;

        return snap;
    }

    private static float probeSysfs12vVoltage() {
        String[] candidates = new String[]{
            "/sys/class/power_supply/battery/voltage_now",
            "/sys/class/power_supply/vehicle_battery/voltage_now",
            "/sys/class/power_supply/ac/voltage_now"
        };
        for (int i = 0; i < candidates.length; i++) {
            File f = new File(candidates[i]);
            if (f.isFile()) {
                try {
                    String s = AuxMonModels.readFirstLine(f).trim();
                    if (!s.isEmpty()) {
                        long raw = Long.parseLong(s);
                        if (raw > 8000000L && raw < 17000000L) {
                            return raw / 1000000.0f;
                        } else if (raw > 8000L && raw < 17000L) {
                            return raw / 1000.0f;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return 0.0f;
    }

    private static float probeProperty12vVoltage() {
        try {
            Class<?> spCls = Class.forName("android.os.SystemProperties");
            Method getM = spCls.getMethod("get", String.class, String.class);
            String val = (String) getM.invoke(null, "sys.hkg.auxmon.test_voltage", "");
            if (val != null && !val.isEmpty()) {
                return Float.parseFloat(val);
            }
        } catch (Throwable ignored) {
        }
        return 0.0f;
    }

    /**
     * Immediately sends requestPwrKeepStart(2) from listener callback or session start.
     */
    public static synchronized void sendPwrKeepStartImmediate(String reason) {
        if (safetyCutoffTripped || !autoKeepOnShutdownEnabled) {
            return;
        }
        long nowRt = AuxMonModels.elapsedRealtimeMs();
        if (!keepAliveActive || pwrKeepActivatedRealtimeMs <= 0L) {
            pwrKeepActivatedRealtimeMs = nowRt;
        }
        keepAliveActive = true;
        lastPwrKeepSentRealtimeMs = nowRt;
        if (micomPowerMgr != null) {
            try {
                micomPowerMgr.getClass().getMethod("requestPwrKeepStart", int.class).invoke(micomPowerMgr, Integer.valueOf(2));
                System.out.println("[AuxMon] requestPwrKeepStart(2) sent immediately (" + reason + ")");
            } catch (Throwable t) {
                System.out.println("[AuxMon] sendPwrKeepStartImmediate error: " + t);
            }
        }
        manageWakeLock(true);
    }

    /**
     * Periodically re-sends requestPwrKeepStart(2) during parking to prevent OEM apps
     * (HKMC_TAppService, HKMC_TAppVRM, HKMC_DMClient_OTA) from canceling shared mode 2.
     */
    public static synchronized void refreshParkingPowerKeepIfDue(long nowRealtimeMs, long intervalMs) {
        if (!keepAliveActive || !autoKeepOnShutdownEnabled || safetyCutoffTripped) {
            return;
        }
        if (lastPwrKeepSentRealtimeMs <= 0L || (nowRealtimeMs - lastPwrKeepSentRealtimeMs >= intervalMs)) {
            lastPwrKeepSentRealtimeMs = nowRealtimeMs;
            if (micomPowerMgr != null) {
                try {
                    micomPowerMgr.getClass().getMethod("requestPwrKeepStart", int.class).invoke(micomPowerMgr, Integer.valueOf(2));
                    System.out.println("[AuxMon] Heartbeat requestPwrKeepStart(2) re-sent");
                } catch (Throwable ignored) {
                }
            }
            manageWakeLock(true);
        }
    }

    /**
     * Enables or disables headless power keep-alive during parking.
     * Uses MicomPowerManager.requestPwrKeepStart(2)/requestPwrKeepStop(2) + PARTIAL_WAKE_LOCK.
     * Returns true if the MicomPowerManager call succeeded.
     */
    public static synchronized boolean setParkingPowerKeep(boolean enable, boolean confirmShutdownWhenStopping) {
        long nowRt = AuxMonModels.elapsedRealtimeMs();
        if (enable) {
            if (!keepAliveActive || pwrKeepActivatedRealtimeMs <= 0L) {
                pwrKeepActivatedRealtimeMs = nowRt;
            }
            keepAliveActive = true;
            safetyCutoffTripped = false;
            lastPwrKeepSentRealtimeMs = nowRt;
            savePowerLatchState();
        } else {
            keepAliveActive = false;
            pwrKeepActivatedRealtimeMs = 0L;
        }

        boolean micomOk = false;
        if (micomPowerMgr != null) {
            try {
                if (enable) {
                    micomPowerMgr.getClass().getMethod("requestPwrKeepStart", int.class).invoke(micomPowerMgr, Integer.valueOf(2));
                    micomOk = true;
                    System.out.println("[AuxMon] MicomPowerManager.requestPwrKeepStart(2) invoked (keep-alive ON)");
                } else {
                    micomPowerMgr.getClass().getMethod("requestPwrKeepStop", int.class).invoke(micomPowerMgr, Integer.valueOf(2));
                    micomOk = true;
                    System.out.println("[AuxMon] MicomPowerManager.requestPwrKeepStop(2) invoked (keep-alive OFF)");
                    if (confirmShutdownWhenStopping) {
                        try {
                            micomPowerMgr.getClass().getMethod("confirmPrepareSystemOff").invoke(micomPowerMgr);
                        } catch (Throwable ignored) {
                        }
                        try {
                            micomPowerMgr.getClass().getMethod("confirmSystemOff").invoke(micomPowerMgr);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            } catch (Throwable t) {
                System.out.println("[AuxMon] setParkingPowerKeep error: " + t);
            }
        }
        manageWakeLock(enable);
        return micomOk;
    }

    /**
     * Requests LCD screen off when entering headless parking keep-alive, or restores screen when driving.
     * Uses MicomPowerManager.setScreenOn(boolean).
     */
    public static synchronized void setScreenSleepMode(boolean sleep) {
        if (micomPowerMgr == null) return;
        try {
            micomPowerMgr.getClass().getMethod("setScreenOn", boolean.class)
                    .invoke(micomPowerMgr, Boolean.valueOf(!sleep));
        } catch (Throwable ignored) {
        }
    }

    /**
     * Safely releases PwrKeep, stops IG3 ONLY if our app requested IG3 ON, and releases WakeLocks.
     * Returns true if MicomPowerManager.requestPwrKeepStop(2) was actually invoked.
     */
    public static synchronized boolean releaseAllPowerAndIg3(boolean markSafetyCutoff, boolean confirmShutdown) {
        if (markSafetyCutoff) {
            safetyCutoffTripped = true;
        }
        autoKeepOnShutdownEnabled = false;
        savePowerLatchState();
        stopIg3IfRequestedByUs();
        return setParkingPowerKeep(false, confirmShutdown);
    }

    /**
     * Sends IG3 OFF (reqEVIG3Change(false) & sendIG3RlyMode((byte) 0)) ONLY if our app
     * previously requested IG3 ON (scheduled 1h mode or manual IG3 test), so we never
     * interrupt the vehicle's own OEM Aux. Battery Saver+ charging or driving IG3 state.
     */
    public static synchronized boolean stopIg3IfRequestedByUs() {
        File flagFile = new File(IG3_REQ_FLAG_PATH);
        if (!weRequestedIg3On && ! manualIg3TestActive && !flagFile.isFile()) {
            return false;
        }
        return triggerIg3Charging(false, false);
    }

    private static void manageWakeLock(boolean acquire) {
        try {
            if (sysContext == null) return;
            if (wakeLock == null) {
                Method getSysSvc = sysContext.getClass().getMethod("getSystemService", String.class);
                Object pm = getSysSvc.invoke(sysContext, "power");
                if (pm != null) {
                    Method newWl = pm.getClass().getMethod("newWakeLock", int.class, String.class);
                    wakeLock = newWl.invoke(pm, Integer.valueOf(1), "HKGAuxMon:ParkingKeepAlive");
                    wakeLock.getClass().getMethod("setReferenceCounted", boolean.class).invoke(wakeLock, Boolean.FALSE);
                }
            }
            if (wakeLock != null) {
                boolean held = ((Boolean) wakeLock.getClass().getMethod("isHeld").invoke(wakeLock)).booleanValue();
                if (acquire && !held) {
                    wakeLock.getClass().getMethod("acquire").invoke(wakeLock);
                } else if (!acquire && held) {
                    wakeLock.getClass().getMethod("release").invoke(wakeLock);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Requests turning IG3 (12V Aux Battery Saver+ / LDC Charging) ON or OFF.
     * Uses TM_HSCANAdapter.sendIG3RlyMode(byte) per firmware signature (B)V.
     */
    public static synchronized boolean triggerIg3Charging(boolean enable, boolean isManualTest) {
        manualIg3TestActive = isManualTest && enable;
        weRequestedIg3On = enable;
        try {
            File flagFile = new File(IG3_REQ_FLAG_PATH);
            if (enable) {
                File p = flagFile.getParentFile();
                if (p != null && !p.exists()) p.mkdirs();
                AuxMonModels.writeBytesDirect(flagFile, "1\n".getBytes(AuxMonModels.UTF8));
            } else if (flagFile.exists()) {
                flagFile.delete();
            }
        } catch (Throwable ignored) {
        }

        boolean invokedAny = false;

        if (hevMgr != null) {
            try {
                hevMgr.getClass().getMethod("reqEVIG3Change", boolean.class).invoke(hevMgr, Boolean.valueOf(enable));
                invokedAny = true;
                System.out.println("[AuxMon] HEVManager.reqEVIG3Change(" + enable + ") invoked");
            } catch (Throwable t) {
                System.out.println("[AuxMon] reqEVIG3Change error: " + t);
            }
            try {
                hevMgr.getClass().getMethod("getIGN3Status").invoke(hevMgr);
            } catch (Throwable ignored) {
            }
        }

        if (hscanAdapter != null) {
            try {
                byte mode = (byte) (enable ? 1 : 0);
                hscanAdapter.getClass().getMethod("sendIG3RlyMode", byte.class).invoke(hscanAdapter, Byte.valueOf(mode));
                invokedAny = true;
                System.out.println("[AuxMon] TM_HSCANAdapter.sendIG3RlyMode((byte)" + mode + ") invoked");
            } catch (Throwable t) {
                System.out.println("[AuxMon] sendIG3RlyMode error: " + t);
            }
        }

        return invokedAny;
    }

    // ========================================================================
    // LAZILY-LOADED HKMC FRAMEWORK LISTENER SUBCLASSES
    // ========================================================================

    private static final class ListenerRegistrar {
        static void registerAll(final Object powerMgr, final Object bcm, final Object hev) {
            if (powerMgr != null) {
                try {
                    MicomPowerStatusListener pwrListener = new MicomPowerStatusListener() {
                        @Override
                        public void onPowerStatusChanged(int status) {
                            cachedPowerStatus = status;
                            if (status == 0) {
                                // ACCON: vehicle is running again -> clear safety cutoff & re-enable auto keep
                                resetSafetyCutoff();
                            } else if ((status == 1 || status == 2 || status == 6 || status == 7)
                                    && autoKeepOnShutdownEnabled && !safetyCutoffTripped) {
                                // Immediately assert PwrKeep(2) on LOGICON / ADM / DELAYMODE / PRESYSTEMOFF
                                sendPwrKeepStartImmediate("onPowerStatusChanged:" + status);
                                notifyAccOffIntercepted("POWER_STATUS_" + status);
                            }
                            notifyEvent("POWER_STATUS_" + status);
                        }

                        @Override
                        public void onTeleAccStatusChanged(int status) {
                            cachedTeleAccStatus = status;
                            if (status == 2) {
                                // TeleAcc ACCON -> clear safety cutoff & re-enable auto keep
                                resetSafetyCutoff();
                            } else if (status == 1 && autoKeepOnShutdownEnabled && !safetyCutoffTripped) {
                                // TeleAcc ACCOFF: immediately assert PwrKeep(2) before Micom starts shutdown
                                sendPwrKeepStartImmediate("onTeleAccStatusChanged:ACCOFF");
                                notifyAccOffIntercepted("TELE_ACC_OFF");
                            }
                            notifyEvent("TELE_ACC_" + status);
                        }

                        @Override
                        public void onPrepareSystemOff() {
                            if (autoKeepOnShutdownEnabled && !safetyCutoffTripped) {
                                // Intercept system off and request power keep immediately; do NOT confirmPrepareSystemOff
                                sendPwrKeepStartImmediate("onPrepareSystemOff");
                                notifyAccOffIntercepted("PREPARE_SYSTEM_OFF");
                            } else {
                                try {
                                    powerMgr.getClass().getMethod("confirmPrepareSystemOff").invoke(powerMgr);
                                } catch (Throwable ignored) {
                                }
                            }
                            notifyEvent("PREPARE_SYSTEM_OFF");
                        }

                        @Override
                        public void onSystemOff() {
                            if (autoKeepOnShutdownEnabled && !safetyCutoffTripped) {
                                // Intercept system off and request power keep; do NOT confirmSystemOff
                                sendPwrKeepStartImmediate("onSystemOff");
                                notifyAccOffIntercepted("SYSTEM_OFF");
                            } else {
                                try {
                                    powerMgr.getClass().getMethod("confirmSystemOff").invoke(powerMgr);
                                } catch (Throwable ignored) {
                                }
                            }
                            notifyEvent("SYSTEM_OFF");
                        }

                        @Override
                        public void onDischargeAlert() {
                            notifyDanger("MICOM_DISCHARGE_ALERT");
                        }

                        @Override
                        public void onLogicOnDischargeAlert() {
                            notifyDanger("MICOM_LOGICON_DISCHARGE_ALERT");
                        }
                    };
                    powerMgr.getClass().getMethod("registerPowerStatusListener", MicomPowerStatusListener.class)
                            .invoke(powerMgr, pwrListener);
                    try {
                        powerMgr.getClass().getMethod("registerSystemOffResponder", MicomPowerStatusListener.class)
                                .invoke(powerMgr, pwrListener);
                    } catch (Throwable ignored) {
                    }
                    System.out.println("[AuxMon] Registered MicomPowerStatusListener & SystemOffResponder");
                } catch (Throwable t) {
                    System.out.println("[AuxMon] MicomPowerStatusListener registration error: " + t);
                }
            }

            if (hev != null) {
                try {
                    HEVListener hevListener = new HEVListener() {
                        @Override
                        public void onEVIG3Changed(boolean ig3On) {
                            cachedIg3Status = Boolean.valueOf(ig3On);
                            notifyEvent("EV_IG3_CHANGED_" + ig3On);
                        }

                        @Override
                        public void onEVIG3Responded(boolean ig3On) {
                            cachedIg3Status = Boolean.valueOf(ig3On);
                            notifyEvent("EV_IG3_RESPONDED_" + ig3On);
                        }
                    };
                    hev.getClass().getMethod("addListener", HEVListener.class).invoke(hev, hevListener);
                    System.out.println("[AuxMon] Registered HEVListener (onEVIG3Changed, onEVIG3Responded)");
                } catch (Throwable t) {
                    System.out.println("[AuxMon] HEVListener registration error: " + t);
                }
            }

            if (bcm != null) {
                try {
                    BCMBattListener battListener = new BCMBattListener() {
                        @Override
                        public void onBatEvtAlarmChanged(int alarm) {
                            cachedBatEvtAlarm = alarm;
                            if (alarm > 0) {
                                notifyDanger("BCM_BAT_EVT_ALARM_" + alarm);
                            }
                        }

                        @Override
                        public void onChangedBattAutoCutPreWrng(int warning) {
                            cachedAutoCutPreWrng = warning;
                            if (warning == 1) {
                                notifyDanger("BCM_AUTOCUT_PRE_WARNING");
                            }
                        }

                        @Override
                        public void onChangedBattMngAlert(int alert) {
                            // BattMngAlert: 1=HAZARDON, 2=TAILON
                            cachedBattMngAlert = alert;
                            notifyEvent("BCM_BATT_MNG_ALERT_" + alert);
                        }
                    };
                    bcm.getClass().getMethod("addBattListener", BCMBattListener.class).invoke(bcm, battListener);

                    BCMVehicleStatusListener vsListener = new BCMVehicleStatusListener() {
                        @Override
                        public void onBatSocStatus_M(int statusFlag) {
                            // Firmware BatSocStatus_M is a status flag: NORMAL=0, UNNORMAL=1 (NOT percentage!)
                            cachedBatSocStatusFlag = statusFlag;
                            if (statusFlag == 1) {
                                notifyDanger("BCM_BAT_SOC_STATUS_UNNORMAL");
                            } else {
                                notifyEvent("BCM_BAT_SOC_STATUS_NORMAL");
                            }
                        }
                    };
                    bcm.getClass().getMethod("addVehicleStatusListener", BCMVehicleStatusListener.class).invoke(bcm, vsListener);
                    System.out.println("[AuxMon] Registered BCMBattListener & BCMVehicleStatusListener");
                } catch (Throwable t) {
                    System.out.println("[AuxMon] BCM listener registration error: " + t);
                }
            }
        }

        private static void notifyEvent(String ev) {
            EventCallback cb = eventCallback;
            if (cb != null) {
                cb.onVehicleEvent(ev);
            }
        }

        private static void notifyAccOffIntercepted(String src) {
            EventCallback cb = eventCallback;
            if (cb != null) {
                cb.onAccOffShutdownIntercepted(src);
            }
        }

        private static void notifyDanger(String reason) {
            EventCallback cb = eventCallback;
            if (cb != null) {
                cb.onDischargeDanger(reason);
            }
        }
    }
}
