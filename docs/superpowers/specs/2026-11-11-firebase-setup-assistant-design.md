# Firebase setup assistant

Date: 2026-11-11 · Branch: `feature/volunteer-portal` · Status: to be reviewed by the project owner.
Replaces the content of `FirebaseSetupTutorialDialog` and `GuestFormHostingGuide`.

## 1. Goal

Setting up Firebase for an association is long, scattered and error prone today: copy-paste of
rules, a zip to unzip, Terminal commands, Google errors on first deploy (Secret Manager not
enabled, Eventarc permissions still propagating). Goal: one friendly page that

- covers every step, from an empty Google account to the optional extras;
- does by itself everything the app can do on the admin's computer, with a "Run" button;
- reduces each human-only step to one button that opens the exact page and, when needed, one
  field to paste the result;
- keeps a complete manual guide for when automation fails.

Success: an admin with no command-line knowledge goes from nothing to a working setup (including
the volunteer space and automatic processing) without opening a Terminal, and today's two deploy
errors cannot happen.

## 2. Decisions validated with the owner

| Topic | Decision |
|---|---|
| Platform | Automation on the desktop app only (macOS, Windows, Linux). Android shows the manual tab only. Phones keep joining by QR. |
| Tools | Node.js 22 and the Firebase CLI are downloaded only when the admin presses "Get the tools", into the app's own folder. Never for other users. If both are already installed and suitable, they are used. |
| Layout | Two tabs: "Automatic" (step cards with Run / Open buttons) and "Manual" (same steps, same numbers, plain instructions with copy blocks). |
| Look | Same visual language as the rest of the app (Mist colors, cards). No mockup round. |
| Copy | Short plain sentences, no em dashes, no AI-sounding phrasing. 7 app languages. |

## 3. Steps

Same numbering in both tabs. Each step has: id, title, one-sentence purpose, kind (`RUN`,
`OPEN`, `RUN_THEN_OPEN`), a "done" probe, and an anchor into the manual tab.

| # | Step | Kind | What the app does | What the human does |
|---|---|---|---|---|
| 0 | Get the tools | RUN | Detects suitable `node` (>= 22) and `firebase` on PATH; otherwise downloads Node.js 22 (official archive, SHA-256 checked against `SHASUMS256.txt`) and runs `npm install firebase-tools` into `~/.noctulist/tools/`. Progress bar. | Nothing. |
| 1 | Sign in to Firebase | RUN | `firebase login` (browser opens), then `firebase login:list --json` to show the account. | Chooses the Google account in the browser. |
| 2 | Project | RUN | `projects:list --json`, picker; or `projects:create <id> --display-name <name>` (which also adds Firebase). Saves the project ID in settings. | Picks or names a project. |
| 3 | Database | RUN | If `firestore:databases:list` has no `(default)`: location picker (Zurich `europe-west6` first, then `eur3`, then the rest from `firestore:locations`), then `firestore:databases:create "(default)" --location <loc>`. | Picks a location. |
| 4 | Security rules | RUN | Deploys `firestore.rules` from the app's site workspace (§5): `deploy --only firestore:rules`. | Nothing. |
| 5 | Sign-in methods | OPEN | Opens `console.firebase.google.com/project/<id>/authentication/providers`. | Turns on Google (and Email/Password if the volunteer space will be used), presses "I did it". |
| 6 | Connect NoctuList | RUN_THEN_OPEN | `apps:list WEB --json`; creates `apps:create WEB NoctuList` if none; `apps:sdkconfig WEB <appId>`; fills Project ID, API key and App ID in settings. Opens `console.cloud.google.com/apis/credentials?project=<id>`. | Pastes the Web client ID and secret (two fields), then signs in with Google as today. |
| 7 | Public forms site (optional) | RUN | Materializes the site workspace and runs `deploy --only hosting`. Shows the site URL. | Nothing. |
| 8 | Profile photos (optional) | OPEN then RUN | Opens the Storage page; then deploys `storage.rules` (`deploy --only storage`) and turns on the existing "Enable profile photos" setting. | Upgrades to Blaze and clicks "Get started" in Storage, presses "I did it". |
| 9 | Automatic processing (optional) | OPEN then RUN | See §4. | Upgrades to Blaze and sets a budget alert (one Open button each), creates the Gmail app password (Open button) and pastes it plus the Gmail address. |

Optional steps 7 to 9 sit under "Optional extras", each with an on/off switch; 9 requires 7 and
the volunteer portal setting.

Done probes (run when the page opens and after each step, fast and read-only): tools present,
`login:list` non-empty, project ID set and listed, `(default)` database exists, rules version
deployed matches the app (reuse the existing rules-version check), providers confirmed by the
user, settings filled, hosting URL responds, Storage rules deployed (recorded locally),
`functions:list --json` contains `onSignupWritten` and `onPortalRequestWritten` in the chosen
region. Probe results and "I did it" confirmations are stored per project in settings so the
page resumes where it stopped.

## 4. Automatic processing sequence (step 9)

Order chosen so that neither first-deploy error from 2026-11-11 can happen:

1. Region: `firestore:databases:get "(default)" --json` gives `locationId`; mapped to a
   functions region (`eur3` → `europe-west1`, `nam5` → `us-central1`, a single region maps to
   itself). Shown, editable.
2. Params file: write `functions/.env.<projectId>` in the site workspace with `GMAIL_USER` and
   `REGION`, so the deploy never prompts.
3. Secret: `functions:secrets:set GMAIL_APP_PASSWORD --data-file - --force`, password written to
   the process stdin then dropped from memory. This also enables the Secret Manager API before
   the deploy needs it. Never written to disk, logs or settings.
4. `npm install` in `functions/` (bundled Node).
5. `deploy --only hosting,functions --non-interactive --force`.
6. Retry policy: if the output matches the Eventarc or "API recently enabled / retry in a few
   minutes" signatures, the card shows "Google is getting ready, next try in 2 min" with a
   countdown and retries, up to 5 times. Other errors stop with a plain message.
7. `functions:artifacts:setpolicy --location <region> --force`.
8. Done when `functions:list` shows both functions; the existing "Automatic processing is on"
   indicator appears after the first request.

## 5. Site workspace

The app writes a working folder `~/.noctulist/firebase-site/<projectId>/` from its bundled
resources before every RUN step that deploys: site files, `functions/` (package files and
`lib/`), `firestore.rules`, `storage.rules`, `.firebaserc` with the project, and a
`firebase.json` with `hosting`, `functions`, `firestore.rules` and (when step 8 is on)
`storage.rules`. `node_modules` is kept between runs to avoid reinstalling.

Fix included: `hosting.ignore` gains `functions/**` (and in the exported zip as well), so the
functions code and its libraries are never published on the public site. The "Export the form
site" button stays for the manual tab.

## 6. Components

All automation lives in `shared/src/desktopMain/.../setup/`; models and UI in commonMain.

- `SetupStep`, `SetupStepState` (commonMain): step ids, kinds, states (`TODO`, `RUNNING`,
  `WAITING_RETRY`, `DONE`, `ERROR`, `SKIPPED`), stored progress.
- `SetupAutomation` (expect/actual): desktop actual runs steps; Android actual reports
  "unavailable", which hides the Automatic tab.
- `ToolsInstaller` (desktop): detection, download with checksum, extraction, `npm install`,
  removal. Platform matrix: macOS arm64/x64 `.tar.gz`, Windows x64 `.zip`, Linux x64 `.tar.xz`.
- `FirebaseCli` (desktop): runs the fixed command set above with `ProcessBuilder`, PATH pointing
  to the bundled Node first, `--json` where available, line-streamed output, stdin for secrets,
  cancel. Only constant command templates with validated arguments (project ID regex,
  location from the CLI's own list, Gmail address validated); no free text reaches a command
  line.
- `CliErrorClassifier` (commonMain, pure): maps output to `{ plainMessageKey, retryable,
  manualAnchor }`. Known cases: not logged in, billing required (Blaze), API not enabled,
  Eventarc propagation, permission denied, network, quota.
- `RegionMapper` (commonMain, pure).
- `SiteWorkspace` (desktop): materializes §5.
- `SetupProbe` (desktop): done probes of §3.
- UI: `FirebaseSetupAssistant` (commonMain) with the tab row, progress header, step cards
  (`StepCard` with status chip, action button, collapsible output area, error box with "See the
  manual step" link), and `ManualGuide` reusing `CopyableTerminalBlock`. It replaces
  `FirebaseSetupTutorialDialog` at its 5 call sites (setup wizard, backend migration, Firebase
  settings, both Settings screens); on desktop it opens as a large window-sized dialog.
- `DesktopAppEraser` also deletes `~/.noctulist/tools/` and `~/.noctulist/firebase-site/`
  (already covered by deleting `~/.noctulist`, checked by a test).

## 7. Manual tab content

Same steps 0 to 9, each with: what it is for (one sentence), what to click or type, the exact
command in a copy block with the project ID filled in when known, what the screen asks and what
to answer, and a "If you see..." line for the known errors (Secret Manager not enabled: enable
link and retry; Eventarc permission: wait 5 minutes and retry; billing required: Blaze link).
Region table, budget alert, Gmail app password, `functions:artifacts:setpolicy`. Rules copy
buttons stay for the console route. `FIREBASE_SETUP.md` mirrors this tab.

## 8. Security

- The Gmail app password goes from the field to the CLI's stdin only; never persisted, logged or
  shown again. The field is a password field and is cleared after the step.
- The Web client secret keeps its existing secure storage.
- Downloads come only from `nodejs.org` (checksum verified) and npm's official registry.
- Commands are fixed templates; arguments are validated; output shown in the UI is passed through
  a redactor that removes anything matching the secret value.
- The CLI's own Google credentials stay where the CLI keeps them (its config store); the app
  never reads them.

## 9. Out of scope

Phones; creating the Google account; payment and budget creation (Google requires a person);
turning on sign-in providers by API; Brevo or other mail services; firebase-functions v7 upgrade.

## 10. Tests

- Pure unit tests: `CliErrorClassifier` against captured real outputs (including the two from
  2026-11-11), `RegionMapper`, command template validation, redactor.
- `FirebaseCli` with a fake process runner: JSON parsing fixtures for `login:list`,
  `projects:list`, `firestore:databases:list/get`, `apps:list`, `apps:sdkconfig`,
  `functions:list`; retry loop timing with a fake clock.
- `SiteWorkspace`: produced `firebase.json` (ignore contains `functions/**`, rules entries,
  storage only when enabled), `.env.<projectId>` content, no secret on disk.
- `ToolsInstaller`: checksum mismatch rejected, platform selection, detection of an existing
  suitable install (fake filesystem).
- Strings: 7 locales, check-i18n, no em dashes.
- Manual end-to-end on a test Firebase project by the owner (automatic tab from scratch, then
  manual tab spot-check).
