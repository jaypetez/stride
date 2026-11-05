# OWASP MASVS v2 — L1 checklist for the Android app

The Stride Android app (solo Garmin MVP, [ADR 0010](../adr/0010-solo-garmin-mvp.md))
holds a long-lived Garmin Personal API key, health data (HR, HRV, sleep, weight)
and optionally an Anthropic key. **MASVS v2 L1** is our baseline (SEC-003). Each
control lists its status — **met**, **planned** or **n/a** — and the evidence
that proves it. Update this table in the same PR that changes a control.

| Control | Requirement (summary) | Status | Evidence |
|---|---|---|---|
| **MASVS-STORAGE-1** | Sensitive data is stored securely | met | Garmin key, cached token and Anthropic key are encrypted with Tink AES-256-GCM; the keyset is wrapped by an Android Keystore master key (`core/datastore/.../SecretCipher.kt`). `SecretStoreTest.keyRoundTripsAndIsEncryptedAtRest` asserts no plaintext on disk. Health data lives in the app-private Room DB. |
| **MASVS-STORAGE-2** | No sensitive data leaks (backups, logs, shared storage) | met | `data_extraction_rules.xml` excludes everything from cloud backup and the secrets + keyset files from device transfer; `backup_rules.xml` excludes all for Android ≤ 11 (`ManifestSecurityTest`). No HTTP logging interceptor; `OkHttpGarminClientTest.keyAndTokenNeverAppearInErrorsLogsOrToString`. Export uses the system file picker (SAF) only on user request. |
| **MASVS-CRYPTO-1** | Strong, current cryptography | met | Tink `AES256_GCM` AEAD; no custom crypto. |
| **MASVS-CRYPTO-2** | Key management per best practice | met | Master key in Android Keystore (`android-keystore://stride_master_key`), never exported; ciphertext bound to its preference name via associated data. |
| **MASVS-AUTH-1** | Secure authentication/authorization protocols | met | Personal API key → 24 h bearer token exchange per Garmin's documented flow (`docs/garmin/personal-api.md`); token re-exchanged on expiry/401. |
| **MASVS-AUTH-2** | Secure local authentication | n/a | No app lock/local auth in the MVP (no accounts). |
| **MASVS-AUTH-3** | Additional auth for sensitive operations | n/a | No server-side sensitive operations; destructive local actions (disconnect, delete all data) require explicit confirmation. |
| **MASVS-NETWORK-1** | Secure network traffic | met | `network_security_config.xml` forbids cleartext; all endpoints are HTTPS (Garmin, Anthropic). `ManifestSecurityTest.cleartextTrafficIsNotPermitted`. |
| **MASVS-NETWORK-2** | Identity pinning for critical endpoints | n/a | Google discourages hard pinning (rotation breaks apps); system trust store only (see `docs/android/best-practices.md`). |
| **MASVS-PLATFORM-1** | IPC used securely | met | Only `MainActivity` is exported (launcher). No content providers, services or broadcast receivers are exported. |
| **MASVS-PLATFORM-2** | WebViews used securely | n/a | No WebView; Garmin Connect opens in Custom Tabs / the browser. |
| **MASVS-PLATFORM-3** | UI used securely | met | API key fields use password masking; no secrets in notifications or screenshots of the connect form beyond the masked field. |
| **MASVS-CODE-1** | Up-to-date platform version | met | targetSdk 36, minSdk 28 (`StrideSdk.kt`). |
| **MASVS-CODE-2** | Mechanism to enforce updates | planned | Obtainium tracks GitHub Releases (REL-008); no forced-update mechanism in a sideloaded MVP. |
| **MASVS-CODE-3** | No known-vulnerable components | met | Dependabot for Gradle (`.github/dependabot.yml`), dependency review and CodeQL in CI. |
| **MASVS-CODE-4** | Untrusted input is validated | met | Garmin JSON parsed leniently with typed failures (`GarminError.Parse`); FIT decoding returns `FitError` on corrupt/partial files (`FitParserTest`). |
| **MASVS-RESILIENCE-1..4** | Anti-tampering / reverse-engineering | n/a | L2/R only; out of scope for L1. R8 minification is enabled for release. |
| **MASVS-PRIVACY-1** | Minimal access to sensitive data | met | Only `INTERNET` and `ACCESS_NETWORK_STATE` permissions; no location, no Health Connect in the MVP. |
| **MASVS-PRIVACY-2** | Prevent identification of the user | met | No analytics, telemetry, crash reporting or accounts. Data stays on the device. |
| **MASVS-PRIVACY-3** | Transparency about data collection | met | Settings → About explains: "your data never leaves this device except to Garmin, and to Anthropic only if you enable the coach" (MVP-021). |
| **MASVS-PRIVACY-4** | User control over their data | met | Disconnect (optionally deleting synced data), export as JSON, and "Delete all data" wiping Room, DataStore, keys and token (MVP-021). |

## How to verify
- `./gradlew -p android :app:testDebugUnitTest :app:testReleaseUnitTest` — manifest, network-security and backup-rule assertions (`ManifestSecurityTest`).
- `./gradlew -p android :core:datastore:testDebugUnitTest` — encrypted-at-rest round trips.
- `./gradlew -p android :core:garmin:test` — no key or token in errors, logs or `toString()`.
- `./gradlew -p android lintDebug` — Android Lint security checks run with warnings as errors.
