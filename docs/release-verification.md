# Release verification

Статус: stage 15 audit for commit `b1530684ac2b2b25fba76881f94ba074b2164ade`, updated in the stage commit. This document separates source/static evidence, synthetic tests, CI evidence and real-account read-only observations. It does not authorize push, merge, release signing, publication or distribution.

## Executive result

- Release acceptance is **pending**: no CI run exists for the audited commit, and this stage did not push or dispatch a workflow.
- Local Android build, unit tests, lint and instrumented tests were **not run**. The host has Java and adb but no Android SDK (`ANDROID_HOME` and `ANDROID_SDK_ROOT` are unset, `$HOME/Android/Sdk` is absent). Heavy Android checks are CI-only by project rule.
- The workflow now contains both required CI command lines and uploads Android instrumented-test results as part of the verification reports artifact.
- Real-account read-only testing was attempted through the secret-invisible path. Login returned only `POST /api/v2/os/login`, HTTP `401`, `application/json`; dependent reads were not attempted after login failed. No secret, token, cookie, personal value or response body was printed or saved.
- No evidence-backed product-code fix was required. Unsupported sources remain explicitly unsupported rather than being replaced with invented data.

## CI gate

Workflow: `.github/workflows/android-ci.yml`

Required commands present in the workflow:

```text
./gradlew --no-daemon assembleDebug testDebugUnitTest lintDebug
./gradlew --no-daemon connectedDebugAndroidTest
```

The workflow has read-only repository permissions (`contents: read`), wrapper validation, JDK 17, Gradle setup, verification-report upload and debug APK upload. The second command is a separate step and runs only after the build/unit/lint step succeeds. Reports include `app/build/reports/**`, `app/build/test-results/**` and `app/build/outputs/androidTest-results/**`.

CI evidence for the exact SHA: **none**. `gh run list --repo darkydtm/materialclass --commit b1530684ac2b2b25fba76881f94ba074b2164ade` returned an empty list. The branch is local-only and has not been pushed. The remote branch `chore/android-project-structure` was not found in the read-only `git ls-remote` result. Do not treat local static checks as CI acceptance.

## Screen and transition audit

The prototype inventory in `internal/design/screens.js` contains 21 keys and 21 unique HTML paths:

```text
login-ready, login-loading, login-invalid-credentials, login-2fa-mock,
home-ready, schedule-week-ready, lesson-detail-regular, diary-week-ready,
subject-detail-overview, calculator-complete, settings-ready, profile-ready,
background-work, home-error, home-empty-day, schedule-offline,
calculator-missing-component, settings-language, settings-theme,
settings-notifications, login-connection-error
```

The Compose app has routes for login, home, schedule, lesson detail, grades, attendance, diary, subject detail, calculator, profile, settings, language, theme and notifications. Implemented transitions cover login to home, home to schedule, schedule to lesson detail, diary to subject detail, settings subpages, profile and back navigation. Unconfirmed lesson/subject/grades/attendance/profile data is rendered as an unsupported state where the source contract is not confirmed. Prototype-only `login-loading`, `login-invalid-credentials`, `login-connection-error`, `home-error`, `home-empty-day` and `schedule-offline` are represented by state models/messages rather than 21 separate route names. The prototype `login-2fa-mock` is not presented as a real 2FA flow.

## Data, error, offline and lifecycle audit

Confirmed gateway paths are limited to the documented TLS host and observed/read-only web paths:

- `POST /api/v2/os/login` (secret-invisible login establishment only).
- `GET /api/v4/os/clientoffice/diary/weeks`.
- `GET /api/v4/os/clientoffice/diary`.
- `GET /api/v4/os/clientoffice/diary/periods`.
- `GET /api/v4/os/clientoffice/diary/subjects`.
- `GET /api/v4/os/clientoffice/schedule`.

The implementation keeps home, dedicated lesson detail, lesson grades, attendance, subject detail, profile/context, notifications sources, refresh, 2FA and server logout as `UnsupportedContract` or local-only behavior. APK strings and third-party/web source are not treated as server-contract proof.

Synthetic source evidence present in `app/src/test/` covers:

- HTTP/TLS/error mapping, JSON shape rejection, empty-vs-error and unsupported routes without transport calls.
- Cache preservation after failed refresh, atomic slice updates, timeout/error events and stale-generation write rejection.
- Encrypted token/cache lifecycle, tamper/missing-key cleanup, account isolation, expiration cleanup and no server logout.
- Grade calculator thresholds, weighted components and missing categories.
- Notification default-off settings, six-hour policy, baseline/deduplication, permission denial, no session/token, unsupported source, generation changes and logout/expiration cancellation.
- UI route stack and unsupported/empty/error state models.

The repository exposes retry events (`RepositoryEvent.RetryAvailable`) and the UI renders retry actions for offline, stale and error states. A failed refresh retains a prior snapshot in synthetic tests. No fake data is used for unsupported or cache-missing states.

## UI, localization and accessibility source audit

- RU/KK/EN `strings.xml` sets have equal key counts: 44 each; the static parity check found no missing keys.
- XML parse check passed for the manifest and 10 resource XML files.
- Light, dark, system and black theme modes are present. Black mode sets background and primary surface colors to `#000000` and foreground colors to white in `Theme.kt`.
- `MaterialExpressiveTheme` and `MotionScheme.expressive()` are used in the theme source. Runtime application in a built APK remains CI/device evidence pending.
- Compose semantics include button, switch, text-field and tab controls through the Material components and explicit tab roles. The source has no dedicated reduced-motion branch or runtime font-scale/accessibility/device evidence. 200% font scale, contrast on every screen, focus traversal and reduced-motion behavior remain pending CI/device verification.
- No screenshot, emulator, or packaged-APK inspection was possible in this stage.

## Manifest, backup, dependency and privacy audit

Static source checks passed:

- `android:usesCleartextTraffic="false"`.
- `android:allowBackup="false"` with namespace-qualified `dataExtractionRules` and `fullBackupContent` attributes on the sole application element.
- Cloud/device-transfer rules exclude file, database, sharedpref, external and root domains.
- Only `POST_NOTIFICATIONS` is declared. No INTERNET permission is declared in the manifest; network transport integration therefore remains a source contract/test boundary until the runtime build configuration is reviewed in CI.
- No analytics, tracker, Firebase, GMS/Play Services, Crashlytics, Sentry, Segment, Mixpanel or Amplitude references were found in tracked non-internal source/configuration. WorkManager is used for local scheduling only.
- No secret-looking token/private-key payload was found by the tracked-source scan. Synthetic secret names and values occur only in tests; they are not real credentials. `internal/` is ignored and was not added to the commit.
- `LICENSE` is tracked. A complete resolved dependency/license tree and packaged APK license audit remain pending the first CI build; `docs/dependencies.md` explicitly requires that graph audit.

## Real API observations

The permitted secret-invisible read-only attempt used only the documented TLS host and login endpoint. Safe output was reduced to method/path/status/content type and structural evidence.

| Evidence level | Method/path | Result | Safe structural observation |
|---|---|---|---|
| Confirmed in this stage | `POST /api/v2/os/login` | HTTP `401`, `application/json` | No login response schema was accepted; dependent reads stopped. |
| Existing repository evidence | `GET /api/v4/os/clientoffice/diary/weeks` | Previously documented HTTP `200`, JSON in `internal/docs/design/android-client-contracts.md` | `data` object with `week`, `weeks`, `periods` in one prior observation. |
| Existing repository evidence | `GET /api/v4/os/clientoffice/diary/periods` | Previously documented HTTP `200`, JSON | `data.periods` array in one prior observation. |
| Existing repository evidence | `GET /api/v4/os/clientoffice/diary` | Previously documented HTTP `200`, JSON | `data.date` and `data.days` in one prior observation. |
| Existing repository evidence | `GET /api/v4/os/clientoffice/schedule` | Previously documented HTTP `200`, JSON | `data.days` array in one prior observation. |
| Existing repository evidence | `GET /api/v4/os/clientoffice/diary/subjects` | Previously documented HTTP `200`, JSON | `data` array in one prior observation. |
| Existing repository evidence | `GET /api/mobile/diary` | Previously documented HTTP `422`, JSON | Root `errors` key; complete required parameter contract unknown. |

The stage login failure does not prove credentials, account role, service availability or a universal error schema. It only blocks this session's dependent reads. No retry, refresh, 2FA, logout, mutation, endpoint brute force, alternate host or guessed parameter was used.

## Commands and results

Read-only/static commands run in this stage:

- `git status --short --branch`, `git log`, `git diff`, `git ls-files`, `git ls-remote`: completed; working branch was clean at start; no remote stage branch was present.
- Prototype/source inventory scripts: completed; 21 prototype screen keys/paths found.
- XML parse and localization parity scripts: passed; 11 XML files parsed, 44 string keys in each RU/KK/EN/default set, no key differences.
- Tracker/runtime dependency scan: passed with zero matches.
- Manifest/TLS/backup/Expressive source assertions: passed.
- `git diff --check`: passed before this document update.
- `gh auth status`: authenticated as the configured GitHub account; no workflow was triggered.
- Local Android commands were intentionally not run due to absent SDK and CI-first policy.

## Remaining blockers

1. Push-free CI evidence is unavailable. A permitted push or workflow dispatch is required before claiming the exact-SHA CI gate; this stage intentionally did neither.
2. The debug APK, resolved dependency graph, license report, packaged manifest/resource audit and artifact-to-SHA checksum evidence require CI.
3. `connectedDebugAndroidTest` requires a CI emulator/device; its exact test execution and zero-skip result are unverified.
4. 200% font scale, contrast, focus/accessibility semantics, black/dark/light runtime themes, Expressive motion and reduced-motion behavior need built-app/device evidence. Reduced-motion handling has no dedicated source branch yet and is not claimed.
5. Current live login returned 401, so no new real-account evidence exists for home, lesson detail, grades/attendance, profile/context or notification sources. These contracts remain blocked; no code was expanded from the failed attempt.
6. The API gateway currently has no runtime HTTP client in the Android app; the implementation is a typed contract/test boundary, not a claim of a production-connected client.
7. Full dependency/license tree verification remains pending CI resolution.

No release readiness claim is made while these blockers remain.
