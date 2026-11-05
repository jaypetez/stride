# Installing Stride on Android

Stride for Android is distributed as a **signed APK on GitHub Releases** (tags
`android-v*`), not through the Play Store yet. You can install it once by hand,
or let **Obtainium** install it and keep it updated.

## 1. Allow installs from your browser or Obtainium
Android blocks apps from unknown sources by default. The first time you open an
APK, Android asks for permission:
1. Tap the downloaded APK (or press **Install** in Obtainium).
2. When prompted, tap **Settings** → enable **Allow from this source**.
3. Go back and tap **Install**.

You can turn the permission off again afterwards.

## 2a. Install with Obtainium (recommended — automatic updates)
[Obtainium](https://github.com/ImranR98/Obtainium) watches GitHub Releases and
installs updates for you.
1. Install Obtainium from its GitHub releases page (or F-Droid / IzzyOnDroid).
2. In Obtainium tap **Add App** and enter `https://github.com/jaypetez/stride`.
3. Open **Additional options** and set:
   - **Filter release titles by regular expression:** leave empty.
   - **Filter release tags by regular expression:** `^android-v` (the repo also has
     non-Android `v*` releases; this keeps Obtainium on the app's releases only).
   - **Filter APKs by regular expression:** `\.apk$`.
4. Tap **Add**, then **Install**.

Obtainium checks for new releases in the background and notifies you when an
update is ready. Updates install in place and keep your data, as long as they
are signed with the same key (see below).

## 2b. Install by hand
1. Open the [latest Android release](https://github.com/jaypetez/stride/releases?q=android-v&expanded=true).
2. Download `stride-android-v<version>.apk`.
3. Open it and tap **Install**.

Repeat for each update.

## 3. Verify the download (optional but recommended)
Every release attaches `SHA256SUMS` and a **build provenance attestation**:

```bash
sha256sum -c SHA256SUMS --ignore-missing
gh attestation verify stride-android-v<version>.apk -R jaypetez/stride
```

You can also check the signing certificate with `apksigner` from the Android
build tools:

```bash
apksigner verify --print-certs stride-android-v<version>.apk
# Signer #1 certificate SHA-256 digest: <must match the fingerprint below>
```

**Release signing certificate SHA-256 fingerprint:** published in
[`docs/release/signing.md`](signing.md) when the release keystore is created
(GOV-015). If the fingerprint ever differs, do not install the APK.

## 4. First launch
1. Stride asks for a short **profile** (age, units, optional heart-rate values)
   and runs a **PAR-Q** health screening. Nothing leaves your phone.
2. Choose **Connect Garmin** (you need your own Garmin Personal API key — the app
   walks you through creating one) or **Try demo** to explore with synthetic data.
3. With Garmin connected, the first sync imports up to 180 days of history. It runs
   in the background; pull down on **Today** to refresh any time.

## Uninstalling
Uninstalling removes all Stride data from the phone. To keep a copy first, use
**You → Export all data**.
