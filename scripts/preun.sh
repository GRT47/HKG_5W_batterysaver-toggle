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

# Ensure PwrKeep(mode=2) and IG3 are explicitly released before stopping daemon
echo "release" > /data/local/tmp/hkg_auxmon_cmd 2>/dev/null
sleep 1
CLASSPATH="$APP_CLASSPATH" /system/bin/app_process /system/bin com.hkg.auxmon.AuxMonMain release-keep >>/data/local/tmp/hkg_auxmon.log 2>&1

/system/bin/busybox pkill -f "auxmon-daemon.sh" >/dev/null 2>&1
pkill -f "auxmon-daemon.sh" >/dev/null 2>&1
/system/bin/busybox pkill -f "com.hkg.auxmon.AuxMonMain" >/dev/null 2>&1
pkill -f "com.hkg.auxmon.AuxMonMain" >/dev/null 2>&1

rm -f /data/local/tmp/hkg_auxmon_svc.pid \
      /data/local/tmp/hkg_auxmon_java.pid \
      /data/local/tmp/hkg_auxmon_cmd \
      /data/local/tmp/hkg_auxmon_state.json 2>/dev/null

exit 0
