#!/usr/bin/env bash
# Bootstrap a fresh Linux cloud/CI box for Stride factory work:
# Node 22+ with pnpm via corepack, JDK 21, Android SDK (cmdline-tools, platform 37,
# build-tools), and workspace deps. Idempotent; safe to re-run.
#
# Usage: bash scripts/factory/setup-cloud-env.sh
set -euo pipefail

ANDROID_PLATFORM="${ANDROID_PLATFORM:-android-37}"
ANDROID_BUILD_TOOLS="${ANDROID_BUILD_TOOLS:-37.0.0}"
CMDLINE_TOOLS_ZIP="${CMDLINE_TOOLS_ZIP:-commandlinetools-linux-13114758_latest.zip}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"

log() { printf '\n[setup] %s\n' "$*"; }

# ── Node + pnpm ──────────────────────────────────────────────────────────────
if ! command -v node >/dev/null || [ "$(node -p 'process.versions.node.split(".")[0]')" -lt 22 ]; then
  echo "Node >= 22 is required (found: $(node --version 2>/dev/null || echo none))." >&2
  exit 1
fi
log "Enabling pnpm via corepack"
corepack enable >/dev/null 2>&1 || sudo corepack enable
pnpm --version

# ── JDK 21 ───────────────────────────────────────────────────────────────────
if ! java -version 2>&1 | grep -q 'version "21'; then
  log "Installing JDK 21"
  if command -v apt-get >/dev/null; then
    sudo apt-get update -y && sudo apt-get install -y --no-install-recommends openjdk-21-jdk-headless unzip
  else
    echo "Install JDK 21 manually (no apt-get)." >&2
    exit 1
  fi
fi
JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"
export JAVA_HOME

# ── Android SDK ──────────────────────────────────────────────────────────────
if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
  log "Installing Android cmdline-tools into $ANDROID_HOME"
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  tmp="$(mktemp -d)"
  curl -fsSL "https://dl.google.com/android/repository/$CMDLINE_TOOLS_ZIP" -o "$tmp/tools.zip"
  unzip -q "$tmp/tools.zip" -d "$tmp"
  rm -rf "$ANDROID_HOME/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  rm -rf "$tmp"
fi
export ANDROID_HOME ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
log "Installing SDK packages ($ANDROID_PLATFORM, build-tools $ANDROID_BUILD_TOOLS)"
yes | sdkmanager --licenses >/dev/null || true
sdkmanager --install "platform-tools" "platforms;$ANDROID_PLATFORM" "build-tools;$ANDROID_BUILD_TOOLS" >/dev/null

# Persist for later shells.
PROFILE="$HOME/.bashrc"
grep -q 'ANDROID_HOME=' "$PROFILE" 2>/dev/null || cat >>"$PROFILE" <<EOF
export JAVA_HOME="$JAVA_HOME"
export ANDROID_HOME="$ANDROID_HOME"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools:\$PATH"
EOF
[ -d android ] && echo "sdk.dir=$ANDROID_HOME" > android/local.properties

# ── Workspace ────────────────────────────────────────────────────────────────
log "Installing workspace dependencies"
pnpm install --frozen-lockfile
pnpm factory validate

log "Done. Next: gh auth status (needs repo + project scopes), then /factory-next"
