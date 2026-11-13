#!/bin/bash
# Installs Sketcher on the emulator, runs the test-image generation matrix,
# the autoexport/autovideo runs, and captures screenshots + logs.
set +e
set -x
OUT=emu; mkdir -p $OUT
PKG=com.smitnk.sketcher
adb install -r apk/*.apk > $OUT/install.txt 2>&1
adb logcat -c
shot() { adb exec-out screencap -p > $OUT/$1.png; }
: > $OUT/gen_times.txt

# Starts one generation run. $1 = label for logs, $2 = screenshot name, rest = am start extras.
run_gen() {
  local label=$1 shot_name=$2
  shift 2
  adb shell am force-stop $PKG
  adb logcat -c
  adb shell am start -n $PKG/.MainActivity --ez autodemo true "$@"
  local waited=0 seen=0
  while [ $waited -lt 40 ]; do
    if adb logcat -d -s SketcherGen 2>/dev/null | grep -q "GEN_DONE"; then
      seen=1
      break
    fi
    sleep 2
    waited=$((waited + 2))
  done
  if [ $seen -eq 0 ]; then
    echo "GEN_DONE not seen for $label, falling back to 25s wait"
    sleep 25
  fi
  { echo "== $label (GEN_DONE seen=$seen)"; adb logcat -d -s SketcherGen; } >> $OUT/gen_times.txt 2>&1
  sleep 14
  shot "$shot_name"
}

# a) plain launch
adb shell am start -n $PKG/.MainActivity
sleep 6; shot 00_home

# b) each test image, outline-only and with medium shading
for img in astronaut coffee chelsea cartoon toy_grass rocket; do
  for cfg in "outline:none" "shading:medium"; do
    shading=${cfg#*:}
    run_gen "$img $cfg" "${img}_${cfg//:/-}" \
      --es testimage "$img" --es engine auto --es shading "$shading"
  done
done

# c) colour mode on cartoon
run_gen "cartoon color" cartoon_color \
  --es testimage cartoon --es engine auto --es mode color

# d) autoexport and autovideo on the demo
adb shell am force-stop $PKG
adb shell am start -n $PKG/.MainActivity --ez autodemo true --ez autoexport true
sleep 25; shot 07_export
adb shell am start -n $PKG/.MainActivity --ez autodemo true --ez autovideo true
sleep 70; shot 08_video_export

mkdir -p $OUT/files
adb pull /sdcard/Pictures/Sketcher $OUT/files/pictures >/dev/null 2>&1
adb pull /sdcard/Movies/Sketcher $OUT/files/movies >/dev/null 2>&1
{ adb shell ls -lR /sdcard/Movies; adb shell ls -lR /sdcard/Pictures; } > $OUT/pictures.txt 2>&1
adb shell pidof $PKG > $OUT/pid.txt 2>&1
adb logcat -d -b crash > $OUT/crash.txt 2>&1
adb logcat -d | grep -iE "sketcher|AndroidRuntime|FATAL" | tail -n 400 > $OUT/logcat.txt
exit 0
