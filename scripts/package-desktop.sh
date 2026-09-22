#!/usr/bin/env bash
# Package NoctuList desktop installers.
#
# macOS IMPORTANT — Apple Silicon vs Intel
# ─────────────────────────────────────────
# Compose Desktop bundles the JVM of the *build host*; there is no cross-compilation.
# You MUST run this script on the target architecture:
#
#   • Apple Silicon (M-series) Mac → produces NoctuList-<ver>-arm64.dmg
#   • Intel Mac                   → produces NoctuList-<ver>-x86_64.dmg
#
# Both DMGs should be published for every release.
# Use `scripts/rebuild-macos-dylibs.sh` to regenerate LocalAuthenticationEngine.dylib
# and CameraAuthorizationEngine.dylib as universal binaries (x86_64 + arm64)
# before packaging on either platform.
#
# JDK requirements: Temurin JDK 17.  Homebrew JDK is unsupported (jpackage is missing).
#   • arm64: https://adoptium.net/  (Temurin 17 macOS aarch64)
#   • x86_64: https://adoptium.net/ (Temurin 17 macOS x64)
set -euo pipefail
cd "$(dirname "$0")/.."

# ── JDK check ──────────────────────────────────────────────────────────────────
if [[ -z "${JAVA_HOME:-}" ]]; then
  echo "Tip: set JAVA_HOME to Temurin JDK 17 if packaging fails (Homebrew JDK is unsupported)."
fi

JDK_VENDOR=$(java -XshowSettings:all -version 2>&1 | grep "java.vendor " | awk '{print $3}' || true)
if [[ "${JDK_VENDOR:-}" == *"Homebrew"* ]]; then
  echo "ERROR: Homebrew JDK detected. jpackage is absent in Homebrew builds."
  echo "  Install Temurin 17 from https://adoptium.net/ and set JAVA_HOME."
  exit 1
fi

# ── Architecture detection ──────────────────────────────────────────────────────
HOST_ARCH="$(uname -m)"
OS_NAME="$(uname -s)"

if [[ "${OS_NAME}" == "Darwin" ]]; then
  if [[ "${HOST_ARCH}" == "arm64" ]]; then
    ARCH_SUFFIX="-arm64"
    echo "Building native Apple Silicon (arm64) DMG — this is the recommended path for M-series Macs."
  else
    ARCH_SUFFIX="-x86_64"
    echo "Building Intel (x86_64) DMG."
    echo "NOTE: This package will NOT run natively on Apple Silicon."
    echo "      Build on an M-series Mac to get the arm64 DMG."
  fi
else
  ARCH_SUFFIX=""
fi

# ── Rebuild native dylibs (macOS only) ─────────────────────────────────────────
# The LocalAuthentication dylib must be a universal binary so it works on both arches
# regardless of which DMG the user downloads.
if [[ "${OS_NAME}" == "Darwin" ]] && command -v clang &>/dev/null; then
  echo "Rebuilding LocalAuthenticationEngine.dylib as universal binary (x86_64 + arm64)…"
  clang \
    -arch x86_64 \
    -arch arm64 \
    -dynamiclib \
    -framework Foundation \
    -framework LocalAuthentication \
    -o shared/src/desktopMain/resources/LocalAuthenticationEngine.dylib \
    shared/nativeengines/macos/LocalAuthenticationEngine.m
  echo "  → $(lipo -info shared/src/desktopMain/resources/LocalAuthenticationEngine.dylib)"
  echo "Rebuilding CameraAuthorizationEngine.dylib as universal binary (x86_64 + arm64)…"
  clang \
    -arch x86_64 \
    -arch arm64 \
    -fobjc-arc \
    -dynamiclib \
    -framework Foundation \
    -framework AVFoundation \
    -framework CoreMedia \
    -framework CoreVideo \
    -o shared/src/desktopMain/resources/CameraAuthorizationEngine.dylib \
    shared/nativeengines/macos/CameraAuthorizationEngine.m
  echo "  → $(lipo -info shared/src/desktopMain/resources/CameraAuthorizationEngine.dylib)"
fi

# ── Gradle packaging ────────────────────────────────────────────────────────────
./gradlew :desktopApp:packageDmg :desktopApp:packageMsi :desktopApp:packageReleaseDistributionForCurrentOs

# ── Rename macOS artifact with arch suffix ──────────────────────────────────────
if [[ "${OS_NAME}" == "Darwin" && -n "${ARCH_SUFFIX}" ]]; then
  DMG_DIR="desktopApp/build/compose/packaged/main/dmg"
  for dmg in "${DMG_DIR}"/*.dmg; do
    [[ -f "${dmg}" ]] || continue
    # e.g. NoctuList-2.1.0.dmg → NoctuList-2.1.0-arm64.dmg
    base="${dmg%.dmg}"
    renamed="${base}${ARCH_SUFFIX}.dmg"
    mv "${dmg}" "${renamed}"
    echo "Renamed: $(basename "${renamed}")"
  done
fi

echo ""
echo "Artifacts: desktopApp/build/compose/packaged/"
echo ""
if [[ "${OS_NAME}" == "Darwin" ]]; then
  echo "macOS signing (optional but recommended):"
  echo "  export MACOS_SIGN_IDENTITY='Developer ID Application: …'"
  echo "  export NOTARY_APPLE_ID='you@example.com'"
  echo "  export NOTARY_TEAM_ID='XXXXXXXXXX'"
  echo "  export NOTARY_APP_PASSWORD='…'   # App-specific password"
  echo "  codesign --deep --force --options runtime --sign \"\${MACOS_SIGN_IDENTITY}\" <app>"
  echo "  xcrun notarytool submit <dmg> --apple-id \"\${NOTARY_APPLE_ID}\" --team-id \"\${NOTARY_TEAM_ID}\" --password \"\${NOTARY_APP_PASSWORD}\" --wait"
fi
echo "Windows signing: set WINDOWS_SIGN_TOOL and certificate path before packaging on Windows."
