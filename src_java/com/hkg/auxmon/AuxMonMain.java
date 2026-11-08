package com.hkg.auxmon;

import com.hkg.auxmon.AuxMonModels.ChargeEvent;
import com.hkg.auxmon.AuxMonModels.ExportResult;
import com.hkg.auxmon.AuxMonModels.LiveTelemetrySnapshot;
import com.hkg.auxmon.AuxMonModels.ParkingSession;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * HKG Standard Gen5W (Android 4.4.2, API 19) 12V Auxiliary Battery Parking Power-Keep &
 * Auto-Charging (Aux. Battery Saver+ / IG3) Monitor Daemon.
 *
 * Key architecture (per firmware review):
 *   1. Event-driven immediate PwrKeepStart(2) on ACCOFF / LOGICON / DELAYMODE / PRESYSTEMOFF / onPrepareSystemOff,
 *      with 60-second heartbeat re-transmission to survive OEM app mode-2 stop collisions.
 *   2. Supports "상시(무제한)" (targetHours = 0) as well as 1h..72h parking duration presets,
 *      and configurable 10s / 30s / 60s parking sampling intervals.
 *   3. Strict safety cutoff: never fabricates 85% SOC when SOC is unknown (-1); releases PwrKeep
 *      and confirms system off if SOC < 55%, BatSocStatus_M == UNNORMAL(1), AutoCutPreWrng == 1,
 *      BatEvtAlarm > 0, or if SOC remains unknown for > 30 minutes during parking.
 *   4. Uses monotonic clock (SystemClock.elapsedRealtime()) for all elapsed/scheduler timings.
 */
public class AuxMonMain {

    public static final String VERSION = "1.1.0";
    private static final String CMD_FILE_PATH = "/data/local/tmp/hkg_auxmon_cmd";
    private static final String STATE_JSON_PATH = "/data/local/tmp/hkg_auxmon_state.json";
    private static final String CONFIG_FILE_PATH = "/data/local/tmp/hkg_auxmon/config.txt";
    private static final String SESSIONS_DIR_PATH = "/data/local/tmp/hkg_auxmon/sessions";

    public static final int SAFETY_MIN_SOC_PCT = 55;
    public static final float SAFETY_MIN_DIRECT_VOLT = 11.55f;
    public static final long UNKNOWN_SOC_MAX_PARKING_SEC = 1800L; // Max 30 min keep-alive if SOC is unknown (-1)
    private static final long PWR_KEEP_HEARTBEAT_MS = 60000L;     // Re-send requestPwrKeepStart(2) every 60s
    private static final long SAVE_INTERVAL_MS = 30000L;          // Persist session to disk every 30s
    private static final long ACTIVE_IG3_PERIOD_SEC = 3600L;      // 1 hour period for active IG3 mode
    private static final long ACTIVE_IG3_DURATION_SEC = 1200L;    // 20 min charging duration per active cycle

    private static final Object STATE_LOCK = new Object();
    private static final List<ParkingSession> sessionHistory = new ArrayList<ParkingSession>();
    private static volatile ParkingSession activeParkingSession = null;
    private static volatile int displayedSessionIdx = -1;

    // Default target: 0 = 상시(무제한) 유지 (or 1..72 hours)
    private static volatile int configuredTargetHours = AuxMonModels.TARGET_HOURS_ALWAYS_ON;
    private static volatile int configuredSampleIntervalSec = 30; // 10s, 30s, 60s (default 30s in parking)
    private static volatile int configuredAutoChargeMode = AuxMonModels.MODE_PASSIVE_OBSERVE;
    private static volatile LiveTelemetrySnapshot latestLiveSnapshot = new LiveTelemetrySnapshot();

    private static volatile String statusBannerMessage =
            "💡 시동 종료(ACC/IGN OFF) 시 즉시 PwrKeep(2)을 요청하여 헤드유닛 절전 상태를 유지하고 전압·충전 이력을 기록합니다.";
    private static volatile int statusBannerColor = 0xFFFBBF24;
    private static volatile String pendingChargeHint = null;
    private static volatile boolean wakePollRequested = false;
    private static volatile boolean accOffInterceptedFlag = false;
    private static volatile boolean daemonRunning = true;

    // Active 1h IG3 scheduler tracking (monotonic seconds)
    private static volatile boolean scheduledIg3Running = false;
    private static volatile long scheduledIg3StartSec = -1L;
    private static volatile long lastScheduledIg3CycleSec = 0L;
    private static volatile long lastValidSocRealtimeMs = 0L;

    public static void main(String[] args) {
        String mode = (args != null && args.length > 0) ? args[0] : "daemon";
        if ("test-self".equals(mode)) {
            runSelfTests();
            return;
        }
        if ("release-keep".equals(mode)) {
            runReleaseKeepOneShot();
            return;
        }
        String installDir = (args != null && args.length > 1) ? args[1] : "/data/nemesis/apps/hkg.auxmon";
        runDaemon(installDir);
    }

    /**
     * One-shot release invoked by auxmon-daemon.sh on_stop and scripts/preun.sh
     * to guarantee Micom PwrKeep(2) and IG3 are turned OFF when the daemon stops or is uninstalled.
     */
    private static void runReleaseKeepOneShot() {
        try {
            System.out.println("[AuxMon] Executing release-keep one-shot...");
            HkmcVehicleBridge.initBridge(null);
            HkmcVehicleBridge.releaseAllPowerAndIg3(true, true);
            System.out.println("[AuxMon] release-keep completed.");
        } catch (Throwable t) {
            System.out.println("[AuxMon] release-keep error: " + t);
        }
    }

    private static void runDaemon(String installDir) {
        System.out.println("[AuxMon] Starting AuxMonMain v" + VERSION + " (installDir=" + installDir + ")");
        loadConfigFromDisk();
        loadExistingSessionsFromDisk();

        AuxMonOverlay.init();
        HkmcVehicleBridge.initBridge(AuxMonOverlay.getSysContext());
        HkmcVehicleBridge.setEventCallback(new HkmcVehicleBridge.EventCallback() {
            @Override
            public void onVehicleEvent(String eventName) {
                wakePollRequested = true;
                AuxMonOverlay.requestRefresh();
            }

            @Override
            public void onAccOffShutdownIntercepted(String triggerSource) {
                accOffInterceptedFlag = true;
                wakePollRequested = true;
            }

            @Override
            public void onDischargeDanger(String reason) {
                handleSafetyDischargeCutoff(reason);
            }
        });

        // Do NOT automatically pop up overlay on normal boot; only process command file if present.
        File cmdFile = new File(CMD_FILE_PATH);
        if (cmdFile.exists()) {
            handleCommandFile(cmdFile);
        }

        boolean wasDriving = true;
        long lastPollRt = 0L;
        long lastSampleRt = 0L;
        long lastDiskSaveRt = 0L;

        while (daemonRunning) {
            try {
                if (AuxMonOverlay.isUiLooperReady() && cmdFile.exists()) {
                    handleCommandFile(cmdFile);
                    if (!daemonRunning) {
                        break;
                    }
                }

                long nowRt = AuxMonModels.elapsedRealtimeMs();
                long nowEpochMs = System.currentTimeMillis();

                boolean monitoringParking = isCurrentlyMonitoringParking();
                boolean overlayShown = AuxMonOverlay.isOverlayVisible();
                long pollIntervalMs = (wasDriving || overlayShown)
                        ? 2000L
                        : Math.min(15000L, configuredSampleIntervalSec * 1000L);

                boolean shouldPollHardware = wakePollRequested
                        || accOffInterceptedFlag
                        || lastPollRt == 0L
                        || (nowRt - lastPollRt >= pollIntervalMs);

                if (!shouldPollHardware) {
                    if (monitoringParking) {
                        HkmcVehicleBridge.refreshParkingPowerKeepIfDue(nowRt, PWR_KEEP_HEARTBEAT_MS);
                    }
                    Thread.sleep(1000L);
                    continue;
                }

                lastPollRt = nowRt;
                LiveTelemetrySnapshot snap = HkmcVehicleBridge.pollSnapshot();
                latestLiveSnapshot = snap;
                if (snap.isSocValid()) {
                    lastValidSocRealtimeMs = nowRt;
                }

                boolean isDrivingNow = snap.isDrivingState();
                boolean intercepted = accOffInterceptedFlag;
                accOffInterceptedFlag = false;

                synchronized (STATE_LOCK) {
                    if ((wasDriving && !isDrivingNow) || (intercepted && !isDrivingNow && !isCurrentlyMonitoringParking())) {
                        // Driving just ended -> Start parking session (PwrKeepStart(2) already sent by callback or sent now)
                        String targetLbl = AuxMonModels.formatTargetHoursLabel(configuredTargetHours);
                        System.out.println("[AuxMon] Detected Driving -> Parking transition. Starting session (" + targetLbl + ").");
                        startNewParkingSessionLocked(
                                nowEpochMs,
                                nowRt,
                                "시동 OFF 감지: " + targetLbl + " 절전 유지(PwrKeep) 및 전압·충전 기록을 시작합니다.",
                                true);
                        lastSampleRt = nowRt;
                    } else if (!wasDriving && isDrivingNow) {
                        // Vehicle started driving again -> Finalize ongoing parking session & restore screen
                        HkmcVehicleBridge.resetSafetyCutoff();
                        HkmcVehicleBridge.setAutoKeepOnShutdownEnabled(true);
                        HkmcVehicleBridge.setScreenSleepMode(false);
                        if (activeParkingSession != null && activeParkingSession.status == AuxMonModels.SESSION_MONITORING_PARKING) {
                            activeParkingSession.closeOngoingChargeIfAny(nowEpochMs);
                            activeParkingSession.status = AuxMonModels.SESSION_ENDED_BY_DRIVE;
                            activeParkingSession.pwrKeepActive = false;
                            stopAllIg3Charging();
                            HkmcVehicleBridge.setParkingPowerKeep(false, false);
                            saveSessionQuietly(activeParkingSession);
                            setStatusBanner(
                                    "✓ 주행 재개 감지: 직전 주차 기록(충전 " + activeParkingSession.getChargeCount()
                                            + "회, 경과 " + AuxMonModels.formatDurationHms(activeParkingSession.getElapsedParkingSec())
                                            + ")이 저장되었습니다.",
                                    0xFF10B981);
                            AuxMonOverlay.showOverlay();
                        }
                    }
                    wasDriving = isDrivingNow;

                    // Process active parking monitoring session
                    if (activeParkingSession != null && activeParkingSession.status == AuxMonModels.SESSION_MONITORING_PARKING) {
                        long elapsedSec = Math.max(0L, (nowRt - activeParkingSession.driveEndRealtimeMs) / 1000L);
                        long targetSec = activeParkingSession.getTargetParkingSec();

                        // 1. Evaluate strict safety cutoff rules
                        String safetyViolation = evaluateSafetyCutoffReason(snap, nowRt, activeParkingSession.driveEndRealtimeMs);
                        if (safetyViolation != null) {
                            handleSafetyDischargeCutoff(safetyViolation);
                        } else {
                            // 2. Re-assert PwrKeepStart(2) every 60s to survive OEM app mode-2 stop collisions
                            HkmcVehicleBridge.refreshParkingPowerKeepIfDue(nowRt, PWR_KEEP_HEARTBEAT_MS);

                            // 3. Active 1h IG3 charging scheduler if enabled
                            manageActive1hIg3ScheduleLocked(elapsedSec, snap);

                            // 4. Record sample at configured interval (10s/30s/60s) or on event
                            long samplePeriodMs = configuredSampleIntervalSec * 1000L;
                            boolean shouldSample = wakePollRequested
                                    || (nowRt - lastSampleRt >= samplePeriodMs)
                                    || activeParkingSession.samples.isEmpty();
                            if (shouldSample) {
                                wakePollRequested = false;
                                lastSampleRt = nowRt;
                                String hint = pendingChargeHint;
                                pendingChargeHint = null;
                                activeParkingSession.recordSnapshot(snap, hint);
                            }

                            // 5. Check if bounded N hours completed (targetSec > 0; 0 means indefinite 상시 유지)
                            if (targetSec > 0L && elapsedSec >= targetSec) {
                                activeParkingSession.closeOngoingChargeIfAny(nowEpochMs);
                                activeParkingSession.status = AuxMonModels.SESSION_COMPLETED_N_HOURS;
                                activeParkingSession.pwrKeepActive = false;
                                stopAllIg3Charging();
                                HkmcVehicleBridge.setAutoKeepOnShutdownEnabled(false);
                                HkmcVehicleBridge.setParkingPowerKeep(false, true);
                                saveSessionQuietly(activeParkingSession);
                                setStatusBanner(
                                        "✓ 설정된 " + activeParkingSession.targetParkingHours + "시간 주차 기록 완료! (총 충전 "
                                                + activeParkingSession.getChargeCount() + "회). PwrKeep을 해제하고 슬립으로 전환합니다.",
                                        0xFF10B981);
                                System.out.println("[AuxMon] Completed N-hour parking session: " + activeParkingSession.sessionId);
                            } else if (nowRt - lastDiskSaveRt >= SAVE_INTERVAL_MS) {
                                lastDiskSaveRt = nowRt;
                                saveSessionQuietly(activeParkingSession);
                            }
                        }
                    } else {
                        wakePollRequested = false;
                    }
                }

                writeStateJson();
                Thread.sleep(1000L);
            } catch (Throwable t) {
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    /**
     * Evaluates whether any battery safety condition is violated during parking keep-alive.
     * Returns null if safe, or a human-readable reason string if cutoff must trigger immediately.
     */
    public static String evaluateSafetyCutoffReason(LiveTelemetrySnapshot snap, long nowRt, long sessionStartRt) {
        if (snap == null) return null;
        if (snap.autoCutPreWrng == 1) {
            return "BCM_AUTOCUT_PRE_WARNING(ICU 차단 사전경보)";
        }
        if (snap.batEvtAlarm > 0) {
            return "BCM_BAT_EVT_ALARM=" + snap.batEvtAlarm;
        }
        if (snap.batSocStatusFlag == 1) {
            return "BatSocStatus_M=UNNORMAL(1)";
        }
        if (snap.isSocValid() && snap.socPct < SAFETY_MIN_SOC_PCT) {
            return "SOC=" + snap.socPct + "% (<" + SAFETY_MIN_SOC_PCT + "%)";
        }
        if (snap.isDirectVoltageMeasured() && snap.voltageV > 1.0f && snap.voltageV < SAFETY_MIN_DIRECT_VOLT) {
            return "실측전압=" + AuxMonModels.formatFloat2(snap.voltageV) + "V (<" + SAFETY_MIN_DIRECT_VOLT + "V)";
        }
        if (!snap.isSocValid()) {
            long refRt = (lastValidSocRealtimeMs > sessionStartRt) ? lastValidSocRealtimeMs : sessionStartRt;
            long unknownSec = Math.max(0L, (nowRt - refRt) / 1000L);
            if (unknownSec >= UNKNOWN_SOC_MAX_PARKING_SEC) {
                return "SOC 미확인 " + (unknownSec / 60L) + "분 경과 (안전 한도 초과)";
            }
        }
        return null;
    }

    private static void manageActive1hIg3ScheduleLocked(long elapsedSec, LiveTelemetrySnapshot snap) {
        if (activeParkingSession == null) return;
        if (activeParkingSession.autoChargeMode != AuxMonModels.MODE_ACTIVE_1H_IG3) {
            stopScheduledIg3IfActive();
            return;
        }
        if (scheduledIg3Running) {
            boolean socFull = (snap.isSocValid() && snap.socPct >= 95);
            if (elapsedSec - scheduledIg3StartSec >= ACTIVE_IG3_DURATION_SEC || socFull) {
                stopScheduledIg3IfActive();
                setStatusBanner("⚡ 1시간 주기 IG3 강제충전 사이클 종료 (현재 SOC "
                        + AuxMonModels.formatSocPct(snap.socPct) + "). 다음 주기 대기 중...", 0xFF38BDF8);
            }
        } else {
            boolean timeForHourlyCharge = (elapsedSec - lastScheduledIg3CycleSec >= ACTIVE_IG3_PERIOD_SEC);
            boolean lowSocTrigger = (snap.isSocValid() && snap.socPct <= 75 && elapsedSec - lastScheduledIg3CycleSec >= 900L);
            if (timeForHourlyCharge || lowSocTrigger) {
                scheduledIg3Running = true;
                scheduledIg3StartSec = elapsedSec;
                lastScheduledIg3CycleSec = elapsedSec;
                pendingChargeHint = timeForHourlyCharge ? "ACTIVE_1H_IG3_SCHED" : "LOW_SOC_IG3_BOOST";
                HkmcVehicleBridge.triggerIg3Charging(true, false);
                setStatusBanner("⚡ 1시간 주기 IG3 충전 요청 전송 (20분간 차량 IG3 응답 감시)...", 0xFF10B981);
            }
        }
    }

    private static void stopScheduledIg3IfActive() {
        if (scheduledIg3Running) {
            scheduledIg3Running = false;
            HkmcVehicleBridge.triggerIg3Charging(false, false);
        }
    }

    /**
     * Ensures both scheduled IG3 and manual IG3 test are turned OFF when a session ends or safety cutoff trips.
     */
    private static void stopAllIg3Charging() {
        scheduledIg3Running = false;
        if (HkmcVehicleBridge.isManualIg3TestActive()) {
            HkmcVehicleBridge.triggerIg3Charging(false, false);
        } else {
            HkmcVehicleBridge.triggerIg3Charging(false, false);
        }
    }

    private static void handleSafetyDischargeCutoff(String reason) {
        synchronized (STATE_LOCK) {
            stopAllIg3Charging();
            HkmcVehicleBridge.releaseAllPowerAndIg3(true, true);
            if (activeParkingSession != null && activeParkingSession.status == AuxMonModels.SESSION_MONITORING_PARKING) {
                activeParkingSession.closeOngoingChargeIfAny(System.currentTimeMillis());
                activeParkingSession.status = AuxMonModels.SESSION_SAFETY_CUTOFF;
                activeParkingSession.pwrKeepActive = false;
                saveSessionQuietly(activeParkingSession);
            }
            setStatusBanner("⚠ 보조배터리 방전 보호 안전 차단 발동 (" + reason + ") — PwrKeep 및 IG3를 해제하고 종료를 승인했습니다.", 0xFFEF4444);
            System.out.println("[AuxMon] Safety discharge cutoff triggered: " + reason);
            AuxMonOverlay.requestRefresh();
        }
    }

    // ========================================================================
    // UI & COMMAND ACTIONS
    // ========================================================================

    private static void handleCommandFile(File cmdFile) {
        String raw = AuxMonModels.readFirstLine(cmdFile).trim();
        try {
            cmdFile.delete();
        } catch (Throwable ignored) {
        }
        if (raw.isEmpty()) return;
        String cmd = raw.toLowerCase(Locale.US);
        System.out.println("[AuxMon] Received command: " + cmd);

        if ("show".equals(cmd) || "start".equals(cmd)) {
            HkmcVehicleBridge.setScreenSleepMode(false);
            AuxMonOverlay.showOverlay();
        } else if ("hide".equals(cmd)) {
            AuxMonOverlay.hideOverlay();
        } else if ("toggle".equals(cmd)) {
            AuxMonOverlay.toggleOverlay();
        } else if ("release".equals(cmd) || "release-keep".equals(cmd)) {
            synchronized (STATE_LOCK) {
                if (activeParkingSession != null && activeParkingSession.status == AuxMonModels.SESSION_MONITORING_PARKING) {
                    activeParkingSession.closeOngoingChargeIfAny(System.currentTimeMillis());
                    activeParkingSession.status = AuxMonModels.SESSION_INTERRUPTED_REBOOT;
                    activeParkingSession.pwrKeepActive = false;
                    saveSessionQuietly(activeParkingSession);
                }
                stopAllIg3Charging();
                HkmcVehicleBridge.releaseAllPowerAndIg3(true, true);
                daemonRunning = false;
            }
            return;
        } else if ("start_parking".equals(cmd)) {
            AuxMonOverlay.showOverlay();
            synchronized (STATE_LOCK) {
                startNewParkingSessionLocked(
                        System.currentTimeMillis(),
                        AuxMonModels.elapsedRealtimeMs(),
                        "수동 주차 기록 시작 (" + AuxMonModels.formatTargetHoursLabel(configuredTargetHours) + ")",
                        false);
            }
        } else if ("stop_parking".equals(cmd)) {
            stopActiveParkingSessionManual();
        } else if ("demo".equals(cmd)) {
            AuxMonOverlay.showOverlay();
            loadDemoParkingSession();
        } else if ("export".equals(cmd)) {
            AuxMonOverlay.showOverlay();
            exportToUsbAsync();
        } else if (cmd.startsWith("hours:")) {
            try {
                int h = Integer.parseInt(cmd.substring(6).trim());
                setTargetParkingHours(h);
            } catch (Throwable ignored) {
            }
        }
        AuxMonOverlay.requestRefresh();
    }

    private static void startNewParkingSessionLocked(long nowEpochMs, long nowRt, String bannerMsg, boolean enterHeadlessSleep) {
        // Finalize any previous MONITORING session so it never stays as an orphan
        if (activeParkingSession != null && activeParkingSession.status == AuxMonModels.SESSION_MONITORING_PARKING) {
            activeParkingSession.closeOngoingChargeIfAny(nowEpochMs);
            activeParkingSession.status = AuxMonModels.SESSION_COMPLETED_N_HOURS;
            activeParkingSession.pwrKeepActive = false;
            saveSessionQuietly(activeParkingSession);
        }

        String sid = AuxMonModels.formatSessionIdTime(nowEpochMs);
        ParkingSession s = new ParkingSession(
                sid,
                nowEpochMs,
                configuredTargetHours,
                configuredAutoChargeMode,
                detectVehicleSummary());
        s.driveEndRealtimeMs = nowRt;
        s.lastUpdateRealtimeMs = nowRt;
        s.status = AuxMonModels.SESSION_MONITORING_PARKING;
        s.pwrKeepActive = true;

        LiveTelemetrySnapshot snap = HkmcVehicleBridge.pollSnapshot();
        latestLiveSnapshot = snap;
        if (snap.isSocValid()) {
            lastValidSocRealtimeMs = nowRt;
        }
        s.recordSnapshot(snap, null);

        activeParkingSession = s;
        sessionHistory.add(s);
        displayedSessionIdx = sessionHistory.size() - 1;
        scheduledIg3Running = false;
        lastScheduledIg3CycleSec = 0L;

        HkmcVehicleBridge.setAutoKeepOnShutdownEnabled(true);
        HkmcVehicleBridge.setParkingPowerKeep(true, false);
        if (enterHeadlessSleep) {
            AuxMonOverlay.hideOverlay();
            HkmcVehicleBridge.setScreenSleepMode(true);
        }
        saveSessionQuietly(s);
        setStatusBanner("▶ " + bannerMsg + " (세션 " + sid + ", PwrKeep ON)", 0xFF38BDF8);
    }

    public static void toggleManualParkingSession() {
        synchronized (STATE_LOCK) {
            if (activeParkingSession != null && activeParkingSession.status == AuxMonModels.SESSION_MONITORING_PARKING) {
                stopActiveParkingSessionManual();
            } else {
                startNewParkingSessionLocked(
                        System.currentTimeMillis(),
                        AuxMonModels.elapsedRealtimeMs(),
                        "수동 주차 감시 시작: " + AuxMonModels.formatTargetHoursLabel(configuredTargetHours) + " 동안 전압과 충전 횟수를 기록합니다.",
                        false);
            }
        }
    }

    public static void stopActiveParkingSessionManual() {
        synchronized (STATE_LOCK) {
            if (activeParkingSession != null && activeParkingSession.status == AuxMonModels.SESSION_MONITORING_PARKING) {
                long nowMs = System.currentTimeMillis();
                activeParkingSession.closeOngoingChargeIfAny(nowMs);
                activeParkingSession.status = AuxMonModels.SESSION_COMPLETED_N_HOURS;
                activeParkingSession.pwrKeepActive = false;
                stopAllIg3Charging();
                HkmcVehicleBridge.setParkingPowerKeep(false, false);
                saveSessionQuietly(activeParkingSession);
                setStatusBanner(
                        "■ 주차 기록을 종료하고 PwrKeep을 해제했습니다 (세션 " + activeParkingSession.sessionId
                                + ", 총 충전 " + activeParkingSession.getChargeCount() + "회).",
                        0xFFFBBF24);
            }
        }
    }

    public static void toggleManualIg3Test() {
        boolean nextState = !HkmcVehicleBridge.isManualIg3TestActive();
        pendingChargeHint = nextState ? "MANUAL_IG3_TEST" : null;
        HkmcVehicleBridge.triggerIg3Charging(nextState, true);

        synchronized (STATE_LOCK) {
            LiveTelemetrySnapshot snap = HkmcVehicleBridge.pollSnapshot();
            latestLiveSnapshot = snap;
            if (activeParkingSession == null || activeParkingSession.status != AuxMonModels.SESSION_MONITORING_PARKING) {
                startNewParkingSessionLocked(
                        System.currentTimeMillis(),
                        AuxMonModels.elapsedRealtimeMs(),
                        "IG3 수동충전 테스트용 주차 기록 세션 시작",
                        false);
            } else {
                activeParkingSession.recordSnapshot(snap, nextState ? "MANUAL_IG3_TEST" : null);
                saveSessionQuietly(activeParkingSession);
            }
        }

        if (nextState) {
            setStatusBanner("⚡ [IG3 수동충전 요청 ON] reqEVIG3Change(true) + sendIG3RlyMode(1) 전송! 차량의 실제 IG3 응답(ON/OFF)을 확인하세요.", 0xFF10B981);
        } else {
            setStatusBanner("⚡ [IG3 수동충전 요청 OFF] 수동 IG3 충전 요청을 해제했습니다.", 0xFF38BDF8);
        }
    }

    public static void loadDemoParkingSession() {
        synchronized (STATE_LOCK) {
            ParkingSession demo = AuxMonModels.createDemoParkingSession(configuredTargetHours, detectVehicleSummary());
            sessionHistory.add(demo);
            displayedSessionIdx = sessionHistory.size() - 1;
            saveSessionQuietly(demo);
            setStatusBanner(
                    "🧪 시뮬레이션 데이터(자동충전 " + demo.getChargeCount()
                            + "회, 샘플 " + demo.samples.size() + "건) 생성 완료! [데이터 내보내기]로 USB 저장을 테스트해보세요.",
                    0xFFC7D2FE);
        }
    }

    public static void exportToUsbAsync() {
        setStatusBanner("💾 연결된 USB 메모리(/mnt/media_rw/usb0 등)를 탐색하여 주차 전압 및 충전 이력 데이터를 저장하고 있습니다...", 0xFF38BDF8);
        AuxMonOverlay.requestRefresh();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                ParkingSession target;
                List<ParkingSession> allCopy;
                synchronized (STATE_LOCK) {
                    target = getDisplayedSession();
                    allCopy = new ArrayList<ParkingSession>(sessionHistory);
                }
                // Do NOT fabricate a demo session if no sessions exist!
                ExportResult res = AuxMonModels.exportSessionToUsb(target, allCopy, null);
                setStatusBanner(res.message, res.success ? 0xFF10B981 : 0xFFEF4444);
                AuxMonOverlay.requestRefresh();
            }
        }, "AuxMonUsbExport");
        t.setDaemon(true);
        t.start();
    }

    public static void adjustTargetParkingHours(int delta) {
        synchronized (STATE_LOCK) {
            if (configuredTargetHours == 0) {
                setTargetParkingHours(delta > 0 ? 1 : 48);
            } else {
                int next = configuredTargetHours + delta;
                if (next <= 0) {
                    setTargetParkingHours(0); // 0 = 상시(무제한)
                } else {
                    setTargetParkingHours(Math.min(72, next));
                }
            }
        }
    }

    public static void cyclePresetParkingHours() {
        // Presets include 0 (상시 무제한) as well as 1, 3, 6, 12, 24, 48 hours
        int[] presets = new int[]{0, 1, 3, 6, 12, 24, 48};
        synchronized (STATE_LOCK) {
            int next = 0;
            for (int i = 0; i < presets.length; i++) {
                if (presets[i] == configuredTargetHours) {
                    next = presets[(i + 1) % presets.length];
                    break;
                }
            }
            setTargetParkingHours(next);
        }
    }

    private static void setTargetParkingHours(int hours) {
        int normalized = ParkingSession.normalizeTargetHours(hours);
        configuredTargetHours = normalized;
        if (activeParkingSession != null && activeParkingSession.status == AuxMonModels.SESSION_MONITORING_PARKING) {
            activeParkingSession.targetParkingHours = normalized;
        }
        saveConfigToDisk();
        setStatusBanner("⏱ 시동 종료 후 절전 유지(PwrKeep) 목표 시간이 ["
                + AuxMonModels.formatTargetHoursLabel(normalized) + "]으로 설정되었습니다.", 0xFF38BDF8);
    }

    public static void cycleSampleIntervalSec() {
        int[] intervals = new int[]{10, 30, 60};
        synchronized (STATE_LOCK) {
            int next = 30;
            for (int i = 0; i < intervals.length; i++) {
                if (intervals[i] == configuredSampleIntervalSec) {
                    next = intervals[(i + 1) % intervals.length];
                    break;
                }
            }
            configuredSampleIntervalSec = next;
            saveConfigToDisk();
            setStatusBanner("⏱ 주차 중 샘플링 및 폴링 주기가 [" + next + "초]로 설정되었습니다.", 0xFF38BDF8);
        }
    }

    public static void toggleAutoChargeMode() {
        synchronized (STATE_LOCK) {
            if (configuredAutoChargeMode == AuxMonModels.MODE_PASSIVE_OBSERVE) {
                configuredAutoChargeMode = AuxMonModels.MODE_ACTIVE_1H_IG3;
                setStatusBanner("⚡ [1시간 주기 IG3 강제충전 모드] 주차 중 1시간마다 IG3 충전을 요청하여 보조배터리와 상시 절전 전원을 유지합니다.", 0xFF10B981);
            } else {
                configuredAutoChargeMode = AuxMonModels.MODE_PASSIVE_OBSERVE;
                stopScheduledIg3IfActive();
                setStatusBanner("🔍 [순정 Aux. Battery Saver+ 감시 모드] 차량 자체의 자동충전 동작 여부와 SOC·전압 변화를 그대로 관찰·기록합니다.", 0xFF38BDF8);
            }
            if (activeParkingSession != null && activeParkingSession.status == AuxMonModels.SESSION_MONITORING_PARKING) {
                activeParkingSession.autoChargeMode = configuredAutoChargeMode;
            }
            saveConfigToDisk();
        }
    }

    public static void selectAdjacentSession(int dir) {
        synchronized (STATE_LOCK) {
            if (sessionHistory.isEmpty()) return;
            if (displayedSessionIdx < 0 || displayedSessionIdx >= sessionHistory.size()) {
                displayedSessionIdx = sessionHistory.size() - 1;
            }
            int next = displayedSessionIdx + dir;
            if (next < 0) next = 0;
            if (next >= sessionHistory.size()) next = sessionHistory.size() - 1;
            displayedSessionIdx = next;
            ParkingSession s = sessionHistory.get(displayedSessionIdx);
            setStatusBanner("📂 세션 [" + s.sessionId + "] 조회 중 (" + (displayedSessionIdx + 1) + "/" + sessionHistory.size()
                    + " | 충전 " + s.getChargeCount() + "회 | 샘플 " + s.samples.size() + "건)", 0xFFE2E8F0);
        }
    }

    // ========================================================================
    // GETTERS FOR OVERLAY UI
    // ========================================================================

    public static int getConfiguredTargetHours() {
        return configuredTargetHours;
    }

    public static int getConfiguredSampleIntervalSec() {
        return configuredSampleIntervalSec;
    }

    public static int getConfiguredAutoChargeMode() {
        return configuredAutoChargeMode;
    }

    public static LiveTelemetrySnapshot getLatestLiveSnapshot() {
        return latestLiveSnapshot;
    }

    public static boolean isCurrentlyMonitoringParking() {
        ParkingSession s = activeParkingSession;
        return s != null && s.status == AuxMonModels.SESSION_MONITORING_PARKING;
    }

    public static ParkingSession getDisplayedSession() {
        synchronized (STATE_LOCK) {
            if (sessionHistory.isEmpty()) {
                return activeParkingSession;
            }
            if (displayedSessionIdx >= 0 && displayedSessionIdx < sessionHistory.size()) {
                return sessionHistory.get(displayedSessionIdx);
            }
            return sessionHistory.get(sessionHistory.size() - 1);
        }
    }

    public static String getSessionNavLabel() {
        synchronized (STATE_LOCK) {
            if (sessionHistory.isEmpty()) {
                return "세션 없음 (0/0)";
            }
            int idx = (displayedSessionIdx >= 0 && displayedSessionIdx < sessionHistory.size())
                    ? displayedSessionIdx : (sessionHistory.size() - 1);
            ParkingSession s = sessionHistory.get(idx);
            String shortId = (s.sessionId != null && s.sessionId.length() > 11)
                    ? s.sessionId.substring(s.sessionId.length() - 11) : s.sessionId;
            return shortId + " (" + (idx + 1) + "/" + sessionHistory.size() + ")";
        }
    }

    public static String getStatusBannerMessage() {
        return statusBannerMessage;
    }

    public static int getStatusBannerColor() {
        return statusBannerColor;
    }

    private static void setStatusBanner(String msg, int color) {
        statusBannerMessage = msg;
        statusBannerColor = color;
    }

    // ========================================================================
    // CONFIG & STATE PERSISTENCE
    // ========================================================================

    private static void loadConfigFromDisk() {
        try {
            File f = new File(CONFIG_FILE_PATH);
            if (!f.isFile()) return;
            String txt = AuxMonModels.readFileText(f);
            String[] lines = txt.split("\n");
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.startsWith("target_hours=")) {
                    configuredTargetHours = ParkingSession.normalizeTargetHours(Integer.parseInt(line.substring(13).trim()));
                } else if (line.startsWith("sample_interval_sec=")) {
                    int sec = Integer.parseInt(line.substring(20).trim());
                    if (sec == 10 || sec == 30 || sec == 60) {
                        configuredSampleIntervalSec = sec;
                    }
                } else if (line.startsWith("auto_mode=")) {
                    configuredAutoChargeMode = Integer.parseInt(line.substring(10).trim());
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void saveConfigToDisk() {
        try {
            File f = new File(CONFIG_FILE_PATH);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            String content = "target_hours=" + configuredTargetHours
                    + "\nsample_interval_sec=" + configuredSampleIntervalSec
                    + "\nauto_mode=" + configuredAutoChargeMode + "\n";
            AuxMonModels.writeBytesAtomic(f, content.getBytes(AuxMonModels.UTF8));
        } catch (Throwable ignored) {
        }
    }

    private static void loadExistingSessionsFromDisk() {
        synchronized (STATE_LOCK) {
            File dir = new File(SESSIONS_DIR_PATH);
            List<ParkingSession> loaded = AuxMonModels.loadSavedSessions(dir);
            sessionHistory.clear();
            sessionHistory.addAll(loaded);
            if (!sessionHistory.isEmpty()) {
                displayedSessionIdx = sessionHistory.size() - 1;
            }
        }
    }

    private static void saveSessionQuietly(ParkingSession s) {
        try {
            AuxMonModels.saveSessionToDisk(new File(SESSIONS_DIR_PATH), s);
        } catch (Throwable ignored) {
        }
    }

    private static void writeStateJson() {
        try {
            ParkingSession s = getDisplayedSession();
            LiveTelemetrySnapshot live = latestLiveSnapshot;
            StringBuilder sb = new StringBuilder(512);
            sb.append("{\n");
            sb.append("  \"version\": \"").append(VERSION).append("\",\n");
            sb.append("  \"target_hours\": ").append(configuredTargetHours).append(",\n");
            sb.append("  \"sample_interval_sec\": ").append(configuredSampleIntervalSec).append(",\n");
            sb.append("  \"auto_charge_mode\": ").append(configuredAutoChargeMode).append(",\n");
            sb.append("  \"pwr_keep_active\": ").append(HkmcVehicleBridge.isKeepAliveActive()).append(",\n");
            sb.append("  \"live_voltage_v\": ").append(AuxMonModels.formatFloat2(live != null ? live.voltageV : 0f)).append(",\n");
            sb.append("  \"live_voltage_source\": \"").append(live != null ? live.voltageSource : "UNKNOWN").append("\",\n");
            sb.append("  \"live_soc_pct\": ").append(live != null ? live.socPct : -1).append(",\n");
            sb.append("  \"live_ig3_on\": ").append(live != null && live.ig3On).append(",\n");
            sb.append("  \"session_id\": \"").append(s != null && s.sessionId != null ? AuxMonModels.escapeJson(s.sessionId) : "-").append("\",\n");
            sb.append("  \"charge_count\": ").append(s != null ? s.getChargeCount() : 0).append(",\n");
            sb.append("  \"elapsed_sec\": ").append(s != null ? s.getElapsedParkingSec() : 0L).append(",\n");
            sb.append("  \"sample_count\": ").append(s != null ? s.samples.size() : 0).append("\n");
            sb.append("}\n");
            FileOutputStream fos = new FileOutputStream(STATE_JSON_PATH);
            try {
                fos.write(sb.toString().getBytes(AuxMonModels.UTF8));
            } finally {
                fos.close();
            }
        } catch (Throwable ignored) {
        }
    }

    private static String detectVehicleSummary() {
        String prod = getSystemProperty("ro.build.product2", "");
        if (prod.isEmpty()) prod = getSystemProperty("ro.build.product", "Gen5W");
        String soc = getSystemProperty("ro.board.platform", "tcc897x");
        String rel = getSystemProperty("ro.build.version.release", "4.4.2");
        return prod + " (" + soc + " / Android " + rel + ")";
    }

    public static String getSystemProperty(String key, String defValue) {
        try {
            Class<?> spCls = Class.forName("android.os.SystemProperties");
            Method getM = spCls.getMethod("get", String.class, String.class);
            Object val = getM.invoke(null, key, defValue);
            return (val != null) ? val.toString() : defValue;
        } catch (Throwable t) {
            return defValue;
        }
    }

    // ========================================================================
    // HOST SELF-TEST SUITE (Executed by build_package.py before DEX packaging)
    // ========================================================================

    private static void runSelfTests() {
        try {
            // 1. Test 12V Voltage Synthesis & Unknown SOC Handling
            float vRest80 = AuxMonModels.synthesize12vVoltage(0.0f, 80, false, 0);
            assertTrue(vRest80 >= 12.50f && vRest80 <= 12.65f, "80% resting voltage in 12.50~12.65V: " + vRest80);

            float vChg80 = AuxMonModels.synthesize12vVoltage(0.0f, 80, true, 2);
            assertTrue(vChg80 >= 14.30f && vChg80 <= 14.50f, "IG3 charging voltage in 14.30~14.50V: " + vChg80);

            float vUnknown = AuxMonModels.synthesize12vVoltage(0.0f, -1, false, 0);
            assertEq(Float.valueOf(0.0f), Float.valueOf(vUnknown), "unknown SOC (-1) with IG3 OFF returns 0.0V (not fake 85%)");

            float vDirect = AuxMonModels.synthesize12vVoltage(12.42f, -1, false, 0);
            assertEq(Float.valueOf(12.42f), Float.valueOf(vDirect), "direct CAN voltage pass-through");

            // 2. Test Driving vs Parking Detection & Charging Detection (no circular logic, ig3RlyMode=1 is standby)
            LiveTelemetrySnapshot snapStandby = new LiveTelemetrySnapshot();
            snapStandby.powerStatus = 1; // LOGICON
            snapStandby.teleAccStatus = 1; // ACCOFF
            snapStandby.ignOn = false;
            snapStandby.accOn = false;
            snapStandby.ig3On = false;
            snapStandby.ig3RlyMode = 1; // MONITORING standby
            snapStandby.socPct = 82;
            snapStandby.voltageV = 14.40f;
            snapStandby.voltageSource = "SOC_EST(추정)";
            assertTrue(!snapStandby.isDrivingState(), "LOGICON + ACCOFF is NOT driving");
            assertTrue(!snapStandby.isChargingActive(), "ig3RlyMode=1 (MONITORING) and SOC_EST voltage must NOT trigger charging");

            // 3. Test Safety Cutoff Rules (Low SOC, BatSocStatus_M UNNORMAL, Unknown SOC timeout)
            LiveTelemetrySnapshot snapUnnormal = new LiveTelemetrySnapshot();
            snapUnnormal.socPct = 80;
            snapUnnormal.batSocStatusFlag = 1; // UNNORMAL
            assertTrue(evaluateSafetyCutoffReason(snapUnnormal, 10000L, 0L) != null, "BatSocStatus_M=1 triggers safety cutoff");

            LiveTelemetrySnapshot snapUnknownSoc = new LiveTelemetrySnapshot();
            snapUnknownSoc.socPct = -1; // Unknown SOC
            lastValidSocRealtimeMs = 0L;
            assertEq(null, evaluateSafetyCutoffReason(snapUnknownSoc, 600000L, 0L), "Unknown SOC at 10m within grace window");
            assertTrue(evaluateSafetyCutoffReason(snapUnknownSoc, 1900000L, 0L) != null, "Unknown SOC > 30m triggers safety cutoff");

            // 4. Test Parking Session Recording, Serialization, Orphan Cleanup & USB Export
            ParkingSession demo = AuxMonModels.createDemoParkingSession(6, "DE22EV (tcc897x / Android 4.4.2)");
            assertEq(Integer.valueOf(2), Integer.valueOf(demo.getChargeCount()), "demo session 2 charging events");
            assertTrue(demo.samples.size() >= 100, "demo session sample count >= 100");

            ChargeEvent ev1 = demo.chargeEvents.get(0);
            assertEq(Integer.valueOf(1), Integer.valueOf(ev1.chargeIndex), "first charge index = 1");
            assertTrue(ev1.endSocPct > ev1.startSocPct, "SOC increased during charge #1");

            String recText = AuxMonModels.serializeSessionRecord(demo);
            ParkingSession restored = AuxMonModels.deserializeSessionRecord(recText);
            assertTrue(restored != null, "deserialized session not null");
            assertEq(demo.sessionId, restored.sessionId, "restored sessionId");
            assertEq(Integer.valueOf(demo.getChargeCount()), Integer.valueOf(restored.getChargeCount()), "restored charge count");

            File tmpUsb = new File(System.getProperty("java.io.tmpdir"), "hkg_auxmon_usb_test_" + System.nanoTime());
            tmpUsb.mkdirs();
            try {
                // Verify empty export refuses without fabricating fake demo data
                ExportResult emptyRes = AuxMonModels.exportSessionToUsb(null, new ArrayList<ParkingSession>(), tmpUsb);
                assertTrue(!emptyRes.success, "empty export must not fabricate demo data");

                List<ParkingSession> all = new ArrayList<ParkingSession>();
                all.add(restored);
                ExportResult res = AuxMonModels.exportSessionToUsb(restored, all, tmpUsb);
                assertTrue(res.success, "USB export succeeded: " + res.message);
                assertEq(Integer.valueOf(4), Integer.valueOf(res.filesWritten), "4 export files written");

                File logDir = new File(tmpUsb, "hkg_aux_battery_logs");
                File csvSamples = new File(logDir, "parking_voltage_samples_" + restored.sessionId + ".csv");
                File csvCharges = new File(logDir, "parking_charge_events_" + restored.sessionId + ".csv");
                File jsonFile = new File(logDir, "parking_session_" + restored.sessionId + ".json");
                File reportTxt = new File(logDir, "parking_report_" + restored.sessionId + ".txt");

                assertTrue(csvSamples.isFile() && csvSamples.length() > 1000L, "samples CSV exists and non-empty");
                assertTrue(csvCharges.isFile() && csvCharges.length() > 100L, "charges CSV exists and non-empty");
                assertTrue(jsonFile.isFile() && jsonFile.length() > 200L, "session JSON exists and non-empty");
                assertTrue(reportTxt.isFile() && reportTxt.length() > 500L, "Korean report TXT exists and non-empty");
            } finally {
                deleteRecursive(tmpUsb);
            }

            System.out.println("[AuxMon SelfTest] All 4 test suites passed successfully.");
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (int i = 0; i < children.length; i++) {
                    deleteRecursive(children[i]);
                }
            }
        }
        f.delete();
    }

    private static void assertTrue(boolean cond, String label) {
        if (!cond) throw new RuntimeException("Assertion failed: " + label);
    }

    private static void assertEq(Object expected, Object actual, String label) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new RuntimeException("Assertion failed [" + label + "]: expected=" + expected + ", actual=" + actual);
        }
    }
}
