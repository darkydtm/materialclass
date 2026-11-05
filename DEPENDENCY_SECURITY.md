# Dependency and build integrity notes

The Gradle 9.5.0 binary distribution checksum and wrapper JAR checksum are
pinned against the [Gradle release checksum reference](https://gradle.org/release-checksums/).
The CI wrapper check runs before Gradle; update both pins and tests after
independently checking a new Gradle release. The check does not authenticate
changes to the workflow itself, the wrapper scripts, or other dependencies.

No `gradle/verification-metadata.xml` or dependency lockfiles are currently
committed. Dependency checksums and signatures are therefore not enforced by
this repository. Generate a complete candidate for every build, plugin, test,
and variant configuration in an isolated CI environment, then independently
review artifact origins, checksums and signing keys before enforcement.
[Gradle warns](https://docs.gradle.org/current/userguide/dependency_verification.html)
that bootstrapping trusts whatever the repository currently serves. Do not
commit a partial metadata file as if it covered the full build.

The direct declarations are AndroidX Compose BOM, Material3, Activity Compose,
JUnit, AndroidX JUnit and AndroidX Test Runner; the build also uses the Android
Gradle plugin and Kotlin plugins. They pull in transitive dependencies: see the
`Report resolved dependency graphs` step in Android CI. A version catalog is
not a complete resolved inventory. No complete third-party license inventory or
attribution bundle was found in the repository. Before distribution, inventory
all shipped artifacts and their license/NOTICE files, separately assess test
and build-only dependencies, and review actual obligations; this note is not a
legal compliance determination.

The main manifest grants INTERNET and disables cleartext traffic. Source code
contains HTTPS requests to `api.bilimclass.kz` and uses `www.bilimclass.kz` as
Origin and Referer. This source inspection and the selected CI dependency
reports are not a proof of every possible SDK, endpoint, or runtime connection;
check merged manifests, packaged libraries and observed traffic separately.
