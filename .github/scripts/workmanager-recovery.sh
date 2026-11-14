#!/usr/bin/env bash
set -euo pipefail

package=org.materialclass.recovery
report=app/build/recovery-report
mkdir -p "$report"
exec > >(tee "$report/recovery.log") 2>&1
printf 'revision=%s\n' "$SOURCE_SHA"
test "$(git rev-parse HEAD)" = "$SOURCE_SHA"
./gradlew --no-daemon -PrecoveryTest assembleRecovery
adb install app/build/outputs/apk/recovery/app-recovery.apk
adb shell svc wifi disable
adb shell svc data disable
adb shell am broadcast -n "$package/org.materialclass.recovery.RecoveryReceiver"
for i in $(seq 1 60); do
	seed=$(adb shell run-as "$package" cat files/recovery-seed 2>/dev/null | tr -d '\r' || true)
	if [ -n "$seed" ]; then break; fi
	sleep 1
done
test -n "${seed:-}"
printf 'seed=%s\n' "$seed"
IFS='|' read -r old_pid work_id state <<< "$seed"
test "$state" = ENQUEUED
test "$(adb shell pidof "$package" | tr -d '\r')" = "$old_pid"
if adb shell run-as "$package" test -e files/recovery-marker; then
	printf 'worker ran before kill\n' >&2
	exit 1
fi
adb shell run-as "$package" kill -9 "$old_pid"
for i in $(seq 1 20); do
	if ! adb shell kill -0 "$old_pid" 2>/dev/null; then break; fi
	sleep 1
done
if adb shell kill -0 "$old_pid" 2>/dev/null; then exit 1; fi
printf 'killed_pid=%s\n' "$old_pid"
adb shell svc wifi enable
adb shell svc data enable
for i in $(seq 1 180); do
	marker=$(adb shell run-as "$package" cat files/recovery-marker 2>/dev/null | tr -d '\r' || true)
	if [ -n "$marker" ]; then break; fi
	sleep 1
done
test -n "${marker:-}"
printf 'marker=%s\n' "$marker"
test "$(printf '%s\n' "$marker" | wc -l)" -eq 1
IFS='|' read -r new_pid marker_id result <<< "$marker"
test "$new_pid" != "$old_pid"
test "$marker_id" = "$work_id"
test "$result" = true
test "$(adb shell pidof "$package" | tr -d '\r')" = "$new_pid"
printf 'PASS old_pid=%s new_pid=%s work_id=%s\n' "$old_pid" "$new_pid" "$work_id"
