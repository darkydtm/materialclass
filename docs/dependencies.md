# Android dependencies and toolchain

Статус: решение этапа 03, применено на этапе 04. Документ фиксирует проверенный набор для минимального Android-клиента.

Проверка источников выполнена 12 ноября 2026 года. Версии ниже зафиксированы явно: динамические версии (`+`, `latest`) запрещены.

## Выбранный набор

| Слой | Выбор | Обоснование и совместимость |
|---|---|---|
| Android Gradle Plugin | `com.android.application:9.1.1` | Официальная таблица AGP 9.1.1: максимум API 37.0, Gradle 9.3.1, JDK 17; встроенная KGP-версия по таблице — 2.2.10.[1] |
| Gradle Wrapper | `9.3.1` | Это минимальная версия, указанная официально для AGP 9.1.1.[1] SHA-256 для `gradle-9.3.1-bin.zip`: `b266d5ff6b90eada6dc3b20cb090e3731302e553a27c5d3e4df1f0d76beaff06`.[18] |
| JDK | `17` | AGP 9.1.1 требует JDK 17; Gradle 9.3.1 также поддерживает запуск на Java 17.[1][4] Android Studio следует запускать на поставляемом JBR, а Gradle в IDE и терминале — на одном и том же JDK.[3] |
| Kotlin / KGP | встроенный Kotlin AGP 9.1.1; language/compiler level `2.2.10` | AGP 9.1.1 официально указывает KGP 2.2.10.[1] AGP 9.0+ включает built-in Kotlin и заменяет `org.jetbrains.kotlin.android`; этот plugin не добавляется повторно.[23] Для Compose Compiler plugin применяется `org.jetbrains.kotlin.plugin.compose` той же версии Kotlin.[7] KGP compatibility table дополнительно подтверждает, что ветка 2.2.0–2.2.10 не поддерживает AGP 9.1; поэтому built-in Kotlin AGP 9.1.1 — обязательный путь для этого набора.[5] |
| Compose Compiler | `org.jetbrains.kotlin.plugin.compose:2.2.10` | После Kotlin 2.0 Compose Compiler больше не выбирается отдельной версией: используется Kotlin Compose Compiler Gradle plugin с версией Kotlin.[7][8] |
| Compose BOM | `androidx.compose:compose-bom:2026.09.00` | Stable BOM metadata maps `material3:1.4.0`; the official Compose mapping confirms that mapping.[8][9] It is the reproducible stable baseline for Compose core. Since this stage requires Material 3 Expressive, the selected `material3:1.5.0-rc01` is pinned explicitly above the stable BOM and must be treated as an RC override. Do not claim that the stable BOM itself supplies Material 3 1.5.0-rc01. |
| Compose core alignment | `androidx.compose.ui:ui:1.12.0`, `androidx.compose.runtime:runtime:1.12.0` under the explicit Material 3 RC graph | The Material 3 `1.5.0-rc01` POM resolves its runtime Compose dependencies to `1.12.0`; the stable BOM remains the baseline for ordinary Compose coordinates, but the RC Material 3 artifact controls its published compatible graph.[16] |
| Material 3 | `androidx.compose.material3:material3:1.5.0-rc01` | Версия опубликована в Google Maven и имеет официальный release note как RC.[10][16] Она выбрана явно поверх BOM для доступа к Expressive API. |
| Adaptive navigation | `androidx.compose.material3:material3-adaptive-navigation-suite:1.5.0-rc01` | Версия присутствует в официальном примере Material 3 release page для ветки 1.5.[10] Добавлять только если экрану действительно нужен adaptive navigation. |
| Window size classes | `androidx.compose.material3:material3-window-size-class:1.5.0-rc01` | Совместимая с выбранной Material 3 веткой версия; не добавлять транзитивно «на всякий случай».[10] |
| Activity integration | `androidx.activity:activity-compose:1.13.0` | Версия приведена в официальной инструкции Compose для BOM 2026.09.00.[7] При обновлении BOM проверить mapping и release notes. |
| Lifecycle ViewModel Compose | `androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0` | Версия приведена в официальной инструкции Compose для BOM 2026.09.00; это optional dependency, не обязательная часть toolchain.[7] |

### Почему не стабильный Material 3 `1.4.0`

Стабильная `androidx.compose.material3:material3:1.4.0` входит в stable Compose BOM 2026.09.00, но официальные release notes указывают, что public APIs, отмеченные `ExperimentalMaterial3ExpressiveApi`, были удалены из beta-линии 1.4; для Expressive API следует использовать 1.5.0-alpha/RC.[10] Поэтому baseline для этого проекта — 1.5.0-rc01 с явно принятой RC-оговоркой, а не стабильная 1.4.0 без требуемого API.

## Material 3 Expressive: проверка API

Выбранный artifact действительно содержит требуемую API-поверхность:

- `MaterialExpressiveTheme(colorScheme, motionScheme, shapes, typography, content)` присутствует в официальном Android API reference.[11]
- `MotionScheme` документирует companion-функции `MotionScheme.standard()` и `MotionScheme.expressive()`; `expressive()` предназначена для prominent UI и hero interactions.[12]
- Официальная Material 3 Compose documentation описывает M3 Expressive как расширение Material 3 с обновлениями theming, components, motion, typography и shapes.[13]
- Для выбранного `material3-android:1.5.0-rc01` локально проверен опубликованный AAR: `classes.jar` присутствует, `META-INF/.../LICENSE.txt` присутствует, `AndroidManifest.xml` объявляет `android:minSdkVersion="24"`, а `META-INF/com/android/build/gradle/aar-metadata.properties` объявляет `minCompileSdk=37` и `minAndroidGradlePluginVersion=9.1.0`. Таким образом, `MaterialExpressiveTheme` / `MotionScheme.expressive()` не следует подменять сторонней Material-библиотекой или самописным shim.
- Не использовать Wear Compose Material 3 для phone/tablet клиента: официальная страница Material 3 отдельно направляет Wear OS приложения к Wear Compose Material 3.[10]

Минимальная будущая схема темы (только как API-решение, не реализация этапа 03):

```kotlin
MaterialExpressiveTheme(
    colorScheme = colorScheme,
    motionScheme = MotionScheme.expressive(),
    content = content,
)
```

На API 31+ можно использовать dynamic color; ниже API 31 нужен статический fallback. Это требование относится к runtime Android API, а не к `compileSdk`.[11]

## API levels

| Параметр | Выбор | Обоснование |
|---|---:|---|
| `minSdk` | `24` | Опубликованный AAR Material 3 `1.5.0-rc01` объявляет minSdk 24. Это выше общего Compose quick-start baseline API 21; для выбранной Expressive версии используется более строгий минимум.[13] |
| `compileSdk` | `37` | AAR metadata выбранного `material3-android:1.5.0-rc01` требует `minCompileSdk=37`; AGP 9.1.1 официально поддерживает API 37.0.[1] |
| `targetSdk` | `36` | Android 16 setup официально показывает `compileSdk = 36` и `targetSdk = 36` для включения Android 16 behavior changes.[14] В этом документе target 36 отделён от compile 37: compile нужен библиотеке, target фиксирует проверенный Android 16 runtime behavior. Перед публикацией нужно отдельно сверить актуальное Google Play target-API требование; оно здесь не выдумывается. |

`minSdk`, `targetSdk` и `compileSdk` имеют разные смыслы: `minSdk` ограничивает установку, `targetSdk` сообщает протестированный уровень поведения, а `compileSdk` определяет доступные compile-time API.[15]

## Gradle Wrapper and checksum policy

Будущий `gradle-wrapper.properties` должен содержать:

```properties
distributionUrl=https\\://services.gradle.org/distributions/gradle-9.3.1-bin.zip
distributionSha256Sum=b266d5ff6b90eada6dc3b20cb090e3731302e553a27c5d3e4df1f0d76beaff06
```

SHA-256 взят из официального Gradle distribution endpoint.[18] Wrapper JAR должен проверяться официальным `gradle/actions/wrapper-validation@v6`; action сверяет SHA-256 со списком официальных Gradle wrapper checksums и должен выполняться до любого Gradle build.[19] На этапе 03 wrapper-файлы не создаются.

## Licenses and GPL-3.0 compatibility

### Проверенные license evidence

- AndroidX repository, включая Compose Material 3, публикуется под Apache License 2.0; это подтверждается license file AndroidX и license header/source metadata выбранного artifact.[20][16]
- Kotlin и Kotlin Gradle plugin распространяются под Apache License 2.0; upstream Kotlin repository указывает Apache 2 для Kotlin и отдельно перечисляет third-party notices.[21]
- Compose BOM `compose-bom` and selected Material 3 POM contain `The Apache Software License, Version 2.0`; the stable BOM mapping is `2026.09.00`.[16][17]
- Gradle распространяется под Apache License 2.0; Android SDK/Build Tools имеют отдельные Google SDK license terms и не являются GPL-зависимостями. Их принятие в локальном SDK installation — не основание менять license проекта.

### Проверка совместимости с GPL-3.0

Apache-2.0 совместима с GPL-3.0 в направлении «Apache-2.0 dependency в GPL-3.0 work», но не наоборот: Apache Software Foundation прямо фиксирует эту асимметрию.[22] Следовательно, выбранные Apache-2.0 прямые зависимости совместимы с GPL-3.0 license проекта при сохранении Apache notices, LICENSE/NOTICE и patent terms.

В inspected POM/BOM для выбранного Material 3/Compose набора не обнаружены GPL-2-only, GPL-3-only или AGPL artifacts; видимые AndroidX/Compose/Kotlin runtime dependencies — Apache-2.0. В частности, проверенные POM `material3:1.5.0-rc01`, `material3-android:1.5.0-rc01` и stable `compose-bom:2026.09.00` объявляют Apache 2.0; это проверка metadata выбранного набора, а не замена полному dependency graph audit.

Перед первым реальным build обязательны:

1. получить resolved dependency graph (`runtimeClasspath`, `releaseRuntimeClasspath`, plugin/buildscript dependencies);
2. собрать license report для прямых и транзитивных artifacts, включая POM licenses и embedded `META-INF` notices;
3. остановить интеграцию при GPL-2-only, unknown, proprietary или license terms, несовместимых с GPL-3.0;
4. сохранить Apache LICENSE/NOTICE attribution в distributed app notices.

Этот документ не утверждает, что полный graph уже разрешён: проекта и Gradle resolution на этапе 03 ещё нет, поэтому полный report будет выполнен на этапе, где появится проект.

## Ограничения решения

- Набор Expressive использует `1.5.0-rc01` поверх stable Compose BOM `2026.09.00`, поэтому Material 3 часть не является production-stable baseline; это осознанный выбор ради проверяемых `MaterialExpressiveTheme` и `MotionScheme.expressive()`.
- AGP 9.1.1 с встроенным Kotlin и Compose Compiler plugin выбраны по официальной совместимости. Нельзя добавлять `org.jetbrains.kotlin.android` поверх AGP 9.1.1.[5][23] POM Material 3 RC содержит runtime `kotlin-stdlib:2.2.20`; Gradle resolution и фактический compiler/runtime graph должны быть проверены первым build.[16]
- `minSdk=24` и `compileSdk=37` основаны на реально скачанном AAR metadata; если версия Material 3 изменится, эти значения нужно проверить заново.
- `targetSdk=36` не является утверждением о текущем Play deadline; перед релизом нужна отдельная проверка официальной policy.
- Этап 04 создаёт только минимальный Gradle project, launcher, wrapper и CI; domain models, API и product screens остаются для последующих этапов.

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
