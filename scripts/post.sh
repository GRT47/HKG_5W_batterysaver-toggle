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

mkdir -p /data/dalvik-cache /data/local/tmp /data/local/tmp/hkg_auxmon/sessions 2>/dev/null
chmod 777 /data/local /data/local/tmp /data/local/tmp/hkg_auxmon /data/local/tmp/hkg_auxmon/sessions 2>/dev/null
chmod 755 "$INSTALL_DIR/bin/launch.sh" "$INSTALL_DIR/svc/auxmon-daemon.sh" 2>/dev/null

SVC_PID_FILE="/data/local/tmp/hkg_auxmon_svc.pid"
JAVA_PID_FILE="/data/local/tmp/hkg_auxmon_java.pid"
STOP_FLAG="/data/local/tmp/hkg_auxmon_stopping"

# Note: On package update, /system/xbin/nemesis runs the previous installation's preun.sh
# before unpacking and invoking post.sh (so updates should be performed with ignition ON / ACCON).
# If any previous wrapper or daemon is still lingering, stop it and wait up to 10s before starting.
old_svc_pid="$(cat "$SVC_PID_FILE" 2>/dev/null)"
old_java_pid="$(cat "$JAVA_PID_FILE" 2>/dev/null)"

if [ -n "$old_svc_pid" ] || [ -n "$old_java_pid" ]; then
    echo "1" > "$STOP_FLAG" 2>/dev/null
    /system/bin/busybox pkill -f "auxmon-daemon.sh" >/dev/null 2>&1
    pkill -f "auxmon-daemon.sh" >/dev/null 2>&1
    /system/bin/busybox pkill -f "com.hkg.auxmon.AuxMonMain daemon" >/dev/null 2>&1
    pkill -f "com.hkg.auxmon.AuxMonMain daemon" >/dev/null 2>&1

    for _w in 1 2 3 4 5 6 7 8 9 10; do
        still_alive=0
        if [ -n "$old_svc_pid" ] && kill -0 "$old_svc_pid" 2>/dev/null; then
            still_alive=1
        fi
        if [ -n "$old_java_pid" ] && kill -0 "$old_java_pid" 2>/dev/null; then
            still_alive=1
        fi
        if [ "$still_alive" = "0" ]; then
            break
        fi
        sleep 1
    done
fi

rm -rf /data/local/tmp/hkg_auxmon_svc.lock 2>/dev/null
rm -f "$SVC_PID_FILE" "$JAVA_PID_FILE" "$STOP_FLAG" 2>/dev/null

# Delayed fallback launch (2s): if NEMESIS service manager starts auxmon-daemon.sh first,
# SVC_LOCK_DIR + SVC_PID_FILE in auxmon-daemon.sh prevents duplicate startup; if NEMESIS does
# not auto-start services on install, this ensures the daemon starts immediately without reboot.
( sleep 2; exec /system/bin/sh "$INSTALL_DIR/svc/auxmon-daemon.sh" ) </dev/null >>/data/local/tmp/hkg_auxmon.log 2>&1 &

exit 0
