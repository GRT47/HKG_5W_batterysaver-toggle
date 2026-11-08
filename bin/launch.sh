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

JAVA_PID_FILE="/data/local/tmp/hkg_auxmon_java.pid"

svc_running=0
if [ -f "$JAVA_PID_FILE" ]; then
    jpid="$(cat "$JAVA_PID_FILE" 2>/dev/null)"
    if [ -n "$jpid" ] && kill -0 "$jpid" 2>/dev/null && [ -r "/proc/$jpid/cmdline" ]; then
        if /system/bin/busybox grep -q "com.hkg.auxmon.AuxMonMain" "/proc/$jpid/cmdline" 2>/dev/null || grep -q "com.hkg.auxmon.AuxMonMain" "/proc/$jpid/cmdline" 2>/dev/null; then
            svc_running=1
        fi
    fi
fi

echo "show" > /data/local/tmp/hkg_auxmon_cmd 2>/dev/null
chmod 666 /data/local/tmp/hkg_auxmon_cmd 2>/dev/null

if [ "$svc_running" = "0" ]; then
    /system/bin/sh "$INSTALL_DIR/svc/auxmon-daemon.sh" </dev/null >>/data/local/tmp/hkg_auxmon.log 2>&1 &
fi

exit 0
