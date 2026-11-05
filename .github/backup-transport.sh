#!/usr/bin/env bash
set -euo pipefail

package=org.materialclass
transport=com.android.localtransport/.LocalTransport
adb install app/build/outputs/apk/debug/app-debug.apk
adb shell bmgr enable true
adb shell bmgr enabled | tr -d '\r' | grep -F 'Backup Manager currently enabled'
adb shell bmgr list transports | tee "$RUNNER_TEMP/transports.txt"
grep -Fq "$transport" "$RUNNER_TEMP/transports.txt"
adb shell bmgr transport "$transport" | tee "$RUNNER_TEMP/transport-selection.txt"
adb shell bmgr list transports | tr -d '\r' | grep -F "* $transport"
adb shell am force-stop "$package"
adb shell run-as "$package" sh -c "mkdir -p shared_prefs cache files; printf '%s' '<map><string name=\"fixture\">allowed-appearance</string></map>' > shared_prefs/appearance.xml; printf '%s' '<map><string name=\"fixture\">forbidden-session</string></map>' > shared_prefs/session.xml; printf '%s' forbidden-cache > cache/fixture; printf '%s' forbidden-file > files/fixture"
adb shell bmgr backupnow "$package" | tr -d '\r' | tee "$RUNNER_TEMP/backup-result.txt"
grep -F "Package $package with result: Success" "$RUNNER_TEMP/backup-result.txt"
adb shell am force-stop "$package"
adb shell run-as "$package" sh -c 'rm -f shared_prefs/appearance.xml shared_prefs/session.xml cache/fixture files/fixture'
adb shell bmgr list sets | tr -d '\r' | tee "$RUNNER_TEMP/restore-sets.txt"
token=$(awk '/^[[:space:]]*[0-9a-fA-F]+[[:space:]]*:/ {gsub(":", "", $1); print $1; exit}' "$RUNNER_TEMP/restore-sets.txt")
test -n "$token"
adb shell bmgr restore "$token" "$package" | tr -d '\r' | tee "$RUNNER_TEMP/restore-result.txt"
grep -F 'restoreFinished: 0' "$RUNNER_TEMP/restore-result.txt"
adb shell am force-stop "$package"
adb shell run-as "$package" sh -c "test -f shared_prefs/appearance.xml && grep -q allowed-appearance shared_prefs/appearance.xml && test ! -e shared_prefs/session.xml && test ! -e cache/fixture && test ! -e files/fixture"
printf '%s\n' 'Local transport restored appearance and excluded session, cache and files'
printf '%s\n' 'Cloud backup and device-to-device transfer were not tested'
