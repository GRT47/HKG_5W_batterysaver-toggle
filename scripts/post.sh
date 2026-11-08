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

/system/bin/busybox pkill -f "com.hkg.auxmon.AuxMonMain" >/dev/null 2>&1
pkill -f "com.hkg.auxmon.AuxMonMain" >/dev/null 2>&1
/system/bin/busybox pkill -f "auxmon-daemon.sh" >/dev/null 2>&1
pkill -f "auxmon-daemon.sh" >/dev/null 2>&1
rm -f /data/local/tmp/hkg_auxmon_svc.pid /data/local/tmp/hkg_auxmon_java.pid 2>/dev/null

/system/bin/sh "$INSTALL_DIR/svc/auxmon-daemon.sh" </dev/null >>/data/local/tmp/hkg_auxmon.log 2>&1 &

exit 0
