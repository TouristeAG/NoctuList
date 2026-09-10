# Changelog

All notable changes to NoctuList are documented here. Version numbers follow [Semantic Versioning](https://semver.org/). Build metadata lives in [`version.json`](version.json) (single source of truth for Android, desktop, and in-app update checks).

---

## 2.1.4 — 2026-09-09

Optional patch: customizable public labels on artist guest forms, and a publish fix so forms with logos actually reach Firestore.

### Features

- **Artist forms** — production can rename public field labels (comments, access request, access disclaimer, email, phone, people heading) from a collapsed panel at the bottom of the form creator. Empty keeps the default translations. Strings only; submit shape and review UI are unchanged.

### Fixes

- **Artist forms** — creating a form no longer reports success if Firestore never received the document (the public page then stayed "closed"). Logo data URIs are stored once, not duplicated in the json envelope, so large logos no longer exceed the 1 MiB document cap.
- **Artist forms** — association logos larger than a Firestore field (~1 MiB) are compressed (or omitted) at publish time so Create no longer fails with `institutionLogoDataUri` INVALID_ARGUMENT.

### Upgrade notes

- **Version code:** 31 (`2.1.4`). Minimum supported code remains **27** (2.1.0) — optional update for 2.1.0–2.1.3.
- **Database:** Room schema 50 → 51 (`guest_forms.fieldLabelsJson`).
- **Firestore rules:** unchanged.
- **Hosting:** redeploy the guest-form site (`webform/`) so custom labels apply on the public page.
- **Publishing:** tag `2.1.4`, then copy `version.json` to the AdminList update manifest. Do not raise `minSupportedVersionCode`.

---

## 2.1.3 — 2026-09-08

Optional patch: leftover Android Firebase credentials, duplicate POS sales, and per-access caps on artist guest forms.

### Fixes

- **Android reinstall / factory reset** — Auto Backup no longer restores API keys and org config after uninstall. Encrypted credential storage is wiped on in-app factory reset. Scanning a new join QR (or pasting a new Firebase project) re-initializes the SDK with those credentials instead of the previous project.
- **POS** — a pending Firebase sale that times out is retried in place instead of creating a second transfer (duplicate sales on Android).
- **NFC** — UIDs from phone NFC and ACS readers that return 4 of 7 bytes are treated as the same card.
- **Artist forms** — production can set a maximum number of requests per offered access; the public page blocks extras with an error. Empty still means no extra cap beyond the person limit.

### Upgrade notes

- **Version code:** 30 (`2.1.3`). Minimum supported code remains **27** (2.1.0) — optional update for 2.1.0–2.1.2.
- **Database / Firestore rules:** unchanged.
- **Hosting:** redeploy the guest-form site (`webform/`) so per-access quotas apply on the public page.
- **Publishing:** tag `2.1.3`, then copy `version.json` to the AdminList update manifest. Do not raise `minSupportedVersionCode`.

---

## 2.1.2 — 2026-09-07

Artists can fill in their own temporary guest list through a public form. **Firebase only** — Google Sheets is unchanged.

### Public forms

- Production creates a form from *Add a temporary guest → Create a form*: venue, event, date, artist, requestable accesses, person cap, expiry, optional logos, and a pre-filled email or emergency phone.
- Optional **Allow multiple responses**: the shared link stays OPEN until expiry; each public submit creates a separate `PENDING_REVIEW` child (its own validation card). Single-response forms keep the previous in-place close-on-answer behaviour.
- Contact **email** and **emergency phone** can each be shown or hidden per form; prefill fields only appear when the matching option is on.
- Each form is a unique link (`https://<project>.web.app/g/<org>/<id>`). The site is deployed **once** per institution via Firebase Hosting; creating a form never needs another deploy.
- The public page reuses the app's topographic background and dark palette. Accesses are a request only and never guaranteed. No bar discount or other in-house perk is asked.

### Review

- A yellow card at the top of the guest list lists answers waiting for an admin.
- The review dialog shows a full recap (contact, notes, each person and requested accesses), then Accept (✓) or Refuse (✗).
- Accepting (after optional edits) creates the temporary guests through the existing batch path. Refusing or expiring a form closes the public link and drops the per-form artist logo.

### Future / History

- Dialog split into three separate tabs: **Tonight** (Firebase: entered / total + lists), **Future** (upcoming temp guests; Android also volunteers), **History** (past temp guests; Android also volunteers).
- Tonight’s guests no longer mix into Future when entry tracking is on.

### Setup

- Opt-in institution setting (like profile photos), off by default. The one-time Hosting publish steps live only in the Firebase setup guide (export, Node LTS, copyable Terminal commands).
- The association logo is shared with the QR e-mail logo: upload in Settings → Email and it is available on forms (and synced across devices). Stored on disk locally (not in Preferences) so large logos no longer crash the desktop app at launch.

### Public page polish

- Background animation works on phones (scaled WebGL buffer) and no longer burns CPU on long open tabs (~30 fps, pauses when the tab is hidden).
- Access chips show `+` / `✓` so requesting an access for each person is obvious.

### Upgrade notes

- **Version code:** 29 (`2.1.2`). Minimum supported code remains **27** (2.1.0) — optional update for 2.1.0 and 2.1.1.
- **Database:** Room schema 45 → 50 (temporary-guest columns, then `guest_forms` including multi-response and `askEmail` / `askPhone`).
- **Firestore rules:** version 9 — republish `firebase/firestore.rules` so multi-response public creates are allowed and single-response updates stay blocked on multi templates.
- **Hosting:** redeploy the guest-form site (`webform/`) so multi-response submits and contact-field toggles work on the public page.
- **Publishing:** tag `2.1.2`, then copy `version.json` to the AdminList update manifest. Do not raise `minSupportedVersionCode`.

---

## 2.2.0 — 2026-09-05

Temporary artist guest lists become a first-class feature on the Firebase backend. **The Google Sheets backend is untouched**: the `Temp Guest List` tab keeps its seven columns (A–G) and every field below is stored only in Firestore and Room.

### Special accesses per venue

- New **Accès guests temporaires** card in Settings → Catalog. Each active venue holds up to 12 named accesses ("Backstage", "Zone VIP", "Stage"…), synced across devices as a Firebase-only institution setting. Accesses carry a stable ID, so renaming one keeps it attached to the guests who hold it.
- The add/edit form grants accesses **per person**: a single switch when the venue has one access, selectable chips when it has several, and nothing at all when it has none. Changing the venue clears the ticked accesses, since they belong to the venue.

### Temporary guest list form

- **Venue** (one per batch) sits just before Notes, and an **email de contact** for the artist or manager sits just under the emergency number.
- **Bar discount %** is now available on temporary guests too, using the same field as permanent guests.
- The event date uses a new **JJ/MM/AAAA** field with a calendar and *Aujourd'hui* / *Ce vendredi* / *Ce samedi* shortcuts. This one applies to both backends — it changes only the input, not the stored value.

### QR codes and grouped email

- Temporary guests get the same QR icon as permanent guests and volunteers, available to Billeterie as well as Admin.
- Sending offers a choice: **the artist's whole guest list** — every QR of the batch in a single email to the contact address, asked for once and then remembered on all guests of the batch — or **this person only**, which asks for a one-off recipient and sends a single QR.
- A third email template, **Guestlist artiste**, joins Bénévole and Invité·e in the email settings.

### Entry validation, scanner and POS

- Scanning a temporary guest shows their authorized accesses in large contrasted chips and offers **Valider l'entrée**. Entry is single-use: a second scan reports the time of the first one instead of letting them back in.
- A validated guest leaves the day list and reappears under **Déjà entrés** in *Futurs / historique*, with a `validés / total` counter.
- Temporary guests reach the POS only when they can actually use it — that is, when they have a bar discount or when credit accounts are enabled.
- **Credit accounts for temporary guests** are off by default and enabled from the same Settings card.

### Upgrade notes

- **Version code:** features below ship in **2.1.2** (code 29). Minimum supported code remains **27** (2.1.0).
- **Database:** Room schema 45 → 46 adds five nullable columns to `guests`. The migration is additive and needs no action.
- **Firestore rules:** unchanged.

---

## 2.1.1 — 2026-09-04

Patch release focused on sales catalogue sync reliability.

### Sync & sales items

- **Sales item edits now propagate across devices.** Saving a product or toggling active/inactive always advances `lastModified` (and stays ahead of the stored stamp if the device clock is behind), so last-write-wins sync no longer discards legitimate edits.
- **Sheets downloads keep local product sub-categories.** The Sales tab has no sub-category column; re-attaching the device-local assignment prevents a sync from blanking it out whenever any other field changes.
- **Firebase-only institution settings stay off Sheets.** Write and read paths filter keys that are not meant for the Sheets contract, so local-only settings are neither uploaded nor overwritten from stale sheet rows.

### Upgrade notes

- **Version code:** 28 (`2.1.1`). Minimum supported code remains **27** (2.1.0) — devices on 2.1.0 get an optional update.
- **Publishing:** tag the release `2.1.1`, attach platform artifacts, then update the public update manifest (`version.json` on the AdminList mirror if used).

---

## 2.1.0 — 2026-09-04

Bug-fix and UI polish release on top of the 2.0 Firebase stack.

### UI & billetterie

- **Wide billetterie / welcome layouts** for large tablets and desktop screens.
- Clearer **announcements** UI and settings; improved **people counter** layout and priority controls.
- Smoother **space transitions**, startup splash, and typography (Nunito).
- Settings and admin auth polish; desktop guest-list and settings refinements.

### Firebase & setup

- More reliable **org bootstrap / join** flows and clearer permission-denied messaging.
- Updated **Firestore rules** guidance and in-app tutorial alignment.
- Local admin grant and related security edge cases hardened.
- **Firestore rules version 5** — account writes no longer fail on a brand-new organization. The
  rules read `institutionSettings/purchase_credit_buffer` to validate balances, and on a database
  where that document does not exist yet the lookup denied every account write. An unset or
  malformed buffer now means "no overdraft". **Republish `firebase/firestore.rules`.**
- **Sheets → Firebase migration** no longer tries to claim org admin unconditionally. It probes
  membership first, so migrating into an organization that another account already set up reports
  what to do instead of an opaque `PERMISSION_DENIED`, and it no longer rotates the invitation
  code that team devices already hold.
- Institution settings are pushed **before** account balances during migration, so the buffer the
  rules validate against is already in place.
- Documented that publishing rules on a Workspace-owned project requires Owner, Editor, or
  *Firebase Rules Admin* — without it the database stays on the deny-all production default.
- **Organization IDs are normalized as you type.** Entering an institution name such as
  "Collectif Nocturne" produced an ID that Firestore cannot use as a path segment: the setup
  wizard's *Continue* button stayed greyed out with nothing explaining why. Spaces now become
  hyphens, accents are folded to ASCII, and an invalid ID shows the expected format.
- The **join step of the setup wizard** lists what is still missing (organization, project keys,
  Google Sign-In credentials, invitation code) instead of silently disabling *Continue*.
- **A failed migration can no longer strand a device.** The backend switch was announced on the
  source database before the target one, so a refused write on the target rolled the local backend
  back while the announcement already pointed elsewhere — leaving a non-dismissable "connect to the
  new database" dialog that could never be satisfied. The target is announced first, the local
  switch is never reverted afterwards, and failing to notify the old backend is now reported as a
  partial success rather than a failure.
- The migration wizard shows an explicit **"Migration finished — close"** button on success, so
  admins no longer dismiss a completed migration through *Cancel*.
- The follow-migration screen now leads with the same **QR code / invitation code** flow as
  "join an organization", validates a scanned code, and offers a **device reset** as a last resort.
- **Sheets → Firebase migration** accepts **one destination organization only**. Extra orgs are
  still added later in Admin → Firebase sync; multiple IDs during migration made the target ambiguous.
- **Security fix — first-admin setup could be triggered on an existing, non-empty organization.**
  At startup, an offline or misconfigured Firestore SDK was reported as a successful sync ("local
  Room remains source of truth"), which the first-admin gate then read as proof the remote
  organization was empty whenever the local database happened to be empty too. That opened the
  passwordless "create the first admin" wizard on a device that simply never reached the server,
  letting anyone at that device grant admin rights to themselves or to any guest/volunteer. The
  gate now requires a genuine server-confirmed Firestore response before trusting local emptiness;
  an unreachable server is treated the same as a failed sync — no setup offered.

### POS

- **Permanent guests can be granted a bar discount.** Adding or editing a permanent guest now offers
  a *Bar Discount (%)* field, defaulting to 0. When it is above 0, the POS shows the same discount
  badge next to the guest profile as it does for volunteers, and the sale applies it identically:
  account credit is always debited at full price and the percentage only reduces the cash/card
  remainder of discount-eligible lines. The guest profile panel lists the granted percentage.
  A guest at 0% looks and behaves exactly as before.
  The field is **Firebase-only** — like profile photos it is never written to Google Sheets, and it
  stays hidden and inactive on the Sheets backend.
- **The product grid now fills the screen.** Column count was picked by maximising cell area, which
  on any catalogue too long to fit on screen always resolved to a single very wide column — a
  desktop pane with room for four or five products per row showed one. Columns are now derived from
  the available width and the device class (a phone pane stays at two, a tablet reaches five, a wide
  desktop up to nine), and tile height still stretches to fill the viewport when everything fits.
- **Product sub-categories.** A product can now be filed under a sub-category of its general
  category — *Alcohol*, *Deposits* or *Non-alcoholic* inside *Bar*, for example. Sub-categories are
  created and deleted by hand from the sales-item editor, and the POS shows them as a horizontal
  filter bar above the product grid, to the right of the general category rail.
  The bar only appears for sub-categories that actually contain a product, and disappears entirely
  when none are defined or none are assigned — in that case the POS behaves exactly as before.
  Deleting a sub-category also clears it from every product that used it.
  **Firebase-only**: the catalogue is an institution setting and the per-product link is a Firestore
  field, so the Google Sheets product contract is unchanged and the feature stays hidden on Sheets.

### Performance & reliability

- Hot-path ViewModel and profile-photo cache optimizations (fewer redundant recompositions / reloads).
- Desktop Firestore realtime capability and logging improvements.
- Assorted bug fixes across sync, date/time, and POS credit paths.

### Upgrade notes

- **Version code:** 27 (`2.1.0`). Minimum supported code is **26** (2.0.0) — devices below 2.0.0 are prompted to update; 2.0.0 gets an optional upgrade.
- **Publishing:** tag the release `2.1.0`, attach platform artifacts, then update the public update manifest (`version.json` on the AdminList mirror if used).

---

## 2.0.0 — 2026-09-01

Major release: realtime Firebase sync, richer POS accounting, and a refreshed admin experience while keeping Google Sheets as a supported backend.

### Sync & backends

- **Firebase / Firestore backend** — realtime org sync with offline pending writes, snapshot listeners, and encrypted sensitive fields.
- **Backend choice** — each institution can run on **Firebase** (recommended for new setups) or **Google Sheets** (legacy / spreadsheet workflow).
- **In-app migration** — migrate from Sheets to Firebase with guided setup, Firestore rules clipboard, and optional Sheets mirror export.
- **Multi-organization mode** — configure and switch between several Firebase orgs on one project; org color tags and “all orgs” view for cross-venue staff.
- **Institution settings sync** — shared toggles (e.g. profile photos) propagate across org devices via Firestore.

### Profile photos

- Optional **profile photos** for guests and volunteers, stored in **Firebase Storage** when enabled.
- Upload, replace, and remove from detail panels; local image cache for fast UI.
- Storage rules bundled in-app with setup tutorial (`FIREBASE_SETUP.md`).

### Account credits & POS

- **Account ledger** — manual top-ups/debits, shift credits, POS sales, and reversals synced across devices.
- **Internal POS** — sell against guest/volunteer credit with cash remainder and rank-based bar discounts.
- **POS accounting reports** — evening or date-range PDF exports (sales, transfers, manual adjustments).
- Redesigned **manual account adjustment** dialog (quick amounts, balance preview, admin verification unchanged).

### Billeterie & door

- Improved billetterie / scanner flows on desktop and shared UI.
- Volunteer benefit check-in, QR/NFC admin auth, and people counter with device priority.

### UI & admin

- Settings reorganization: Firebase sync section, backend migration wizard, factory reset safeguards.
- Dashboard and stats graph export improvements (tables, category layouts).
- Volunteer inactive cleanup synced correctly with Firebase and Sheets.
- Desktop admin nav (bottom / left / right), themes, layout scale, 7 languages.

### Desktop & hardware

- Native file dialogs, improved **BLE/USB NFC** reader status and SoftDevice support (carried forward from 1.1.x).
- Linux `.deb` / `.AppImage`, Windows `.msi` / `.exe`, macOS `.dmg` packaging from `version.json`.

### Upgrade notes

- **Version code:** 26 (`2.0.0`). Minimum supported code is **25** (1.1.1) — devices below 1.1.1 are prompted to update; 1.1.1 gets an optional upgrade.
- **New Firebase orgs:** follow the in-app tutorial or [FIREBASE_SETUP.md](FIREBASE_SETUP.md). Sheets-only institutions can stay on Sheets or migrate when ready.
- **Publishing:** tag the release `2.0.0`, attach platform artifacts, then update the public update manifest (`version.json` on the release branch / AdminList mirror if used).

---

## 1.1.1

- Faster and more reliable desktop BLE NFC SoftDevice reading.
- Clearer NFC reader status and native file dialogs.
- Various desktop fixes.

## 1.1.0

- Initial public desktop release lineage; guest/volunteer management with Google Sheets sync.
