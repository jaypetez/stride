# Stride for Android

The native Android app (Kotlin + Jetpack Compose). For the solo Garmin MVP it is
**local-only**: each user connects their own Garmin Personal API key and every
metric is computed on the device by `core:science`
([ADR 0010](../docs/adr/0010-solo-garmin-mvp.md), [ADR 0007](../docs/adr/0007-android-architecture.md)).

This is a standalone Gradle build — not part of the pnpm workspace.

## Requirements
- JDK 21 (`JAVA_HOME`), Android SDK with platform `android-37.0` (`ANDROID_HOME` or `local.properties`).
- No secrets. Every test runs offline.

## Commands
| Command | What it does |
|---|---|
| `pnpm check:android` | The Android gate: `./gradlew -p android check` (skips if `android/` is absent). |
| `./gradlew -p android assembleDebug` | Debug APK in `app/build/outputs/apk/debug/`. |
| `./gradlew -p android testDebugUnitTest` | JVM + Robolectric unit tests. |
| `./gradlew -p android :core:science:test` | Pure-Kotlin science parity tests against `contracts/science-golden/`. |
| `./gradlew -p android verifyRoborazziDebug` | Screenshot tests against the committed baselines. |
| `./gradlew -p android recordRoborazziDebug` | Re-record baselines (read [screenshots.md](../docs/testing/screenshots.md) first). |
| `./gradlew -p android detekt spotlessCheck lintDebug` | Static analysis (Detekt + Compose rules, ktlint, Android Lint). |
| `./gradlew -p android checkHardcodedText` | Fails on user-facing text hard-coded in Kotlin UI code; strings belong in `res/values/strings.xml` (AUD-034). |
| `./gradlew -p android spotlessApply` | Auto-format Kotlin and `.kts`. |
| `./gradlew -p android koverHtmlReport` | Aggregated coverage report (threshold: 60% lines, see root `build.gradle.kts`). |

`check` runs unit tests, Android Lint, Detekt, ktlint, `checkHardcodedText` and `koverVerify`. CI adds
`-Proborazzi.test.verify=true` so the same test run also verifies screenshots.

## Modules
```
app                     shell: Navigation 3 + adaptive navigation suite, Hilt root, manifest
core:model              domain models (pure Kotlin)
core:common             dispatchers, application scope, clock, results (pure Kotlin + Hilt)
core:science            Kotlin port of packages/core science (pure Kotlin, parity-tested)
core:navigation         route keys + StrideNavigator (the "api" half of each feature)
core:designsystem       StrideTheme, colours, typography, reusable components
core:ui                 shared Compose helpers: insets, window size classes, formatting
core:testing            MainDispatcherRule, HiltTestRunner, fake clock, screenshot qualifiers
feature:*               screens; each contributes EntryProviderInstallers via Hilt @IntoSet
```
Convention plugins live in `build-logic/convention` (`stride.android.application`,
`stride.android.library(.compose)`, `stride.android.feature`, `stride.android.hilt`,
`stride.android.room`, `stride.android.roborazzi`, `stride.jvm.library`). SDK levels
(compile 37, target 36, min 28) are set once in `StrideSdk.kt`; every version lives in
`gradle/libs.versions.toml`.

## Navigation
Each feature contributes `EntryProviderInstaller`s (`EntryProviderScope<NavKey>.() -> Unit`)
with `@Provides @IntoSet` in an `ActivityRetainedComponent` module. The shell
(`app/.../ui/StrideApp.kt`) keeps one saveable back stack per top-level
destination, so tabs keep their state, and back stacks survive process death.
Screens navigate with `LocalNavigator.current.navigate(SomeRoute)`.

## Debug-only tools
Debug builds install **StrictMode** (thread + VM policies, `penaltyLog`) from
`app/src/debug/.../DebugTools.kt` and include **LeakCanary** as a
`debugImplementation`. Release builds use a no-op `DebugTools` and have neither:
`./gradlew -p android :app:dependencies --configuration releaseRuntimeClasspath | grep -i leakcanary` prints nothing.

## Running on a Raspberry Pi / arm64 Linux
Google ships `aapt2` only for x86_64 Linux. On arm64 hosts install
`qemu-user-static` plus `libc6-amd64-cross`/`libstdc++6-amd64-cross` and link the
x86_64 loader (`/lib64/ld-linux-x86-64.so.2`); builds then work, just slower.
Keep `org.gradle.workers.max=2` in `~/.gradle/gradle.properties` on 8 GB machines.
