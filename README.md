# MaterialClass

Native Android client for BilimClass. The code does not inject demo school data: unconfirmed server sources remain typed as `UnsupportedContract`.

## Structure

- `.github/workflows/` - read-only CI for unit tests, lint, debug APK, and connected debug tests.
- `app/src/main/java/org/materialclass/api/` - confirmed API adapter and boundaries for unconfirmed contracts.
- `app/src/main/java/org/materialclass/data/` - domain models and repositories.
- `app/src/main/java/org/materialclass/session/`, `security/`, `cache/` - session, secure storage, and encrypted cache.
- `app/src/main/java/org/materialclass/ui/` - Compose screens and Material 3 Expressive theme.
- `app/src/main/res/` - resources, localization, and backup rules.
- `app/src/test/`, `app/src/androidTest/` - unit and instrumented tests.
- `docs/` - dependencies and release verification.

## UI performance rule

Design every interactive screen against the target device's frame budget before adding visual polish. At 120 Hz the budget is 8.3 ms per frame, so every scrollable or text-input surface must be tested with rapid gestures and focus changes on a real device. Use virtualized lists with stable keys and content types, keep per-frame composition free of resource lookup and avoid allocations in item content. Use standard motion for utilitarian scrolling surfaces; expressive motion requires a measured interaction benefit and a passing jank profile. A change is not ready when the static code looks efficient - it is ready when the measured fast-scroll profile stays within the frame budget and input remains responsive.

## Verification

Release/integration evidence, known limitations, CI status and the separation between synthetic tests and real read-only API observations are recorded in [`docs/release-verification.md`](docs/release-verification.md).

Detailed implementation stages and constraints are described in the internal plan at `internal/docs/design/android-client-plan.md`; that file is not included in Git. The toolchain is pinned in `docs/dependencies.md`; heavy local Android builds are not run, and CI verifies them. Do not perform release signing, publish, or distribute an APK from this checkout without separate authorization.
