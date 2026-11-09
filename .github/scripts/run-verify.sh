#!/usr/bin/env bash
# Runs the on-device verification suite (app/src/androidTest) on a booted emulator
# and prints every result into the job log, whether the tests pass or fail.
# Called by .github/workflows/verify.yml from inside reactivecircus/android-emulator-runner.
set -uo pipefail

PKG=org.moedog.ehviewer.debug
REPORT=verify-report.txt
OUT=verify-output
mkdir -p "$OUT"

echo "::group::Device"
adb devices
adb shell getprop ro.build.version.sdk
adb shell getprop persist.sys.locale || true
adb shell settings put global window_animation_scale 0 || true
adb shell settings put global transition_animation_scale 0 || true
adb shell settings put global animator_duration_scale 0 || true
# Large buffers, so VERIFY lines of a long run are not rotated out.
adb logcat -G 16M || true
adb logcat -c || true
echo "::endgroup::"

# Capture the VERIFY tag continuously, independent of the logcat ring buffer.
adb logcat -v time -s VERIFY:I > "$OUT/verify-logcat.txt" 2>&1 &
LOGCAT_PID=$!

./gradlew :app:connectedDebugAndroidTest --continue --stacktrace \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
STATUS=$?

sleep 2
kill "$LOGCAT_PID" 2>/dev/null || true

echo "::group::VERIFY lines (logcat, captured during the run)"
cat "$OUT/verify-logcat.txt"
echo "::endgroup::"

echo "================ VERIFY lines (logcat -d) ================"
adb logcat -d -v time -s VERIFY:I | tee "$OUT/verify-logcat-dump.txt" | tail -n 400

echo "================ Report file ($REPORT) ================"
if adb shell run-as "$PKG" cat "cache/$REPORT" > "$OUT/$REPORT" 2>/dev/null && [ -s "$OUT/$REPORT" ]; then
  echo "(from app cache via run-as)"
elif adb pull "/sdcard/Android/data/$PKG/files/$REPORT" "$OUT/$REPORT" >/dev/null 2>&1 && [ -s "$OUT/$REPORT" ]; then
  echo "(from external files dir)"
else
  echo "Report file not found (app uninstalled or no test wrote it)."
fi
[ -s "$OUT/$REPORT" ] && cat "$OUT/$REPORT"

echo "================ Crashes ================"
adb logcat -d -v time -b crash > "$OUT/crash-buffer.txt" 2>&1 || true
adb logcat -d -v time > "$OUT/logcat-full.txt" 2>&1 || true
# Ignore the suite's own "PASS no FATAL EXCEPTION ..." report lines (tag VERIFY).
if grep -n -A 25 "FATAL EXCEPTION" "$OUT/logcat-full.txt" "$OUT/crash-buffer.txt" | grep -v "VERIFY" | grep -q "FATAL EXCEPTION"; then
  grep -n -A 25 "FATAL EXCEPTION" "$OUT/logcat-full.txt" "$OUT/crash-buffer.txt" | grep -v "I/VERIFY"
  echo "^^^ FATAL EXCEPTION found in logcat"
else
  echo "No FATAL EXCEPTION in logcat."
fi
grep -n "E/AndroidRuntime" "$OUT/logcat-full.txt" | head -n 80 || true

echo "================ Instrumentation test results ================"
shopt -s globstar nullglob
XMLS=(app/build/outputs/androidTest-results/connected/**/*.xml)
if [ ${#XMLS[@]} -eq 0 ]; then
  echo "No androidTest result XML found (build or install failed?)."
fi
for f in "${XMLS[@]}"; do
  echo "--- $f"
  grep -o '<testsuite [^>]*>' "$f" | head -n 1
  python3 - "$f" <<'PY'
import sys
import xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
for case in root.iter("testcase"):
    name = f'{case.get("classname")}.{case.get("name")}'
    problems = list(case.findall("failure")) + list(case.findall("error"))
    if case.find("skipped") is not None:
        print(f"SKIPPED {name}")
    elif problems:
        print(f"FAILED  {name} ({case.get('time')} s)")
        for p in problems:
            text = (p.text or p.get("message") or "").strip()
            print("    " + "\n    ".join(text.splitlines()[:40]))
    else:
        print(f"PASSED  {name} ({case.get('time')} s)")
PY
done

echo "Gradle exit status: $STATUS"
exit "$STATUS"
