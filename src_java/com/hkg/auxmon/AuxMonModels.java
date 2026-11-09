package com.hkg.auxmon;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Data models, local persistence, and USB export engine for the 12V Auxiliary Battery
 * Parking Power-Keep & Auto-Charging (IG3 / Aux. Battery Saver+) Monitor.
 */
public final class AuxMonModels {

    public static final Charset UTF8 = Charset.forName("UTF-8");

    // Session Status Constants
    public static final int SESSION_WAITING_DRIVE_END = 0;
    public static final int SESSION_MONITORING_PARKING = 1;
    public static final int SESSION_COMPLETED_N_HOURS = 2;
    public static final int SESSION_SAFETY_CUTOFF = 3;
    public static final int SESSION_ENDED_BY_DRIVE = 4;
    public static final int SESSION_INTERRUPTED_REBOOT = 5;

    // Auto-Charge Mode Constants
    public static final int MODE_PASSIVE_OBSERVE = 0;   // 순정 Aux. Battery Saver+ 감시 모드
    public static final int MODE_ACTIVE_1H_IG3 = 1;     // 1시간 주기 IG3 강제충전 유지 모드

    // Target Parking Hours Special Constant: 0 = Indefinite ("상시 유지" until safety cutoff or drive resume)
    public static final int TARGET_HOURS_ALWAYS_ON = 0;

    private AuxMonModels() {
    }

    /**
     * Returns monotonic milliseconds since boot (SystemClock.elapsedRealtime() on Android,
     * or System.nanoTime() / 1_000_000L on host JVM). Immune to GPS/RTC wall-clock jumps.
     */
    public static long elapsedRealtimeMs() {
        try {
            Class<?> scCls = Class.forName("android.os.SystemClock");
            Method m = scCls.getMethod("elapsedRealtime");
            Object v = m.invoke(null);
            if (v instanceof Long) {
                long rt = ((Long) v).longValue();
                if (rt > 0L) {
                    return rt;
                }
            }
        } catch (Throwable ignored) {
        }
        return System.nanoTime() / 1000000L;
    }

    public static final class LiveTelemetrySnapshot {
        public long timestampMs;
        public long elapsedRealtimeMs;
        public int powerStatus = 0;       // 0=ACCON, 1=LOGICON, 2=ADM, 5=SYSTEMREADY, 6=DELAYMODE, 7=PRESYSTEMOFF, 255=OFF
        public int teleAccStatus = -1;    // 2=ACCON, 1=ACCOFF, 255=INVALID
        public boolean accOn = true;
        public boolean ignOn = true;
        public boolean ig3On = false;
        public int ig3RlyMode = 0;        // 0=OFF, 1=MONITORING(대기), 2=ACTIVE/RESERVATION, 3=REMOTE
        public float voltageV = 0.0f;
        public String voltageSource = "SOC_EST"; // SYSFS_ADC, SYS_PROP, CAN_VB, SOC_EST, UNKNOWN
        public int socPct = -1;           // -1 = 미확인(UNKNOWN), 0..100 = 실측 SOC(%)
        public int batSocStatusFlag = 0;  // BatSocStatus_M: 0=NORMAL, 1=UNNORMAL
        public int socSegment = 0;
        public int batEvtAlarm = 0;       // 0=정상, 1..4=배터리 방전 경보
        public int battMngAlert = 0;      // 0=정상, 1=HAZARDON(비상등), 2=TAILON(미등)
        public int autoCutPreWrng = 0;    // 0=정상, 1=ICU 전원 자동 차단 사전 경보
        public int auxBattOpt = 1;

        /**
         * Determines whether the vehicle is currently in ACC/IGN ON (driving/accessory) state.
         * 1. powerStatus == 0 (ACCON) is checked FIRST and ALWAYS means driving, even if
         *    a stale teleAccStatus == 1 (ACCOFF) remains cached from an earlier remote event.
         * 2. Explicit non-driving Micom states (LOGICON=1, ADM=2, DELAYMODE=6, PRESYSTEMOFF=7, OFF=255)
         *    or teleAccStatus == 1 (when powerStatus != 0) mean NOT driving, even if BCM
         *    getIGNStatus() remains cached true after CAN sleep.
         */
        public boolean isDrivingState() {
            if (powerStatus == 0) {
                return true;
            }
            if (powerStatus == 1 || powerStatus == 2 || powerStatus == 6 || powerStatus == 7 || powerStatus == 255) {
                return false;
            }
            if (teleAccStatus == 1 || teleAccStatus == 2) {
                return false;
            }
            return ignOn || accOn;
        }

        /**
         * Returns true only when Micom powerStatus is in shutdown phase (DELAYMODE=6 or PRESYSTEMOFF=7),
         * where turning off the LCD screen for headless parking keep-alive is safe.
         * LOGICON(1) and ADM(2) keep the screen ON because the user may be operating the headunit with IGN OFF.
         */
        public boolean isShutdownPowerState() {
            return powerStatus == 6 || powerStatus == 7;
        }

        public boolean isDirectVoltageMeasured() {
            return "SYSFS_ADC".equals(voltageSource)
                    || "SYS_PROP".equals(voltageSource)
                    || "CAN_VB".equals(voltageSource);
        }

        public boolean isSocValid() {
            return socPct > 0 && socPct <= 100;
        }

        /**
         * Determines whether 12V auxiliary battery charging (IG3 / LDC) is currently active.
         * Uses authoritative HEV/DATC ig3On status or direct measured voltage rise (>= 13.55V).
         * Excludes OEM telematics remote climate/control sessions (teleAccStatus == 2 while powerStatus != 0)
         * and does NOT use getIG3RlyMode() (VCU10_CF_Vcu_IG3RlyMd) directly for charging detection.
         */
        public boolean isChargingActive() {
            if (powerStatus != 0 && teleAccStatus == 2) {
                return false;
            }
            if (ig3On) return true;
            if (!ignOn && !accOn && isDirectVoltageMeasured() && voltageV >= 13.55f) return true;
            return false;
        }
    }

    public static final class VoltageSample {
        public long epochMs;
        public long elapsedParkingSec;
        public int powerStatus;
        public boolean accOn;
        public boolean ignOn;
        public boolean ig3On;
        public int ig3RlyMode;
        public float voltageV;
        public String voltageSource;
        public int socPct; // -1 if unknown
        public int batSocStatusFlag;
        public int batEvtAlarm;
        public int battMngAlert;
        public int autoCutPreWrng;
        public int activeChargeIdx; // 0 if not charging, 1..N if inside a charging event

        public VoltageSample() {
        }

        public VoltageSample(
                long epochMs,
                long elapsedParkingSec,
                int powerStatus,
                boolean accOn,
                boolean ignOn,
                boolean ig3On,
                int ig3RlyMode,
                float voltageV,
                String voltageSource,
                int socPct,
                int batSocStatusFlag,
                int batEvtAlarm,
                int battMngAlert,
                int autoCutPreWrng,
                int activeChargeIdx) {
            this.epochMs = epochMs;
            this.elapsedParkingSec = elapsedParkingSec;
            this.powerStatus = powerStatus;
            this.accOn = accOn;
            this.ignOn = ignOn;
            this.ig3On = ig3On;
            this.ig3RlyMode = ig3RlyMode;
            this.voltageV = voltageV;
            this.voltageSource = (voltageSource != null) ? voltageSource : "SOC_EST";
            this.socPct = socPct;
            this.batSocStatusFlag = batSocStatusFlag;
            this.batEvtAlarm = batEvtAlarm;
            this.battMngAlert = battMngAlert;
            this.autoCutPreWrng = autoCutPreWrng;
            this.activeChargeIdx = activeChargeIdx;
        }
    }

    public static final class ChargeEvent {
        public int chargeIndex; // 1-based (#1, #2, ...)
        public long startEpochMs;
        public long endEpochMs; // 0 if currently ongoing
        public long startElapsedSec;
        public long endElapsedSec;
        public String triggerReason;
        public float startVoltageV;
        public float peakVoltageV;
        public float endVoltageV;
        public int startSocPct;
        public int endSocPct;

        public ChargeEvent() {
        }

        public long getDurationSec(long currentElapsedSec) {
            if (endElapsedSec >= startElapsedSec && endEpochMs > 0L) {
                return Math.max(1L, endElapsedSec - startElapsedSec);
            }
            if (currentElapsedSec >= startElapsedSec) {
                return Math.max(1L, currentElapsedSec - startElapsedSec);
            }
            return 1L;
        }

        public boolean isOngoing() {
            return endEpochMs <= 0L;
        }
    }

    public static final class ParkingSession {
        public String sessionId;
        public String vehicleInfo = "Gen5W";
        public long driveEndEpochMs;
        public long lastUpdateEpochMs;
        public long driveEndRealtimeMs;
        public long lastUpdateRealtimeMs;
        public long accumulatedElapsedSec;
        public int targetParkingHours = TARGET_HOURS_ALWAYS_ON; // 0 = 상시(무제한), 1..72 = N시간
        public int autoChargeMode = MODE_PASSIVE_OBSERVE;
        public int status = SESSION_WAITING_DRIVE_END;
        public boolean pwrKeepActive = false;

        public float startVoltageV = 0.0f;
        public float latestVoltageV = 0.0f;
        public float minVoltageV = 99.0f;
        public float maxVoltageV = 0.0f;
        public int startSocPct = -1;
        public int latestSocPct = -1;
        public int minSocPct = 101;
        public int maxSocPct = -1;

        public final List<VoltageSample> samples = new ArrayList<VoltageSample>();
        public final List<ChargeEvent> chargeEvents = new ArrayList<ChargeEvent>();

        public ParkingSession() {
        }

        public ParkingSession(String sessionId, long driveEndEpochMs, int targetParkingHours, int autoChargeMode, String vehicleInfo) {
            this.sessionId = sessionId;
            this.driveEndEpochMs = driveEndEpochMs;
            this.lastUpdateEpochMs = driveEndEpochMs;
            long rt = elapsedRealtimeMs();
            this.driveEndRealtimeMs = rt;
            this.lastUpdateRealtimeMs = rt;
            this.accumulatedElapsedSec = 0L;
            this.targetParkingHours = normalizeTargetHours(targetParkingHours);
            this.autoChargeMode = autoChargeMode;
            this.vehicleInfo = (vehicleInfo != null) ? vehicleInfo : "Gen5W";
        }

        public static int normalizeTargetHours(int hours) {
            if (hours <= 0) return TARGET_HOURS_ALWAYS_ON;
            return Math.min(72, hours);
        }

        public synchronized boolean isAlwaysOnTarget() {
            return targetParkingHours <= 0;
        }

        public synchronized long getElapsedParkingSec() {
            if (driveEndRealtimeMs > 0L && lastUpdateRealtimeMs >= driveEndRealtimeMs) {
                long monoSec = (lastUpdateRealtimeMs - driveEndRealtimeMs) / 1000L;
                if (monoSec >= accumulatedElapsedSec) {
                    return monoSec;
                }
            }
            if (accumulatedElapsedSec > 0L) {
                return accumulatedElapsedSec;
            }
            if (driveEndEpochMs > 0L && lastUpdateEpochMs >= driveEndEpochMs) {
                return (lastUpdateEpochMs - driveEndEpochMs) / 1000L;
            }
            return 0L;
        }

        public synchronized long getTargetParkingSec() {
            if (targetParkingHours <= 0) {
                return 0L; // 0 means indefinite (상시 유지)
            }
            return (long) targetParkingHours * 3600L;
        }

        public synchronized int getProgressPercent() {
            long target = getTargetParkingSec();
            if (target <= 0L) return 100; // Always-on mode
            long pct = (getElapsedParkingSec() * 100L) / target;
            if (pct < 0L) return 0;
            if (pct > 100L) return 100;
            return (int) pct;
        }

        public synchronized int getChargeCount() {
            return chargeEvents.size();
        }

        public synchronized long getTotalChargeDurationSec() {
            long sum = 0L;
            long curElapsed = getElapsedParkingSec();
            for (int i = 0; i < chargeEvents.size(); i++) {
                sum += chargeEvents.get(i).getDurationSec(curElapsed);
            }
            return sum;
        }

        public synchronized ChargeEvent getOngoingChargeEvent() {
            if (chargeEvents.isEmpty()) return null;
            ChargeEvent last = chargeEvents.get(chargeEvents.size() - 1);
            return last.isOngoing() ? last : null;
        }

        public synchronized void recordSnapshot(LiveTelemetrySnapshot snap, String chargeTriggerHint) {
            long now = (snap.timestampMs > 0L) ? snap.timestampMs : System.currentTimeMillis();
            long rtNow = (snap.elapsedRealtimeMs > 0L) ? snap.elapsedRealtimeMs : elapsedRealtimeMs();

            if (driveEndEpochMs <= 0L) {
                driveEndEpochMs = now;
            }
            if (driveEndRealtimeMs <= 0L) {
                driveEndRealtimeMs = rtNow;
            }
            lastUpdateEpochMs = now;
            lastUpdateRealtimeMs = rtNow;

            long elapsedSec;
            if (rtNow >= driveEndRealtimeMs && driveEndRealtimeMs > 0L) {
                elapsedSec = (rtNow - driveEndRealtimeMs) / 1000L;
            } else {
                elapsedSec = Math.max(0L, (now - driveEndEpochMs) / 1000L);
            }
            accumulatedElapsedSec = Math.max(accumulatedElapsedSec, elapsedSec);

            float prevV = latestVoltageV > 0.0f ? latestVoltageV : snap.voltageV;
            boolean chargingNow = snap.isChargingActive();

            ChargeEvent ongoing = getOngoingChargeEvent();
            int activeChargeIdx = 0;

            if (chargingNow) {
                if (ongoing == null) {
                    ChargeEvent ev = new ChargeEvent();
                    ev.chargeIndex = chargeEvents.size() + 1;
                    ev.startEpochMs = now;
                    ev.endEpochMs = 0L;
                    ev.startElapsedSec = elapsedSec;
                    ev.endElapsedSec = elapsedSec;
                    if (chargeTriggerHint != null && !chargeTriggerHint.isEmpty()) {
                        ev.triggerReason = chargeTriggerHint;
                    } else if (snap.ig3On) {
                        ev.triggerReason = "IG3_AUX_SAVER";
                    } else {
                        ev.triggerReason = "MEASURED_VOLTAGE_RISE";
                    }
                    ev.startVoltageV = prevV;
                    ev.peakVoltageV = snap.voltageV;
                    ev.endVoltageV = snap.voltageV;
                    ev.startSocPct = (latestSocPct >= 0) ? latestSocPct : snap.socPct;
                    ev.endSocPct = snap.socPct;
                    chargeEvents.add(ev);
                    ongoing = ev;
                } else {
                    ongoing.endElapsedSec = elapsedSec;
                    if (snap.voltageV > ongoing.peakVoltageV) {
                        ongoing.peakVoltageV = snap.voltageV;
                    }
                    ongoing.endVoltageV = snap.voltageV;
                    if (snap.socPct >= 0) {
                        ongoing.endSocPct = snap.socPct;
                    }
                }
                activeChargeIdx = ongoing.chargeIndex;
            } else if (ongoing != null) {
                ongoing.endEpochMs = now;
                ongoing.endElapsedSec = elapsedSec;
                ongoing.endVoltageV = snap.voltageV;
                if (snap.socPct >= 0) {
                    ongoing.endSocPct = snap.socPct;
                }
            }

            if (samples.isEmpty()) {
                startVoltageV = snap.voltageV;
                startSocPct = snap.socPct;
            }
            if (startSocPct < 0 && snap.socPct >= 0) {
                startSocPct = snap.socPct;
            }
            latestVoltageV = snap.voltageV;
            if (snap.socPct >= 0) {
                latestSocPct = snap.socPct;
                if (snap.socPct < minSocPct) {
                    minSocPct = snap.socPct;
                }
                if (snap.socPct > maxSocPct) {
                    maxSocPct = snap.socPct;
                }
            }
            if (snap.voltageV > 1.0f && snap.voltageV < minVoltageV) {
                minVoltageV = snap.voltageV;
            }
            if (snap.voltageV > maxVoltageV) {
                maxVoltageV = snap.voltageV;
            }

            VoltageSample sample = new VoltageSample(
                    now,
                    elapsedSec,
                    snap.powerStatus,
                    snap.accOn,
                    snap.ignOn,
                    snap.ig3On,
                    snap.ig3RlyMode,
                    snap.voltageV,
                    snap.voltageSource,
                    snap.socPct,
                    snap.batSocStatusFlag,
                    snap.batEvtAlarm,
                    snap.battMngAlert,
                    snap.autoCutPreWrng,
                    activeChargeIdx);
            samples.add(sample);

            // Cap in-memory sample list to 8,640 samples
            if (samples.size() > 8640) {
                List<VoltageSample> compacted = new ArrayList<VoltageSample>(5000);
                int half = samples.size() / 2;
                for (int i = 0; i < half; i += 2) {
                    compacted.add(samples.get(i));
                }
                for (int i = half; i < samples.size(); i++) {
                    compacted.add(samples.get(i));
                }
                samples.clear();
                samples.addAll(compacted);
            }
        }

        public synchronized void closeOngoingChargeIfAny(long nowMs) {
            ChargeEvent ongoing = getOngoingChargeEvent();
            if (ongoing != null) {
                ongoing.endEpochMs = (nowMs > 0L) ? nowMs : System.currentTimeMillis();
                ongoing.endElapsedSec = getElapsedParkingSec();
                ongoing.endVoltageV = latestVoltageV;
                if (latestSocPct >= 0) {
                    ongoing.endSocPct = latestSocPct;
                }
            }
        }
    }

    public static final class ExportResult {
        public final boolean success;
        public final String message;
        public final String exportDirPath;
        public final int filesWritten;

        public ExportResult(boolean success, String message, String exportDirPath, int filesWritten) {
            this.success = success;
            this.message = message;
            this.exportDirPath = exportDirPath;
            this.filesWritten = filesWritten;
        }
    }

    // ========================================================================
    // VOLTAGE ESTIMATION & CALIBRATION HELPER
    // ========================================================================

    /**
     * Computes 12V auxiliary battery voltage (V).
     * - If direct measured voltage (SYSFS_ADC / SYS_PROP / CAN_VB) is valid (8.0V ~ 16.5V), returns it directly.
     * - If no direct voltage sensor is present, derives an estimated voltage from real SOC (%) and IG3 status.
     * - If both direct voltage and SOC are unknown (socPct < 0) and IG3 is off, returns 0.0f (UNKNOWN).
     */
    public static float synthesize12vVoltage(float directVolts, int socPct, boolean ig3On, int ig3RlyMode) {
        if (directVolts >= 8.0f && directVolts <= 16.5f) {
            return Math.round(directVolts * 100.0f) / 100.0f;
        }
        if (ig3On) {
            int refSoc = (socPct > 0 && socPct <= 100) ? socPct : 80;
            float boost = 14.32f + Math.min(0.15f, (refSoc * 0.0012f));
            return Math.round(boost * 100.0f) / 100.0f;
        }
        if (socPct <= 0 || socPct > 100) {
            return 0.0f;
        }
        float baseV = 11.50f + (socPct * 0.0132f);
        return Math.round(baseV * 100.0f) / 100.0f;
    }

    // ========================================================================
    // LOCAL SESSION PERSISTENCE (.rec file in /data/local/tmp/hkg_auxmon/sessions)
    // ========================================================================

    public static void saveSessionToDisk(File sessionDir, ParkingSession session) throws Exception {
        if (session == null || session.sessionId == null) return;
        if (!sessionDir.exists()) {
            sessionDir.mkdirs();
        }
        File outFile = new File(sessionDir, "session_" + session.sessionId + ".rec");
        String text = serializeSessionRecord(session);
        writeBytesAtomic(outFile, text.getBytes(UTF8));
    }

    public static String serializeSessionRecord(ParkingSession s) {
        synchronized (s) {
            StringBuilder sb = new StringBuilder(8192);
            sb.append("HKG_AUXMON_V1\n");
            sb.append("META\t")
              .append(safeTab(s.sessionId)).append('\t')
              .append(s.driveEndEpochMs).append('\t')
              .append(s.lastUpdateEpochMs).append('\t')
              .append(s.targetParkingHours).append('\t')
              .append(s.autoChargeMode).append('\t')
              .append(s.status).append('\t')
              .append(s.pwrKeepActive ? 1 : 0).append('\t')
              .append(formatFloat2(s.startVoltageV)).append('\t')
              .append(formatFloat2(s.latestVoltageV)).append('\t')
              .append(formatFloat2(s.minVoltageV > 50f ? 0f : s.minVoltageV)).append('\t')
              .append(formatFloat2(s.maxVoltageV)).append('\t')
              .append(s.startSocPct).append('\t')
              .append(s.latestSocPct).append('\t')
              .append(s.minSocPct > 100 ? -1 : s.minSocPct).append('\t')
              .append(s.maxSocPct).append('\t')
              .append(safeTab(s.vehicleInfo)).append('\t')
              .append(s.getElapsedParkingSec()).append('\n');

            for (int i = 0; i < s.chargeEvents.size(); i++) {
                ChargeEvent ce = s.chargeEvents.get(i);
                sb.append("CHG\t")
                  .append(ce.chargeIndex).append('\t')
                  .append(ce.startEpochMs).append('\t')
                  .append(ce.endEpochMs).append('\t')
                  .append(ce.startElapsedSec).append('\t')
                  .append(ce.endElapsedSec).append('\t')
                  .append(safeTab(ce.triggerReason)).append('\t')
                  .append(formatFloat2(ce.startVoltageV)).append('\t')
                  .append(formatFloat2(ce.peakVoltageV)).append('\t')
                  .append(formatFloat2(ce.endVoltageV)).append('\t')
                  .append(ce.startSocPct).append('\t')
                  .append(ce.endSocPct).append('\n');
            }

            for (int i = 0; i < s.samples.size(); i++) {
                VoltageSample vs = s.samples.get(i);
                sb.append("SMP\t")
                  .append(vs.epochMs).append('\t')
                  .append(vs.elapsedParkingSec).append('\t')
                  .append(vs.powerStatus).append('\t')
                  .append(vs.accOn ? 1 : 0).append('\t')
                  .append(vs.ignOn ? 1 : 0).append('\t')
                  .append(vs.ig3On ? 1 : 0).append('\t')
                  .append(vs.ig3RlyMode).append('\t')
                  .append(formatFloat2(vs.voltageV)).append('\t')
                  .append(safeTab(vs.voltageSource)).append('\t')
                  .append(vs.socPct).append('\t')
                  .append(vs.batSocStatusFlag).append('\t')
                  .append(vs.batEvtAlarm).append('\t')
                  .append(vs.battMngAlert).append('\t')
                  .append(vs.autoCutPreWrng).append('\t')
                  .append(vs.activeChargeIdx).append('\n');
            }
            return sb.toString();
        }
    }

    public static ParkingSession deserializeSessionRecord(String text) {
        if (text == null || !text.startsWith("HKG_AUXMON_V1")) {
            return null;
        }
        ParkingSession s = new ParkingSession();
        s.driveEndRealtimeMs = 0L;
        s.lastUpdateRealtimeMs = 0L;
        String[] lines = text.split("\n");
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            String[] p = line.split("\t", -1);
            try {
                if ("META".equals(p[0]) && p.length >= 17) {
                    s.sessionId = p[1];
                    s.driveEndEpochMs = Long.parseLong(p[2]);
                    s.lastUpdateEpochMs = Long.parseLong(p[3]);
                    s.targetParkingHours = ParkingSession.normalizeTargetHours(Integer.parseInt(p[4]));
                    s.autoChargeMode = Integer.parseInt(p[5]);
                    s.status = Integer.parseInt(p[6]);
                    s.pwrKeepActive = "1".equals(p[7]);
                    s.startVoltageV = Float.parseFloat(p[8]);
                    s.latestVoltageV = Float.parseFloat(p[9]);
                    s.minVoltageV = Float.parseFloat(p[10]);
                    if (s.minVoltageV <= 0.0f) s.minVoltageV = 99.0f;
                    s.maxVoltageV = Float.parseFloat(p[11]);
                    s.startSocPct = Integer.parseInt(p[12]);
                    s.latestSocPct = Integer.parseInt(p[13]);
                    s.minSocPct = Integer.parseInt(p[14]);
                    if (s.minSocPct < 0) s.minSocPct = 101;
                    s.maxSocPct = Integer.parseInt(p[15]);
                    s.vehicleInfo = p[16];
                    if (p.length >= 18) {
                        s.accumulatedElapsedSec = Long.parseLong(p[17]);
                    }
                } else if ("CHG".equals(p[0]) && p.length >= 12) {
                    ChargeEvent ce = new ChargeEvent();
                    ce.chargeIndex = Integer.parseInt(p[1]);
                    ce.startEpochMs = Long.parseLong(p[2]);
                    ce.endEpochMs = Long.parseLong(p[3]);
                    ce.startElapsedSec = Long.parseLong(p[4]);
                    ce.endElapsedSec = Long.parseLong(p[5]);
                    ce.triggerReason = p[6];
                    ce.startVoltageV = Float.parseFloat(p[7]);
                    ce.peakVoltageV = Float.parseFloat(p[8]);
                    ce.endVoltageV = Float.parseFloat(p[9]);
                    ce.startSocPct = Integer.parseInt(p[10]);
                    ce.endSocPct = Integer.parseInt(p[11]);
                    s.chargeEvents.add(ce);
                } else if ("SMP".equals(p[0]) && p.length >= 16) {
                    long elSec = Long.parseLong(p[2]);
                    if (elSec > s.accumulatedElapsedSec) {
                        s.accumulatedElapsedSec = elSec;
                    }
                    VoltageSample vs = new VoltageSample(
                            Long.parseLong(p[1]),
                            elSec,
                            Integer.parseInt(p[3]),
                            "1".equals(p[4]),
                            "1".equals(p[5]),
                            "1".equals(p[6]),
                            Integer.parseInt(p[7]),
                            Float.parseFloat(p[8]),
                            p[9],
                            Integer.parseInt(p[10]),
                            Integer.parseInt(p[11]),
                            Integer.parseInt(p[12]),
                            Integer.parseInt(p[13]),
                            Integer.parseInt(p[14]),
                            Integer.parseInt(p[15]));
                    s.samples.add(vs);
                }
            } catch (Throwable ignored) {
            }
        }
        return (s.sessionId != null && !s.sessionId.isEmpty()) ? s : null;
    }

    public static List<ParkingSession> loadSavedSessions(File sessionDir) {
        List<ParkingSession> list = new ArrayList<ParkingSession>();
        if (sessionDir == null || !sessionDir.isDirectory()) {
            return list;
        }
        File[] files = sessionDir.listFiles();
        if (files == null) return list;
        for (int i = 0; i < files.length; i++) {
            File f = files[i];
            if (f.isFile() && f.getName().startsWith("session_") && f.getName().endsWith(".rec")) {
                try {
                    String txt = readFileText(f);
                    ParkingSession s = deserializeSessionRecord(txt);
                    if (s != null) {
                        // Clean up orphan MONITORING sessions left over from reboot/kill
                        if (s.status == SESSION_MONITORING_PARKING || s.pwrKeepActive) {
                            s.closeOngoingChargeIfAny(s.lastUpdateEpochMs);
                            s.status = SESSION_INTERRUPTED_REBOOT;
                            s.pwrKeepActive = false;
                            try {
                                saveSessionToDisk(sessionDir, s);
                            } catch (Throwable ignored) {
                            }
                        }
                        list.add(s);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        Collections.sort(list, new Comparator<ParkingSession>() {
            @Override
            public int compare(ParkingSession a, ParkingSession b) {
                if (a.driveEndEpochMs == b.driveEndEpochMs) return 0;
                return (a.driveEndEpochMs < b.driveEndEpochMs) ? -1 : 1;
            }
        });
        return list;
    }

    // ========================================================================
    // USB STORAGE DISCOVERY & DATA EXPORT (CSV + JSON + KOREAN REPORT)
    // ========================================================================

    public static File findWritableUsbMount() {
        String[] candidates = new String[]{
            "/mnt/media_rw/usb0",
            "/mnt/media_rw/usb1",
            "/mnt/media_rw/usb2",
            "/storage/usb0",
            "/storage/usb1",
            "/storage/usb2",
            "/mnt/usb0",
            "/mnt/udisk"
        };
        String mountsText = "";
        try {
            File procMounts = new File("/proc/mounts");
            if (procMounts.isFile()) {
                mountsText = readFileText(procMounts);
            }
        } catch (Throwable ignored) {
        }
        for (int i = 0; i < candidates.length; i++) {
            String path = candidates[i];
            if (!mountsText.isEmpty() && !isMountPointInProcMounts(mountsText, path)) {
                continue;
            }
            File dir = new File(path);
            if (dir.isDirectory() && dir.canWrite()) {
                return dir;
            }
        }
        return null;
    }

    public static boolean isMountPointInProcMounts(String mountsText, String mountPoint) {
        if (mountsText == null || mountPoint == null) return false;
        String[] lines = mountsText.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String[] parts = lines[i].trim().split("\\s+");
            if (parts.length >= 2 && mountPoint.equals(parts[1])) {
                return true;
            }
        }
        return false;
    }

    public static ExportResult exportSessionToUsb(ParkingSession currentSession, List<ParkingSession> allSessions, File usbRootOverride) {
        try {
            List<ParkingSession> toExport = new ArrayList<ParkingSession>();
            if (allSessions != null) {
                for (int i = 0; i < allSessions.size(); i++) {
                    ParkingSession s = allSessions.get(i);
                    if (s != null && !toExport.contains(s)) {
                        toExport.add(s);
                    }
                }
            }
            if (currentSession != null && !toExport.contains(currentSession)) {
                toExport.add(currentSession);
            }
            if (toExport.isEmpty()) {
                return new ExportResult(
                        false,
                        "내보낼 주차 기록 세션이 없습니다. 주차 기록이 시작된 후 내보내거나 [시뮬레이션 기록 생성]을 먼저 실행하세요.",
                        "-",
                        0);
            }

            File usbRoot = (usbRootOverride != null) ? usbRootOverride : findWritableUsbMount();
            if (usbRoot == null || !usbRoot.exists() || !usbRoot.canWrite()) {
                return new ExportResult(
                        false,
                        "쓰기 가능한 USB 메모리를 찾을 수 없습니다. FAT32/exFAT USB를 연결한 뒤 다시 눌러주세요.",
                        "-",
                        0);
            }

            File outDir = new File(usbRoot, "hkg_aux_battery_logs");
            if (!outDir.exists() && !outDir.mkdirs()) {
                return new ExportResult(
                        false,
                        "USB에 저장 폴더를 생성할 수 없습니다: " + outDir.getAbsolutePath(),
                        outDir.getAbsolutePath(),
                        0);
            }

            int filesWritten = 0;
            for (int i = 0; i < toExport.size(); i++) {
                ParkingSession s = toExport.get(i);
                String sid = (s.sessionId != null && !s.sessionId.isEmpty()) ? s.sessionId : "latest";

                // 1. Voltage Time-Series CSV
                File samplesCsv = new File(outDir, "parking_voltage_samples_" + sid + ".csv");
                writeBytesDirect(samplesCsv, buildSamplesCsv(s).getBytes(UTF8));
                filesWritten++;

                // 2. Charging Events CSV
                File chargesCsv = new File(outDir, "parking_charge_events_" + sid + ".csv");
                writeBytesDirect(chargesCsv, buildChargeEventsCsv(s).getBytes(UTF8));
                filesWritten++;

                // 3. Full Session JSON
                File jsonFile = new File(outDir, "parking_session_" + sid + ".json");
                writeBytesDirect(jsonFile, buildSessionJson(s).getBytes(UTF8));
                filesWritten++;

                // 4. Korean Summary Report TXT
                File reportTxt = new File(outDir, "parking_report_" + sid + ".txt");
                writeBytesDirect(reportTxt, buildKoreanSummaryReport(s).getBytes(UTF8));
                filesWritten++;
            }

            try {
                Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/sync"});
                p.waitFor();
            } catch (Throwable ignored) {
            }

            String primaryId = (currentSession != null && currentSession.sessionId != null) ? currentSession.sessionId : "all";
            return new ExportResult(
                    true,
                    "✓ USB 내보내기 완료! (" + outDir.getAbsolutePath() + " / 세션 " + primaryId + ", 총 " + filesWritten + "개 파일)",
                    outDir.getAbsolutePath(),
                    filesWritten);
        } catch (Throwable t) {
            return new ExportResult(
                    false,
                    "USB 데이터 내보내기 중 오류 발생: " + t.getMessage(),
                    "-",
                    0);
        }
    }

    public static String buildSamplesCsv(ParkingSession s) {
        synchronized (s) {
            StringBuilder sb = new StringBuilder(Math.max(1024, s.samples.size() * 96));
            sb.append('\uFEFF');
            sb.append("timestamp_iso,epoch_ms,elapsed_parking_sec,elapsed_hhmmss,power_status,acc_on,ign_on,ig3_on,ig3_rly_mode,voltage_v,voltage_source,soc_pct,bat_soc_status_flag,bat_evt_alarm,batt_mng_alert,auto_cut_pre_wrng,active_charge_idx\n");
            for (int i = 0; i < s.samples.size(); i++) {
                VoltageSample v = s.samples.get(i);
                sb.append(formatTimestampIso(v.epochMs)).append(',')
                  .append(v.epochMs).append(',')
                  .append(v.elapsedParkingSec).append(',')
                  .append(formatDurationHms(v.elapsedParkingSec)).append(',')
                  .append(powerStatusName(v.powerStatus)).append(',')
                  .append(v.accOn ? 1 : 0).append(',')
                  .append(v.ignOn ? 1 : 0).append(',')
                  .append(v.ig3On ? 1 : 0).append(',')
                  .append(v.ig3RlyMode).append(',')
                  .append(formatFloat2(v.voltageV)).append(',')
                  .append(v.voltageSource).append(',')
                  .append(v.socPct).append(',')
                  .append(v.batSocStatusFlag).append(',')
                  .append(v.batEvtAlarm).append(',')
                  .append(v.battMngAlert).append(',')
                  .append(v.autoCutPreWrng).append(',')
                  .append(v.activeChargeIdx).append('\n');
            }
            return sb.toString();
        }
    }

    public static String buildChargeEventsCsv(ParkingSession s) {
        synchronized (s) {
            StringBuilder sb = new StringBuilder(1024);
            sb.append('\uFEFF');
            sb.append("charge_index,start_time_iso,end_time_iso,start_elapsed_sec,end_elapsed_sec,duration_sec,duration_hhmmss,trigger_reason,start_voltage_v,peak_voltage_v,end_voltage_v,start_soc_pct,end_soc_pct\n");
            long curElapsed = s.getElapsedParkingSec();
            for (int i = 0; i < s.chargeEvents.size(); i++) {
                ChargeEvent ce = s.chargeEvents.get(i);
                long dur = ce.getDurationSec(curElapsed);
                sb.append(ce.chargeIndex).append(',')
                  .append(formatTimestampIso(ce.startEpochMs)).append(',')
                  .append(ce.endEpochMs > 0L ? formatTimestampIso(ce.endEpochMs) : "ONGOING").append(',')
                  .append(ce.startElapsedSec).append(',')
                  .append(ce.endElapsedSec).append(',')
                  .append(dur).append(',')
                  .append(formatDurationHms(dur)).append(',')
                  .append(ce.triggerReason).append(',')
                  .append(formatFloat2(ce.startVoltageV)).append(',')
                  .append(formatFloat2(ce.peakVoltageV)).append(',')
                  .append(formatFloat2(ce.endVoltageV)).append(',')
                  .append(ce.startSocPct).append(',')
                  .append(ce.endSocPct).append('\n');
            }
            return sb.toString();
        }
    }

    public static String buildSessionJson(ParkingSession s) {
        synchronized (s) {
            StringBuilder sb = new StringBuilder(4096);
            long curElapsed = s.getElapsedParkingSec();
            sb.append("{\n");
            sb.append("  \"session_id\": \"").append(escapeJson(s.sessionId)).append("\",\n");
            sb.append("  \"vehicle_info\": \"").append(escapeJson(s.vehicleInfo)).append("\",\n");
            sb.append("  \"drive_end_epoch_ms\": ").append(s.driveEndEpochMs).append(",\n");
            sb.append("  \"drive_end_time\": \"").append(formatTimestampIso(s.driveEndEpochMs)).append("\",\n");
            sb.append("  \"last_update_epoch_ms\": ").append(s.lastUpdateEpochMs).append(",\n");
            sb.append("  \"target_parking_hours\": ").append(s.targetParkingHours).append(",\n");
            sb.append("  \"target_parking_label\": \"").append(formatTargetHoursLabel(s.targetParkingHours)).append("\",\n");
            sb.append("  \"elapsed_parking_sec\": ").append(curElapsed).append(",\n");
            sb.append("  \"auto_charge_mode\": \"").append(autoChargeModeName(s.autoChargeMode)).append("\",\n");
            sb.append("  \"status\": \"").append(sessionStatusName(s.status)).append("\",\n");
            sb.append("  \"charge_count\": ").append(s.getChargeCount()).append(",\n");
            sb.append("  \"total_charge_duration_sec\": ").append(s.getTotalChargeDurationSec()).append(",\n");
            sb.append("  \"start_voltage_v\": ").append(formatFloat2(s.startVoltageV)).append(",\n");
            sb.append("  \"latest_voltage_v\": ").append(formatFloat2(s.latestVoltageV)).append(",\n");
            sb.append("  \"min_voltage_v\": ").append(formatFloat2(s.minVoltageV > 50f ? 0f : s.minVoltageV)).append(",\n");
            sb.append("  \"max_voltage_v\": ").append(formatFloat2(s.maxVoltageV)).append(",\n");
            sb.append("  \"start_soc_pct\": ").append(s.startSocPct).append(",\n");
            sb.append("  \"latest_soc_pct\": ").append(s.latestSocPct).append(",\n");
            sb.append("  \"charge_events\": [\n");
            for (int i = 0; i < s.chargeEvents.size(); i++) {
                ChargeEvent ce = s.chargeEvents.get(i);
                sb.append("    {\n");
                sb.append("      \"charge_index\": ").append(ce.chargeIndex).append(",\n");
                sb.append("      \"start_time\": \"").append(formatTimestampIso(ce.startEpochMs)).append("\",\n");
                sb.append("      \"end_time\": \"").append(ce.endEpochMs > 0L ? formatTimestampIso(ce.endEpochMs) : "ONGOING").append("\",\n");
                sb.append("      \"duration_sec\": ").append(ce.getDurationSec(curElapsed)).append(",\n");
                sb.append("      \"trigger_reason\": \"").append(escapeJson(ce.triggerReason)).append("\",\n");
                sb.append("      \"start_voltage_v\": ").append(formatFloat2(ce.startVoltageV)).append(",\n");
                sb.append("      \"peak_voltage_v\": ").append(formatFloat2(ce.peakVoltageV)).append(",\n");
                sb.append("      \"end_voltage_v\": ").append(formatFloat2(ce.endVoltageV)).append(",\n");
                sb.append("      \"start_soc_pct\": ").append(ce.startSocPct).append(",\n");
                sb.append("      \"end_soc_pct\": ").append(ce.endSocPct).append("\n");
                sb.append("    }").append(i + 1 < s.chargeEvents.size() ? ",\n" : "\n");
            }
            sb.append("  ],\n");
            sb.append("  \"sample_count\": ").append(s.samples.size()).append("\n");
            sb.append("}\n");
            return sb.toString();
        }
    }

    public static String buildKoreanSummaryReport(ParkingSession s) {
        synchronized (s) {
            StringBuilder sb = new StringBuilder(2048);
            long curElapsed = s.getElapsedParkingSec();
            sb.append("========================================================================\n");
            sb.append("  HKG 12V 보조배터리 주차 절전 유지 & 자동충전(Aux. Battery Saver+) 리포트\n");
            sb.append("========================================================================\n");
            sb.append("세션 ID          : ").append(s.sessionId).append("\n");
            sb.append("차량 정보        : ").append(s.vehicleInfo).append("\n");
            sb.append("주행 종료 시각   : ").append(formatTimestampIso(s.driveEndEpochMs)).append("\n");
            sb.append("마지막 기록 시각 : ").append(formatTimestampIso(s.lastUpdateEpochMs)).append("\n");
            sb.append("목표 유지 시간   : ").append(formatTargetHoursLabel(s.targetParkingHours)).append("\n");
            sb.append("실제 경과 시간   : ").append(formatDurationHms(curElapsed)).append("\n");
            sb.append("동작 모드        : ").append(autoChargeModeName(s.autoChargeMode)).append("\n");
            sb.append("세션 상태        : ").append(sessionStatusName(s.status)).append("\n\n");

            sb.append("------------------------------------------------------------------------\n");
            sb.append("[1] 12V 보조배터리 전압(추정/실측) 및 충전율(SOC) 요약\n");
            sb.append("------------------------------------------------------------------------\n");
            sb.append("  • 주차 시작 전압 / SOC : ").append(formatFloat2(s.startVoltageV)).append(" V  (").append(formatSocPct(s.startSocPct)).append(")\n");
            sb.append("  • 현재(최종) 전압 / SOC: ").append(formatFloat2(s.latestVoltageV)).append(" V  (").append(formatSocPct(s.latestSocPct)).append(")\n");
            sb.append("  • 최저 / 최고 전압     : ").append(formatFloat2(s.minVoltageV > 50f ? 0f : s.minVoltageV)).append(" V  ~  ").append(formatFloat2(s.maxVoltageV)).append(" V\n");
            sb.append("  • 최저 / 최고 SOC      : ").append(s.minSocPct > 100 ? "미확인" : (s.minSocPct + "%")).append("  ~  ").append(formatSocPct(s.maxSocPct)).append("\n");
            sb.append("  • 총 전압 샘플 수      : ").append(s.samples.size()).append(" 건\n\n");

            sb.append("------------------------------------------------------------------------\n");
            sb.append("[2] 주차 중 자동충전(IG3 / Aux. Battery Saver+) 실행 이력 (총 ").append(s.getChargeCount()).append("회)\n");
            sb.append("------------------------------------------------------------------------\n");
            if (s.chargeEvents.isEmpty()) {
                sb.append("  (기록된 자동충전 이벤트가 없습니다.)\n\n");
            } else {
                sb.append("  • 총 충전 횟수         : ").append(s.getChargeCount()).append(" 회\n");
                sb.append("  • 누적 충전 소요 시간  : ").append(formatDurationHms(s.getTotalChargeDurationSec())).append("\n\n");
                for (int i = 0; i < s.chargeEvents.size(); i++) {
                    ChargeEvent ce = s.chargeEvents.get(i);
                    long dur = ce.getDurationSec(curElapsed);
                    sb.append(String.format(Locale.US,
                            "  [#%d] 주차 +%s 경과 (%s ~ %s, 지속 %s)\n" +
                            "       전압: %sV (%s) -> 최고 %sV -> 종료 %sV (%s) | 사유: %s\n",
                            Integer.valueOf(ce.chargeIndex),
                            formatDurationHms(ce.startElapsedSec),
                            formatTimeOnly(ce.startEpochMs),
                            ce.endEpochMs > 0L ? formatTimeOnly(ce.endEpochMs) : "진행중",
                            formatDurationHms(dur),
                            formatFloat2(ce.startVoltageV),
                            formatSocPct(ce.startSocPct),
                            formatFloat2(ce.peakVoltageV),
                            formatFloat2(ce.endVoltageV),
                            formatSocPct(ce.endSocPct),
                            ce.triggerReason));
                }
                sb.append("\n");
            }

            sb.append("------------------------------------------------------------------------\n");
            sb.append("[3] 시간대별 전압·SOC 기록 요약 (최대 24개 구간 샘플링)\n");
            sb.append("------------------------------------------------------------------------\n");
            int total = s.samples.size();
            int step = Math.max(1, total / 24);
            for (int i = 0; i < total; i += step) {
                VoltageSample vs = s.samples.get(i);
                sb.append(String.format(Locale.US,
                        "  +%s (%s) | %s V (%s) | SOC %4s | IG3=%s (Rly=%d) | 충전=%s\n",
                        formatDurationHms(vs.elapsedParkingSec),
                        formatTimeOnly(vs.epochMs),
                        formatFloat2(vs.voltageV),
                        vs.voltageSource,
                        formatSocPct(vs.socPct),
                        vs.ig3On ? "ON " : "OFF",
                        Integer.valueOf(vs.ig3RlyMode),
                        vs.activeChargeIdx > 0 ? ("#" + vs.activeChargeIdx + " 충전중") : "-"));
            }
            if (total > 1 && (total - 1) % step != 0) {
                VoltageSample vs = s.samples.get(total - 1);
                sb.append(String.format(Locale.US,
                        "  +%s (%s) | %s V (%s) | SOC %4s | IG3=%s (Rly=%d) | 충전=%s\n",
                        formatDurationHms(vs.elapsedParkingSec),
                        formatTimeOnly(vs.epochMs),
                        formatFloat2(vs.voltageV),
                        vs.voltageSource,
                        formatSocPct(vs.socPct),
                        vs.ig3On ? "ON " : "OFF",
                        Integer.valueOf(vs.ig3RlyMode),
                        vs.activeChargeIdx > 0 ? ("#" + vs.activeChargeIdx + " 충전중") : "-"));
            }
            sb.append("========================================================================\n");
            return sb.toString();
        }
    }

    // ========================================================================
    // DEMO PARKING SESSION GENERATOR (FOR PANEL & USB EXPORT VERIFICATION)
    // ========================================================================

    public static ParkingSession createDemoParkingSession(int targetHours, String vehicleInfo) {
        int hours = (targetHours <= 0) ? 12 : Math.max(1, Math.min(48, targetHours));
        long totalSec = (long) hours * 3600L;
        long nowMs = System.currentTimeMillis();
        long startMs = nowMs - (totalSec * 1000L);
        long startRt = Math.max(1000L, elapsedRealtimeMs() - (totalSec * 1000L));
        String sid = "DEMO_" + formatSessionIdTime(startMs);

        ParkingSession s = new ParkingSession(sid, startMs, targetHours, MODE_PASSIVE_OBSERVE, vehicleInfo);
        s.driveEndRealtimeMs = startRt;
        s.lastUpdateRealtimeMs = startRt;
        s.status = SESSION_COMPLETED_N_HOURS;
        s.pwrKeepActive = false;

        long chg1Start = totalSec / 3L;
        long chg1End = chg1Start + 1200L; // 20 min charge
        long chg2Start = (totalSec * 3L) / 4L;
        long chg2End = chg2Start + 1200L; // 20 min charge

        int stepSec = Math.max(30, (int) (totalSec / 180L));
        float currentSoc = 84.0f;

        for (long sec = 0L; sec <= totalSec; sec += stepSec) {
            boolean inChg1 = (sec >= chg1Start && sec <= chg1End);
            boolean inChg2 = (sec >= chg2Start && sec <= chg2End);
            boolean charging = inChg1 || inChg2;

            if (charging) {
                currentSoc = Math.min(94.0f, currentSoc + (stepSec * 0.012f));
            } else {
                currentSoc = Math.max(68.0f, currentSoc - (stepSec * 0.0016f));
            }

            int socInt = Math.round(currentSoc);
            float volt = synthesize12vVoltage(0.0f, socInt, charging, charging ? 2 : 1);

            LiveTelemetrySnapshot snap = new LiveTelemetrySnapshot();
            snap.timestampMs = startMs + (sec * 1000L);
            snap.elapsedRealtimeMs = startRt + (sec * 1000L);
            snap.powerStatus = 6; // DELAYMODE
            snap.teleAccStatus = 1; // ACCOFF
            snap.accOn = false;
            snap.ignOn = false;
            snap.ig3On = charging;
            snap.ig3RlyMode = charging ? 2 : 1;
            snap.voltageV = volt;
            snap.voltageSource = "SOC_EST";
            snap.socPct = socInt;
            snap.batSocStatusFlag = 0;
            snap.batEvtAlarm = 0;
            snap.battMngAlert = 0;
            snap.autoCutPreWrng = 0;

            s.recordSnapshot(snap, charging ? "IG3_AUX_SAVER" : null);
        }
        s.closeOngoingChargeIfAny(nowMs);
        return s;
    }

    // ========================================================================
    // FORMATTING & FILE UTILITIES
    // ========================================================================

    public static String formatTargetHoursLabel(int targetHours) {
        if (targetHours <= 0) {
            return "상시(무제한)";
        }
        return targetHours + "시간";
    }

    public static String formatSocPct(int socPct) {
        if (socPct <= 0 || socPct > 100) {
            return "미확인(--)";
        }
        return socPct + "%";
    }

    public static String formatSessionIdTime(long epochMs) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
        return sdf.format(new Date(epochMs));
    }

    public static String formatTimestampIso(long epochMs) {
        if (epochMs <= 0L) return "-";
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        return sdf.format(new Date(epochMs));
    }

    public static String formatTimeOnly(long epochMs) {
        if (epochMs <= 0L) return "--:--:--";
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.US);
        return sdf.format(new Date(epochMs));
    }

    public static String formatDurationHms(long totalSec) {
        if (totalSec < 0L) return "--:--:--";
        long h = totalSec / 3600L;
        long m = (totalSec % 3600L) / 60L;
        long s = totalSec % 60L;
        return String.format(Locale.US, "%02d:%02d:%02d", Long.valueOf(h), Long.valueOf(m), Long.valueOf(s));
    }

    public static String formatFloat2(float val) {
        return String.format(Locale.US, "%.2f", Float.valueOf(val));
    }

    public static String powerStatusName(int st) {
        switch (st) {
            case 0: return "ACCON";
            case 1: return "LOGICON";
            case 2: return "ADM";
            case 5: return "SYSTEMREADY";
            case 6: return "DELAYMODE";
            case 7: return "PRESYSTEMOFF";
            case 255: return "OFF";
            default: return "STATE_" + st;
        }
    }

    public static String sessionStatusName(int status) {
        switch (status) {
            case SESSION_WAITING_DRIVE_END:
                return "주행 중 (시동 OFF 시 절전 유지 자동 시작)";
            case SESSION_MONITORING_PARKING:
                return "주차 절전 유지(PwrKeep) & 충전 감시 중";
            case SESSION_COMPLETED_N_HOURS:
                return "설정된 주차 감시 시간 완료 ✓";
            case SESSION_SAFETY_CUTOFF:
                return "방전 보호 안전 차단 (PwrKeep 해제)";
            case SESSION_ENDED_BY_DRIVE:
                return "재시동(주행 시작)으로 주차 감시 종료";
            case SESSION_INTERRUPTED_REBOOT:
                return "시스템 재부팅/종료로 이전 세션 마감";
            default:
                return "대기 중";
        }
    }

    public static String autoChargeModeName(int mode) {
        if (mode == MODE_ACTIVE_1H_IG3) {
            return "1시간 주기 IG3 강제충전 모드";
        }
        return "순정 Aux. Battery Saver+ 감시 모드";
    }

    private static String safeTab(String s) {
        if (s == null) return "";
        return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    public static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public static void writeBytesDirect(File f, byte[] data) throws Exception {
        FileOutputStream fos = new FileOutputStream(f);
        try {
            fos.write(data);
            fos.flush();
            try {
                fos.getFD().sync();
            } catch (Throwable ignored) {
            }
        } finally {
            fos.close();
        }
    }

    public static void writeBytesAtomic(File f, byte[] data) throws Exception {
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        writeBytesDirect(tmp, data);
        if (f.exists()) {
            f.delete();
        }
        if (!tmp.renameTo(f)) {
            writeBytesDirect(f, data);
            tmp.delete();
        }
    }

    public static String readFileText(File f) throws Exception {
        FileInputStream fis = new FileInputStream(f);
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) > 0) {
                baos.write(buf, 0, n);
            }
            return new String(baos.toByteArray(), UTF8);
        } finally {
            fis.close();
        }
    }

    public static String readFirstLine(File f) {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(new FileInputStream(f), UTF8));
            String line = br.readLine();
            return (line != null) ? line : "";
        } catch (Throwable t) {
            return "";
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
