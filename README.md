# NoctuList

**Guest lists, volunteers, door check-in, and internal POS** for live music venues — a Kotlin Multiplatform app for Android tablets/phones and desktop (macOS, Windows, Linux).

Data lives locally in **Room (SQLite)** and syncs across devices through either **Firebase / Firestore** (realtime, recommended for new organizations) or **Google Sheets** (service account). You can migrate between the two.

[![Version](https://img.shields.io/badge/version-2.1.4-blue)](https://github.com/TouristeAG/NoctuList/releases)
[![Platforms](https://img.shields.io/badge/platforms-Android%20%7C%20macOS%20%7C%20Windows%20%7C%20Linux-brightgreen)](https://github.com/TouristeAG/NoctuList/releases)
[![Kotlin](https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/docs/multiplatform.html)
[![Compose](https://img.shields.io/badge/UI-Compose%20Multiplatform-4285F4)](https://www.jetbrains.com/compose-multiplatform/)

---

## Features

- **Guest lists** — permanent guests, one-off event guests, invitations, notes, venue assignment, optional NFC cards
- **Volunteer roster & shifts** — profiles, job history, configurable job types
- **Benefit system** — ranks and perks (Nova, Galaxie, Orion, Veteran, manual rewards) driven by job-type configuration
- **Billeterie** — door / ticketing: guest views for the night, QR/barcode and NFC check-in, optional POS
- **Internal POS** — merch / bar / entry against guest or volunteer account credit, cash remainder, purchase credit buffer, bar discounts by rank
- **Account ledger** — credit top-ups, POS sales, and transfers synced across devices
- **POS accounting reports** — evening or date-range PDFs (sales, cash/card, internal credit)
- **People counter & announcements** — venue-scoped headcount (with device priority) and staff messages
- **Email & wallet passes** — send QR codes via Gmail API; optional Apple Wallet / Google Pay `.pkpass` attachments
- **Two sync backends** — Firestore realtime (with offline pending writes) or Google Sheets; in-app migration and optional Sheets mirror export
- **Multi-organization** — several Firebase orgs on one project, switcher in the UI
- **Appearance** — themes, color profiles, layout scale, desktop admin nav (bottom / left / right), background animations per mode, 7 languages
- **Hardware** — NFC (built-in or USB/BLE readers), QR scanning, optional biometric admin login

---

## Modes

Every launch starts on a welcome screen with three entry points:

| Mode | Who it's for | Access |
|------|----------------|--------|
| **Admin** | Full back-office (dashboard, guests, volunteers, shifts, benefits, settings) | Auth via NFC, QR, or enrolled biometrics |
| **Billeterie** | Door / ticketing tablets | No full admin session — check-in focused; POS available from this mode |
| **Internal POS** | Bar / merch selling | Opens from welcome; does not require admin |

Admin sessions end after idle timeout or when the screen turns off, so shared tablets don't stay logged in. If the institution has no administrator yet, Admin auth can create one (NFC/QR enrollment).

---

## Download

Prebuilt binaries are on the [Releases](https://github.com/TouristeAG/NoctuList/releases) page:

| Platform | Artifact |
|----------|----------|
| Android | `.apk` |
| macOS | `.dmg` |
| Windows | `.msi` / `.exe` |
| Linux | `.deb` / `.AppImage` |

In-app updates check `version.json` against the published release manifest.

---

## Quick start

1. Install the app for your platform.
2. On first launch, complete the **setup wizard**: language, theme, color, layout — then choose how this device connects.
3. Pick one path:

| Path | When to use |
|------|-------------|
| **Join an organization** | Another device is already set up. Scan the admin QR **or** paste the long configuration code (`noctulist-fb:1:…`). A full code includes project config, Google Sign-In credentials, and the invitation code — one scan is enough. Sign in with Google. |
| **Create with Firebase** | New institution, recommended. Org ID → Firebase/Cloud project (in-app tutorial) → Google Sign-In. First admin can then invite other devices. |
| **Create with Google Sheets** | Spreadsheet-based sync. Cloud project → service account JSON → spreadsheet ID → share the sheet as **Editor** with the service account. |

4. Choose **Admin**, **Billeterie**, or **POS** from the welcome screen.

Detailed Firebase walkthrough: [FIREBASE_SETUP.md](FIREBASE_SETUP.md). Sheet tabs: [Google Sheets sync](#google-sheets-sync) below.

---

## Sync backends

Each device uses one live backend. Existing installs default to **Sheets** until you migrate.

### Firebase (recommended for new orgs)

- Realtime Firestore under `orgs/{orgId}/…`
- Google Sign-In with the **institution** Web OAuth client (not the developer Gmail Desktop JSON)
- Other devices join with a **QR / configuration code** plus a separate **invitation code**
- Team access: org members in Firestore (`admin` or `member`; legacy `door` / `pos` still accepted). Optional allowed email domains.
- Offline: local Room database plus a pending-write queue; status pill shows live / pending / failed / offline
- Several org IDs can share one Firebase project; switch orgs from the admin / billeterie UI
- Publish [`firebase/firestore.rules`](firebase/firestore.rules) from Firebase Console (or the in-app tutorial clipboard) before going live

See [FIREBASE_SETUP.md](FIREBASE_SETUP.md) for consoles, redirect URIs, GDPR notes, and the smoke checklist.

### Google Sheets

Uses a **Google Cloud service account** (JSON key uploaded in the wizard or Settings — not end-user OAuth).

1. Enable the **Google Sheets API**.
2. Create a service account and download a JSON key.
3. Create a spreadsheet and share it with the service account email as **Editor**.
4. Paste the spreadsheet ID and upload the key in the app.
5. Ensure the expected tabs exist (names are mostly configurable in Settings).

#### Default tabs

| Tab | Purpose |
|-----|---------|
| Guest List | Permanent guests |
| Volunteer Guest List | Benefit-linked guest rows (app-owned; do not edit by hand) |
| Volunteers | Volunteer roster |
| Shifts | Jobs history |
| **JobTypes** | Job type / benefit / wallet credit config *(fixed name)* |
| Venues | Venues, people counter, announcements |
| Temp Guest List | One-off event guests |
| Sales | POS catalog |
| Transfers | Wallet / POS ledger |
| Settings | Shared institution settings (email texts, currency, date offset, purchase credit buffer) |

The app can repair or normalize many headers on sync. The **`JobTypes`** tab name is fixed in code — that sheet must be named exactly `JobTypes`.

> Older notes in `GOOGLE_SHEETS_SETUP.md` may be outdated (credentials are uploaded in-app, not via bundled assets). Prefer the in-app connection test and the table above.

### Migration and Sheets mirror

Admins can migrate **Sheets → Firebase** or **Firebase → Sheets**. Peer devices are soft-locked until an admin reconnects them (follow dialog / join QR).

While Firebase is live, an optional **one-way Sheets mirror** can export to a separate spreadsheet (manual or timed).

---

## Tech stack

| Layer | Choice |
|-------|--------|
| Language | Kotlin Multiplatform 2.0 |
| UI | Compose Multiplatform + Material 3 |
| Local data | Room (bundled SQLite) |
| Sync | Firestore (GitLive Firebase) **or** Google Sheets API v4 |
| Email | Gmail API (OAuth desktop client or Workspace domain-wide delegation) |
| Architecture | MVVM + repositories + Coroutines / Flow |
| Hosts | `app/` (Android), `desktopApp/` (JVM desktop) |
| Shared code | `shared/` (`commonMain`, `androidMain`, `desktopMain`) |

Secrets (API keys, service-account JSON, Gmail tokens, wallet certificate passwords) are stored in the platform secure store, not in synced Firestore/Sheets documents.

**Requirements:** JDK 17+, Android Studio (or SDK) for Android builds, Gradle wrapper included in the repo.

---

## Project layout

```
NoctuList/
├── app/                 # Android application host
├── desktopApp/          # Compose Desktop host + packaging
├── shared/              # KMP UI, Room, Sheets, Firebase, POS, benefits
├── firebase/            # Firestore security rules (source of truth)
├── scripts/             # Tooling (e.g. i18n parity check)
├── version.json         # Version + release download URLs
├── FIREBASE_SETUP.md    # Institution Firebase / Auth walkthrough
└── .github/workflows/   # CI (Linux desktop packages, i18n check)
```

---

## Build & run

### Android

1. Open the project in **Android Studio**.
2. Sync Gradle, then run on a device or emulator (tablets preferred).
3. Complete the in-app setup wizard.

```bash
./gradlew :app:assembleDebug
```

Copy [`app/google-services.json.example`](app/google-services.json.example) to `app/google-services.json` for local Android builds if required. Do not commit real secrets.

### Desktop

```bash
./gradlew :desktopApp:run
```

### Package installers

Compose Desktop packages must be built **on the target OS** (no cross-compilation):

```bash
# macOS
./gradlew :desktopApp:packageReleaseDmg

# Windows
./gradlew :desktopApp:packageReleaseMsi
./gradlew :desktopApp:packageReleaseExe

# Linux
./gradlew :desktopApp:packageReleaseDeb
./gradlew :desktopApp:packageReleaseAppImage
APP_VERSION_NAME=X.Y.Z ./scripts/build-appimage.sh
```

Outputs land under `desktopApp/build/compose/packaged/main-release/`.

`packageReleaseAppImage` writes a jpackage application directory, not a `.AppImage` file. `scripts/build-appimage.sh` wraps that directory with `appimagetool`.

Before packaging on Windows, quit any running NoctuList build you launched from a previous package (otherwise the old `exe` folder can stay locked).

**Linux packaging prerequisites** (Ubuntu/Debian):

```bash
sudo apt-get update
sudo apt-get install -y fakeroot binutils libfuse2
```

Linux `.deb` / AppImage formats are enabled when Gradle runs on Linux. Publishing a GitHub Release also triggers `.github/workflows/desktop-linux.yml`.

### Release checklist

1. Bump version in `version.json` (and keep `desktopApp` version aligned — it reads from that file).
2. Build platform artifacts (APK, DMG, MSI/EXE, DEB/AppImage).
3. Attach them to the GitHub Release and update download URLs in `version.json`.
4. Smoke-test in-app update on each platform.

---

## Platform support

| Feature | Android | Desktop |
|---------|---------|---------|
| NFC | Built-in + optional USB / BLE ACS reader | USB PC/SC (preferred); BLE on Windows via ACS BT PC/SC + Management Tool |
| QR scan | Camera | Webcam or file picker |
| Biometric admin login | Fingerprint / Face unlock | Touch ID, Windows Hello, Linux Polkit |
| Dynamic launcher icons | Yes | — |
| POS + PDF accounting reports | Yes | Yes |
| In-app updates | APK | Platform installer |
| Charts / graph export | Canvas graphs | Summary + XLSX/JPG export |
| Gmail send | Account picker / OAuth | Desktop OAuth JSON or Workspace service account |

**Desktop keyboard shortcuts:** `Ctrl+,` / `Cmd+,` (Settings), `Ctrl+F` / `Cmd+F` (guest search), `Esc` (dismiss overlays).

### External NFC readers (desktop)

Desktop NFC uses **PC/SC only** (no raw USB / GATT stack like Android). Windows ACS readers need the correct OS drivers for UID reads to work.

#### USB (recommended on Windows)

Examples: ACR122U, ACR1255U-J1 with the physical switch set to **USB**.

1. Install the **ACS USB PC/SC** driver from the [ACR1255U-J1 driver page](https://www.acs.com.hk/en/driver/340/acr1255u-j1-usb-nfc-reader-with-bluetooth-interface/) (or [ACR122U](https://www.acs.com.hk/en/driver/3/acr122u-usb-nfc-reader/)). Prefer ACS over a bare Microsoft Usbccid binding.
2. Confirm a **PICC** (contactless) interface in Device Manager / **Settings → NFC reader → List PC/SC** — do not use an ICC-only entry for NFC.
3. If the reader beeps but **Test USB** shows no UID and Escape is blocked under Microsoft CCID: use **Enable Escape Command (admin)** in Settings (sets `EscapeCommandEnable=1`), then unplug/replug (or reboot).
4. Hold a card and run **Test USB reader** — expect a real `UID: …` line.

On macOS/Linux: ACS USB PC/SC (or `pcscd` + CCID) and the same List / Test flow.

#### Bluetooth ACR1255U-J1 (Windows only)

Windows Bluetooth pairing alone is **not** enough (connect/disconnect loops are common without the ACS tool).

1. Install the **ACS Bluetooth PC/SC** MSI from the same ACS product driver page.
2. Switch the reader to Bluetooth and pair it in Windows Settings.
3. Open **ACS Bluetooth Device Management Tool** and **install** the reader so it is registered with PC/SC.
4. Confirm a **BLE** entry under **List PC/SC**, then select it in the app.

**Product recommendation:** prefer **USB mode** for door/POS on Windows. macOS/Linux have no ACS BLE PC/SC driver — use USB.

---

## Localization

UI strings ship in:

- English, French, Spanish, Hindi, Latin
- Chinese (Simplified), Chinese (Traditional)

New user-facing strings must be added to all locale `strings.xml` files. CI runs `scripts/check-i18n.py` on string changes.

---

## Contributing

- Keep **Sheets column contracts** compatible unless you update sync and docs together (`JobTypes` name is especially sensitive).
- Keep **Firestore rules** in `firebase/firestore.rules` in sync with the copy bundled in compose resources (`shared/.../composeResources/files/firestore.rules`).
- Keep **benefit** and **wallet / POS ledger** rules consistent (`BenefitCalculator`, `AccountTransfer`, Transfers collection/sheet).
- Prefer layouts that work on **tablet and phone** touch targets.
- Add strings in **all** locales; run the i18n check locally if you edit copy.

Product wording for perk amounts and benefit copy is owned by **in-app strings** — treat those as the source of truth when docs and UI diverge.

---

## License & credits

Built for venue operations (notably Groove / Le Terreau) by **Collectif Nocturne**.

See [Releases](https://github.com/TouristeAG/NoctuList/releases) for binaries and [CHANGELOG.md](CHANGELOG.md) for release notes.
