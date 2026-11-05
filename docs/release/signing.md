# Android release signing

Release APKs are signed by `.github/workflows/release-android.yml` with a
keystore held in the protected `release` GitHub environment
(`ANDROID_KEYSTORE_B64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`,
`ANDROID_KEY_PASSWORD`). The keystore itself is kept offline with two backups;
losing it means users cannot upgrade in place (GOV-015).

| | |
|---|---|
| Certificate SHA-256 fingerprint | _to be recorded when the keystore is generated (GOV-015, human task)_ |
| Key algorithm | RSA 4096 (recommended) |

How to generate (maintainer, once):

```bash
keytool -genkeypair -keystore stride-release.jks -alias stride -keyalg RSA -keysize 4096 -validity 10000
keytool -list -v -keystore stride-release.jks -alias stride | grep 'SHA256:'
base64 -w0 stride-release.jks | gh secret set ANDROID_KEYSTORE_B64 --env release
gh secret set ANDROID_KEYSTORE_PASSWORD --env release
gh secret set ANDROID_KEY_ALIAS --env release --body stride
gh secret set ANDROID_KEY_PASSWORD --env release
```

Then record the SHA-256 fingerprint in the table above.
