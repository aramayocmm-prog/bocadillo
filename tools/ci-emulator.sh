#!/usr/bin/env bash
# Emulator part of .github/workflows/screens.yml. Every adb call has a timeout, so a hung
# emulator can never block the job; results.txt is written as each test finishes.
set -u
R=results.txt
RUNNER=app.bocadillo.debug.test/androidx.test.runner.AndroidJUnitRunner
a() { timeout "${T:-60}" adb "$@"; }
log() { echo "$*" >> $R; }
inst() { # inst <label> <seconds> <am instrument args...>
  local label=$1 secs=$2; shift 2
  log "=== $label"
  if ! T=$secs a shell am instrument -w -r "$@" $RUNNER >> $R 2>&1; then
    log "TIMEOUT_OR_ERROR $label"
    T=30 a logcat -d -t 400 > "screens/files/screens/logcat_$(echo "$label" | tr -c 'A-Za-z0-9_\n' '_').txt" 2>&1 || true
    a shell am force-stop app.bocadillo.debug || true
  fi
  a logcat -b crash -d >> crash.txt 2>/dev/null || true
  a logcat -b crash -c 2>/dev/null || true
  T=60 a wait-for-device 2>/dev/null || { log "DEVICE_LOST after $label"; revive || log "REVIVE failed"; }
  pull_screens
}
revive() { # the emulator process can die (host GL context lost); start it again with the same AVD
  log "REVIVE: restarting the emulator"
  pkill -f qemu-system 2>/dev/null; sleep 3
  nohup "$ANDROID_HOME/emulator/emulator" -port 5554 -avd test -no-window -gpu swangle_indirect -no-snapshot -noaudio -no-boot-anim > emulator_revive.log 2>&1 &
  T=300 a wait-for-device || return 1
  for i in $(seq 1 90); do [ "$(T=10 a shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break; sleep 2; done
  a shell settings put global window_animation_scale 0; a shell settings put global transition_animation_scale 0; a shell settings put global animator_duration_scale 0
  a shell settings put global hide_error_dialogs 1; a shell input keyevent 82
  # make sure the app and the test APK are there (the restarted emulator may have lost them)
  T=180 a install -r app/build/outputs/apk/debug/app-debug.apk >> $R 2>&1
  T=180 a install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >> $R 2>&1
  a shell cmd locale set-app-locales app.bocadillo.debug --locales ${LOC:-es-ES} || true
  log "REVIVE: emulator back"
}
pull_screens() { # keep what exists so far, even if a later step hangs
  T=120 a exec-out run-as app.bocadillo.debug tar c files/screens > screens/screens.tar 2>/dev/null && (cd screens && tar xf screens.tar 2>/dev/null); rm -f screens/screens.tar
  cp $R crash.txt screens/files/screens/ 2>/dev/null || true
}

# recorded run of one test: screen video in files/screens/<name>.mp4
rec() { # rec <name> <label> <seconds> <am instrument args...>
  local name=$1 label=$2 secs=$3; shift 3
  a shell rm -f /sdcard/$name.mp4
  (timeout 200 adb shell screenrecord --bit-rate 5000000 --time-limit 175 /sdcard/$name.mp4 >/dev/null 2>&1 &)
  sleep 1
  inst "$label (recorded)" "$secs" "$@"
  a shell pkill -INT screenrecord || true
  sleep 3
  T=120 a pull /sdcard/$name.mp4 screens/files/screens/$name.mp4 >/dev/null 2>&1 || true
}
M11=app.bocadillo.Mac11Test

mkdir -p screens/files/screens
a shell settings put global hide_error_dialogs 1

# quick run while developing: only the tests listed in tools/ci-quick.txt ("<video name> <class#method>"), recorded
if [ -s tools/ci-quick.txt ]; then
  T=180 a install -r app/build/outputs/apk/debug/app-debug.apk >> $R 2>&1
  T=180 a install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >> $R 2>&1
  a shell cmd locale set-app-locales app.bocadillo.debug --locales es-ES >> $R 2>&1 || true
  while read -r name test; do [ -n "$name" ] && rec "$name" "$test" 600 -e class "$test"; done < tools/ci-quick.txt
  a logcat -d -s Mac11:I Mac12:I >> $R 2>/dev/null || true
  pull_screens
  ! grep -q 'TIMEOUT_OR_ERROR\|FAILURES!!!\|INSTRUMENTATION_FAILED\|Process crashed\|DEVICE_LOST' $R
  exit $?
fi

log "=== UPGRADE: install 1.1.0 and create data with it"
T=180 a install -r ../old/app/build/outputs/apk/debug/app-debug.apk >> $R 2>&1
T=180 a install -r ../old/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >> $R 2>&1
inst "1.1.0 StickerLibraryFlowTest (creates a project and the sticker library)" 300 -e class app.bocadillo.StickerLibraryFlowTest
inst "1.1.0 Mac10FlowsTest#fontsImportPickAndPersist (imports fonts, project with a title)" 420 -e class app.bocadillo.Mac10FlowsTest#fontsImportPickAndPersist
a push app/src/main/assets/fonts/Comfortaa-Bold.ttf /data/local/tmp/upgrade_font.ttf >> $R 2>&1
a shell "run-as app.bocadillo.debug sh -c 'mkdir -p files/fonts && cp /data/local/tmp/upgrade_font.ttf files/fonts/UpgradeFont.ttf && echo 1.1.0 > files/upgrade_marker.txt'" >> $R 2>&1
log "--- 1.1.0 data and preferences before the update"
a shell "run-as app.bocadillo.debug ls -R files" >> $R 2>&1 || true
a shell "run-as app.bocadillo.debug sh -c 'cat shared_prefs/*.xml'" >> $R 2>&1 || true
a shell dumpsys package app.bocadillo.debug | grep -E 'versionName|versionCode' >> $R 2>&1 || true

log "=== UPGRADE: install 1.2.0 over it (adb install -r, same signature)"
T=180 a install -r app/build/outputs/apk/debug/app-debug.apk >> $R 2>&1
T=180 a install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >> $R 2>&1
a shell dumpsys package app.bocadillo.debug | grep -E 'versionName|versionCode' >> $R 2>&1 || true
log "--- preferences after the update"
a shell "run-as app.bocadillo.debug sh -c 'cat shared_prefs/*.xml'" >> $R 2>&1 || true
inst app.bocadillo.LayoutAndUpgradeTest#dataSurvivedUpgrade 300 -e class app.bocadillo.LayoutAndUpgradeTest#dataSurvivedUpgrade

# 1.1.0 corrections, app in Spanish (per-app language, Android 13+)
a shell cmd locale set-app-locales app.bocadillo.debug --locales es-ES >> $R 2>&1 || true
a shell dumpsys gfxinfo app.bocadillo.debug reset > /dev/null 2>&1 || true
rec trazo_en_vivo "$M11#liveStrokeLatencyAndFidelity" 600 -e class "$M11#liveStrokeLatencyAndFidelity"
log "--- frame statistics during the live-stroke test (dumpsys gfxinfo)"
a shell dumpsys gfxinfo app.bocadillo.debug | grep -E 'Total frames|Janky|percentile|Number Missed|Number High input|Number Slow' >> $R 2>&1 || true
rec asa_tamano_perspectiva "$M11#handleTapTogglesSizeAndPerspective" 420 -e class "$M11#handleTapTogglesSizeAndPerspective"
a shell dumpsys gfxinfo app.bocadillo.debug reset > /dev/null 2>&1 || true
rec silueta_borrador "$M11#cutOutUnderTheFinger" 420 -e class "$M11#cutOutUnderTheFinger"
log "--- frame statistics during the Cut Out test (dumpsys gfxinfo)"
a shell dumpsys gfxinfo app.bocadillo.debug | grep -E 'Total frames|Janky|percentile|Number Missed|Number High input|Number Slow' >> $R 2>&1 || true
rec navegacion_guardado "$M11#finishSaveAndBackNavigation" 420 -e class "$M11#finishSaveAndBackNavigation"
rec catalogo_fuentes "$M11#fontCatalogueSearchPickPersist" 420 -e class "$M11#fontCatalogueSearchPickPersist"
inst "$M11#homeThumbnailAboutTwiceAsBig" 300 -e class "$M11#homeThumbnailAboutTwiceAsBig"
a logcat -d -s Mac11:I Upgrade:I LiveStroke:I >> $R 2>/dev/null || true

for v in 1.0 1.3 2.0; do
  a shell settings put system font_scale $v; sleep 2
  inst "homeVariants font $v (es)" 300 -e class app.bocadillo.LayoutAndUpgradeTest#homeVariants -e variant font$v
done
a shell settings put system font_scale 1.0
a shell settings put system accelerometer_rotation 0
inst "homeVariants landscape (es)" 300 -e class app.bocadillo.LayoutAndUpgradeTest#homeVariants -e variant landscape

# 1.0 flows (regression)
for t in fontsImportPickAndPersist liveStrokeEqualsAppliedStroke insertPictureCropPlaceCutOut; do
  inst "app.bocadillo.Mac10FlowsTest#$t (es)" 420 -e class "app.bocadillo.Mac10FlowsTest#$t"
done

# regression tests of 0.8/0.9 (system language)
LOC=en-US; a shell cmd locale set-app-locales app.bocadillo.debug --locales en-US >> $R 2>&1 || true
for t in app.bocadillo.ReferenceScreensTest app.bocadillo.StickerLibraryFlowTest \
         app.bocadillo.MacFlowsTest#largePictureAsksForSize app.bocadillo.MacFlowsTest#homeTitleAndPaint \
         app.bocadillo.MacFlowsTest#insertedPictureSkewPerspectiveSaveReopenExport app.bocadillo.MacFlowsTest#svgShapeAndLineStyle; do
  inst "$t" 420 -e class "$t"
done

cp emulator_revive.log screens/files/screens/ 2>/dev/null || true
pull_screens
! grep -q 'TIMEOUT_OR_ERROR\|FAILURES!!!\|INSTRUMENTATION_FAILED\|Process crashed\|DEVICE_LOST' $R
