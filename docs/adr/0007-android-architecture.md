# 0007. Native Android app architecture

- Status: Accepted
- Date: 2026-11-01

## Context
Stride Groups ships as a **native Android app** for the best performance and
platform fit. A future iOS app would be native Swift, so cross-platform
frameworks (Flutter, React Native, KMP UI) bring no benefit here. The app is
distributed as a signed APK for now. It is still built to Google Play policy
so that publishing later is cheap. See `docs/android/best-practices.md` for
the research and sources.

## Decision
- **Language and UI:** Kotlin and Jetpack Compose with Material 3. Expressive
  components are used only where they are stable.
- **Architecture:** Google's *Now in Android* reference architecture:
  - unidirectional data flow, with ViewModels exposing `StateFlow` UI state
  - offline-first repositories with **Room** as the source of truth
  - **WorkManager** sync
  - **DataStore + Tink** for secrets (EncryptedSharedPreferences is deprecated)
- **Libraries:**
  - Hilt for dependency injection
  - Navigation 3 with modular entry providers
  - Retrofit, OkHttp and kotlinx.serialization (OkHttp WebSocket for chat)
  - Coil 3 for images and Vico for charts
  - Credential Manager for Sign in with Google
  - the Health Connect client
- **Build:**
  - Gradle version catalog and `build-logic` convention plugins
  - compileSdk 37, targetSdk 36, minSdk 28
  - R8, Baseline Profiles and Macrobenchmark
  - edge-to-edge, predictive back, and adaptive layouts (required at targetSdk 36)
- **Modules:**
  - `app`
  - `core:{model,common,network,database,datastore,data,designsystem,ui,testing}`
  - `feature:*`
  - `benchmarks`
- **Project layout:** `android/` is a standalone Gradle build. It is not part
  of the pnpm workspace. `pnpm check:android` wraps it.
- **Contract with the backend:** Zod schemas export JSON Schema and golden
  fixtures to `contracts/`. Android tests deserialize them, and CI fails on
  stale fixtures.
- **Tests that run on every PR without an emulator:**
  - JUnit, Turbine and coroutines-test
  - Robolectric Compose UI tests
  - **Roborazzi** screenshot tests
  - Android Lint, Detekt, ktlint and Kover
- **End-to-end tests:** **Maestro** flows run nightly on an emulator against
  a seeded local backend.
- **Security:** follows the OWASP MASVS L1 checklist.

## Consequences
- The sports-science math lives in TypeScript (`packages/core`) and is
  computed **server-side**. The app renders numbers and never recomputes
  them, which upholds compute-in-code.
- The CI and agent environments need JDK 21 and the Android SDK
  (`scripts/factory/setup-cloud-env.sh`).
- Versions in this ADR are snapshots. Check Google Maven release notes before
  pinning anything.
