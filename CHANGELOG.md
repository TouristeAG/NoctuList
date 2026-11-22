# Changelog

All notable changes to NoctuList are documented here. Version numbers follow [Semantic Versioning](https://semver.org/). Build metadata lives in [`version.json`](version.json) (single source of truth for Android, desktop, and in-app update checks).

---

## Unreleased

### Features

- **Guest portal.** Permanent guests get their own web space, and temporary guest groups get a shared space opened and closed from the app. Both are controlled by two Firebase settings. The "Add guest" dialog has a "create access" switch for permanent guests, guests can sign up and send requests from the site, and an account can link several profiles. Closing a group space keeps its account so reopening reuses it.
- **Volunteer portal access without an e-mail.** Opening access no longer sends a mail; the first password is shown once.
- **One address, one portal account.** A volunteer, a permanent guest and the contact of temporary guest lists who share an address get one account with all their profiles; the lists they manage show under their own profile. A cut access stays cut.
- **Guest lists on the portal.** One card per guest with access, balance, emergency contact and QR; lists show until 8:00 the morning after the event. The contact can correct a guest's name.
- **Full guest list access.** From a profile's portal access card, "Manage guest list access" gives a volunteer, a permanent guest or a list contact the whole temporary guest list of a date, for every venue or one. The person sees it in their space (who entered, accesses, contacts) and adds, edits or removes guests directly; a NoctuList device applies the changes. Current access and history are listed apart.
- **Invitations from the portal.** The admin opens invitations event by event, with a maximum number of people and a maximum per access. Additions and removals are reviewed like guest form answers.
- **Approving guest requests from the portal.** A new setting, "The portal approves the forms", lets a person with guest list access review guest requests from the portal. Pending requests sit at the top of the guest list section with the whole request shown; the person corrects it, then accepts or refuses it. A NoctuList device applies the result.
- **Volunteer certificate.** Volunteers can ask for a certificate of their volunteer work from the portal and receive it as a PDF by e-mail. The team reviews each request before it is sent: it can edit the text, then approve it or refuse it with a note the volunteer sees in the portal. Admins can also generate a certificate from a volunteer's panel. The association edits the default texts, logo and signature in Settings, Firebase, Volunteer portal, in five languages, and switches the option on there. Volunteers see the request only once a template is saved. Publish the Firestore rules (version 21); the new function onCertificateApprovalCreated is deployed with `firebase deploy --only hosting,functions`. Update every device: older versions reject the new request type, so this release is mandatory.

### Fixes

- The volunteer QR code read through a Google account in domain mode exceeded the Firestore document-access limit (12 of 10); the rules helpers now use get-only reads.
- The portal re-reads its pages for about 3 minutes after a link request is accepted, without closing an open QR code.

### Upgrade notes

- **Firestore rules:** version 21 (publish again). Storage rules unchanged.
- **Hosting and functions:** export the form site again and run `firebase deploy --only hosting,functions`, at the same time as the rules. The functions add the `onCertificateApprovalCreated` function (certificate sending), the `pdf-lib` and `@pdf-lib/fontkit` dependencies and their fonts; Cloud Build installs them from the lock file on deploy. The old functions drop guest signups and name requests.
- **Database:** Room migrations 53 to 57 (guest portal fields, `temporaryPortalEmail`, portal invite fields and `portalRevoked`, then guest list access grants).
- **Guest list changes from the portal** are applied by a NoctuList device that is online; without one they wait.
- **Portal accounts after the update:** temporary guest lists opened before keep their sign-in without a confirmed e-mail. A portal access cut before the update stays cut only if the profile kept an admin Google address; otherwise it can be joined again to the account of the same address, so check those profiles once.

---

## 3.1.0 — 2026-11-19

Mandatory update: devices on 3.0.0 or older must update before they can be used.

### Features

- **Google accounts filled in automatically.** Settings, Firebase, Volunteer portal: a rule such as `{prénom}.{nom}@asso.ch` (also `{first}`, `{last}`, `{p}`, `{n}`) builds the Google Workspace address of each newly opened portal access, from the admin's button, the add dialog or a registration link. Accents and apostrophes are removed and spaces become `-`. The address is linked only when the volunteer has none, must be on a verified Workspace domain (also in "any Google account" mode, so a guessed address never belongs to a stranger), and is left empty when another volunteer already uses it.
- **Portal access for every new volunteer.** A team setting opens portal access for each volunteer added in the app; the switch in "Add volunteer" is then hidden.
- **Site mail from a Google Workspace address** without a stored app password. The sending address and a test mail are chosen at the end of the server step.
- **Firebase setup assistant hardened.** Every step is checked against the project when the page opens; errors come from a catalogue with their cause, what the app tried and what to do; the tools can be reinstalled from the app; the admin can sign out of Firebase or switch account; only free APIs are enabled before billing is known; a manual check covers Spark, proxies, Defender and tokens.
- **Single NoctuList instance.** A second launch brings the open window forward.

### Fixes

- **Shift credits** are reconciled per volunteer after every shift add, edit or delete, and in a background pass every 15 minutes. An edited shift no longer earns a second credit, shifts deleted on another device are reversed through sync (with guards against half-loaded job lists), and duplicate Galaxie bonuses are removed. On Firebase a reversal never takes the balance below the purchase buffer; the rest is reversed later.
- **Volunteer portal:** a request nobody took for 5 minutes can be sent again.
- **Server account roles:** the default compute account also gets `roles/datastore.user` and `roles/storage.objectAdmin`; without them every portal request failed with PERMISSION_DENIED on new projects.
- Sign-in tokens and encoded secrets are kept out of the setup logs.

### Upgrade notes

- **Version code:** 45 (`3.1.0`). Minimum supported code is now **45**: older versions must update.
- **Firestore and Storage rules:** unchanged (v16).
- **Hosting and functions:** export the form site again and run `firebase deploy --only hosting,functions` (new portal script and Workspace mail sending).
- **Database:** no migration.

---

## 3.0.0 — 2026-11-16

New, optional: a personal web space for volunteers, and a refreshed look for every public page.

### Features

- **Firebase setup assistant.** The Firebase help is now one page with two tabs. Automatic (computer app): the app runs every step it can with a Run button, from installing its tools on request to deploying the volunteer functions, and retries by itself while Google gets ready. Manual: the same steps with every command to copy. The published site no longer includes the functions folder.

- **Volunteer portal (Firebase only, off by default).** Settings, Firebase, "Volunteer portal". Each volunteer signs in on the form site (`/v/{orgId}`) and sees their profile, status, shifts (past and upcoming), current benefits, unique entries left, wallet balance and their 5 latest transfers. They can change their photo (when profile photos are on), first name, last name, phone number and password, ask for their QR code by e-mail and download their data. Works on phones, tablets and computers (1, 2 or 3 columns).
- **Registration links.** "Add volunteer" offers "Send a registration form": a single-use link and QR code valid 7 days. The volunteer picks an e-mail and password, fills in their details and an optional photo; the profile appears in NoctuList on its own.
- **Portal access for existing volunteers.** New "Volunteer portal" card on each profile: create access (a password shown once plus a "choose your password" e-mail), send a password reset link, or remove access. "Add volunteer" can open the access at the same time.
- **QR code by e-mail from the portal**, sent by the server when automatic processing is on (or by any NoctuList device with Gmail set up), only to the address on the profile, at most every 10 minutes, never for admin volunteers.
- **Volunteer space on the home screen.** The portal can be added from the browser menu ("Add to Home Screen" / "Install app"); it then opens full screen with its own icon and stays signed in by default.
- **Sunset Mist theme on the public pages**, light and dark (follows the device, with a toggle), on the guest list form, the photo page, registration and the portal. Nunito is now served by the site itself instead of Google Fonts.
- **Automatic processing (Cloud Functions, optional).** Deploy the functions shipped with the form site and signups, profile edits, account links and QR e-mails are handled within seconds, without a NoctuList device online. Without a server, devices take over after 2 minutes as before. The server's QR e-mail has no Wallet passes. Settings, Firebase shows "Automatic processing is on". Setup is in the Firebase guide and FIREBASE_SETUP.md.
- **QR e-mails from the app through the site.** On a device without a Gmail account, the QR e-mail dialog offers "Send through the site": the app builds the same e-mail (template, logo, Wallet pass) and the site's server sends it from the association's Gmail. A device with Gmail set up still sends with its own account first. Needs automatic processing deployed and the portal on.
- **Upcoming shifts** now show what the volunteer will earn for each one.
- **Today's benefits** are shown in the volunteer space even when no NoctuList device is online.
- **QR code in the volunteer space.** The "My QR code" block has a "My QR" button that opens the code full screen, with "Send me my QR code" as a small link under it. Once the space is installed on the home screen it works offline, and the QR code stays on the device until sign-out. Never shown for admin volunteers. Settings, Firebase, Volunteer portal: "Show the QR code in the volunteer space", on by default. Needs the Firestore rules **v16** and a redeploy of the form site. Wallet passes are unchanged.
- **Rank removed from the volunteer space.** Volunteers no longer see their rank.
- **Sign in with an association Google account (optional).** Associations with Google Workspace list their domains in Settings, Firebase, Volunteer portal (each checked by a DNS lookup; gmail.com is refused), then turn on "Connexion avec un compte Google". The admin sets the "Compte Google associatif" on the volunteer record and the volunteer links it at first sign-in, or, if none is set, links it themselves from "Mon compte" with "Associer mon compte Google" and can remove only a link they made. The Google account stays a separate Firebase account, so staff who are also volunteers keep their staff rights. E-mail and password sign-in still works.
- **Temporary guest accesses per venue.** The special accesses (stage, backstage, VIP…) are now edited in each venue's edit dialog instead of a separate settings card. The temporary guest credits switch moved to Settings, sync, Fonctions en ligne.
- **Sync settings in four pages.** Settings, sync is now split into Connexion, Équipe, Fonctions en ligne and Sauvegarde (Connexion and Synchronisation with Google Sheets). Each page shows its title once, each block keeps its explanation behind a "?", the "last sync" and "last export" times stay current while the page is open, and "Enregistrer" on the Google Sheets page confirms in place. A Sheets sync interval that is not in the menu (for example 10 minutes) is shown as it is.

### Fixes

- **Deleting a volunteer** now also removes, right away, their portal page, QR code, portal photos, pending requests and Google link, instead of waiting for the next publish.

### Security

- Firestore rules **v16** and new Storage rules. A volunteer can only read their own filtered page; NFC data and the admin flag never leave the devices, and the wallet QR id only reaches its own non-admin volunteer (by e-mail or in their space). E-mail verification is required, every write is checked field by field, requests are throttled server-side, and the whole portal is closed by the rules while the setting is off.
- E-mail/password accounts can never act as team members, even with an invitation code.
- Strict Content-Security-Policy and security headers on the form site. Portal pages (`/v/**`, `/portal.html`) use `Cross-Origin-Opener-Policy: same-origin-allow-popups` for the Google window and their Content-Security-Policy also allows `https://apis.google.com` and frames from the site itself; Firebase's reserved `/__/` paths may be framed by the site itself only. Every other page keeps the strict policy.

### Upgrade notes

- **Version code:** 44 (`3.0.0`). Minimum supported code remains **27** (2.1.0). The update is optional, but recommended on every device of a team that turns the portal on.
- **Database:** Room 51 to 52 (volunteer `portalEnabled`, `portalUid`), then 52 to 53 (`portalGoogleEmail`, `portalGoogleUid`, `portalGoogleLinkedBy`).
- **Firestore and Storage rules:** republish both (`NOCTULIST_RULES_VERSION = 16`).
- **Hosting:** export the form site again from the app and redeploy it (new security headers).
- **All devices:** update NoctuList on every device. Older versions publish the old page format and do not wait for the server.
- **Automatic processing (optional):** Blaze plan, Gmail app password, then `firebase deploy --only hosting,functions` from the exported folder (see FIREBASE_SETUP.md). Redeploy after this update: the new `onMailOutboxWritten` function sends the app's QR e-mails and the new `portalGoogleClaims` trigger links Google accounts.
- **Firebase Console, before turning the portal on:** Authentication, Sign-in method, enable **Email/Password**. Recommended: keep **Email enumeration protection** on. For Google sign-in, the Google provider is already on for the team; with a custom portal domain, add it under Authentication, Settings, Authorized domains. In Google Cloud Console, APIs and Services, Credentials, add to the Authorized redirect URIs of the Web OAuth client used by Firebase: `https://<project>.web.app/__/auth/handler`, `https://<project>.firebaseapp.com/__/auth/handler` and the same path on any custom portal domain.
- **Check once after deploying:** the Google sign-in window was verified on the emulators only. On the real site, try "Se connecter avec Google" and "Associer mon compte Google", including on an iPhone. `redirect_uri_mismatch` means the redirect URI above is missing.
- **Known limits:** while a link exists, the linked Google account can still see and change its own volunteer's photo even after the option or its domain is turned off (Storage rules read at most 2 documents). Turning the option off signs out live Google sessions only when their page next refreshes from the server.

---

## 2.1.16 — 2026-11-09

Optional patch: the people counter is visible again on tablets in portrait.

### Fixes

- **People counter on tablet, portrait** — since 2.1.15 only the venue chips and the priority switch were shown; the count itself had no height. The counter now gets the same height as in landscape.

### Upgrade notes

- **Version code:** 43 (`2.1.16`). Minimum supported code remains **27** (2.1.0) — optional update for 2.1.0–2.1.15.
- **Database, Firestore and Storage rules, hosting:** unchanged.
- **Publishing:** tag `2.1.16`, then copy `version.json` to the AdminList update manifest. Do not raise `minSupportedVersionCode`.

---

## 2.1.15 — 2026-11-08

Optional patch: Android layout fixes for guest-list windows, profiles and the tablet Billetterie screen.

### Fixes

- **Windows after a rotation (Android)** — guest-list windows, profiles and other full-width dialogs no longer run off the right or bottom edge after turning the device. The app pinned the screen size it started with, so dialogs kept the size of the other orientation; it now only overrides language and density, and open dialogs resize when the device turns.
- **Guest form on phones** — the per-access “max requests” field moves under its access when there is not enough width, instead of being cut off on the right.
- **Date picker** — the calendar scrolls when the screen is short (phones in landscape) and keeps a margin from the screen edges.
- **Profiles on phones** — guest and volunteer profiles keep a small margin from the screen edge, like on tablets.
- **Billetterie on tablet, portrait** — the Guest list and POS buttons sit side by side above the people counter, which now uses the full width. Landscape is unchanged.
- **Billetterie settings icon** — on tablet and desktop, the settings icon follows the theme colour instead of staying black.

### Upgrade notes

- **Version code:** 42 (`2.1.15`). Minimum supported code remains **27** (2.1.0) — optional update for 2.1.0–2.1.14.
- **Database, Firestore and Storage rules, hosting:** unchanged.
- **Publishing:** tag `2.1.15`, then copy `version.json` to the AdminList update manifest. Do not raise `minSupportedVersionCode`.

---

## 2.1.14 — 2026-11-08

Optional update: ask a guest or volunteer for their profile photo with a link, and the public pages animate again on phones.

### Features

- **Photo requests** — a profile has an “Ask the person” button next to Add / Remove photo, on desktop and Android. It shows a QR code and a link to copy, valid for 7 days. Opening it again for the same person shows the same link.
- **Public upload page** — the link opens `/p/{org}/{request}` on the guest-form site, in the same style. The person picks a photo, sees a round preview, and taps Upload. The photo is resized to 1024 px JPEG in the browser before it is sent. The page then thanks them and closes itself where the browser allows it.
- **Automatic profile update** — the device that created the request stores the photo on the matching profile through the usual photo pipeline, then deletes the request and the temporary file. The dialog closes by itself when the photo arrives. If it was closed earlier, the device still finishes the request in the background while NoctuList is open.
- **Languages** — the app strings exist in every app language; the public page speaks FR, EN, ES, DE and IT.

### Fixes

- **Public pages on phones** — the topographic background no longer collapses into a flat colour. Phones ran the shader at 16-bit precision, which overflowed the noise; it now uses high precision when the GPU supports it, and falls back to the themed gradient if the shader cannot compile. Lines keep the same size on phones and desktop.

### Upgrade notes

- **Version code:** 41 (`2.1.14`). Minimum supported code remains **27** (2.1.0) — optional update for 2.1.0–2.1.13.
- **Database:** unchanged. Open photo requests are kept in local settings on the device that created them.
- **Firestore rules:** version 11 — republish `firebase/firestore.rules` (new `photoRequests` collection: public targeted get and a single `OPEN → UPLOADED` update while open and unexpired).
- **Storage rules:** republish `firebase/storage.rules` (anonymous JPEG upload under 3 MB to `orgs/{org}/photoRequests/{request}.jpg`, only while the request is open).
- **Hosting:** redeploy the guest-form site (`webform/`): new `photo.html`, `photo.js`, `photo-i18n.js`, the `/p/**` rewrite in `firebase.json`, and the fixed `topo.js`.
- **Publishing:** tag `2.1.14`, then copy `version.json` to the AdminList update manifest. Do not raise `minSupportedVersionCode`.

---

## 2.1.13 — 2026-10-21

Optional patch: Linux desktop first launch, webcam, and first sync.

### Fixes

- **Linux UI scale** — the first launch reads the GNOME or KDE scale before the window opens, so a HiDPI Ubuntu desktop is no longer drawn tiny until the next start.
- **Linux webcam** — the scanner stays on an advertised mode at or below 720p, and reopens the camera if the stream stalls after a few seconds.
- **Linux sync** — the first synchronization uses HTTPS instead of the gRPC channel that closed the app. A fatal sync error is shown in the wizard instead of quitting the process.
- **macOS, Windows, Android** — same Firestore channel and scanner resolution as before.

### Upgrade notes

- **Version code:** 40 (`2.1.13`). Minimum supported code remains **27** (2.1.0) — optional update for 2.1.0–2.1.12.
- **Database / Firestore rules / Hosting:** unchanged.
- **Publishing:** tag `2.1.13`, then copy `version.json` to the AdminList update manifest. Do not raise `minSupportedVersionCode`.

---

## 2.1.12 — 2026-10-21

Optional patch: a flashlight toggle on the phone and tablet QR scanner.

### Features

- **QR scanner** — a flashlight button on the phone and tablet camera preview turns the rear LED on or off, for scanning in the dark. It sits at the bottom-right of the preview and leaves the scan window clear. Devices without a rear flash do not show the button.
- **Desktop** — unchanged. There is no flashlight on the desktop scanner.

### Upgrade notes

- **Version code:** 39 (`2.1.12`). Minimum supported code remains **27** (2.1.0) — optional update for 2.1.0–2.1.11.
- **Database / Firestore rules / Hosting:** unchanged.
- **Publishing:** tag `2.1.12`, then copy `version.json` to the AdminList update manifest. Do not raise `minSupportedVersionCode`.

---

## 2.1.11 — 2026-10-10

Optional patch: QR email titles are editable, and automatic emails are signed with the association name.

### Features

- **QR emails** — the heading inside volunteer, guest, and grouped guest-list messages is editable in Settings → Email. Current titles stay the defaults, including “Guestlist artiste” for the grouped list.
- **Grouped guest list** — the single email that carries every QR can be used for an institution. The heading is no longer fixed to an artist title.
- **Signature** — automatic emails are signed with the association name from settings, instead of a hardcoded “Collectif Nocturne”.
- **Sync** — titles, subjects, and the association name are shared across devices of the same organization.

### Upgrade notes

- **Version code:** 38 (`2.1.11`). Minimum supported code remains **27** (2.1.0) — optional update for 2.1.0–2.1.10.
- **Database / Firestore rules / Hosting:** unchanged.
- **Publishing:** tag `2.1.11`, then copy `version.json` to the AdminList update manifest. Do not raise `minSupportedVersionCode`.

---

## 2.1.10 — 2026-10-02

Optional patch: manual shifts credit the volunteer account, and the door can validate guest forms.

### Features

- **Manual shifts** — « Boissons gratuites » is an account credit in CHF, paid when the shift day starts (same timing as Nova, Orion, and Galaxie). A previously saved drink count is that amount, not multiplied by 5 CHF. It is no longer shown as drink tokens.
- **Manual shifts** — the percentage is labeled « Réduction » and applies in the POS only during the configured duration, on eligible cash or card payment. Account credit is still charged at full price.
- **Manual shifts** — « Invités accompagnants pendant la durée » is the number of extra people the volunteer can bring in while the duration is active, shown at the door.
- **Guest forms** — optional setting so the read-only billeterie guest list can accept or refuse pending forms. Off by default, Firebase only.

### Upgrade notes

- **Version code:** 37 (`2.1.10`). Minimum supported code remains **27** (2.1.0) — optional update for 2.1.0–2.1.9.
- **Database / Firestore rules / Hosting:** unchanged.
- **Publishing:** tag `2.1.10`, then copy `version.json` to the AdminList update manifest. Do not raise `minSupportedVersionCode`.

---

## 2.1.9 — 2026-09-22

Optional patch: desktop webcam preview stays fluid, and macOS asks for camera access before scanning.

### Fixes

- **Desktop QR** — the webcam preview no longer waits on ZXing. Video stays smooth while the same multi-pass decoder keeps reading the latest full-resolution frame, including printed cards.
- **macOS** — camera permission is requested before devices are listed, so the system prompt appears instead of an empty webcam list. A denial points to System Settings → Privacy & Security → Camera.
- **macOS** — the signed app itself carries the camera entitlement (not only the bundled JRE), and Continuity Camera is allowed.

### Upgrade notes

- **Version code:** 36 (`2.1.9`). Minimum supported code remains **27** (2.1.0) — optional update for 2.1.0–2.1.8.
- **Database / Firestore rules / Hosting:** unchanged.
- **Publishing:** tag `2.1.9`, then copy `version.json` to the AdminList update manifest. Do not raise `minSupportedVersionCode`.

---

## 2.1.8 — 2026-09-21

Optional patch: announcement compose dialog fits on phones, and POS can send announcements.

### Fixes

- **Announcements** — the send dialog no longer overflows off the right edge on phones. Width/height are capped to the screen; the form scrolls and Cancel / Send stay on screen. Tablets keep the existing inset.
- **POS** — category rail can open “send announcement” when non-admin send is enabled.
- **Admin session** — idle timeout follows real typing (including the Android soft keyboard), keyguard lock, and desktop pointer/keyboard activity; screen-off or OS lock still returns to welcome.
- **Desktop QR** — webcam scan prefers HD capture and extra ZXing passes so printed cards decode, not only bright phone-screen codes.

### Upgrade notes

- **Version code:** 35 (`2.1.8`). Minimum supported code remains **27** (2.1.0) — optional update for 2.1.0–2.1.7.
- **Database / Firestore rules / Hosting:** unchanged.
- **Publishing:** tag `2.1.8`, then copy `version.json` to the AdminList update manifest. Do not raise `minSupportedVersionCode`.

---

## 2.1.7 — 2026-09-21

Optional patch: more reliable automatic admin logout on Android and desktop.

### Fixes

- **Admin session** — idle timeout (10 minutes) now counts real input: touch, mouse, keyboard, scroll wheel, and text-field typing (including the Android soft keyboard). Editing a list or form no longer logs you out mid-work.
- **Admin session** — the idle clock resets when entering admin, so a long stay on the welcome screen cannot expire the session immediately after login.
- **Admin session** — turning the screen off or locking the device / OS session returns to welcome, even without a PIN, so a shared tablet or unattended PC cannot be reused as admin.

### Upgrade notes

- **Version code:** 34 (`2.1.7`). Minimum supported code remains **27** (2.1.0) — optional update for 2.1.0–2.1.6.
- **Database / Firestore rules / Hosting:** unchanged.
- **Publishing:** tag `2.1.7`, then copy `version.json` to the AdminList update manifest. Do not raise `minSupportedVersionCode`.

---

## 2.1.6 — 2026-09-20

Optional patch: import shifts from a Google Doc or Sheet (Desktop + Firebase).

### Features

- **Drive import** — from Add a shift, pick a recent Doc/Sheet or paste its URL. `@` mentions (Google chips and plain text) are matched to NoctuList volunteers, then reviewed (profile, shift type, date) before bulk create. Unique first names are preselected with low confidence; first + last name with medium confidence. Several people sharing a first name stay unselected.
- **Drive import** — loading animation while recent Drive files are fetched.

### Upgrade notes

- **Version code:** 33 (`2.1.6`). Minimum supported code remains **27** (2.1.0) — optional update for 2.1.0–2.1.5.
- **Database / Firestore rules / Hosting:** unchanged.
- **Platform:** Desktop only in this release. The institution Google Cloud project must enable Drive, Docs, Sheets and People APIs; see `GOOGLE_DRIVE_IMPORT_SETUP.md`.
- **Publishing:** tag `2.1.6`, then copy `version.json` to the AdminList update manifest. Do not raise `minSupportedVersionCode`.

---

## 2.1.5 — 2026-09-11

Optional patch: a Catalog page to manage artist guest forms, and a Firebase sync-pill fix after closing a form.

### Features

- **Artist forms** — admin Catalog in Settings lists open forms, pending reviews (read-only here), and forms closed in the last 48 hours. Open forms can be edited, have their public link copied, or be closed (status `EXPIRED`). Multi-response templates expire without rejecting already-submitted children.

### Fixes

- **Firebase** — closing a form no longer queues leftover `pending_remote_writes` after the public document is already `EXPIRED`. The sync pill no longer shows “N pending” for writes that already landed. A remote `OPEN` snapshot cannot reopen a decided or pending form.

### Upgrade notes

- **Version code:** 32 (`2.1.5`). Minimum supported code remains **27** (2.1.0) — optional update for 2.1.0–2.1.4.
- **Database / Firestore rules / Hosting:** unchanged.
- **Publishing:** tag `2.1.5`, then copy `version.json` to the AdminList update manifest. Do not raise `minSupportedVersionCode`.

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
