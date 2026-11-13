#!/bin/bash
# Installs Sketcher on the emulator, runs the auto demo and captures screenshots + logs.
set -x
OUT=emu; mkdir -p $OUT
PKG=com.smitnk.sketcher
adb install -r apk/*.apk > $OUT/install.txt 2>&1
adb logcat -c
shot() { adb exec-out screencap -p > $OUT/$1.png; }
adb shell am start -n $PKG/.MainActivity
sleep 6; shot 01_launch
adb shell am force-stop $PKG
adb shell am start -n $PKG/.MainActivity --ez autodemo true
sleep 3; shot 02_demo_start
sleep 3; shot 03_demo_mid
sleep 5; shot 04_demo_later
sleep 12; shot 05_demo_done
# open layers sheet if the app supports the debug extra
adb shell am start -n $PKG/.MainActivity --ez autodemo true --ez showlayers true
sleep 25; shot 06_layers
adb shell am start -n $PKG/.MainActivity --ez autodemo true --ez autoexport true
sleep 25; shot 07_export
adb shell ls -R /sdcard/Pictures > $OUT/pictures.txt 2>&1
adb shell pidof $PKG > $OUT/pid.txt 2>&1
adb logcat -d -b crash > $OUT/crash.txt 2>&1
adb logcat -d | grep -iE "sketcher|AndroidRuntime|FATAL" | tail -n 400 > $OUT/logcat.txt
exit 0
