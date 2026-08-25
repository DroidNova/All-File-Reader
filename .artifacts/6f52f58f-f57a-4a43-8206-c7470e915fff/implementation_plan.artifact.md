# Implementation Plan - Fix Hilt Metadata Version Mismatch

The project is failing to build because the Kotlin compiler (version 2.4.10) produces metadata version 2.4.0, but the Hilt compiler (currently using version 2.59.2) only supports up to metadata version 2.3.0. This is typically due to an outdated `kotlinx-metadata-jvm` dependency inside Hilt.

## Proposed Changes

### [Component Name] Build Configuration

#### [MODIFY] [libs.versions.toml](file:///D:/Softwares/PlayStoreApps/AllFileReader/gradle/libs.versions.toml)
- Update `hilt` version to `2.60.1` (latest stable).
- Update `ksp` version to `2.3.11` (latest stable for the plugin).

#### [MODIFY] [build.gradle.kts (root)](file:///D:/Softwares/PlayStoreApps/AllFileReader/build.gradle.kts)
- Add a `resolutionStrategy` to force the latest version of `org.jetbrains.kotlinx:kotlinx-metadata-jvm` (0.9.0) across all subprojects. This will ensure that Hilt can read the metadata produced by Kotlin 2.4.10.

## Verification Plan

### Automated Tests
- Run `./gradlew :app:hiltJavaCompileDebug` to verify that the Hilt compilation error is resolved.
- Run a full build: `./gradlew assembleDebug`.

### Manual Verification
- None required beyond successful compilation.
