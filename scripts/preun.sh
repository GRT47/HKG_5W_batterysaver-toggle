#!/system/bin/sh
export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
export LD_LIBRARY_PATH=/vendor/lib:/system/lib
export ANDROID_ROOT=/system
export ANDROID_DATA=/data

if [ -d "/data/nemesis/apps/hkg.auxmon" ]; then
    INSTALL_DIR="/data/nemesis/apps/hkg.auxmon"
else
    INSTALL_DIR="/data/nemesis/packages/hkg.auxmon"
fi
APP_CLASSPATH="$INSTALL_DIR/bin/auxmon.jar:/system/framework/com.hkmc.ext.hcan.jar"
JAVA_PID_FILE="/data/local/tmp/hkg_auxmon_java.pid"
STOP_FLAG="/data/local/tmp/hkg_auxmon_stopping"

# 1. Block wrapper from respawning Java and terminate wrapper first
echo "1" > "$STOP_FLAG" 2>/dev/null
/system/bin/busybox pkill -f "auxmon-daemon.sh" >/dev/null 2>&1
pkill -f "auxmon-daemon.sh" >/dev/null 2>&1

# 2. Ask running daemon to release PwrKeep(2) & exit cleanly, waiting up to 3s
echo "release" > /data/local/tmp/hkg_auxmon_cmd 2>/dev/null
if [ -f "$JAVA_PID_FILE" ]; then
    jpid="$(cat "$JAVA_PID_FILE" 2>/dev/null)"
    for _w in 1 2 3; do
        if [ -n "$jpid" ] && kill -0 "$jpid" 2>/dev/null; then
            sleep 1
        else
            break
        fi
    done
fi

# 3. Terminate any remaining daemon process using narrow pattern ("AuxMonMain daemon")
/system/bin/busybox pkill -f "com.hkg.auxmon.AuxMonMain daemon" >/dev/null 2>&1
pkill -f "com.hkg.auxmon.AuxMonMain daemon" >/dev/null 2>&1

# 4. Run release-keep one-shot (prepares Looper & verifies MicomPowerManager.requestPwrKeepStop(2))
#    Check exit code; retry once if the first attempt fails, and record via logcat.
if ! CLASSPATH="$APP_CLASSPATH" /system/bin/app_process /system/bin com.hkg.auxmon.AuxMonMain release-keep >>/data/local/tmp/hkg_auxmon.log 2>&1; then
    log -t HKGAuxMon "preun.sh: release-keep attempt 1 failed; retrying after 1s..." 2>/dev/null
    sleep 1
    if ! CLASSPATH="$APP_CLASSPATH" /system/bin/app_process /system/bin com.hkg.auxmon.AuxMonMain release-keep >>/data/local/tmp/hkg_auxmon.log 2>&1; then
        log -t HKGAuxMon "preun.sh: ERROR: release-keep failed after retry!" 2>/dev/null
    else
        log -t HKGAuxMon "preun.sh: release-keep succeeded on retry." 2>/dev/null
    fi
else
    log -t HKGAuxMon "preun.sh: release-keep succeeded." 2>/dev/null
fi

rm -rf /data/local/tmp/hkg_auxmon_svc.lock 2>/dev/null
rm -f /data/local/tmp/hkg_auxmon_svc.pid \
      /data/local/tmp/hkg_auxmon_java.pid \
      /data/local/tmp/hkg_auxmon_cmd \
      /data/local/tmp/hkg_auxmon_state.json \
      /data/local/tmp/hkg_auxmon_stopping \
      /data/local/tmp/hkg_auxmon/ig3_requested.flag \
      /data/local/tmp/hkg_auxmon/power_latch.txt 2>/dev/null

exit 0
