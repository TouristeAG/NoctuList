# Firebase setup assistant: hardening and repair

Follows `2026-11-11-firebase-setup-assistant-design.md`. That design stays the base (steps 0 to 10, Automatic and Manual tabs, site workspace). This document changes how the assistant finds problems, repairs them and explains them, and adds a Workspace mail mode.

## 1. Intent

Association admins with no technical background (the owner's friend on Windows is the reference user) must reach the end of the automatic setup alone. Today they hit errors that say "Un problème est survenu" with a raw CLI dump, states that lie ("installed" when the tools are gone), and no way back out of a sign-in. Every failure seen while testing on macOS and Windows must either be repaired by the app or explained: what happened, why, what the app already tried, what the person has to do and where.

## 2. Goals, non-goals, anti-goals

Goals
- The app repairs what it can repair with the signed-in account (APIs, IAM roles, retries, tool reinstall), and says what it repaired.
- What it cannot repair (payment, Workspace admin console, Google terms) is explained in numbered steps with a button to the exact page, then "Réessayer".
- Sign out and switch account at any time.
- Every step's tick is checked against reality when the page opens and after each run.
- Mail sender choice: on a Workspace domain, send without any stored password from a chosen address (for example a no-reply@ group); otherwise keep the Gmail app password.
- Fix the problems already met: read-only working folder, two app instances, missing Cloud Build role, transient "Failed to list functions", escaped apostrophes, functions left in FAILED state.

Non-goals
- Creating the OAuth web client (step 6) automatically: Google offers no API for it. It stays guided.
- Mobile: the Automatic tab stays desktop only.
- Supporting system-installed Node or Firebase CLI.

Anti-goals (would be a failure even if it works)
- Linking a billing account, or anything that can cost money, without an explicit confirmation that shows what will happen.
- Creating service account keys, or storing a Google password when the Workspace mode is chosen.
- An error screen whose only content is a generic sentence and a log dump.
- A repair the person does not see: every automatic change is listed ("Réparé : rôle Cloud Build ajouté au compte …").

## 3. Constraints

- Firebase CLI only covers Firebase. IAM roles, Service Usage, Cloud Billing, IAM Credentials and the Gmail API need direct Google REST calls.
- The app must not need its own Google OAuth client: `cloud-platform` is a sensitive scope and Google would require app verification before other associations could use it.
- New Google Cloud organizations (Workspace domains, since 2024) enforce "secure by default" policies: no automatic Editor role for default service accounts, no service account key creation, domain restricted sharing.
- Since late 2024 the default Cloud Storage bucket needs the Blaze plan on new projects.
- Windows: no guaranteed `tar` that reads zip, antivirus file locks, console encoding not UTF-8, paths that can exceed 260 characters, corporate proxies.
- Packaged app working directory is `/` on macOS and the install folder on Windows: not writable.

## 4. Approaches considered

- **A. Helper script on top of the Firebase CLI sign-in (chosen).** A small Node script shipped with the app requires the pinned `firebase-tools` modules (`requireAuth`, `apiv2.Client`, `auth`) and calls Google APIs with the token of the account already signed in. No second sign-in, no app verification by Google. Cost: depends on internal modules of `firebase-tools`, so its version is pinned and a test checks the helper against it.
- **B. Also install `gcloud`.** About 500 MB more, a second sign-in, more Windows breakage. Rejected.
- **C. App's own OAuth client.** Needs Google verification and a security assessment before the app can be offered to other associations. Rejected.

## 5. Design

### 5.1 Process and environment (all commands)

- Every Node, npm, CLI and helper command runs in `<appData>/setup/work` (created on demand). Commands that deploy keep running in the site workspace folder.
- Environment for children: `PATH` with the bundled node first; `XDG_CONFIG_HOME=<appData>/setup/config` so the Firebase sign-in belongs to the app (removed by "Effacer les données", independent of any other Firebase CLI on the computer); `NO_UPDATE_NOTIFIER=1`, `NO_COLOR=1`, `FORCE_COLOR=0`; proxy variables when a system proxy is detected.
- Output is decoded as UTF-8 explicitly, on every OS.
- Each command has a total time limit and an inactivity limit (no output for N minutes), set per command kind (sign-in start, list, deploy, npm install). Hitting one is an error kind of its own.
- Every command writes a full log to `<appData>/logs/setup/<date>-<step>.log`, passed through `OutputRedactor`, kept 7 days. Firebase commands get `--debug` so the real Google response is in it. The error screen offers "Copier le rapport" (redacted summary: step, error id, last error lines, OS, tool versions).

### 5.2 Single app instance

A lock file in `<appData>` taken at startup. A second launch hands over to the first (brings its window to the front) and exits before Firebase starts. This removes the Firestore cache lock crash ("SQLITE_BUSY", stuck on "synchro des données").

### 5.3 Tools (step 0)

- Only the app's own tools are used. `fromPath` detection is removed.
- Versions come from one manifest in the app: Node major and exact `firebase-tools` version, chosen together. Install runs `npm install --global firebase-tools@<exact>`.
- Detection runs the tools: `node -v` and `firebase --version` must answer with the manifest versions. Missing, broken or other version means "à réinstaller", never "installé".
- The step always shows "Réinstaller" (and "Mettre à jour" when the manifest changed). Reinstall stops running commands first.
- Archives are extracted in Java (`java.util.zip` for Windows zip; tar for macOS/Linux stays). File moves and deletes retry with backoff (antivirus locks), then report the folder and ask to pause the antivirus.
- Downloads use the system proxy (`ProxySelector` with system proxies). npm gets the same proxy.
- Checks before install: free disk space, writable `<appData>`.

### 5.4 Sign-in (step 1)

- Same paste-the-code flow (PKCE through `auth.firebase.tools`), now in the app's own config folder. Existing users sign in once more after the update.
- "Se déconnecter" runs `firebase logout`; "Changer de compte" is sign out then sign in. Both clear the account shown and the per-project ticks that depend on it.
- Expired or revoked sign-in (account listed but Google refuses) is detected by a helper `check-auth` call during refresh and shown as "Reconnectez-vous", with the button.

### 5.5 Helper (`noctulist-gcp.js`)

Shipped in the app resources, run as `node noctulist-gcp.js <command> --project <id> [args]`, path of the pinned `firebase-tools` given by argument. One JSON object on stdout: `{ ok, data }` or `{ ok:false, error:{ status, reason, message, service } }`. Commands:

| Command | What it does |
|---|---|
| `check-auth` | token still valid, account email |
| `test-permissions` | `testIamPermissions` for the permissions the next steps need |
| `enable-apis` | Service Usage batch enable, then polls until active |
| `ensure-role` | adds a role binding for a member if missing (read, merge, write with etag retry) |
| `billing-status` | is billing enabled, which billing accounts the user can use |
| `link-billing` | links one billing account (only after the person confirms in the app) |
| `functions-status` | each function with state (ACTIVE, FAILED, DEPLOYING) and state messages |
| `storage-status` | default bucket exists, Firebase Storage enabled |
| `ensure-mail-sa` | Workspace mode, see 5.8 |

The Kotlin side (`GoogleOps`) wraps it the way `FirebaseCli` wraps the CLI: fixed commands, validated arguments, redacted output.

### 5.6 Automatic repairs, per step

- Before step 3: enable the APIs the whole flow needs (Firestore, Rules, Hosting, Storage, Cloud Functions, Cloud Build, Artifact Registry, Cloud Run, Eventarc, Pub/Sub, Secret Manager, IAM Credentials), then wait until active.
- Before step 9: `ensure-role` `roles/cloudbuild.builds.builder` on the default compute service account (`<number>-compute@developer.gserviceaccount.com`). This is the "missing permission on the build service account" failure.
- Billing (steps 8 and 9): if not enabled and the user has exactly one usable billing account, offer "Activer Blaze avec <nom du compte>" with a short explanation of pay-as-you-go and a link to budgets; link only on confirmation. Zero or several accounts: guided steps with the console link.
- Functions left FAILED from an earlier attempt cannot be replaced in place: their build failed before the Eventarc trigger was created, so Google sees them as HTTPS functions and refuses the change ("Changing from an HTTPS function to a background triggered function is not allowed"). Before step 9 deploys, `functions-status` lists FAILED functions of the `noctulist` codebase; the app deletes them (`functions:delete --force`, they do not work anyway) and reports "Réparé : anciennes fonctions en échec supprimées". The same error seen during a deploy triggers the same repair, then one retry.
- Transient Google errors (5xx, "Failed to list functions", "unreachable regions", API just enabled) retry with the existing wait loop.
- Each repair adds a line to the step: "Réparé : …".

### 5.7 Errors: catalogue and screen

`CliErrorClassifier` becomes a catalogue of entries. Each entry has: id, step(s), match (text patterns, HTTP status, Google reason such as `SERVICE_DISABLED`, `PERMISSION_DENIED`, `RESOURCE_EXHAUSTED`), retry policy, optional repair action, and strings: title, cause, what to do (numbered), link.

The error screen shows, in order: title; why it happened; what the app already tried; what to do, numbered, with "Ouvrir la page" when there is one; "Réessayer"; "Copier le rapport"; technical details folded. When no entry matches, the title is the real Google error line extracted from the output (last `Error:` line or the API `message`), never only the generic sentence.

Initial catalogue (each item is a test case built from the real output when we have it):

| # | Where | Cause | Repair or guidance |
|---|---|---|---|
| 1 | all | working folder not writable (`firebase-debug.log`) | fixed by 5.1 |
| 2 | app | second instance, Firestore cache locked | fixed by 5.2 |
| 3 | 0 | tools missing, broken, wrong version | reinstall |
| 4 | 0 | download blocked, proxy, no internet | explain, proxy settings |
| 5 | 0 | antivirus lock during extract or delete | retry, then explain |
| 6 | 0 | not enough disk space | explain with size needed |
| 7 | 1 | sign-in code wrong or expired | restart sign-in |
| 8 | 1 | sign-in expired or revoked | "Reconnectez-vous" |
| 9 | 2 | project ID already taken | propose a free ID |
| 10 | 2 | project quota reached | explain, link to quota request or reuse a project |
| 11 | 2 | Firebase terms not accepted on a new account | link to console to accept, then retry |
| 12 | 2 | Workspace user cannot create projects (no Project Creator role) | explain what the Workspace admin must grant |
| 13 | 3 | API not active yet | enable, wait, retry |
| 14 | 3 | database exists in Datastore mode | explain, no automatic change |
| 15 | 3 | location not allowed or already set | explain |
| 16 | 4, 7, 8 | deploy refused, HTTP 403 | `test-permissions`, name the missing permission and the role that has it |
| 17 | 7 | no Hosting site | create it (exists today) |
| 18 | 8 | Storage not set up or needs Blaze | `storage-status`, Blaze flow, guided bucket creation |
| 19 | 8, 9 | Blaze plan missing | Blaze flow (5.6) |
| 20 | 9 | build service account missing permission | `ensure-role`, retry |
| 21 | 9 | Eventarc agent not ready | wait and retry (exists today) |
| 22 | 9 | "Failed to list functions" and other transient 5xx | retry |
| 23 | 9 | functions in FAILED state, including "Changing from an HTTPS function to a background triggered function is not allowed" | delete the FAILED functions, redeploy |
| 24 | 9 | `npm install` failed (network, long paths, antivirus) | retry, then cause-specific text |
| 25 | 9 | secret write refused | enable Secret Manager, retry |
| 26 | 9 | app password refused by Gmail, or blocked by the Workspace admin | explain; propose the Workspace mode on a Workspace domain |
| 27 | 9 | test mail not delivered | show the SMTP or Gmail API reason |
| 28 | all | command timed out or stopped answering | explain, retry, report |
| 29 | all | quota or rate limit | wait and retry later |
| 30 | all | unknown | real error line + report |

The bundled `firebase-functions` dependency is upgraded and pinned with the tools manifest, which removes the "outdated version" warning from the deploy output.

### 5.8 Mail sender

Step 9 asks for the sending address first. The domain decides the mode:

- `gmail.com` / `googlemail.com`: app password mode, as today (`GMAIL_USER`, secret `GMAIL_APP_PASSWORD`, SMTP).
- Any other domain: the app offers the Workspace mode (recommended) and keeps the app password mode as an alternative.

Workspace mode, without any stored password:
- Fields: "Compte qui envoie" (a real Workspace user) and "Adresse affichée" (defaults to the same; may be a group such as no-reply@collectif-nocturne.ch).
- `ensure-mail-sa` creates a service account `noctulist-mail@<project>.iam.gserviceaccount.com` with no roles and no key, enables IAM Credentials, and gives the functions' runtime account `roles/iam.serviceAccountTokenCreator` on it only.
- The app shows the one thing a super admin must do: Admin console, Security, API controls, Domain-wide delegation, add client ID `<numeric id>` with the single scope `https://www.googleapis.com/auth/gmail.send`. Copy buttons for both values, a button to the page.
- The function signs a JWT for `noctulist-mail` through the IAM Credentials `signJwt` call (keyless), with `sub` = sending user, exchanges it for a token, and sends through the Gmail API `users.messages.send` with the raw message it already builds.
- If the displayed address differs from the sending user, that address must be a send-as alias of the sending user, otherwise Gmail rewrites the From. The app guides the one-time setup (Gmail, Paramètres, Comptes, Envoyer des e-mails en tant que) and the test mail checks the From that arrived. Creating the alias automatically would need the `gmail.settings.sharing` scope, which also allows managing forwarding on that mailbox: too much power for a mail sender, so it is not requested (owner's call to revisit).
- The functions declare the `GMAIL_APP_PASSWORD` secret, and a deploy fails when a declared secret does not exist. In Workspace mode the app writes the fixed value `unused` to it, and the functions read it only in app password mode.
- Function parameters: `MAIL_MODE` (`app_password` | `workspace`), `MAIL_USER`, `MAIL_FROM`, `MAIL_SA`. `GMAIL_USER` stays readable for sites already deployed.
- "Envoyer un e-mail de test" to the signed-in admin ends step 9; the step is done only when the test mail is accepted. Its failure maps to catalogue entries 26 or 27 (for example "délégation pas encore autorisée", "alias refusé").

### 5.9 Ticks that tell the truth

Refresh checks every step, not only 0 to 3 and 6 and 9: rules released (Rules API), Hosting site has a live version, Storage bucket and rules, functions ACTIVE (FAILED is not done), mail mode configured and last test mail successful. A "Tout vérifier" button runs the same refresh.

### 5.10 Text

- Remove `\'` from every `composeResources` strings file (it is shown as typed) and add a test that fails when one comes back.
- New strings follow the project's copy rules: plain sentences, no em dashes, no redundant subtitles.

## 6. Tests

- Catalogue: one unit test per entry, using the real outputs captured in this conversation (Cloud Build permission, `firebase-debug.log` permission, "Failed to list functions", Eventarc, Blaze) plus synthetic ones.
- Helper: tests with a fake HTTP server for each command, and one test that loads the pinned `firebase-tools` and checks the internal functions it uses still exist.
- Tools: detection with missing, broken and wrong-version tools; Java zip extraction; retry on locked files.
- Runner: repairs run before the step and are listed; billing never links without confirmation; refresh unticks FAILED functions.
- Mail: emulator capture for both modes; JWT building and sendAs call with a fake Google.
- Manual checklist on a clean Windows machine and a clean macOS account, both launched from the installed app (not from Gradle).

## 7. Left to the builder

- Exact time limits per command kind.
- How the second instance reaches the first (local socket or watched file).
- Storage default bucket: use the Firebase Storage API when it allows creating the default bucket for the account, otherwise guided steps.
- Wording of each catalogue entry, following 5.10.
- Helper commands run with the same environment as the CLI (5.1), so they read the same sign-in.

## 8. Open

- The Windows errors the owner saw are not captured yet. Each one gets a catalogue entry when it is.
