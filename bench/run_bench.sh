#!/bin/bash
# Runs inside the emulator job: installs the debug APK and benchmarks each renderer.
set -u
PKG=com.cadlite.viewer
OUT=bench-out
mkdir -p $OUT
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell settings put system screen_off_timeout 1800000
adb shell svc power stayon true
if [ -n "${BENCH_SIZE:-}" ]; then adb shell wm size $BENCH_SIZE; adb shell wm density ${BENCH_DENSITY:-220}; sleep 3; fi
adb shell wm size > $OUT/device.txt
adb push bench/heavy.dxf /data/local/tmp/heavy.dxf
adb shell "cat /data/local/tmp/heavy.dxf | run-as $PKG sh -c 'mkdir -p files && cat > files/heavy.dxf'"
for R in ${BENCH_RENDERERS:-canvas gl-nocache gl}; do
  adb shell run-as $PKG rm -f files/bench.txt
  adb shell am force-stop $PKG
  adb shell am start -W -n $PKG/.MainActivity --es bench heavy.dxf --es renderer $R
  out_done=0; in_done=0
  for i in $(seq 1 360); do
    sleep 0.5
    T=$(adb shell run-as $PKG cat files/bench.txt 2>/dev/null)
    if [ $out_done = 0 ] && echo "$T" | grep -q SHOT_out; then adb exec-out screencap -p > $OUT/${R}_zoomed_out.png; out_done=1; fi
    if [ $in_done = 0 ] && echo "$T" | grep -q SHOT_in; then adb exec-out screencap -p > $OUT/${R}_zoomed_in.png; in_done=1; fi
    if echo "$T" | grep -q DONE; then break; fi
  done
  echo "$T" > $OUT/${R}.txt
  adb logcat -d -s CADBENCH AndroidRuntime:E > $OUT/${R}_logcat.txt
  adb logcat -c
done
cat $OUT/*.txt
