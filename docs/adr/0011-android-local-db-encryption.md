# 0011. Android local database: unencrypted at rest, kept on the device

- Status: Accepted
- Date: 2026-11-05

## Context

The Android app ([ADR 0010](0010-solo-garmin-mvp.md)) keeps its Room database
(`stride.db`, and `stride-demo.db` in demo mode) in the app's private storage.
It holds synced runs (summaries, laps, HR/pace streams), daily wellness,
computed metrics, and the PAR-Q "health check" flag. The athlete profile
(name, birth year, HR anchors) sits in a Preferences DataStore next to it.

Secrets are already handled separately: the Garmin key, its cached token and
the optional Anthropic key are encrypted with a Tink AEAD whose master key
lives in the Android Keystore (MVP-004). They are excluded from cloud backup
and from device-to-device transfer.

The audit (AUD-041) found two gaps around the rest of the data:

- The database is stored unencrypted, and `data_extraction_rules.xml` let it
  travel in a device-to-device transfer.
- `AndroidManifest.xml` set `android:allowBackup="true"`, although every
  cloud-backup domain was excluded, so the flag said the opposite of what the
  app does.

Options considered:

1. **SQLCipher for Room.** Encrypts the database file with a key wrapped by the
   Keystore. It adds about 7 MB of native libraries per ABI to a sideloaded
   APK, makes every query slower (page-level AES), needs a one-off migration of
   existing plaintext databases, and makes Room schema testing (Robolectric,
   `MigrationTestHelper`) harder. It also ties the database to the Keystore, so
   a lost key loses the data.
2. **Accept the risk and keep the data on the device.** Rely on Android's
   app sandbox and file-based encryption, and stop the data from leaving the
   device through backup or transfer.

## Decision

Accept the risk of an unencrypted database (option 2), and make sure it never
leaves the device by Android's backup paths:

- `android:allowBackup="false"` in the manifest.
- Cloud backup excludes every domain (unchanged), and **device-to-device
  transfer now excludes the `database` domain** in addition to the secrets.
  A new phone starts without runs and re-syncs them from Garmin.
- `ManifestSecurityTest` asserts both, on the source resources and on the
  merged release manifest, so a regression fails `./gradlew -p android check`.

Rationale:

- Since Android 10, app-private storage is protected by file-based encryption
  tied to the user's lock screen, and the sandbox stops other apps from reading
  it. SQLCipher would mainly add protection on rooted or compromised devices,
  where a running app's Keystore key is reachable anyway.
- The data is training data the user already holds in Garmin Connect, and
  Garmin remains the source of truth: losing the local copy costs a re-sync,
  not data.
- The cost of SQLCipher (APK size, query speed, migration, harder tests) is
  high for a small, offline-first, sideloaded app (ADR 0010).

## Consequences

- Anyone with root access, or a forensic image of an unlocked device, can read
  runs, wellness and the PAR-Q flag. This is the accepted residual risk and is
  recorded on the MASVS L1 checklist (SEC-003) as MASVS-STORAGE-1 "accepted".
- A device-to-device transfer carries non-secret preferences (onboarding done,
  profile, units) but no database and no secrets, so the user reconnects Garmin
  and runs re-sync. If they were in demo mode, the demo database is recreated
  empty until they leave and re-enter demo mode.
- Revisit this ADR if the app starts storing data that Garmin does not hold
  (for example free-text notes or medical history), if it ships on the Play
  Store to a wider audience, or if Room gains first-party encryption support.
  Moving to SQLCipher later means a new ADR that supersedes this one and a
  plaintext-to-encrypted migration.
