#!/usr/bin/env bash
set -euo pipefail

output=${1:?instrumentation output path is required}
app_apk=${2:-app-debug.apk}
test_apk=${3:-app-debug-androidTest.apk}
runner=org.materialclass.test/androidx.test.runner.AndroidJUnitRunner

printf 'STAGE device-install-start app=%s test=%s\n' "$app_apk" "$test_apk"
adb install -r "$app_apk"
adb install -r "$test_apk"
printf 'STAGE device-install-complete\n'
printf 'STAGE device-instrumentation-start\n'
adb shell am instrument -w -r -e class "$DEVICE_SMOKE_CLASSES" "$runner" > "$output"
printf 'STAGE device-instrumentation-complete output=%s\n' "$output"
python3 .github/scripts/reconcile-instrumentation.py "$output" \
	--source-root app/src/androidTest/java \
	--include-class org.materialclass.LauncherTest \
	--include-class org.materialclass.OfflineUiTest \
	--include-class org.materialclass.NativeControllerIncompleteIdentityTest \
	--include-class org.materialclass.NativeControllerPresenceTest \
	--include-class org.materialclass.api.NativeGatewayTest \
	--include-class org.materialclass.data.NativeIdentityVerifierTest \
	--include-class org.materialclass.data.RepositorySessionTest \
	--include-class org.materialclass.security.StorageSecurityTest
