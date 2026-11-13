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

## Verification

Release/integration evidence, known limitations, CI status and the separation between synthetic tests and real read-only API observations are recorded in [`docs/release-verification.md`](docs/release-verification.md).

Detailed implementation stages and constraints are described in the internal plan at `internal/docs/design/android-client-plan.md`; that file is not included in Git. The toolchain is pinned in `docs/dependencies.md`; heavy local Android builds are not run, and CI verifies them. Do not perform release signing, publish, or distribute an APK from this checkout without separate authorization.
