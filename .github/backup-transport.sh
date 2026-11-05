#!/usr/bin/env bash
set -euo pipefail

package=org.materialclass
transport=com.android.localtransport/.LocalTransport
adb install app/build/outputs/apk/debug/app-debug.apk
adb install app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
printf '%s\n' 'DIAGNOSTIC: device and package'
adb shell getprop ro.build.fingerprint
adb shell am get-current-user
adb shell settings get global device_provisioned
adb shell settings get secure user_setup_complete
adb shell dumpsys package "$package" | grep -E 'flags=|privateFlags=|backupAgent|fullBackupContent|dataExtractionRules' || true
adb shell pm list packages | grep -E 'backup|transport' || true
adb shell bmgr enable true
adb shell bmgr enabled | tr -d '\r' | grep -F 'Backup Manager currently enabled'
adb shell bmgr list transports | tee "$RUNNER_TEMP/transports.txt"
printf '%s\n' 'DIAGNOSTIC: backup manager state'
adb shell dumpsys backup | grep -E -i 'enabled|provision|transport|eligible|setup|user|pending|backup is' || true
if grep -Fq 'android/com.android.internal.backup.LocalTransport' "$RUNNER_TEMP/transports.txt"; then
	transport=android/com.android.internal.backup.LocalTransport
fi
if ! grep -Fq "$transport" "$RUNNER_TEMP/transports.txt"; then
	printf '%s\n' 'BLOCKER: Android local backup transport is unavailable' >&2
	exit 1
fi
adb shell bmgr transport "$transport" | tee "$RUNNER_TEMP/transport-selection.txt"
adb shell bmgr list transports | tr -d '\r' | grep -F "* $transport"
fixture=org.materialclass.security.BackupTransportFixtureTest
run_fixture() {
	adb shell am instrument -w -r -e class "$fixture#$1" "$package.test/androidx.test.runner.AndroidJUnitRunner" | tr -d '\r' | tee "$RUNNER_TEMP/fixture-$1.txt"
	grep -Fx 'OK (1 test)' "$RUNNER_TEMP/fixture-$1.txt"
}
run_fixture seed
adb shell monkey -p "$package" -c android.intent.category.LAUNCHER 1
adb shell run-as "$package" mkdir -p shared_prefs cache files
printf '%s' '<map><string name="fixture">forbidden-session</string></map>' | adb shell run-as "$package" tee shared_prefs/session.xml > /dev/null
printf '%s' forbidden-cache | adb shell run-as "$package" tee cache/fixture > /dev/null
printf '%s' forbidden-file | adb shell run-as "$package" tee files/fixture > /dev/null
printf '%s\n' 'STAGE: fixtures seeded'
adb exec-out run-as "$package" cat shared_prefs/appearance.xml | grep -F 'name="theme">black'
for path in shared_prefs/session.xml cache/fixture files/fixture no_backup/session files/backup-cache/index; do
	adb shell run-as "$package" sh -c "'test -f $path'"
done
adb shell bmgr backupnow "$package" | tr -d '\r' | tee "$RUNNER_TEMP/backup-result.txt"
grep -F "Package $package with result: Success" "$RUNNER_TEMP/backup-result.txt"
printf '%s\n' 'STAGE: package backup succeeded'
adb shell am force-stop "$package"
adb shell pm clear "$package" | tr -d '\r' | grep -Fx Success
for path in shared_prefs/appearance.xml shared_prefs/session.xml cache/fixture files/fixture no_backup/session files/backup-cache; do
	adb shell run-as "$package" sh -c "'test ! -e $path'"
done
printf '%s\n' 'STAGE: app storage cleared'
adb shell bmgr list sets | tr -d '\r' | tee "$RUNNER_TEMP/restore-sets.txt"
token=$(awk '/^[[:space:]]*[0-9a-fA-F]+[[:space:]]*:/ {gsub(":", "", $1); print $1; exit}' "$RUNNER_TEMP/restore-sets.txt")
test -n "$token"
api=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
if [ "$api" -eq 24 ]; then
	adb shell bmgr restore "$package" | tr -d '\r' | tee "$RUNNER_TEMP/restore-result.txt"
else
	adb shell bmgr restore "$token" "$package" | tr -d '\r' | tee "$RUNNER_TEMP/restore-result.txt"
fi
grep -E "^onUpdate: [0-9]+ = $package$" "$RUNNER_TEMP/restore-result.txt"
grep -Fx 'restoreFinished: 0' "$RUNNER_TEMP/restore-result.txt"
adb shell am force-stop "$package"
adb exec-out run-as "$package" cat shared_prefs/appearance.xml | grep -F 'name="theme">black'
for path in shared_prefs/session.xml cache/fixture files/fixture no_backup/session files/backup-cache; do
	adb shell run-as "$package" sh -c "'test ! -e $path'"
done
run_fixture verifyRestoredAppearanceAndAbsentSession
printf '%s\n' 'Local transport restored appearance and excluded session, cache and files'
printf '%s\n' 'Cloud backup and device-to-device transfer were not tested'
