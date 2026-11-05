#!/usr/bin/env bash
set -euo pipefail
package=org.materialclass
runner=org.materialclass.test/androidx.test.runner.AndroidJUnitRunner
test_class=org.materialclass.session.CleanupProcessRestartTest
for stage in write cache session clear; do
  adb shell pm clear "$package" >/dev/null
  adb logcat -c
  output="${RUNNER_TEMP}/cleanup-prepare-${stage}.txt"
  adb shell am instrument -w -r -e cleanupRestartPhase prepare -e cleanupRestartStage "$stage" -e class "${test_class}#preparePendingCleanupAndWaitForKill" "$runner" >"$output" 2>&1 &
  instrumentation_pid=$!
  ready=false
  for attempt in $(seq 1 90); do
    if adb logcat -d -s CleanupRestartTest:I '*:S' | grep -q "READY:${stage}:"; then ready=true; break; fi
    if ! kill -0 "$instrumentation_pid" 2>/dev/null; then break; fi
    sleep 1
  done
  if [ "$ready" != true ]; then
    cat "$output"
    kill "$instrumentation_pid" 2>/dev/null || true
    wait "$instrumentation_pid" || true
    exit 1
  fi
  pid=$(adb shell run-as "$package" cat files/../no_backup/restart-test-pid | tr -d '\r')
  test -n "$pid"
  adb shell test -d "/proc/$pid"
  adb shell am force-stop "$package"
  wait "$instrumentation_pid" || true
  for attempt in $(seq 1 20); do
    if ! adb shell test -d "/proc/$pid"; then break; fi
    sleep 1
  done
  if adb shell test -d "/proc/$pid"; then
    echo "Process $pid survived force-stop" >&2
    exit 1
  fi
  echo "Terminated process $pid after $stage failure"
  output="${RUNNER_TEMP}/cleanup-verify-${stage}.txt"
  adb shell am instrument -w -r -e cleanupRestartPhase verify -e cleanupRestartStage "$stage" -e class "${test_class}#recoverBeforeLoginAfterProcessDeath" "$runner" >"$output"
  cat "$output"
  tr -d '\r' < "$output" > "${output}.normalized"
  grep -Fx "INSTRUMENTATION_STATUS: cleanupRestartVerified=$stage" "${output}.normalized"
  grep -Fx 'INSTRUMENTATION_CODE: -1' "${output}.normalized"
  grep -Fx 'OK (1 test)' "${output}.normalized"
done
adb shell pm clear "$package" >/dev/null
