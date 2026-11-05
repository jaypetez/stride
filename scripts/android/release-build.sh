#!/usr/bin/env bash
# Builds the signed release APK + AAB into dist/ with SHA256SUMS (REL-002).
# Usage: release-build.sh <asset-base-name>
# Signing: if KEYSTORE_B64 is set, it is decoded into a temp file (real release);
# otherwise a throwaway key is generated (PR dry run). The keystore is always deleted.
set -euo pipefail
name="$1"
keystore="$(mktemp -d)/stride-release.jks"
trap 'rm -f "$keystore"' EXIT

if [ -n "${KEYSTORE_B64:-}" ]; then
  echo "$KEYSTORE_B64" | base64 --decode > "$keystore"
  export STRIDE_KEYSTORE_PASSWORD="$KEYSTORE_PASSWORD" STRIDE_KEY_ALIAS="$KEY_ALIAS" STRIDE_KEY_PASSWORD="$KEY_PASSWORD"
else
  pass="$(openssl rand -hex 16)"
  echo "::add-mask::$pass"
  keytool -genkeypair -keystore "$keystore" -storepass "$pass" -keypass "$pass" -alias dryrun \
    -keyalg RSA -keysize 2048 -validity 2 -dname "CN=Stride CI dry run" -noprompt
  export STRIDE_KEYSTORE_PASSWORD="$pass" STRIDE_KEY_ALIAS="dryrun" STRIDE_KEY_PASSWORD="$pass"
fi
export STRIDE_KEYSTORE_PATH="$keystore"

./android/gradlew -p android :app:assembleRelease :app:bundleRelease

mkdir -p dist
cp android/app/build/outputs/apk/release/app-release.apk "dist/${name}.apk"
cp android/app/build/outputs/bundle/release/app-release.aab "dist/${name}.aab"
(cd dist && sha256sum ./*.apk ./*.aab > SHA256SUMS)
cat dist/SHA256SUMS
