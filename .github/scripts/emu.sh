#!/bin/bash
# Installs Sketcher on the emulator, runs the test-image generation matrix,
# the autoexport/autovideo runs, and captures screenshots + logs.
set +e
set -x
OUT=emu; mkdir -p $OUT
PKG=com.smitnk.sketcher
APP_APK=$(find apk -name '*.apk' ! -name '*androidTest*' | head -n 1)
TEST_APK=$(find apk -name '*androidTest*.apk' | head -n 1)
adb install -r "$APP_APK" > $OUT/install.txt 2>&1
if [ -n "$TEST_APK" ]; then
  adb install -r "$TEST_APK" >> $OUT/install.txt 2>&1
  adb shell am instrument -w -r com.smitnk.sketcher.test/androidx.test.runner.AndroidJUnitRunner > $OUT/instrumented.txt 2>&1
  adb shell am force-stop $PKG
else
  echo "no androidTest apk found" > $OUT/instrumented.txt
fi
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

# b) diagnostic matrix: one run per combination, screenshots at +3s/+7s/+20s after GEN_DONE,
# debug dumps (ink_raw, strokes, coverage, renders, crop.txt) pulled into emu/debug/<label>/.
mkdir -p $OUT/debug
dbg_run() {
  local label=$1 img=$2
  shift 2
  adb shell am force-stop $PKG
  adb logcat -c
  adb shell am start -n $PKG/.MainActivity --ez autodemo true --es testimage "$img" --es label "$label" "$@"
  local waited=0 seen=0
  while [ $waited -lt 90 ]; do   # up to 90 s (model downloads)
    if adb logcat -d -s SketcherGen 2>/dev/null | grep -q "GEN_DONE"; then seen=1; break; fi
    sleep 1
    waited=$((waited + 1))
  done
  echo "== $label (GEN_DONE seen=$seen after ${waited}s)" >> $OUT/gen_times.txt
  if [ $seen -eq 1 ]; then
    sleep 3; shot "${label}_t03"
    sleep 4; shot "${label}_t07"
    sleep 13; shot "${label}_end"
  else
    sleep 5; shot "${label}_nogen"
  fi
  adb logcat -d -s SketcherGen >> $OUT/gen_times.txt 2>&1
  adb logcat -d -s SketcherGen > $OUT/debug/${label}_log.txt 2>&1
  adb pull /sdcard/Android/data/$PKG/files/debug/$label $OUT/debug/ >/dev/null 2>&1
}

for img in beard elf cartoon; do
  models="informative realistic coarse"
  [ "$img" = beard ] && models="$models anime"
  for m in $models; do
    dbg_run "${img}_${m}_ai_pencil" "$img" --es engine ai --es model "$m" --es outline pencil
  done
done
dbg_run beard_cartoon_vector beard --es engine cartoon --es outline vector
for acc in cpu nnapi_gpu; do
  dbg_run "beard_accel_${acc}" beard --es engine ai --es model informative --es accel "$acc" --es outline pencil
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
