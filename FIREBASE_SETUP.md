# Firebase setup (opt-in)

Sheets remains the default backend. Firebase is additive: **one admin** configures the project,
then other devices **join** (QR / follow) without typing API keys.

In the app, open the **?** help on Firebase sync / migration for the same walkthrough with a
choice of console.

## OAuth: two separate clients (by design)

| Purpose | Who owns it | What the app uses |
|--------|-------------|-------------------|
| **Gmail / Sheets email** | App developer | Desktop `gmail_oauth_client.json` — same on every institution |
| **Firebase Google Sign-In** | **Institution** | Firebase/Google Cloud **Web client ID + Client secret** |

Do **not** reuse the Gmail Desktop OAuth JSON for Firebase Sign-In.

## Choose a console

Pick **one** complete method. Values end up the same in NoctuList.

| Method | Best when… |
|--------|------------|
| **A — Firebase Console** (recommended) | You want clearer screens for Auth, Firestore, and web config |
| **B — Google Cloud Console** | You already live in `console.cloud.google.com` (e.g. Sheets) |

Both need a short visit to the **other** console once:

- Firebase path → Cloud Credentials for **Web client secret** + `localhost` redirects.
- Cloud path → Firebase for **Authentication → Google** and **Application ID (appId)**.

---

### Method A — Firebase Console (recommended)

1. Open [console.firebase.google.com](https://console.firebase.google.com) → Add project  
   (or **Use an existing Google Cloud project** if Sheets already uses one).
2. **Build → Firestore** → Create database (production, region near you / EU if required).
3. **Firestore → Rules** → paste [`firebase/firestore.rules`](firebase/firestore.rules) → **Publish**.
4. **Authentication → Sign-in method → Google** → Enable. Note the **Web client ID**.
5. **Project settings → General → Your apps → Web** → add a Web app if needed → copy  
   `apiKey`, `appId`, `projectId` (or paste the whole snippet in NoctuList).
6. **Web client secret (Desktop + Android):** [Cloud Console → Credentials](https://console.cloud.google.com/apis/credentials)  
   → same **Web application** OAuth client → copy **Client secret**.  
   Add them under **Authorized redirect URIs** (French: *URI de redirection autorisées*) —
   **not** under *Authorized JavaScript origins* / *Origines JavaScript autorisées*
   (origins cannot include `/Callback`). Add all of:
   - `http://localhost:8889/Callback`
   - `http://localhost:8888/Callback`
   - `http://localhost:8765/Callback`
   - `http://localhost:9090/Callback`
7. **API key (Desktop):** same Credentials page → the Browser/API key from the Firebase web
   config. For NoctuList Desktop, set **Application restrictions = None** (HTTP-referrer
   restrictions cause `API key not valid` on Identity Toolkit). Optionally restrict by API to
   Identity Toolkit API + Token Service API only. Enable **Identity Toolkit API** in the library
   if prompted.
8. In NoctuList: Org ID + those values → Sign-In → join QR for other devices.  
   **Optional photos:** skip Storage to stay on Spark — see [Optional — profile photos](#optional--profile-photos-firebase-storage) below.

### Method B — Google Cloud Console

1. Open [console.cloud.google.com](https://console.cloud.google.com) → institution project (Sheets project if any).
2. Enable **Cloud Firestore API** → create Firestore database if needed.
3. **Firestore → Rules** → paste rules → Publish  
   (or Firebase → Firestore → Rules if Cloud has no editor).
4. [Firebase Console](https://console.firebase.google.com) → same project → **Authentication → Google** → Enable.
5. **APIs & Services → Credentials**:
   - API key
   - OAuth **Web application** client → **Client ID + Client secret**  
     (not the developer Gmail Desktop JSON)
   - Redirect URIs (Desktop + Android — `localhost`):
     - `http://localhost:8889/Callback`
     - `http://localhost:8888/Callback`
     - `http://localhost:8765/Callback`
     - `http://localhost:9090/Callback`
6. **Application ID:** Firebase → Project settings → Web app → `appId`  
   (or paste Firebase web config into NoctuList).
7. Same NoctuList steps as Method A. **Optional photos:** skip Storage to stay on Spark — see below.

### Optional — profile photos (Firebase Storage)

**Skip this entire section to stay on the free Spark plan.** Enabling Storage can require a paid Blaze plan. The rest of NoctuList (wizard, join, sync, guest list, volunteers, scanner, ticketing) works without Storage. Circles still show initials.

If you want profile photos later:

1. Firebase Console → **Build → Storage** → Get started (same project).
2. **Storage → Rules** (URL must contain `/storage/rules`, not Firestore) → paste [`firebase/storage.rules`](firebase/storage.rules) → **Publish**. Republish if you already published an older copy: members must be allowed to **delete** profile photos (a single `write` rule with `request.resource` size/type checks blocks Remove).
3. In NoctuList **Settings → Firebase**, turn on **Enable profile photos** (off by default). This setting syncs to every device in the organization.

Without that toggle, there is no upload UI and no Storage `put`. You can copy the same rules from the in-app Firebase help (`?`).

### Optional — import shifts from Google Docs/Sheets (Desktop)

A shorter operator-facing copy of this section lives in [GOOGLE_DRIVE_IMPORT_SETUP.md](GOOGLE_DRIVE_IMPORT_SETUP.md).

**Skip this section unless you want to create shifts from a planning document or meeting minutes.**
With it enabled, an admin opens **Admin → Shifts → Add Shift → Import**, picks a Google Doc or
Sheet, and NoctuList extracts every person mentioned in it (contact smart chips and plain
`@Name` text), matches each one to a volunteer, and creates the shifts in one pass.

This is **Desktop only** for now. Android discards the OAuth refresh token during sign-in, so
there is no durable credential to call the Google APIs with; the entry point simply does not
appear there.

1. Google Cloud Console (**the same project as your Web OAuth client**) → **APIs & Services →
   Library** → enable all four: **Google Drive API**, **Google Docs API**, **Google Sheets API**,
   **People API**.
2. **APIs & Services → OAuth consent screen → Data access** → add the four scopes:

   | Scope | Google class | Used for |
   | --- | --- | --- |
   | `.../auth/documents.readonly` | Sensitive | Reading a Google Doc |
   | `.../auth/spreadsheets.readonly` | Sensitive | Reading a Google Sheet |
   | `.../auth/contacts.readonly` | Sensitive | Phone/birthday enrichment, which sharpens matching |
   | `.../auth/drive.metadata.readonly` | **Restricted** | The "Recent documents" list only |

3. In NoctuList **Settings → Firebase**, turn on **Import shifts from Google documents** (off by
   default). The setting syncs to every device in the organization.
4. **Sign out and sign in again.** The extra scopes are requested as part of Google Sign-In, so an
   existing session does not have them. If you skip this, the import dialog offers a *Grant Google
   Drive access* button that runs the consent flow on its own.

Notes on the scopes:

- `drive.metadata.readonly` is a **restricted** scope. If you publish the consent screen to
  *External / In production*, Google requires a (paid) third-party security assessment for
  restricted scopes. Two ways to avoid that: keep the app in **Testing** with your admins as test
  users, or use an **Internal** consent screen if you have Google Workspace. You can also simply
  not grant it — everything still works, the **Recent documents** tab disappears, and admins paste
  a document link instead.
- Google may grant a subset of what was requested. NoctuList records what it actually received and
  enables only the matching parts of the import.
- If the whole authorization is rejected (typically because the APIs above are not enabled),
  **sign-in is retried automatically with the basic identity scopes so login never breaks**. The
  reason is shown under the toggle in Settings → Firebase.
- Sheets person chips carry only an email address, with no display name — that is a Sheets API
  limitation. The People API enrichment fills the name back in; without the contacts scope, the
  review list shows the email's local part instead.

### Android Sign-In (Web OAuth loopback — no SHA-1 per institution)

Phones and tablets use **Chrome Custom Tabs** with your institution **Web OAuth client** — the **same localhost redirect URIs as Desktop**. You do **not** register an Android app or SHA-1 in each institution Firebase project.

1. Confirm step 6 redirect URIs are present on the institution **Web application** OAuth client (same list as Desktop).
2. In NoctuList on the device: paste **Web client ID** and **Web client secret** (join QR includes both for team devices).
3. Tap **Sign in with Google** — Chrome opens briefly; NoctuList listens on `http://localhost:PORT/Callback` and completes sign-in.

**Do not** add Firebase → Android app `com.eventmanager.app` + SHA-1 for Sign-In. That path conflicts with other Google Cloud projects and causes `DEVELOPER_ERROR`.

**Do not** use custom-scheme redirect URIs (`com.eventmanager.app:/…`) on a Web OAuth client — Google returns `invalid_request`.

**Google Play:** Play App Signing SHA-1 is unrelated to Firebase Sign-In with this flow.

Redirect URI constant in code: `InstitutionGoogleWebOAuth.kt`.

---

## Admin device only

In Admin → Sync (Firebase) or Setup wizard → “Set up new Firebase”:

1. Paste web config **or** fill fields (secrets masked by default).
2. Enter institution **Web client ID** + **Web client secret** (required on Desktop and Android).
3. Set **Org ID**, Sign in with Google.
4. Show / copy the **join QR** (includes project options + Web client credentials + invitation code).
5. Sheets→Firebase migration dual-announces options so peers can follow without retyping.
6. If the invitation code is missing on the admin device, use **Create invitation code** in Settings before sharing the QR.

Gmail OAuth stays in Email settings (developer Desktop JSON) — never put that file in the join QR.

## Other devices (join)

- **New install** → Setup → Firebase → **Join existing institution (QR)** → Sign-In only. One full v1 scan is enough.
- Older v2 QRs omit the OAuth secret and/or invitation — those need manual entry and often cause “Permission denied” on first sync.
- **Already on Sheets** after migration → follow dialog applies options silently → Sign-In → Connect.

Treat the join QR as a physical deployment secret.

## Example `google-services.json`

Copy [`app/google-services.json.example`](app/google-services.json.example) to
`app/google-services.json` and replace placeholders. Do **not** commit real secrets.

## Auth summary

1. Enable Google provider in Firebase Authentication.
2. Institution Web client ID (+ secret on Desktop).
3. Joiners: Sign-In after QR/follow.
4. First admin sign-in upserts `orgs/{orgId}/members/{uid}` as admin.
5. **Optional — allowed email domains** (Admin → Firebase Sync). Republish
   [`firebase/firestore.rules`](firebase/firestore.rules) so rules include `emailDomainAllowed`.

## Release smoke checklist (staging org)

1. Admin configures once → QR → second device joins → Sign-In → first pull OK.
2. Peer follow after Sheets→Firebase: secrets hidden; Connect succeeds.
3. CRUD guest / job / temp guest → second device.
4. POS sale → transfer + balance.
5. Soft-lock + follow still works.

Only then migrate a real institution.

## GDPR / data protection (institution responsibilities)

This app syncs **personal data** (guest/volunteer identity, NFC, POS ledger with holder names,
member emails) to Google Firestore under your `orgId`. Sheets remains optional and, if you use
the mirror exporter, creates a **second copy**.

### Who is responsible

- The **institution** (event organizer) is the controller (responsable de traitement) for how
  guest, volunteer, and staff data are collected and used.
- Google acts as a processor for Firebase/Firestore under Google’s Cloud / Firebase terms and
  [Data Processing Addendum (DPA)](https://cloud.google.com/terms/data-processing-addendum).
  Accept/sign the DPA in Google Cloud/Firebase Console for your project before production use.
- NoctuList does not replace your privacy notice, consent/base légale assessment, or DPIA.

### Region and residency

- Prefer a Firebase/Firestore location in the **EU** (or another region required by your policy)
  when creating the database. Location is fixed at creation — choose carefully.
- Document the chosen region in your processing register.

### Deploy security rules (required)

Access control is enforced by [`firebase/firestore.rules`](firebase/firestore.rules). After any
rules change (or first setup):

1. Open Firebase Console → **Firestore** → **Rules** (URL must contain `/firestore/rules`, not
   Realtime Database).
2. Paste the exact file contents (or deploy via Firebase CLI) and **Publish**.
3. Confirm the in-app tutorial clipboard still matches the repo file
   (`shared/.../composeResources/files/firestore.rules`).

**Google Workspace projects:** publishing needs `firebaserules.releases.create` — Owner, Editor,
or *Firebase Rules Admin* on the Google Cloud project. When the Workspace organization owns the
project and the person doing the setup only has a limited role, **Publish** is unavailable, the
database keeps the production-mode default (`allow read, write: if false`), and every NoctuList
write fails with `PERMISSION_DENIED`. Have the organization grant that role before the first
migration — the app never deploys rules on its own.

Hardened expectations (post–GDPR audit remediations):

- Only **org members** can read `members` (not every signed-in Google user who knows `orgId`).
- Member `role` must be one of `admin` | `door` | `pos`; first bootstrap self-create is **admin**
  only while `metadata/config` does not exist; later invites require an existing admin.
- `transfers` stay **append-only** (no update/delete via admin catch-all).
- **Institution settings** (`orgs/{orgId}/institutionSettings/*` — currency, email templates,
  purchase credit buffer, profile photos flag, Sheets mirror, etc.) are **writable only by
  Firebase org admins** (`members.role == admin`). Local admins and POS `member` devices can
  still **read** them. Republish rules after this change; the catch-all write path must also
  exclude `institutionSettings`.

### Erasure and portability limits

- Factory reset / local wipe clears **device** data only — not Firestore.
- The ledger (`transfers`) is append-only by design; the app does **not** guarantee subject
  erasure or anonymization of historical transfers (`holderName`, amounts, etc.).
- Plan an institution process for access/portability requests and, if needed, manual Console
  edits or a future admin anonymization tool. Retention should be defined in your policy.
- Optional Sheets mirror = extra copy to delete or restrict separately.

### Desktop session token

Desktop stores an OpenID `id_token` under the app data directory to restore Firebase Auth.
Treat the machine as trusted; Sign out clears the file. File permissions are restricted to the
owner where the OS allows it — this is not full at-rest encryption.
