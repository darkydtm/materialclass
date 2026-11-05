#!/bin/bash
set -eu
failures=0
for attempt in 1 2 3 4 5; do
	printf 'Homework diagnostic attempt %s/5\n' "$attempt"
	if ! ./gradlew --no-daemon -PintegrationTestNativeUi=true connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=org.materialclass.NativeUiControllerTest#homeworkFiltersOnlyTheCurrentSnapshotAndClearsOnSignOut,org.materialclass.NativeUiControllerTest#homeworkFilterOptionsExposeLocalizedRadioSelection,org.materialclass.NativeUiControllerTest#savedFilterNodeIsRejectedAfterRecomposition,org.materialclass.NativeUiControllerTest#freshFilterNodeWorksAfterRecomposition'; then
		failures=$((failures + 1))
	fi
done
printf 'Homework diagnostic failures: %s/5\n' "$failures"
test "$failures" -eq 0
