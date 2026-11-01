# Android best practices for Stride Groups (research digest, 2026-11)

This is the research behind [ADR 0007](../adr/0007-android-architecture.md).
The versions are **snapshots**. Confirm them on the Google Maven release pages
before pinning, and re-check this page when you bump the toolchain.

## Toolchain
| Item | Recommendation | Source |
|---|---|---|
| Language | Kotlin 2.3.x, with the Compose Compiler Gradle plugin matched to it | https://developer.android.com/develop/ui/compose/compiler |
| Build | AGP 9.x, Gradle 9.x, version catalog, `build-logic` convention plugins | (Now in Android pattern) |
| SDK levels | compileSdk 37 (Compose 1.12+ needs it), targetSdk 36, minSdk 28 | https://developer.android.com/google/play/requirements/target-sdk |
| UI | Compose BOM 2026.08+, Material 3 1.4 stable. Expressive APIs may still be `@Experimental…` | https://developer.android.com/jetpack/androidx/releases/compose-material3 |
| Navigation | Navigation 3 (stable since Nov 2025), with the modular Hilt recipe | https://developer.android.com/guide/navigation/navigation-3/recipes/modular-hilt |
| DI | Hilt. Metro is promising but not yet mainstream | https://developer.android.com/training/dependency-injection/hilt-android |

## Architecture (Now in Android)
- Unidirectional data flow. ViewModels expose `StateFlow<UiState>`, and screens are stateless composables.
- Offline-first: Room is the single source of truth, and the network updates Room through a WorkManager `Synchronizer`.
- DataStore for preferences. Secrets go in DataStore encrypted with **Tink** AEAD, with the key in Android Keystore. `security-crypto` / EncryptedSharedPreferences is **deprecated**. (https://proandroiddev.com/goodbye-encryptedsharedpreferences-a-2026-migration-guide-4b819b4a537a)
- Libraries:
  - Networking: Retrofit, OkHttp and kotlinx.serialization, with OkHttp WebSocket for chat.
  - Images and charts: Coil 3 for images, Vico for charts.

## Platform behaviour at targetSdk 36
- Edge-to-edge is enforced, and the opt-out flag no longer works.
- Predictive back animations are on by default.
- On sw600dp+ screens (tablets and foldables), orientation and resizability restrictions are ignored. Build adaptive layouts with window size classes and list-detail.
- (https://developer.android.com/about/versions/16/behavior-changes-16)

## Performance
- R8 (full mode is the default), Baseline Profiles, a Macrobenchmark module, and Compose strong skipping (on by default).
- Native code, including code pulled in by dependencies, must support the **16 KB page size**. (https://developer.android.com/guide/practices/page-sizes)

## Testing (offline, on every PR)
| Layer | Tool | Notes |
|---|---|---|
| Unit | JUnit, Turbine, kotlinx-coroutines-test | ViewModels, repositories |
| UI | Robolectric and Compose UI test in `src/test` | Runs on the JVM, so no emulator is needed |
| Screenshot | **Roborazzi** | Robolectric-based, supports interactions and Hilt; Now in Android uses it |
| Network | MockWebServer | Fakes in `core:testing` |
| Static | Android Lint, Detekt, ktlint | |
| Coverage | Kover | Threshold enforced in CI |
| E2E | **Maestro** on an emulator (KVM) | Runs nightly or on the `e2e` label, against `wrangler dev` with seeded data |

We chose Roborazzi over Paparazzi, which can't interact with UI or run alongside Robolectric, and over Compose Preview Screenshot Testing, which is preview-only and less mature. (https://proandroiddev.com/screenshot-testing-in-compose-f8a7389a7e6)

## Health Connect
- Garmin Connect writes the following to Health Connect:
  - exercise sessions
  - heart rate
  - distance and speed
  - elevation
  - cadence
- Garmin does not write training load, HRV or power. (https://www.tomsguide.com/wellness/fitness-trackers/garmin-clarifies-what-data-its-sharing-with-google-health-connect-heres-the-full-list)
- Strava writes time, distance and calories. (https://support.strava.com/en-us/articles/15401554-health-connect-and-strava)
- Permissions are granted per data type:
  - By default an app can read **30 days** of history before the first grant. Reading further back needs `READ_HEALTH_DATA_HISTORY`.
  - Background reads need `READ_HEALTH_DATA_IN_BACKGROUND`.
  - Routes go through `ExerciseRouteRequestContract`.
  - (https://developer.android.com/health-and-fitness/guides/health-connect/develop/read-data)

## Security
- Use **Credential Manager** for Sign in with Google and passkeys. Legacy `play-services-auth` sign-in is deprecated. (https://developer.android.com/identity/sign-in/legacy-gsi-migration)
- Use Network Security Config for TLS. Google discourages hard certificate pinning because cert rotation can break the app.
- Use the OWASP **MASVS** L1 checklist with MASTG test cases.

## Google Play readiness (we are not publishing yet; build to this anyway)
- **Target API 36** is required for new apps and updates since 2026-08-31.
- **Account deletion:** provide it **in-app** and through a **web URL**, and actually delete server-side data. (https://support.google.com/googleplay/android-developer/answer/13327111)
- **User-generated content:** require ToS acceptance before posting, let users report and block both users and content, and moderate. (https://support.google.com/googleplay/android-developer/answer/12923286)
- **Health data:** complete the Health apps declaration, publish a privacy policy that is reachable in-app and on the listing, and request only the Health Connect data types you need. (https://developer.android.com/health-and-fitness/health-connect/publish)
- **Photos:** use the system **Photo Picker** and declare no `READ_MEDIA_*` permission. (https://support.google.com/googleplay/android-developer/answer/14115180)
- **Personal developer account:** before production, run a closed test with **12 testers for 14 days**. (https://support.google.com/googleplay/android-developer/answer/14151465)
