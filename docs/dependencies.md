# Android dependencies and toolchain

Status: stage 03 decision, applied in stage 04. This document records the verified stack for the minimal Android client.

Source verification was completed on November 12, 2026. The versions below are pinned explicitly: dynamic versions (`+`, `latest`) are forbidden.

## Selected stack

| Layer | Selection | Rationale and compatibility |
|---|---|---|
| Android Gradle Plugin | `com.android.application:9.1.1` | The official AGP 9.1.1 table specifies API 37.0, Gradle 9.3.1, and JDK 17; the table lists built-in KGP version 2.2.10.[1] |
| Gradle Wrapper | `9.3.1` | This is the minimum version officially specified for AGP 9.1.1.[1] SHA-256 for `gradle-9.3.1-bin.zip`: `b266d5ff6b90eada6dc3b20cb090e3731302e553a27c5d3e4df1f0d76beaff06`.[18] |
| JDK | `17` | AGP 9.1.1 requires JDK 17; Gradle 9.3.1 also supports running on Java 17.[1][4] Android Studio should run on its bundled JBR, while Gradle in the IDE and terminal should use the same JDK.[3] |
| Kotlin / KGP | AGP 9.1.1 built-in Kotlin; language/compiler level `2.2.10` | AGP 9.1.1 officially specifies KGP 2.2.10.[1] AGP 9.0+ includes built-in Kotlin and replaces `org.jetbrains.kotlin.android`; this plugin must not be added again.[23] The Compose Compiler plugin uses `org.jetbrains.kotlin.plugin.compose` with the same Kotlin version.[7] The KGP compatibility table also confirms that the 2.2.0-2.2.10 line does not support AGP 9.1; built-in Kotlin in AGP 9.1.1 is therefore required for this stack.[5] |
| Compose Compiler | `org.jetbrains.kotlin.plugin.compose:2.2.10` | Since Kotlin 2.0, Compose Compiler is no longer selected as a separate version: use the Kotlin Compose Compiler Gradle plugin with the Kotlin version.[7][8] |
| Compose BOM | `androidx.compose:compose-bom:2026.09.00` | Stable BOM metadata maps `material3:1.4.0`; the official Compose mapping confirms that mapping.[8][9] It is the reproducible stable baseline for Compose core. Since this stage requires Material 3 Expressive, the selected `material3:1.5.0-rc01` is pinned explicitly above the stable BOM and must be treated as an RC override. Do not claim that the stable BOM itself supplies Material 3 1.5.0-rc01. |
| Compose core alignment | `androidx.compose.ui:ui:1.12.0`, `androidx.compose.runtime:runtime:1.12.0` under the explicit Material 3 RC graph | The Material 3 `1.5.0-rc01` POM resolves its runtime Compose dependencies to `1.12.0`; the stable BOM remains the baseline for ordinary Compose coordinates, but the RC Material 3 artifact controls its published compatible graph.[16] |
| Material 3 | `androidx.compose.material3:material3:1.5.0-rc01` | The version is published in Google Maven and has an official release note as an RC.[10][16] It is selected explicitly over the BOM to access the Expressive API. |
| Adaptive navigation | `androidx.compose.material3:material3-adaptive-navigation-suite:1.5.0-rc01` | The version appears in the official Material 3 release page example for the 1.5 line.[10] Add it only if a screen actually needs adaptive navigation. |
| Window size classes | `androidx.compose.material3:material3-window-size-class:1.5.0-rc01` | Version compatible with the selected Material 3 line; do not add it transitively "just in case".[10] |
| Activity integration | `androidx.activity:activity-compose:1.13.0` | The version is listed in the official Compose instructions for BOM 2026.09.00.[7] Recheck the mapping and release notes when updating the BOM. |
| Lifecycle ViewModel Compose | `androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0` | The version is listed in the official Compose instructions for BOM 2026.09.00; this is an optional dependency, not a required part of the toolchain.[7] |

### Why not stable Material 3 `1.4.0`

Stable `androidx.compose.material3:material3:1.4.0` is included in stable Compose BOM 2026.09.00, but the official release notes state that public APIs marked with `ExperimentalMaterial3ExpressiveApi` were removed from the 1.4 beta line; use 1.5.0-alpha/RC for the Expressive API.[10] Therefore, this project's baseline is 1.5.0-rc01 with an explicit RC caveat, rather than stable 1.4.0 without the required API.

## Material 3 Expressive: API verification

The selected artifact contains the required API surface:

 - `MaterialExpressiveTheme(colorScheme, motionScheme, shapes, typography, content)` is present in the official Android API reference.[11]
 - `MotionScheme` documents the companion functions `MotionScheme.standard()` and `MotionScheme.expressive()`; `expressive()` is intended for prominent UI and hero interactions.[12]
 - The official Material 3 Compose documentation describes M3 Expressive as an extension of Material 3 with updates to theming, components, motion, typography, and shapes.[13]
 - For the selected `material3-android:1.5.0-rc01`, the published AAR was checked locally: `classes.jar` is present, `META-INF/.../LICENSE.txt` is present, `AndroidManifest.xml` declares `android:minSdkVersion="24"`, and `META-INF/com/android/build/gradle/aar-metadata.properties` declares `minCompileSdk=37` and `minAndroidGradlePluginVersion=9.1.0`. Therefore, do not replace `MaterialExpressiveTheme` / `MotionScheme.expressive()` with a third-party Material library or a custom shim.
 - Do not use Wear Compose Material 3 for a phone/tablet client: the official Material 3 page directs Wear OS applications to Wear Compose Material 3 separately.[10]

Minimal future theme shape (API decision only, not a stage 03 implementation):

```kotlin
MaterialExpressiveTheme(
    colorScheme = colorScheme,
    motionScheme = MotionScheme.expressive(),
    content = content,
)
```

Dynamic color can be used on API 31+; below API 31, use a static fallback. This requirement applies to the Android runtime API, not to `compileSdk`.[11]

## API levels

| Parameter | Selection | Rationale |
|---|---:|---|
| `minSdk` | `24` | The published Material 3 `1.5.0-rc01` AAR declares minSdk 24. This is higher than the general Compose quick-start baseline of API 21; the selected Expressive version uses the stricter minimum.[13] |
| `compileSdk` | `37` | The selected `material3-android:1.5.0-rc01` AAR metadata requires `minCompileSdk=37`; AGP 9.1.1 officially supports API 37.0.[1] |
| `targetSdk` | `36` | Android 16 setup officially shows `compileSdk = 36` and `targetSdk = 36` to enable Android 16 behavior changes.[14] This document separates target 36 from compile 37: the library needs compile 37, while target fixes the verified Android 16 runtime behavior. Before publication, check the current Google Play target API requirement separately; no claim is made here about that deadline. |

`minSdk`, `targetSdk`, and `compileSdk` have different meanings: `minSdk` limits installation, `targetSdk` declares the tested behavior level, and `compileSdk` determines the available compile-time APIs.[15]

## Gradle Wrapper and checksum policy

Future `gradle-wrapper.properties` must contain:

```properties
distributionUrl=https\\://services.gradle.org/distributions/gradle-9.3.1-bin.zip
distributionSha256Sum=b266d5ff6b90eada6dc3b20cb090e3731302e553a27c5d3e4df1f0d76beaff06
```

The SHA-256 value comes from the official Gradle distribution endpoint.[18] The Wrapper JAR must be checked by the official `gradle/actions/wrapper-validation@v6`; the action compares its SHA-256 with the official Gradle wrapper checksum list and must run before any Gradle build.[19] Wrapper files are not created in stage 03.

## Licenses and GPL-3.0 compatibility

### Verified license evidence

- The AndroidX repository, including Compose Material 3, is published under the Apache License 2.0; this is confirmed by the AndroidX license file and the selected artifact's license header/source metadata.[20][16]
- Kotlin and the Kotlin Gradle plugin are distributed under the Apache License 2.0; the upstream Kotlin repository specifies Apache 2 for Kotlin and lists third-party notices separately.[21]
- Compose BOM `compose-bom` and selected Material 3 POM contain `The Apache Software License, Version 2.0`; the stable BOM mapping is `2026.09.00`.[16][17]
- Gradle is distributed under the Apache License 2.0; Android SDK/Build Tools have separate Google SDK license terms and are not GPL dependencies. Accepting them in the local SDK installation is not a reason to change the project's license.

### GPL-3.0 compatibility check

Apache-2.0 is compatible with GPL-3.0 in the direction of an Apache-2.0 dependency in GPL-3.0 work, but not in reverse: the Apache Software Foundation explicitly documents this asymmetry.[22] Therefore, the selected Apache-2.0 direct dependencies are compatible with the project's GPL-3.0 license when Apache notices, LICENSE/NOTICE, and patent terms are retained.

The inspected POM/BOM files for the selected Material 3/Compose stack contain no GPL-2-only, GPL-3-only, or AGPL artifacts; the visible AndroidX/Compose/Kotlin runtime dependencies are Apache-2.0. In particular, the checked POM files for `material3:1.5.0-rc01`, `material3-android:1.5.0-rc01`, and stable `compose-bom:2026.09.00` declare Apache 2.0; this checks the selected stack's metadata and does not replace a full dependency graph audit.

Before the first real build, the following are mandatory:

1. obtain the resolved dependency graph (`runtimeClasspath`, `releaseRuntimeClasspath`, plugin/buildscript dependencies);
2. generate a license report for direct and transitive artifacts, including POM licenses and embedded `META-INF` notices;
3. stop integration when GPL-2-only, unknown, proprietary, or GPL-3.0-incompatible license terms are found;
4. preserve Apache LICENSE/NOTICE attribution in distributed app notices.

This document does not claim that the full graph has already been resolved: the project and Gradle resolution do not yet exist in stage 03, so the full report will be completed at the stage where the project appears.

## Decision constraints

- The Expressive stack uses `1.5.0-rc01` on top of stable Compose BOM `2026.09.00`, so the Material 3 portion is not a production-stable baseline; this is an intentional choice for the verifiable `MaterialExpressiveTheme` and `MotionScheme.expressive()` APIs.
- AGP 9.1.1 with built-in Kotlin and the Compose Compiler plugin were selected for official compatibility. Do not add `org.jetbrains.kotlin.android` on top of AGP 9.1.1.[5][23] The Material 3 RC POM contains runtime `kotlin-stdlib:2.2.20`; Gradle resolution and the actual compiler/runtime graph must be checked by the first build.[16]
- `minSdk=24` and `compileSdk=37` are based on the downloaded AAR metadata; if the Material 3 version changes, verify these values again.
- `targetSdk=36` is not a claim about the current Play deadline; check the official policy separately before release.
- Stage 04 creates only the minimal Gradle project, launcher, wrapper, and CI; domain models, API, and product screens remain for later stages.

## Sources

[1] https://developer.android.com/build/releases/agp-9-1-0-release-notes
[2] https://developer.android.com/build/releases/about-agp
[3] https://developer.android.com/build/jdks
[4] https://docs.gradle.org/9.3.1/userguide/compatibility.html
[5] https://kotlinlang.org/docs/gradle-configure-project.html
[6] https://kotlinlang.org/docs/releases.html
[7] https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler
[8] https://developer.android.com/develop/ui/compose/bom
[9] https://developer.android.com/develop/ui/compose/bom/bom-mapping
[10] https://developer.android.com/jetpack/androidx/releases/compose-material3
[11] https://developer.android.com/reference/kotlin/androidx/compose/material3/MaterialExpressiveTheme.composable
[12] https://developer.android.com/reference/kotlin/androidx/compose/material3/MotionScheme
[13] https://developer.android.com/develop/ui/compose/setup
[14] https://developer.android.com/about/versions/16/setup-sdk
[15] https://developer.android.com/guide/topics/manifest/uses-sdk-element
[16] https://dl.google.com/dl/android/maven2/androidx/compose/material3/material3/1.5.0-rc01/material3-1.5.0-rc01.pom
[17] https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/2026.09.00/compose-bom-2026.09.00.pom
[18] https://services.gradle.org/distributions/gradle-9.3.1-bin.zip.sha256
[19] https://github.com/gradle/actions/blob/main/docs/wrapper-validation.md
[20] https://github.com/androidx/androidx/blob/androidx-main/LICENSE.txt
[21] https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt
[22] https://apache.org/licenses/GPL-compatibility
[23] https://developer.android.com/build/migrate-to-built-in-kotlin
