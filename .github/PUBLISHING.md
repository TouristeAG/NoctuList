# Release checklist — NoctuList

## TL;DR — release in 3 commands

```bash
# 1. Bump version.json, commit, push
git add version.json && git commit -m "chore: bump to X.Y.Z"
git push origin main

# 2. Create & push the version tag
git tag X.Y.Z
git push origin X.Y.Z

# 3. That's it. GitHub Actions does the rest ↓
```

Pushing the tag automatically triggers **`.github/workflows/release.yml`**, which:
- Builds 7 artifacts **in parallel** (Android APK, 2× macOS DMG, Windows MSI + EXE, Linux DEB + AppImage)
- Creates the **GitHub Release** with the correct tag and release notes
- Attaches every artifact to the release

No manual uploads. No manual release creation.

---

## Detailed checklist

### 1. Prepare the version bump

| File | Field | Example |
|------|--------|---------|
| [`version.json`](../version.json) | `latestVersionCode` | `28` |
| [`version.json`](../version.json) | `latestVersionName` | `2.2.0` |
| [`version.json`](../version.json) | `minSupportedVersionCode` | minimum version that can auto-update |
| [`version.json`](../version.json) | `changelogShort` | one-line summary (used if no release notes file) |
| [`version.json`](../version.json) | download URLs | update all 7 URLs to the new version |

Version numbers in `version.json` are automatically read by:
- `app/build.gradle` → Android `versionCode` / `versionName`
- `shared/build.gradle.kts` → shared module version
- `desktopApp/build.gradle.kts` → desktop package version

### 2. Write release notes (optional but recommended)

Create `.github/release-notes/X.Y.Z.md` with the release body.
If this file is missing, the workflow falls back to `changelogShort` in `version.json`.

See [release-notes/2.1.0.md](release-notes/2.1.0.md) for an example.

### 3. Tag & push

```bash
git tag X.Y.Z          # lightweight tag is fine; annotated also works
git push origin X.Y.Z  # this triggers the release workflow
```

You can also trigger the workflow manually from **Actions → Release — all platforms → Run workflow**.

### 4. Monitor the build

Go to **Actions → Release — all platforms** to watch progress.

| Job | Runner | Duration |
|-----|--------|----------|
| Android APK | `ubuntu-latest` | ~8 min |
| macOS DMG (x86_64) | `macos-13` (Intel) | ~15 min |
| macOS DMG (arm64) | `macos-14` (Apple Silicon) | ~15 min |
| Windows MSI + EXE | `windows-latest` | ~12 min |
| Linux DEB + AppImage | `ubuntu-latest` | ~10 min |
| **Publish release** | `ubuntu-latest` | ~1 min |

All build jobs run in **parallel**. The release is only published once every
single artifact has been produced successfully.

### 5. Expected release assets

These names must match the download URLs in `version.json`:

| File | Platform |
|------|----------|
| `app-release-X.Y.Z.apk` | Android |
| `NoctuList-X.Y.Z-arm64.dmg` | macOS Apple Silicon (M1/M2/M3/M4) |
| `NoctuList-X.Y.Z-x86_64.dmg` | macOS Intel |
| `NoctuList-X.Y.Z.msi` | Windows (installer) |
| `NoctuList-X.Y.Z.exe` | Windows (standalone setup) |
| `NoctuList-X.Y.Z.deb` | Linux Debian/Ubuntu |
| `NoctuList-X.Y.Z.AppImage` | Linux (universal) |

The publish job **fails** if any of these seven files is missing (including
Windows installers and the AppImage). Do not upload them by hand.

> **Why two macOS DMGs?**  
> Compose Desktop bundles the JVM of the build host — there is no cross-compilation
> path. An Intel DMG runs on Apple Silicon only via Rosetta 2 (performance penalty,
> may be Gatekeeper-blocked). Ship a native `arm64` DMG for a first-class experience
> on M-series Macs.

### 6. Update the in-app update manifest

Apps poll the update manifest from:

```
https://raw.githubusercontent.com/TouristeAG/AdminList/main/version.json
```

After the release is published, copy `version.json` from this repo to the
**AdminList** repo (or your canonical manifest branch) so existing installs
see the new update prompt.

---

## Individual platform builds (manual / debugging)

The three platform-specific workflows are kept for standalone test builds.
They no longer trigger on GitHub Release publication (the release.yml handles that).

```bash
# macOS only (manual test build — produces downloadable artifact, no release)
gh workflow run desktop-macos.yml

# Windows only
gh workflow run desktop-windows.yml

# Linux only
gh workflow run desktop-linux.yml
```

## Local builds

```bash
# macOS (requires Temurin JDK 17 — NOT Homebrew JDK)
./scripts/package-desktop.sh
# → desktopApp/build/compose/packaged/…/NoctuList-X.Y.Z-arm64.dmg   (Apple Silicon)
# → desktopApp/build/compose/packaged/…/NoctuList-X.Y.Z-x86_64.dmg  (Intel)

# Android
./gradlew :app:assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

---

## Smoke test checklist

- [ ] Fresh install: Firebase create-org + join flow
- [ ] Sheets-only institution still syncs
- [ ] Wide billetterie / people counter on a large tablet or desktop
- [ ] Announcements create / acknowledge
- [ ] Manual account credit (+/−) on guest and volunteer
- [ ] In-app update dialog shows new version changelog on previous version device
