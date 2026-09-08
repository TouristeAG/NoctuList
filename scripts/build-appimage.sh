#!/usr/bin/env bash
# Wraps the jpackage app-image produced by :desktopApp:packageReleaseAppImage
# into a real Linux .AppImage.
#
# Compose Multiplatform's TargetFormat.AppImage is jpackage's "app-image": an
# unpacked application directory (bin/<App>, lib/...), NOT the single-file
# AppImage bundle format. This script builds the AppDir scaffolding (AppRun,
# .desktop entry, icon) around that directory and packs it with appimagetool.
#
# Output: desktopApp/build/compose/packaged/main-release/appImage/NoctuList-<ver>.AppImage
#
# Requires Linux. Set APP_VERSION_NAME (e.g. 2.1.3). appimagetool and the
# AppImage runtime are downloaded pinned by version AND sha256.
set -euo pipefail

cd "$(dirname "$0")/.."

if [[ "$(uname -s)" != "Linux" ]]; then
  echo "ERROR: AppImage packaging must run on Linux." >&2
  exit 1
fi

VERSION="${APP_VERSION_NAME:?APP_VERSION_NAME must be set (e.g. 2.1.3)}"
BINARIES_DIR="${BINARIES_DIR:-desktopApp/build/compose/packaged/main-release}"

APPIMAGETOOL_VERSION="1.9.1"
RUNTIME_VERSION="20251108"

ARCH="$(uname -m)"
case "$ARCH" in
  x86_64)
    APPIMAGETOOL_SHA256="ed4ce84f0d9caff66f50bcca6ff6f35aae54ce8135408b3fa33abfc3cb384eb0"
    RUNTIME_SHA256="2fca8b443c92510f1483a883f60061ad09b46b978b2631c807cd873a47ec260d"
    ;;
  aarch64)
    APPIMAGETOOL_SHA256="f0837e7448a0c1e4e650a93bb3e85802546e60654ef287576f46c71c126a9158"
    RUNTIME_SHA256="00cbdfcf917cc6c0ff6d3347d59e0ca1f7f45a6df1a428a0d6d8a78664d87444"
    ;;
  *)
    echo "ERROR: Unsupported architecture for AppImage: ${ARCH}" >&2
    exit 1
    ;;
esac

app_dir=""
count=0
for d in "${BINARIES_DIR}/app"/*/; do
  [ -d "$d" ] || continue
  app_dir="${d%/}"
  count=$((count + 1))
done
if [ "$count" -ne 1 ]; then
  echo "ERROR: Expected exactly one app-image directory under ${BINARIES_DIR}/app, found ${count}" >&2
  echo "Contents of ${BINARIES_DIR}:" >&2
  find "${BINARIES_DIR}" -maxdepth 3 2>/dev/null || true
  exit 1
fi
app_name="$(basename "$app_dir")"

launcher="${app_dir}/bin/${app_name}"
if [ ! -x "$launcher" ]; then
  echo "ERROR: jpackage launcher not found or not executable: ${launcher}" >&2
  exit 1
fi

icon="${app_dir}/lib/${app_name}.png"
if [ ! -f "$icon" ]; then
  icon="desktopApp/icons/icon.png"
fi
if [ ! -f "$icon" ]; then
  echo "ERROR: No icon found for the AppImage (looked in lib/ and desktopApp/icons/icon.png)" >&2
  exit 1
fi

slug="$(printf '%s' "$app_name" | tr '[:upper:]' '[:lower:]' | tr ' ' '-')"

out_dir="${BINARIES_DIR}/appImage"
appdir="${out_dir}/${slug}.AppDir"
rm -rf "$appdir"
mkdir -p "$appdir"
cp -a "${app_dir}/." "$appdir/"

cat > "${appdir}/AppRun" <<EOF
#!/bin/sh
HERE="\$(dirname "\$(readlink -f "\$0")")"
exec "\$HERE/bin/${app_name}" "\$@"
EOF
chmod +x "${appdir}/AppRun"

cp "$icon" "${appdir}/${slug}.png"

cat > "${appdir}/${slug}.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=${app_name}
Exec=AppRun
Icon=${slug}
Comment=NoctuList — Guest list and volunteer management
Categories=Office;
Terminal=false
EOF

tools_dir="$(mktemp -d)"
trap 'rm -rf "$tools_dir" "$appdir"' EXIT

curl -fsSL --retry 5 --retry-delay 10 --connect-timeout 10 --max-time 300 -o "${tools_dir}/appimagetool" \
  "https://github.com/AppImage/appimagetool/releases/download/${APPIMAGETOOL_VERSION}/appimagetool-${ARCH}.AppImage"
echo "${APPIMAGETOOL_SHA256}  ${tools_dir}/appimagetool" | sha256sum -c -
chmod +x "${tools_dir}/appimagetool"

curl -fsSL --retry 5 --retry-delay 10 --connect-timeout 10 --max-time 300 -o "${tools_dir}/runtime" \
  "https://github.com/AppImage/type2-runtime/releases/download/${RUNTIME_VERSION}/runtime-${ARCH}"
echo "${RUNTIME_SHA256}  ${tools_dir}/runtime" | sha256sum -c -

output="${out_dir}/NoctuList-${VERSION}.AppImage"
# APPIMAGE_EXTRACT_AND_RUN: run appimagetool itself without FUSE (GitHub-hosted runners).
# --runtime-file: embed the pinned runtime instead of downloading `continuous`.
# --no-appstream: we ship no AppStream metadata, skip that validation.
ARCH="$ARCH" APPIMAGE_EXTRACT_AND_RUN=1 "${tools_dir}/appimagetool" \
  --no-appstream --runtime-file "${tools_dir}/runtime" \
  "$appdir" "$output"

echo "Built $(du -h "$output" | cut -f1) AppImage: ${output}"
