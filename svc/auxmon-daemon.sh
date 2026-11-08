#!/system/bin/sh
export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
export LD_LIBRARY_PATH=/vendor/lib:/system/lib
export ANDROID_ROOT=/system
export ANDROID_DATA=/data
# Do NOT override BOOTCLASSPATH so system .odex dependency tables remain valid.

if [ -d "/data/nemesis/apps/hkg.auxmon" ]; then
    INSTALL_DIR="/data/nemesis/apps/hkg.auxmon"
else
    INSTALL_DIR="/data/nemesis/packages/hkg.auxmon"
fi
mkdir -p /data/dalvik-cache /data/local/tmp /data/local/tmp/hkg_auxmon/sessions 2>/dev/null

SVC_PID_FILE="/data/local/tmp/hkg_auxmon_svc.pid"
SVC_LOCK_DIR="/data/local/tmp/hkg_auxmon_svc.lock"
JAVA_PID_FILE="/data/local/tmp/hkg_auxmon_java.pid"
STOP_FLAG="/data/local/tmp/hkg_auxmon_stopping"
LOG_FILE="/data/local/tmp/hkg_auxmon.log"
APP_CLASSPATH="$INSTALL_DIR/bin/auxmon.jar:/system/framework/com.hkmc.ext.hcan.jar"
MON_PID=""

is_svc_pid_alive() {
    pid="$1"
    [ -n "$pid" ] || return 1
    kill -0 "$pid" 2>/dev/null || return 1
    [ -r "/proc/$pid/cmdline" ] || return 1
    /system/bin/busybox grep -q "auxmon-daemon.sh" "/proc/$pid/cmdline" 2>/dev/null && return 0
    grep -q "auxmon-daemon.sh" "/proc/$pid/cmdline" 2>/dev/null && return 0
    return 1
}

is_java_pid_alive() {
    pid="$1"
    [ -n "$pid" ] || return 1
    kill -0 "$pid" 2>/dev/null || return 1
    [ -r "/proc/$pid/cmdline" ] || return 1
    /system/bin/busybox grep -q "com.hkg.auxmon.AuxMonMain" "/proc/$pid/cmdline" 2>/dev/null && return 0
    grep -q "com.hkg.auxmon.AuxMonMain" "/proc/$pid/cmdline" 2>/dev/null && return 0
    return 1
}

# Acquire atomic directory lock around SVC_PID_FILE check-and-write to prevent race with post.sh / NEMESIS
lock_acquired=0
for _lk in 1 2 3 4 5; do
    if mkdir "$SVC_LOCK_DIR" 2>/dev/null; then
        lock_acquired=1
        break
    fi
    sleep 1
done

if [ -f "$SVC_PID_FILE" ]; then
    old_svc_pid="$(cat "$SVC_PID_FILE" 2>/dev/null)"
    if [ -n "$old_svc_pid" ] && [ "$old_svc_pid" != "$$" ] && is_svc_pid_alive "$old_svc_pid"; then
        [ "$lock_acquired" = "1" ] && rmdir "$SVC_LOCK_DIR" 2>/dev/null
        exit 0
    fi
fi
rm -f "$STOP_FLAG" 2>/dev/null
echo "$$" > "$SVC_PID_FILE" 2>/dev/null
[ "$lock_acquired" = "1" ] && rmdir "$SVC_LOCK_DIR" 2>/dev/null

log_info() {
    echo "[AuxMon] $*"
    log -t HKGAuxMon "$*" 2>/dev/null
}

kill_mon_java() {
    if [ -n "$MON_PID" ]; then
        kill "$MON_PID" >/dev/null 2>&1
        MON_PID=""
    fi
    /system/bin/busybox pkill -f "com.hkg.auxmon.AuxMonMain daemon" >/dev/null 2>&1
    pkill -f "com.hkg.auxmon.AuxMonMain daemon" >/dev/null 2>&1
}

release_power_keep_safely() {
    log_info "Releasing Micom PwrKeep & IG3 before daemon exit..."
    echo "1" > "$STOP_FLAG" 2>/dev/null
    echo "release" > /data/local/tmp/hkg_auxmon_cmd 2>/dev/null
    for _w in 1 2; do
        if [ -n "$MON_PID" ] && kill -0 "$MON_PID" 2>/dev/null; then
            sleep 1
        else
            break
        fi
    done
    kill_mon_java
    if ! CLASSPATH="$APP_CLASSPATH" /system/bin/app_process /system/bin com.hkg.auxmon.AuxMonMain release-keep >>"$LOG_FILE" 2>&1; then
        log_info "release-keep attempt 1 failed; retrying after 1s..."
        sleep 1
        if ! CLASSPATH="$APP_CLASSPATH" /system/bin/app_process /system/bin com.hkg.auxmon.AuxMonMain release-keep >>"$LOG_FILE" 2>&1; then
            log_info "ERROR: release-keep failed after retry!"
        else
            log_info "release-keep succeeded on retry."
        fi
    else
        log_info "release-keep succeeded."
    fi
}

start_mon_java() {
    [ -f "$STOP_FLAG" ] && return 0
    if is_java_pid_alive "$MON_PID"; then
        return 0
    fi
    if [ -f "$JAVA_PID_FILE" ]; then
        saved_jpid="$(cat "$JAVA_PID_FILE" 2>/dev/null)"
        if is_java_pid_alive "$saved_jpid"; then
            MON_PID="$saved_jpid"
            return 0
        fi
    fi
    kill_mon_java
    CLASSPATH="$APP_CLASSPATH" /system/bin/app_process /system/bin com.hkg.auxmon.AuxMonMain daemon "$INSTALL_DIR" >>"$LOG_FILE" 2>&1 &
    MON_PID="$!"
    echo "$MON_PID" > "$JAVA_PID_FILE" 2>/dev/null
    log_info "Started AuxMonMain daemon (pid=$MON_PID, dir=$INSTALL_DIR)"
}

on_stop() {
    log_info "Service stopping via signal; releasing PwrKeep & IG3..."
    release_power_keep_safely
    rm -f "$SVC_PID_FILE" "$JAVA_PID_FILE" "$STOP_FLAG" 2>/dev/null
    exit 0
}
trap 'on_stop' TERM INT

log_info "Service wrapper started (pid=$$)"

while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 2 &
    wait $!
done

sleep 1 &
wait $!

start_mon_java

while true; do
    if [ -f "$STOP_FLAG" ]; then
        exit 0
    fi
    if ! is_java_pid_alive "$MON_PID"; then
        start_mon_java
    fi
    sleep 5 &
    wait $!
done
