# Firebase Setup Assistant Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Firebase setup guide with a two-tab assistant: an "Automatic" tab where the desktop app runs every possible step itself (tools, sign-in, project, database, rules, web config, site, photos, Cloud Functions) and a "Manual" tab with the same steps as plain instructions.

**Architecture:** Pure logic (step model, error classifier, region mapper, redactor, validators) in commonMain; process execution, tools download, site workspace and step orchestration in desktopMain behind an `expect`/`actual` `SetupAutomation`; one Compose `FirebaseSetupAssistant` replaces `FirebaseSetupTutorialDialog` at its 5 call sites. The Firebase CLI does the Google work; the app only runs fixed command templates.

**Tech Stack:** Kotlin Multiplatform (commonMain, desktopMain JVM 17), Compose Multiplatform, kotlinx.serialization, `java.net.http.HttpClient`, `ProcessBuilder`, system `tar` (macOS, Linux, Windows 10+), Node.js 22, firebase-tools (npm).

**Spec:** `docs/superpowers/specs/2026-11-11-firebase-setup-assistant-design.md`

## Global Constraints

- Automation on desktop only (macOS, Windows, Linux). Android shows only the Manual tab.
- Tools are downloaded only when the admin presses "Get the tools", into `~/.noctulist/tools/`; existing `node` >= 22 and `firebase` on PATH are reused.
- Node.js 22 comes only from `https://nodejs.org/dist/` and is checked against that release's `SHASUMS256.txt`; firebase-tools only from the npm registry.
- Commands are fixed templates passed as argument arrays (never a shell string); every argument is validated; no free text reaches a command line.
- The Gmail app password goes only to the CLI's stdin (`functions:secrets:set GMAIL_APP_PASSWORD --data-file - --force`); never written to disk, settings or logs; UI output passes through the redactor.
- Site workspace: `~/.noctulist/firebase-site/<projectId>/`; `firebase.json` hosting ignore contains `functions/**`; the exported zip's `firebase.json` too.
- Automatic processing order: region → `.env.<projectId>` (`GMAIL_USER`, `REGION`) → secret → `npm install` in `functions/` → `deploy --only hosting,functions --non-interactive --force` → retry up to 5 times, 2 minutes apart, on Eventarc / "retry in a few minutes" signatures → `functions:artifacts:setpolicy --location <region> --force`.
- Region mapping: `eur3` → `europe-west1`, `nam5` → `us-central1`, any single region → itself.
- Location picker order: `europe-west6` first, then `eur3`, then the rest from `firestore:locations`.
- Same step numbering 0 to 9 in both tabs; each step has a manual anchor.
- Copy: short plain sentences, no em dashes (—), no AI-sounding phrasing, all 7 app locales (`values`, `values-fr`, `values-es`, `values-hi`, `values-la`, `values-zh-rCN`, `values-zh-rTW`); French uses "vous"; `python3 scripts/check-i18n.py` passes.
- Branch `feature/volunteer-portal`; each task ends with its own commit (trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`); never stage `docs/NoctuList_Guide_Staff_Bar.pptx` or `.DS_Store`.

## Review Focus

1. The admin closes the assistant while a step runs: the process is destroyed and the step goes back to `TODO`, never stuck in `RUNNING`. Test in Task 5.
2. Download interrupted or checksum wrong: nothing half-installed is ever detected as installed (extract to a temp dir, rename at the end). Test in Task 4.
3. Paths with spaces (the owner's real folder was `noctulist-guestform-site 4`): workspace and tools paths with a space work. Test in Tasks 2 and 3.
4. Windows binaries: `node.exe`, `npm.cmd`, `firebase.cmd` are resolved instead of the Unix names. Test in Task 3.
5. A Gmail app password with `$`, quotes or spaces reaches stdin byte for byte and never appears in the shown output. Test in Task 3.

---

### Task 1: Setup core (pure logic)

**Files:**
- Create: `shared/src/commonMain/kotlin/com/eventmanager/app/setup/SetupModel.kt`, `CliErrorClassifier.kt`, `RegionMapper.kt`, `SetupValidators.kt`, `OutputRedactor.kt` (same package)
- Test: `shared/src/commonTest/kotlin/com/eventmanager/app/setup/` (one test file per source file)
- Test fixtures: `shared/src/commonTest/resources/setup/` is not available in commonTest; embed the two real outputs from 2026-11-11 as string constants in `CliErrorClassifierTest`.

**Interfaces:**
- Produces:
  - `enum class SetupStepId(val number: Int, val optional: Boolean) { TOOLS(0,false), LOGIN(1,false), PROJECT(2,false), DATABASE(3,false), RULES(4,false), PROVIDERS(5,false), CONNECT(6,false), SITE(7,true), PHOTOS(8,true), SERVER(9,true) }`
  - `enum class StepKind { RUN, OPEN, RUN_THEN_OPEN, OPEN_THEN_RUN }` and `val SetupStepId.kind: StepKind` (TOOLS, LOGIN, PROJECT, DATABASE, RULES, SITE → RUN; PROVIDERS → OPEN; CONNECT → RUN_THEN_OPEN; PHOTOS, SERVER → OPEN_THEN_RUN)
  - `sealed interface StepState { Todo; Running(val log: List<String>); WaitingRetry(val attempt: Int, val secondsLeft: Int); Done; Skipped; Error(val error: CliError) }`
  - `data class SetupProgress(val projectId: String, val done: Set<SetupStepId>, val confirmed: Set<SetupStepId>, val extras: Set<SetupStepId>)` with `fun encode(): String` / `companion fun decode(raw: String): SetupProgress` (JSON).
  - `data class CliError(val kind: CliErrorKind, val retryable: Boolean, val manualAnchor: SetupStepId, val details: String)`; `enum class CliErrorKind { NOT_LOGGED_IN, BILLING_REQUIRED, API_NOT_ENABLED, EVENTARC_PROPAGATION, PERMISSION_DENIED, NETWORK, QUOTA, UNKNOWN }`; `object CliErrorClassifier { fun classify(output: String, step: SetupStepId): CliError }`. Retryable: EVENTARC_PROPAGATION, API_NOT_ENABLED, NETWORK.
  - `object RegionMapper { fun functionsRegion(firestoreLocation: String): String; fun orderLocations(all: List<String>): List<String> }`
  - `object SetupValidators { fun projectId(v: String): Boolean /* ^[a-z][a-z0-9-]{4,28}[a-z0-9]$ */; fun gmail(v: String): Boolean; fun location(v: String, allowed: List<String>): Boolean; fun displayName(v: String): Boolean /* 1..60 chars, no control chars */ }`
  - `class OutputRedactor(private val secrets: () -> List<String>) { fun redact(line: String): String }` (replaces each non-blank secret with `••••`).

- [ ] **Step 1: Write failing tests**: classifier maps the 2026-11-11 Secret Manager 403 text to `API_NOT_ENABLED` retryable, the Eventarc "Permission denied while using the Eventarc Service Agent" text to `EVENTARC_PROPAGATION` retryable, "Blaze" / "billing account" to `BILLING_REQUIRED` not retryable, "not logged in" / "Failed to authenticate" to `NOT_LOGGED_IN`; `functionsRegion("eur3") == "europe-west1"`, `("nam5") == "us-central1"`, `("europe-west6") == "europe-west6"`; `orderLocations` puts `europe-west6`, then `eur3`, then the rest in input order; `projectId("noctuliste---lethal-tempo") == true`, `projectId("Bad_ID") == false`; redactor turns `pa$$ "word"` inside a line into `••••`; `SetupProgress` round-trips.
- [ ] **Step 2: Run, expect FAIL**: `./gradlew :shared:desktopTest --tests 'com.eventmanager.app.setup.*'`
- [ ] **Step 3: Implement** the interfaces above.
- [ ] **Step 4: Run, expect PASS** (same command).
- [ ] **Step 5: Commit** `git commit -m "Add setup assistant core logic"`

### Task 2: Site workspace and hosting fix

**Files:**
- Create: `shared/src/desktopMain/kotlin/com/eventmanager/app/setup/SiteWorkspace.kt`
- Modify: `webform/firebase.json` (hosting `ignore` adds `"functions/**"`), then `./gradlew :shared:syncWebFormResources`
- Modify: `shared/src/commonMain/kotlin/com/eventmanager/app/ui/guestform/GuestFormSiteBundle.kt` (expose `val files: List<String>` for reuse)
- Test: `shared/src/desktopTest/kotlin/com/eventmanager/app/setup/SiteWorkspaceTest.kt`

**Interfaces:**
- Consumes: `GuestFormSiteBundle.files`, `Res.readBytes("files/webform/...")`, `FirestoreRulesClipboardContent.load()`, `StorageRulesClipboardContent.load()`.
- Produces: `class SiteWorkspace(root: File /* ~/.noctulist/firebase-site */, readResource: suspend (String) -> ByteArray) { suspend fun prepare(projectId: String, withStorage: Boolean): File; fun writeFunctionParams(dir: File, projectId: String, gmailUser: String, region: String) }`. `prepare` writes bundle files, `firestore.rules`, `storage.rules`, `.firebaserc` (`{"projects":{"default":<id>}}`) and `firebase.json` = bundled hosting + functions blocks plus `"firestore": {"rules": "firestore.rules"}` and, only when `withStorage`, `"storage": {"rules": "storage.rules"}`; keeps an existing `functions/node_modules`.

- [ ] **Step 1: Failing tests** (temp root containing a space, e.g. `Files/site 4`): produced `firebase.json` hosting ignore contains `functions/**`; `firestore.rules` entry present; `storage` absent when `withStorage=false`, present when true; `.firebaserc` default project; `writeFunctionParams` writes exactly `GMAIL_USER=<v>\nREGION=<v>\n` to `functions/.env.<projectId>`; a pre-existing `functions/node_modules/x` survives `prepare`; no file in the tree contains a given test secret string.
- [ ] **Step 2: Run, expect FAIL**: `./gradlew :shared:desktopTest --tests '*SiteWorkspaceTest*'`
- [ ] **Step 3: Implement**; add `functions/**` to `webform/firebase.json`, sync resources.
- [ ] **Step 4: Run, expect PASS**; also `cmp webform/firebase.json shared/src/commonMain/composeResources/files/webform/firebase.json`.
- [ ] **Step 5: Commit** `git commit -m "Add the setup site workspace and keep functions off the public site"`

### Task 3: Process runner and Firebase CLI wrapper

**Files:**
- Create: `shared/src/desktopMain/kotlin/com/eventmanager/app/setup/ProcessRunner.kt`, `FirebaseCli.kt`, `ToolPaths.kt`
- Test: `shared/src/desktopTest/kotlin/com/eventmanager/app/setup/FirebaseCliTest.kt`, `ToolPathsTest.kt`, fixtures as JSON strings in the tests (shapes from firebase-tools 15 `--json` output: `{"status":"success","result":...}`)

**Interfaces:**
- Consumes: Task 1 validators, redactor, classifier.
- Produces:
  - `interface ProcessRunner { suspend fun run(cmd: List<String>, workDir: File?, env: Map<String,String>, stdin: ByteArray?, onLine: (String) -> Unit): Int }` with `class RealProcessRunner : ProcessRunner` (ProcessBuilder, merged stderr, destroys the process tree on coroutine cancellation).
  - `data class ToolPaths(val node: File, val npm: File, val firebase: File, val binDir: File)`; `object ToolPathsResolver { fun bundled(toolsDir: File, os: String): ToolPaths; fun fromPath(pathEnv: String, os: String, exists: (File) -> Boolean): ToolPaths? }` (Windows: `node.exe`, `npm.cmd`, `firebase.cmd`).
  - `class FirebaseCli(runner: ProcessRunner, tools: ToolPaths, redactor: OutputRedactor)` with: `suspend fun login(onLine)`, `loginList(): List<String>`, `projectsList(): List<ProjectInfo(id, displayName)>`, `projectsCreate(id, displayName, onLine)`, `databases(projectId): List<DatabaseInfo(name, locationId)>`, `firestoreLocations(projectId): List<String>`, `createDatabase(projectId, location, onLine)`, `webApps(projectId): List<String /*appId*/>`, `createWebApp(projectId, onLine): String`, `sdkConfig(projectId, appId): WebConfig(apiKey, appId, projectId)`, `deploy(projectId, dir, only: String, onLine)`, `setSecret(projectId, dir, name, value: CharArray, onLine)`, `npmInstall(dir, onLine)`, `functionsList(projectId): List<FunctionInfo(name, region)>`, `setArtifactPolicy(projectId, region, onLine)`. Every call adds `--project <id>` when it takes one, `--json` for read calls, `--non-interactive` for deploys; PATH gets `tools.binDir` first; failures throw `CliException(CliError)` built by the classifier.

- [ ] **Step 1: Failing tests** with a fake runner recording argument arrays: exact argument arrays for each method (e.g. `setSecret` → `[firebase, functions:secrets:set, GMAIL_APP_PASSWORD, --data-file, -, --force, --project, <id>]`); `setSecret` stdin equals the UTF-8 bytes of `p@$$ "w o"` and the redacted log never contains it; `projectsCreate` rejects an invalid id before running anything; JSON parsing of each fixture; a non-zero exit with the Eventarc text throws `CliException` with `EVENTARC_PROPAGATION`; `ToolPathsResolver.bundled(File("/a b/tools"), "Windows 11")` ends with `node.exe` / `firebase.cmd`; `fromPath` returns null when node is missing.
- [ ] **Step 2: Run, expect FAIL**: `./gradlew :shared:desktopTest --tests '*FirebaseCliTest*' --tests '*ToolPathsTest*'`
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit** `git commit -m "Add the Firebase CLI wrapper for the setup assistant"`

### Task 4: Tools installer

**Files:**
- Create: `shared/src/desktopMain/kotlin/com/eventmanager/app/setup/ToolsInstaller.kt`
- Test: `shared/src/desktopTest/kotlin/com/eventmanager/app/setup/ToolsInstallerTest.kt`

**Interfaces:**
- Consumes: `ProcessRunner`, `ToolPathsResolver`.
- Produces: `class ToolsInstaller(toolsDir: File, runner: ProcessRunner, http: suspend (String) -> ByteArray /* or streaming with progress */, os: String, arch: String)` with `suspend fun detect(pathEnv: String): ToolPaths?` (existing `node -v` >= 22 and firebase present, or bundled install present), `suspend fun install(onProgress: (Float, String) -> Unit): ToolPaths`, `fun remove()`. Archive names: `node-v22.<latest>-darwin-arm64.tar.gz`, `-darwin-x64.tar.gz`, `-linux-x64.tar.xz`, `-win-x64.zip`, version read from `https://nodejs.org/dist/latest-v22.x/SHASUMS256.txt`. Extract with `tar -xf` into `toolsDir/.tmp-<random>`, verify, then rename to `toolsDir/node`; then `npm install --prefix <toolsDir>/npm-global firebase-tools` with the bundled npm.

- [ ] **Step 1: Failing tests** (fake http + fake runner + temp dir with a space): wrong SHA-256 → exception and `toolsDir/node` absent; interrupted download (http throws) → nothing detected afterwards; correct checksum → runner called with `tar -xf <archive> -C <tmp>` then npm install; archive selection per (os, arch) for the four platforms; `detect` prefers PATH tools when `node -v` prints `v22.11.0` and rejects `v20.1.0`; `remove()` deletes `toolsDir`.
- [ ] **Step 2: Run, expect FAIL**: `./gradlew :shared:desktopTest --tests '*ToolsInstallerTest*'`
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit** `git commit -m "Add on-demand Node.js and Firebase CLI install"`

### Task 5: Step orchestrator and probes

**Files:**
- Create: `shared/src/commonMain/kotlin/com/eventmanager/app/setup/SetupAutomation.kt` (`expect`), `shared/src/desktopMain/kotlin/com/eventmanager/app/setup/SetupAutomation.desktop.kt`, `SetupRunner.kt`, `SetupProbe.kt`; `shared/src/androidMain/kotlin/com/eventmanager/app/setup/SetupAutomation.android.kt`
- Modify: `shared/src/commonMain/kotlin/com/eventmanager/app/data/sync/SettingsManager.kt` (`getSetupProgressJson()` / `saveSetupProgressJson(String)`)
- Test: `shared/src/desktopTest/kotlin/com/eventmanager/app/setup/SetupRunnerTest.kt`

**Interfaces:**
- Consumes: Tasks 1 to 4; `SettingsManager.setFirebaseProjectId`, `setFirebaseApiKey`, `setFirebaseApplicationId`, `setFirebaseWebClientId`, `setFirebaseWebClientSecret`, the profile photos and volunteer portal settings, the existing rules-version check.
- Produces:
  - `interface SetupAutomation { val available: Boolean; val states: StateFlow<Map<SetupStepId, StepState>>; val progress: StateFlow<SetupProgress>; suspend fun refresh(); fun run(step: SetupStepId, input: StepInput = StepInput()); fun confirm(step: SetupStepId); fun cancel() }`; `expect fun createSetupAutomation(context: PlatformContext, settings: SettingsManager): SetupAutomation` (Android: `available = false`).
  - `data class StepInput(val projectId: String? = null, val newProjectName: String? = null, val location: String? = null, val gmailUser: String? = null, val appPassword: CharArray? = null, val webClientId: String? = null, val webClientSecret: String? = null)`
  - `class SetupRunner(cli: FirebaseCli, tools: ToolsInstaller, workspace: SiteWorkspace, settings: SetupSettings, clock: () -> Long, sleep: suspend (Long) -> Unit)` implementing steps per spec §3 and the §4 sequence; retry: up to 5 attempts, `WaitingRetry(attempt, secondsLeft)` ticking from 120.
  - `class SetupProbe(cli, settings)`: `suspend fun probe(): Set<SetupStepId>` per spec §3 done probes (read-only).

- [ ] **Step 1: Failing tests** (fake CLI/runner, fake sleep and clock): SERVER sequence calls in order region lookup → `writeFunctionParams` → `setSecret` → `npmInstall` → `deploy(only="hosting,functions")` → `setArtifactPolicy(region)`; Eventarc failure twice then success → states pass through `WaitingRetry(1,…)`, `WaitingRetry(2,…)`, then `Done`; 6 failures → `Error` after 5 retries; billing error → `Error` immediately with `BILLING_REQUIRED`; `cancel()` during `deploy` → runner job cancelled and state back to `Todo`; appPassword array zero-filled after the step; CONNECT with no web app → `createWebApp` then `sdkConfig` and settings filled; DATABASE skips creation when `(default)` exists; progress persisted through `saveSetupProgressJson` and restored.
- [ ] **Step 2: Run, expect FAIL**: `./gradlew :shared:desktopTest --tests '*SetupRunnerTest*'`
- [ ] **Step 3: Implement**; `./gradlew :shared:compileDebugKotlinAndroid` for the Android actual.
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit** `git commit -m "Add the setup step runner, probes and retry policy"`

### Task 6: Assistant UI and Automatic tab

**Files:**
- Create: `shared/src/commonMain/kotlin/com/eventmanager/app/ui/setup/FirebaseSetupAssistant.kt`, `StepCard.kt`, `AutomaticTab.kt`
- Modify: the 5 call sites of `FirebaseSetupTutorialDialog` (`SetupWizardScreen.kt:765`, `BackendMigrationScreens.kt:574`, `FirebaseSyncSettingsSection.kt:484`, `SettingsScreen.desktop.kt:1561`, androidMain `SettingsScreen.kt:4927`) to open `FirebaseSetupAssistant(onDismiss, projectId)`
- Modify: 7 `strings.xml` (`setup_*` keys for tabs, header, step titles and one-sentence purposes, buttons "Run", "Open", "I did it", "Retry", "See the manual step", "Show details", state chips, the retry countdown "Google is getting ready, next try in %1$s", plain error messages per `CliErrorKind`, input labels)

**Interfaces:**
- Consumes: `SetupAutomation` (Task 5).
- Produces: `@Composable fun FirebaseSetupAssistant(onDismiss: () -> Unit, projectId: String = "")` (large dialog: fills the window on desktop with max width 900 dp; tabs "Automatic" / "Manual"; Automatic hidden when `!available`); `@Composable fun StepCard(...)` (number badge, title, purpose, state chip, primary action, inputs for the step, collapsible redacted log, error box with "See the manual step" switching tab and scrolling to the anchor); progress header "%1$d of %2$d steps done"; "Optional extras" section with one switch per optional step (SERVER requires SITE and the volunteer portal setting).

- [ ] **Step 1: Implement** the UI with Mist theme components already used by the app (cards, chips, `CopyableTerminalBlock` for logs styling reference); the app password field is a password field cleared after run; Open buttons use the existing external browser helper with the URLs of spec §3 and §4 (`https://console.firebase.google.com/project/<id>/authentication/providers`, `https://console.cloud.google.com/apis/credentials?project=<id>`, `https://console.firebase.google.com/project/<id>/usage/details`, `https://console.cloud.google.com/billing/budgets?project=<id>`, `https://console.firebase.google.com/project/<id>/storage`, `https://myaccount.google.com/apppasswords`).
- [ ] **Step 2: Wire the 5 call sites**; keep `FirebaseSetupTutorialDialog.kt` until Task 7 moves its content.
- [ ] **Step 3: Verify**: `./gradlew :shared:compileKotlinDesktop :shared:compileDebugKotlinAndroid`, `python3 scripts/check-i18n.py`, no `—` in new strings; launch the desktop app with isolated data (init script at `/private/tmp/claude-503/-Users-leonardomondada-Documents-DEV-NoctuList-PC-Edition/8201a555-4044-40af-b4c4-46bec4a07169/scratchpad/clean-run.init.gradle` if still present, otherwise a fresh `-Duser.home` override) and open the assistant from the setup wizard: tabs render, step 0 card shows "Get the tools", light and dark.
- [ ] **Step 4: Commit** `git commit -m "Add the setup assistant with the automatic tab"`

### Task 7: Manual tab, docs and cleanup

**Files:**
- Create: `shared/src/commonMain/kotlin/com/eventmanager/app/ui/setup/ManualTab.kt`
- Delete: `FirebaseSetupTutorialDialog.kt` and its now unused strings (keep `GuestFormHostingGuide` only if still referenced); remove unused imports
- Modify: 7 `strings.xml` (`setup_manual_*` for steps 0 to 9 per spec §7, including the "If you see..." lines for Secret Manager, Eventarc and billing), `FIREBASE_SETUP.md` (mirror of the manual tab), `CHANGELOG.md` (assistant entry), `DesktopAppEraser.kt` comment if needed
- Test: `shared/src/desktopTest/kotlin/com/eventmanager/app/setup/EraserCoversToolsTest.kt` (tools and site dirs live under the directory `DesktopAppEraser` deletes)

**Interfaces:**
- Consumes: `CopyableTerminalBlock`, `portalServerDeployCommands`, `portalServerCleanupCommand`, `guestFormHostingCommands`, rules clipboard objects, Task 6 anchors.
- Produces: `@Composable fun ManualTab(projectId: String, scrollTo: SetupStepId?)`.

- [ ] **Step 1: Write the failing eraser test**, run `./gradlew :shared:desktopTest --tests '*EraserCoversToolsTest*'`, implement if needed, pass.
- [ ] **Step 2: Implement the Manual tab** (each step: purpose, actions, copy blocks with the project ID filled in, what the screen asks and what to answer, "If you see..." lines; rules copy buttons kept).
- [ ] **Step 3: Remove the old dialog and dead strings**; check-i18n; no `—`; both compiles.
- [ ] **Step 4: Docs**: `FIREBASE_SETUP.md` setup section mirrors the manual tab; CHANGELOG line.
- [ ] **Step 5: Regression**: `./gradlew :shared:desktopTest --tests 'com.eventmanager.app.setup.*' --tests 'com.eventmanager.app.portal.*'`.
- [ ] **Step 6: Commit** `git commit -m "Add the manual setup tab and retire the old guide"`
