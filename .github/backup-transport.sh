#!/usr/bin/env bash
set -euo pipefail

package=org.materialclass
transport=com.android.localtransport/.LocalTransport
adb install "$RUNNER_TEMP/normal.apk"
adb install "$RUNNER_TEMP/normal-test.apk"
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
nonce=$(python3 -c 'import uuid; print(uuid.uuid4().hex)')
api=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
reference=org.materialclass.backupreference
adb install backup-reference/build/outputs/apk/debug/backup-reference-debug.apk
adb shell am start -n "$reference/android.app.Activity"
adb shell run-as "$reference" mkdir -p shared_prefs files
reference_prefs="<map><string name=\"fixture\">$nonce</string></map>"
printf '%s' "$reference_prefs" | adb shell run-as "$reference" tee shared_prefs/session.xml > /dev/null
printf '%s' "$nonce" | adb shell run-as "$reference" tee files/fixture > /dev/null
test "$(adb exec-out run-as "$reference" cat shared_prefs/session.xml)" = "$reference_prefs"
test "$(adb exec-out run-as "$reference" cat files/fixture)" = "$nonce"
adb shell bmgr backupnow "$reference" | tr -d '\r' | tee "$RUNNER_TEMP/reference-backup.txt"
python3 .github/scripts/check-bmgr-output.py backup "$reference" "$api" < "$RUNNER_TEMP/reference-backup.txt"
adb shell am force-stop "$reference"
adb shell pm clear "$reference" | tr -d '\r' | grep -Fx Success
for path in shared_prefs/session.xml files/fixture; do
	adb shell run-as "$reference" sh -c "'test ! -e $path'"
done
adb shell bmgr list sets | tr -d '\r' | tee "$RUNNER_TEMP/reference-sets.txt"
reference_token=$(awk '/^[[:space:]]*[0-9a-fA-F]+[[:space:]]*:/ {gsub(":", "", $1); print $1; exit}' "$RUNNER_TEMP/reference-sets.txt")
test -n "$reference_token"
api=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
if [ "$api" -eq 24 ]; then
	adb shell bmgr restore "$reference" | tr -d '\r' | tee "$RUNNER_TEMP/reference-restore.txt"
else
	adb shell bmgr restore "$reference_token" "$reference" | tr -d '\r' | tee "$RUNNER_TEMP/reference-restore.txt"
fi
python3 .github/scripts/check-bmgr-output.py restore "$reference" "$api" < "$RUNNER_TEMP/reference-restore.txt"
adb shell am force-stop "$reference"
test "$(adb exec-out run-as "$reference" cat shared_prefs/session.xml)" = "$reference_prefs"
test "$(adb exec-out run-as "$reference" cat files/fixture)" = "$nonce"
printf '%s\n' 'STAGE: permissive reference restored private fixtures'
fixture=org.materialclass.security.BackupTransportFixtureTest
run_fixture() {
	adb shell am instrument -w -r -e nonce "$nonce" -e class "$fixture#$1" "$package.test/androidx.test.runner.AndroidJUnitRunner" | tr -d '\r' | tee "$RUNNER_TEMP/fixture-$1.txt"
	grep -Fx 'OK (1 test)' "$RUNNER_TEMP/fixture-$1.txt"
}
package=org.materialclass.backupprobe
adb install "$RUNNER_TEMP/probe.apk"
adb install "$RUNNER_TEMP/probe-test.apk"
run_fixture seed
adb shell monkey -p "$package" -c android.intent.category.LAUNCHER 1
adb shell run-as "$package" mkdir -p shared_prefs cache files no_backup
printf '%s' "<map><string name=\"fixture\">$nonce</string></map>" | adb shell run-as "$package" tee shared_prefs/session.xml > /dev/null
printf '%s' "$nonce" | adb shell run-as "$package" tee files/fixture > /dev/null
printf '%s' "$nonce" | adb shell run-as "$package" tee cache/fixture > /dev/null
printf '%s' "$nonce" | adb shell run-as "$package" tee files/backup-reference > /dev/null
printf '%s' "$nonce" | adb shell run-as "$package" tee no_backup/backup-reference > /dev/null
test "$(adb exec-out run-as "$package" cat files/backup-reference)" = "$nonce"
test "$(adb exec-out run-as "$package" cat no_backup/backup-reference)" = "$nonce"
run_fixture verifySeededPayloads
adb shell am force-stop "$package"
cache_file=$(adb shell run-as "$package" ls files/backup-cache | tr -d '\r' | grep -v '^index$')
test -n "$cache_file"
case "$cache_file" in *[!a-zA-Z0-9._-]*|'') exit 1;; esac
adb shell run-as "$package" sh -c "'test -s files/backup-cache/$cache_file && test -s files/backup-cache/index && test -s no_backup/session'"
cache_hash=$(adb exec-out run-as "$package" cat "files/backup-cache/$cache_file" | sha256sum | cut -d ' ' -f 1)
index_hash=$(adb exec-out run-as "$package" cat files/backup-cache/index | sha256sum | cut -d ' ' -f 1)
adb shell monkey -p "$package" -c android.intent.category.LAUNCHER 1
adb shell bmgr backupnow "$package" | tr -d '\r' | tee "$RUNNER_TEMP/probe-backup.txt"
python3 .github/scripts/check-bmgr-output.py backup "$package" "$api" < "$RUNNER_TEMP/probe-backup.txt"
adb shell am force-stop "$package"
adb shell pm clear "$package" | tr -d '\r' | grep -Fx Success
for path in shared_prefs/session.xml files/fixture files/backup-reference cache/fixture no_backup/session no_backup/backup-reference files/backup-cache; do
	adb shell run-as "$package" sh -c "'test ! -e $path'"
done
adb shell bmgr list sets | tr -d '\r' > "$RUNNER_TEMP/probe-sets.txt"
probe_token=$(awk '/^[[:space:]]*[0-9a-fA-F]+[[:space:]]*:/ {gsub(":", "", $1); print $1; exit}' "$RUNNER_TEMP/probe-sets.txt")
test -n "$probe_token"
if [ "$api" -eq 24 ]; then
	adb shell bmgr restore "$package" | tr -d '\r' | tee "$RUNNER_TEMP/probe-restore.txt"
else
	adb shell bmgr restore "$probe_token" "$package" | tr -d '\r' | tee "$RUNNER_TEMP/probe-restore.txt"
fi
python3 .github/scripts/check-bmgr-output.py restore "$package" "$api" < "$RUNNER_TEMP/probe-restore.txt"
adb shell am force-stop "$package"
test "$(adb exec-out run-as "$package" cat shared_prefs/session.xml)" = "<map><string name=\"fixture\">$nonce</string></map>"
test "$(adb exec-out run-as "$package" cat files/fixture)" = "$nonce"
test "$(adb exec-out run-as "$package" cat files/backup-reference)" = "$nonce"
test "$(adb exec-out run-as "$package" cat "files/backup-cache/$cache_file" | sha256sum | cut -d ' ' -f 1)" = "$cache_hash"
test "$(adb exec-out run-as "$package" cat files/backup-cache/index | sha256sum | cut -d ' ' -f 1)" = "$index_hash"
for path in cache/fixture no_backup/session no_backup/backup-reference; do
	adb shell run-as "$package" sh -c "'test ! -e $path'"
done
run_fixture verifyProbeRestoredNonceAndAbsentSession
printf '%s\n' 'STAGE: permissive app restored synthetic file and cache ciphertext; no_backup excluded'
printf '%s\n' 'Cache ciphertext was compared as bytes; Keystore key restoration and decryption were not tested'
package=org.materialclass
run_fixture seed
adb shell monkey -p "$package" -c android.intent.category.LAUNCHER 1
adb shell run-as "$package" mkdir -p shared_prefs cache files
printf '%s' '<map><string name="fixture">forbidden-session</string></map>' | adb shell run-as "$package" tee shared_prefs/session.xml > /dev/null
printf '%s' forbidden-cache | adb shell run-as "$package" tee cache/fixture > /dev/null
printf '%s' forbidden-file | adb shell run-as "$package" tee files/fixture > /dev/null
run_fixture verifySeededPayloads
printf '%s\n' 'STAGE: fixtures seeded and encrypted payloads verified'
adb exec-out run-as "$package" cat shared_prefs/appearance.xml | grep -F 'name="theme">black'
for path in shared_prefs/session.xml cache/fixture files/fixture no_backup/session files/backup-cache/index; do
	adb shell run-as "$package" sh -c "'test -f $path'"
done
adb shell bmgr backupnow "$package" | tr -d '\r' | tee "$RUNNER_TEMP/backup-result.txt"
python3 .github/scripts/check-bmgr-output.py backup "$package" "$api" < "$RUNNER_TEMP/backup-result.txt"
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
python3 .github/scripts/check-bmgr-output.py restore "$package" "$api" < "$RUNNER_TEMP/restore-result.txt"
adb shell am force-stop "$package"
adb exec-out run-as "$package" cat shared_prefs/appearance.xml | grep -F 'name="theme">black'
for path in shared_prefs/session.xml cache/fixture files/fixture no_backup/session files/backup-cache; do
	adb shell run-as "$package" sh -c "'test ! -e $path'"
done
run_fixture verifyRestoredAppearanceAndAbsentSession
printf '%s\n' 'Local transport restored appearance and excluded session, cache and files'
printf '%s\n' 'Cloud backup and device-to-device transfer were not tested'
