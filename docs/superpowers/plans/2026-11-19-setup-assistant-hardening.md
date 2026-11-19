# Setup assistant hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the desktop Firebase setup assistant repair what it can, explain what it cannot, tell the truth about each step, and send mail from a Workspace address without a stored password.

**Architecture:** Every child process runs through one environment (writable work folder, app-owned Firebase sign-in, UTF-8, time limits, log files). A Node helper shipped with the app reuses the Firebase CLI sign-in to call Google REST APIs; a Kotlin `GoogleOps` wraps it. A catalogue maps outputs to error ids with a repair or guidance; the runner applies repairs before and after steps. The Cloud Functions gain a Workspace transport (keyless domain-wide delegation, Gmail API).

**Tech Stack:** Kotlin Multiplatform + Compose Multiplatform (desktop JVM 17), kotlinx.serialization, Node 22, firebase-tools 15.36.0 (pinned), TypeScript Cloud Functions (firebase-functions, nodemailer), `node --test`.

**Spec:** `docs/superpowers/specs/2026-11-19-setup-assistant-hardening-design.md`

## Global Constraints

- Firebase CLI pinned to `firebase-tools@15.36.0`; Node pinned to one exact v22 release (the current `latest-v22.x` at implementation time), both in `ToolsManifest`.
- No system Node or Firebase CLI is used (`ToolPathsResolver.fromPath` is deleted).
- Child processes: working folder `<appData>/setup/work` unless deploying; env `XDG_CONFIG_HOME=<appData>/setup/config`, `NO_UPDATE_NOTIFIER=1`, `NO_COLOR=1`, `FORCE_COLOR=0`; output decoded as UTF-8.
- Logs in `<appData>/logs/setup/`, redacted, kept 7 days.
- Never link billing without the person's confirmation; never create service account keys; Workspace mode stores no password (the `GMAIL_APP_PASSWORD` secret gets the fixed value `unused`).
- Workspace delegation scope is exactly `https://www.googleapis.com/auth/gmail.send`.
- `GMAIL_USER` stays the sending account in both modes (the spec's `MAIL_USER` is not added: same value, one name). New params: `MAIL_MODE` (`app_password` | `workspace`, default `app_password`), `MAIL_FROM` (default `""` = `GMAIL_USER`), `MAIL_SA` (default `""`).
- UI text: plain sentences, no em dashes, no `\'` in `composeResources` strings, no subtitle that repeats its title. New strings go in `values/strings.xml` (English) and `values-fr/strings.xml`.
- Commit messages carry no Claude attribution line.
- Kotlin tests: `./gradlew :shared:desktopTest --tests '<pattern>'`. Functions tests: `cd webform/functions && npm test`. Helper tests: `cd setup-helper && npm test`.

## Review Focus

- Helper calls are billed to the Firebase CLI's own OAuth project (`563584335869`) unless `x-goog-user-project` is set, which gives `SERVICE_DISABLED` for Billing, IAM and Resource Manager. Expected: the helper sends `x-goog-user-project: <projectId>` on every call except Service Usage. Test in Task 6.
- Home folder with spaces and accents (`C:\Users\Léo Martin`, `/Users/léo m`). Expected: install, helper and deploy commands get each path as one argument and work. Test in Tasks 2 and 4.
- People updating from the current version: sign-in moved to the app's config folder and the old progress JSON still exists. Expected: step 1 shows "not signed in" with the sign-in button, no crash, other ticks re-checked. Test in Task 5.
- Cancel while a repair, a retry wait or a reinstall is running. Expected: child processes killed, tools folder either complete or absent, the step back to Todo. Test in Tasks 4 and 10.
- Workspace mode chosen while the super admin has not yet authorized the delegation. Expected: the test mail fails with the "delegation not authorized" entry, step 9 is not done, nothing else changes. Test in Task 13.

---

### Task 1: Remove escaped apostrophes from strings

**Files:**
- Modify: `shared/src/commonMain/composeResources/values/strings.xml`, `shared/src/commonMain/composeResources/values-fr/strings.xml` (all `\'` become `'`)
- Test: `shared/src/desktopTest/kotlin/com/eventmanager/app/resources/StringsEscapeTest.kt`

**Interfaces:** none.

- [ ] **Step 1: Write the failing test**

```kotlin
class StringsEscapeTest {
    @Test
    fun noEscapedApostropheInComposeStrings() {
        val dir = File("src/commonMain/composeResources")
        val offenders = dir.walk().filter { it.name == "strings.xml" }
            .flatMap { f -> f.readLines().mapIndexedNotNull { i, l -> if ("\\'" in l) "${f.parentFile.name}/strings.xml:${i + 1}" else null } }
            .toList()
        assertEquals(emptyList(), offenders)
    }
}
```

- [ ] **Step 2: Run, expect FAIL** listing 54 lines: `./gradlew :shared:desktopTest --tests '*StringsEscapeTest*'`
- [ ] **Step 3: Replace `\'` with `'` in both files** (`sed -i '' "s/\\\\'/'/g"` on macOS, then check the diff only touches apostrophes).
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit** `git commit -m "Show apostrophes as typed in setup and app strings"`

---

### Task 2: One environment for every setup command

**Files:**
- Create: `shared/src/desktopMain/kotlin/com/eventmanager/app/setup/SetupEnvironment.kt`
- Create: `shared/src/desktopMain/kotlin/com/eventmanager/app/setup/SetupLogFiles.kt`
- Modify: `shared/src/desktopMain/kotlin/com/eventmanager/app/setup/ProcessRunner.kt`, `FirebaseCli.kt`, `SetupAutomation.desktop.kt`
- Test: `shared/src/desktopTest/kotlin/com/eventmanager/app/setup/SetupEnvironmentTest.kt`, `ProcessRunnerTest.kt`, `SetupLogFilesTest.kt`

**Interfaces:**
- Produces:
  - `data class CommandLimits(val totalMs: Long, val idleMs: Long)` with `companion object { val READ = CommandLimits(120_000, 60_000); val LOGIN = CommandLimits(120_000, 60_000); val NPM = CommandLimits(600_000, 300_000); val DEPLOY = CommandLimits(1_500_000, 600_000) }`
  - `class CommandTimeoutException(val idle: Boolean) : Exception()`
  - `ProcessRunner.run(cmd, workDir: File?, env, stdin, limits: CommandLimits = CommandLimits.READ, onLine): Int` (new parameter with default; `FakeProcessRunner` adds it)
  - `class SetupEnvironment(appData: File, proxy: ProxyInfo?)` with `val workDir: File`, `val configDir: File`, `val logDir: File`, `fun childEnv(binDir: File, basePath: String): Map<String, String>`
  - `data class ProxyInfo(val host: String, val port: Int)` (filled by Task 4; `null` until then)
  - `class SetupLogFiles(dir: File, redactor: OutputRedactor, now: () -> Long)` with `fun sink(step: SetupStepId): (String) -> Unit`, `fun latest(step: SetupStepId): File?`, `fun prune()`
  - `FirebaseCli(runner, tools, redactor, env: SetupEnvironment, logs: SetupLogFiles)`: every command uses `env.workDir` when no workDir is given, `env.childEnv(...)`, adds `--debug`, and copies each line to `logs.sink(step)`.

- [ ] **Step 1: Write the failing tests**
  - `childEnvSetsConfigHomeAndQuietFlags`: `childEnv(File("/t/bin"), "/usr/bin")` contains `XDG_CONFIG_HOME` = `configDir.path`, `NO_UPDATE_NOTIFIER=1`, `NO_COLOR=1`, `FORCE_COLOR=0`, and `PATH` starts with `/t/bin`.
  - `childEnvAddsProxyWhenKnown`: with `ProxyInfo("proxy.local", 3128)`, `HTTPS_PROXY` and `HTTP_PROXY` = `http://proxy.local:3128`.
  - `firebaseCommandsRunInWorkDirByDefault` (in `FirebaseCliTest`, fake runner records workDir): `projectsList()` gets `env.workDir`, deploy gets the site dir.
  - `firebaseCommandsGetDebugFlag`: every recorded command ends with `--debug`.
  - `realRunnerDecodesUtf8` (`ProcessRunnerTest`, real `RealProcessRunner`, `sh -c "printf 'é ✔\n'"`, skipped on Windows): the line equals `"é ✔"`.
  - `realRunnerStopsOnIdleLimit`: `sh -c "sleep 5"` with `CommandLimits(10_000, 300)` throws `CommandTimeoutException(idle = true)` in under 2 s.
  - `realRunnerHandlesPathsWithSpacesAndAccents`: creates `<tmp>/léo m/x.txt`, runs `cat <that path>`, exit 0.
  - `logFilesAreRedactedAndPruned`: a line containing a secret is written with `***`; a file dated 8 days ago is removed by `prune()`, one dated 6 days ago stays.
- [ ] **Step 2: Run, expect FAIL**: `./gradlew :shared:desktopTest --tests 'com.eventmanager.app.setup.*'`
- [ ] **Step 3: Implement.** `RealProcessRunner` reads with `InputStreamReader(process.inputStream, Charsets.UTF_8)`, tracks the time of the last line, and kills the process tree on either limit. `SetupEnvironment` creates its folders on first use. `createSetupAutomation` builds one `SetupEnvironment(root, null)` and one `SetupLogFiles(env.logDir, ...)` and calls `prune()` once.
- [ ] **Step 4: Run, expect PASS** (all existing setup tests still pass).
- [ ] **Step 5: Commit** `git commit -m "Run setup commands in a writable folder with their own sign-in, limits and logs"`

---

### Task 3: Only one NoctuList at a time

**Files:**
- Create: `shared/src/desktopMain/kotlin/com/eventmanager/app/platform/SingleInstance.kt`
- Modify: `desktopApp/src/jvmMain/kotlin/com/eventmanager/app/desktop/Main.kt`
- Test: `shared/src/desktopTest/kotlin/com/eventmanager/app/platform/SingleInstanceTest.kt`

**Interfaces:**
- Produces: `class SingleInstance(dir: File)` with `fun acquire(onActivate: () -> Unit): Boolean` (true = this process is the first; it locks `dir/app.lock` with `FileChannel.tryLock`, opens a loopback `ServerSocket` on port 0 and writes the port to `dir/app.port`; a connection that sends `show\n` calls `onActivate`), `fun signalFirst(): Boolean` (connects to the stored port and sends `show\n`), `fun release()`.

- [ ] **Step 1: Write the failing tests**
  - `secondAcquireFailsWhileFirstHolds`: two instances on the same temp dir; first `acquire` true, second false.
  - `signalReachesFirst`: second `signalFirst()` returns true and the first's `onActivate` runs within 2 s.
  - `staleFilesDoNotBlock`: a leftover `app.port` with a dead port and no lock: `acquire` returns true.
- [ ] **Step 2: Run, expect FAIL**: `./gradlew :shared:desktopTest --tests '*SingleInstanceTest*'`
- [ ] **Step 3: Implement**, then in `main()` before `application {}`: compute the data folder the same way `createPlatformContext()` does (`~/.noctulist`), `if (!instance.acquire { bringWindowToFront() }) { instance.signalFirst(); exitProcess(0) }`. `bringWindowToFront` sets a flag observed by the `Window` (`window.toFront()`, restore if minimized).
- [ ] **Step 4: Run, expect PASS.** Then build and launch the packaged app twice from Finder; the second launch brings the first window forward and no crash report appears in `~/Library/Logs/NoctuList/`.
- [ ] **Step 5: Commit** `git commit -m "Keep a single NoctuList running and bring it forward on a second launch"`

---

### Task 4: Tools that are checked by running them

**Files:**
- Create: `shared/src/desktopMain/kotlin/com/eventmanager/app/setup/ToolsManifest.kt`, `ArchiveExtractor.kt`, `FileOps.kt`, `SystemProxy.kt`
- Modify: `ToolsInstaller.kt`, `ToolPaths.kt` (delete `fromPath`), `SetupPorts.kt`, `SetupAutomation.desktop.kt`, `SetupRunner.kt` (TOOLS step and refresh)
- Modify: `shared/src/commonMain/kotlin/com/eventmanager/app/setup/SetupAutomation.kt` (`SetupInfo.tools`)
- Modify: `shared/src/commonMain/kotlin/com/eventmanager/app/ui/setup/AutomaticTab.kt` (step 0 buttons)
- Test: `ToolsInstallerTest.kt`, `ArchiveExtractorTest.kt`, `FileOpsTest.kt`, `SetupRunnerTest.kt`

**Interfaces:**
- Produces:
  - `object ToolsManifest { const val NODE_VERSION: String; const val FIREBASE_TOOLS_VERSION = "15.36.0"; const val MIN_FREE_BYTES = 600L * 1024 * 1024 }`
  - desktopMain: `sealed interface ToolsStatus { data class Ready(val paths: ToolPaths); data object Missing; data class Broken(val reason: String); data class Outdated(val node: String, val firebase: String) }`
  - commonMain: `enum class ToolsState { READY, MISSING, BROKEN, OUTDATED }` and `SetupInfo.tools: ToolsState`
  - `class DiskSpaceException(val neededBytes: Long) : IOException()`, `class FileLockedException(val path: File) : IOException()` (thrown by the installer; Task 10 maps them to error ids)
  - `interface SetupTools { suspend fun status(): ToolsStatus; suspend fun install(onProgress: (Float, String) -> Unit): ToolPaths; suspend fun reinstall(onProgress: (Float, String) -> Unit): ToolPaths }`
  - `object ArchiveExtractor { fun extract(archive: File, into: File, os: String, runner: ProcessRunner) }` (zip through `java.util.zip.ZipFile`, tar archives through `tar`)
  - `object FileOps { suspend fun <T> retrying(attempts: Int = 5, firstDelayMs: Long = 500, action: () -> T): T }` (doubling delay; rethrows the last failure)
  - `object SystemProxy { fun detect(): ProxyInfo? }` (sets `java.net.useSystemProxies=true`, `ProxySelector.getDefault().select(URI("https://nodejs.org/"))`)
  - `SetupAutomation.reinstallTools()` (commonMain interface, new)

- [ ] **Step 1: Write the failing tests**
  - `statusIsReadyOnlyWhenBothToolsAnswerManifestVersions`: fake runner answers `node -v` → `v<NODE_VERSION>` and `firebase --version` → `15.36.0`, files exist → `Ready`.
  - `statusIsBrokenWhenFilesExistButNodeFails`: `node -v` exit 1 → `Broken`.
  - `statusIsMissingWhenNothingInstalled` and `statusIgnoresSystemNodeOnPath` (a fake PATH dir with `node` and `firebase` files → still `Missing`).
  - `statusIsOutdatedOnOtherFirebaseVersion`: `15.10.0` → `Outdated`.
  - `installPinsFirebaseToolsVersion`: npm call contains `firebase-tools@15.36.0`.
  - `zipExtractsWithoutTar`: build a zip in a temp dir (`node-v22/bin/node.exe` entry), `extract(..., "Windows 11", runner)` → file exists, `runner.calls` empty.
  - `zipRejectsEntriesOutsideTarget`: entry `../evil` → `SecurityException`.
  - `retryingSucceedsAfterTwoFailures` and `retryingRethrowsAfterLastAttempt` (delays injected as 0).
  - `installFailsEarlyWhenDiskIsFull`: free space provider answers 100 MB → `IOException` mentioning the size needed, no download made.
  - `cancelDuringInstallLeavesNoHalfFolder`: cancel while the fake download suspends → `tools/node` absent, no `.tmp-*` left.
  - Runner: `refreshUnticksToolsWhenBroken` (saved progress has TOOLS done, status `Broken` → state Todo and `info.tools == BROKEN`); `reinstallRemovesThenInstalls`.
- [ ] **Step 2: Run, expect FAIL**: `./gradlew :shared:desktopTest --tests 'com.eventmanager.app.setup.*'`
- [ ] **Step 3: Implement.** `NODE_DIST` becomes `https://nodejs.org/dist/$NODE_VERSION`. `JdkToolsHttp` builds its client with `.proxy(ProxySelector.getDefault())` after `SystemProxy.detect()`. Moves and deletes go through `FileOps.retrying`. `createSetupAutomation` passes `SystemProxy.detect()` to `SetupEnvironment`. Step 0 UI: "Réinstaller" always visible once installed, "Mettre à jour" when `OUTDATED`, state text for `BROKEN`.
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit** `git commit -m "Check setup tools by running them and let people reinstall them"`

---

### Task 5: Sign out and switch account

**Files:**
- Modify: `SetupPorts.kt` (`SetupCli.logout()`), `FirebaseCli.kt`, `SetupRunner.kt`, `commonMain/.../setup/SetupAutomation.kt`, `ui/setup/AutomaticTab.kt`, strings (en, fr)
- Test: `FirebaseCliTest.kt`, `SetupRunnerTest.kt`

**Interfaces:**
- Produces: `SetupCli.logout()` runs `firebase logout`; `SetupAutomation.signOut()` and `SetupAutomation.switchAccount()` (= `signOut()` then `run(SetupStepId.LOGIN)`).

- [ ] **Step 1: Write the failing tests**
  - `logoutRunsFirebaseLogout`: command is `[node, firebase.js, "logout", "--debug"]`.
  - `signOutClearsAccountAndUnticksLogin`: after `signOut()`, `info.account == ""`, LOGIN state Todo, LOGIN not in saved `done`.
  - `switchAccountStartsNewSignIn`: `info.loginChallenge` set after `switchAccount()`.
  - `oldSavedProgressWithoutSignInShowsSignedOut`: saved progress from the current release (TOOLS, LOGIN, PROJECT done) with `loginList()` empty → LOGIN Todo, no exception.
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement**, plus two buttons on step 1 when signed in: "Se déconnecter" and "Changer de compte" (fr), "Sign out" and "Switch account" (en).
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit** `git commit -m "Let the admin sign out of Firebase or switch account in the setup assistant"`

---

### Task 6: Google helper script

**Files:**
- Create: `setup-helper/package.json` (devDependency `firebase-tools@15.36.0`, script `"test": "node --test test/*.test.cjs"`), `setup-helper/noctulist-gcp.cjs`, `setup-helper/test/commands.test.cjs`, `setup-helper/test/firebase-tools-compat.test.cjs`
- Modify: `shared/build.gradle.kts` (task `syncSetupHelper` copying `setup-helper/noctulist-gcp.cjs` into `src/commonMain/composeResources/files/setup/`, wired like `syncWebFormResources`)

**Interfaces:**
- Produces:
  - CLI: `node noctulist-gcp.cjs <firebaseToolsLibDir> <command> --project <id> [--member m] [--role r] [--apis a,b] [--permissions a,b] [--billing-account billingAccounts/X] [--codebase c]`
  - stdout: exactly one JSON line, `{"ok":true,"data":...}` or `{"ok":false,"error":{"status":403,"reason":"PERMISSION_DENIED","message":"...","service":"cloudbilling.googleapis.com"}}`; exit code 0 for both, non-zero only on a crash.
  - `module.exports = { run(argv, deps) }` with `deps = { token: () => Promise<string>, request: (method, url, body, headers) => Promise<{status, body}>, sleep: (ms) => Promise<void> }`.
  - Commands and `data`: `check-auth` → `{email}`; `project-number` → `{number}`; `test-permissions` → `{granted:[], missing:[]}`; `enable-apis` → `{enabled:[]}` (batchEnable, polls the operation, at most 5 min); `ensure-role` → `{changed}` (getIamPolicy, merge, setIamPolicy with etag, one retry on 409); `billing-status` → `{enabled, accounts:[{name, displayName}]}` (open accounts only); `link-billing` → `{enabled:true}`; `functions-status` → `{functions:[{name, region, state, messages:[]}]}` filtered by label `firebase-functions-codebase`; `storage-status` → `{bucket: string|null}`; `rules-status` → `{firestore: bool, storage: bool}`; `hosting-status` → `{live: bool, url: string}`; `ensure-mail-sa` → `{email, clientId}`.

- [ ] **Step 1: Write the failing tests** (fake `deps`, recorded requests)
  - `everyCallExceptServiceUsageSetsUserProject`: for `billing-status`, `ensure-role`, `project-number`, header `x-goog-user-project` = project id; for `enable-apis` it is absent.
  - `ensureRoleAddsMissingBindingOnce`: policy without the binding → one setIamPolicy with it and the same etag; second run with the binding → `{changed:false}` and no write.
  - `ensureRoleRetriesOnEtagConflict`: first set answers 409, second 200 → `{changed:true}`.
  - `enableApisPollsUntilDone`: operation `done:false` twice then `done:true` → `{enabled:[...]}`, three polls.
  - `errorsAreOneJsonLine`: request answers 403 with a Google error body (`reason: "SERVICE_DISABLED"`, `metadata.service`) → `{ok:false,error:{status:403, reason:"SERVICE_DISABLED", service:"cloudbilling.googleapis.com", ...}}`.
  - `functionsStatusFiltersCodebase`: two functions, one labelled `noctulist` → only that one, with `state:"FAILED"` and its state messages.
  - `ensureMailSaCreatesAccountWithoutKeyAndGrantsTokenCreator`: no `serviceAccounts.keys` call; one `setIamPolicy` on the service account giving `roles/iam.serviceAccountTokenCreator` to `serviceAccount:<number>-compute@developer.gserviceaccount.com`; returns the account's `oauth2ClientId` as `clientId`.
  - `firebase-tools-compat.test.cjs`: requiring `node_modules/firebase-tools/lib/requireAuth` exposes `requireAuth`, `lib/apiv2` exposes `getAccessToken` and `Client`, `lib/auth` exposes `getGlobalDefaultAccount`.
- [ ] **Step 2: Run, expect FAIL**: `cd setup-helper && npm install && npm test`
- [ ] **Step 3: Implement `run`** and a `main` that builds real `deps` from `<firebaseToolsLibDir>` (`requireAuth({})`, then `apiv2.getAccessToken()`; `request` through `fetch`).
- [ ] **Step 4: Run, expect PASS**, then `./gradlew :shared:syncSetupHelper` and check `composeResources/files/setup/noctulist-gcp.cjs` exists.
- [ ] **Step 5: Commit** `git commit -m "Add the Google helper that reuses the Firebase sign-in"`

---

### Task 7: `GoogleOps` in Kotlin

**Files:**
- Create: `shared/src/desktopMain/kotlin/com/eventmanager/app/setup/GoogleOps.kt`
- Modify: `SetupAutomation.desktop.kt` (extracts the helper from resources to `<appData>/setup/helper/noctulist-gcp.cjs` when missing or changed)
- Test: `shared/src/desktopTest/kotlin/com/eventmanager/app/setup/GoogleOpsTest.kt`

**Interfaces:**
- Consumes: Task 2 `SetupEnvironment`, `SetupLogFiles`, `CommandLimits`; Task 6 JSON contract.
- Produces:
  - `data class GoogleApiError(val status: Int, val reason: String, val message: String, val service: String)`
  - `class GoogleOpsException(val error: GoogleApiError) : Exception(error.message)`
  - `data class BillingAccount(val name: String, val displayName: String)`, `data class BillingStatus(val enabled: Boolean, val accounts: List<BillingAccount>)`
  - `data class FunctionState(val name: String, val region: String, val state: String, val messages: List<String>)`
  - `data class MailServiceAccount(val email: String, val clientId: String)`
  - `interface GoogleOps { suspend fun checkAuth(): String; suspend fun projectNumber(projectId: String): String; suspend fun missingPermissions(projectId: String, permissions: List<String>): List<String>; suspend fun enableApis(projectId: String, apis: List<String>); suspend fun ensureRole(projectId: String, member: String, role: String): Boolean; suspend fun billing(projectId: String): BillingStatus; suspend fun linkBilling(projectId: String, account: String); suspend fun functions(projectId: String, codebase: String): List<FunctionState>; suspend fun storageBucket(projectId: String): String?; suspend fun rulesReleased(projectId: String): Pair<Boolean, Boolean>; suspend fun hostingLive(projectId: String): Boolean; suspend fun ensureMailServiceAccount(projectId: String): MailServiceAccount }`
  - `class HelperGoogleOps(runner: ProcessRunner, tools: ToolPaths, helper: File, env: SetupEnvironment, logs: SetupLogFiles, redactor: OutputRedactor) : GoogleOps`

- [ ] **Step 1: Write the failing tests**
  - `ensureRoleBuildsFixedCommand`: recorded command = `[node, helper, <firebase-tools lib dir>, "ensure-role", "--project", pid, "--member", m, "--role", r]`, workDir `env.workDir`.
  - `invalidArgumentsNeverReachTheCommandLine`: `ensureRole(pid, "x; rm -rf /", r)` → `IllegalArgumentException`, no call (members must match `^(serviceAccount|user|group):[^\s;]+$`, roles `^roles/[A-Za-z.]+$`).
  - `errorJsonBecomesGoogleOpsException`: helper prints the error JSON → exception with the same status, reason, service.
  - `nonJsonOutputIsUnknownError`: helper exits 1 with a stack trace → `GoogleOpsException` with status 0 and reason `HELPER_CRASHED`, message = last non-empty line.
- [ ] **Step 2: Run, expect FAIL**: `./gradlew :shared:desktopTest --tests '*GoogleOpsTest*'`
- [ ] **Step 3: Implement** (JSON parsed from the last line that starts with `{`).
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit** `git commit -m "Wrap the Google helper for the setup runner"`

---

### Task 8: Error catalogue

**Files:**
- Create: `shared/src/commonMain/kotlin/com/eventmanager/app/setup/ErrorCatalog.kt`
- Delete: `shared/src/commonMain/kotlin/com/eventmanager/app/setup/CliErrorClassifier.kt` (its rules move into the catalogue)
- Modify: every user of `CliError`, `CliErrorKind`, `CliErrorClassifier` (`FirebaseCli.kt`, `SetupRunner.kt`, `SetupModel.kt`, `AutomaticTab.kt`, `StepCard.kt`, tests)
- Test: `shared/src/commonTest/kotlin/com/eventmanager/app/setup/ErrorCatalogTest.kt`, fixtures `shared/src/commonTest/resources/setup-errors/*.txt`

**Interfaces:**
- Produces:
  - `enum class SetupErrorId { WORKDIR_NOT_WRITABLE, TOOLS_BROKEN, DOWNLOAD_FAILED, FILE_LOCKED, DISK_FULL, LOGIN_CODE_INVALID, LOGIN_EXPIRED, PROJECT_ID_TAKEN, PROJECT_QUOTA, TERMS_NOT_ACCEPTED, NO_PROJECT_CREATOR, API_NOT_ENABLED, DATASTORE_MODE, LOCATION_INVALID, PERMISSION_DENIED, NO_HOSTING_SITE, STORAGE_NOT_READY, BILLING_REQUIRED, BUILD_SA_PERMISSION, EVENTARC_NOT_READY, TRANSIENT, FUNCTIONS_FAILED_STATE, NPM_INSTALL_FAILED, SECRET_REFUSED, MAIL_AUTH_REFUSED, MAIL_DELEGATION_MISSING, MAIL_TEST_FAILED, MAIL_TEST_UNAVAILABLE, TIMEOUT, QUOTA, NETWORK, UNKNOWN }`
  - `enum class RetryPolicy { NONE, WAIT_AND_RETRY, AFTER_REPAIR }`
  - `enum class Repair { ENABLE_APIS, GRANT_BUILD_ROLE, DELETE_FAILED_FUNCTIONS, CREATE_HOSTING_SITE, REINSTALL_TOOLS, RELOGIN }`
  - `data class SetupError(val id: SetupErrorId, val step: SetupStepId, val retry: RetryPolicy, val repair: Repair?, val link: String?, val headline: String, val details: String)`
  - `class CliException(val error: SetupError) : Exception(error.id.name)` (same name as today, new payload)
  - `object ErrorCatalog { fun classify(output: String, step: SetupStepId, projectId: String = "", api: GoogleApiStatus? = null): SetupError; fun headline(output: String): String }` with `data class GoogleApiStatus(val status: Int, val reason: String, val service: String)` in commonMain (Task 7 maps `GoogleApiError` to it).
  - `StepState.Error(val error: SetupError)`

Rules, tested in this order (first match wins):

| Id | Match | Retry | Repair | Link |
|---|---|---|---|---|
| WORKDIR_NOT_WRITABLE | `Unable to obtain permissions for firebase-debug.log` | NONE | null | null |
| FUNCTIONS_FAILED_STATE | `Changing from an HTTPS function to a background triggered function is not allowed` | AFTER_REPAIR | DELETE_FAILED_FUNCTIONS | functions console |
| BUILD_SA_PERMISSION | `missing permission on the build service account` | AFTER_REPAIR | GRANT_BUILD_ROLE | IAM page |
| EVENTARC_NOT_READY | today's two Eventarc patterns | WAIT_AND_RETRY | null | null |
| BILLING_REQUIRED | today's Blaze patterns | NONE | null | `https://console.firebase.google.com/project/<id>/usage/details` |
| API_NOT_ENABLED | today's patterns, or api reason `SERVICE_DISABLED` | AFTER_REPAIR | ENABLE_APIS | null |
| STORAGE_NOT_READY | `Firebase Storage has not been set up` | NONE | null | `https://console.firebase.google.com/project/<id>/storage` |
| NO_HOSTING_SITE | `no Hosting site` | AFTER_REPAIR | CREATE_HOSTING_SITE | null |
| TERMS_NOT_ACCEPTED | `terms of service` (any case) | NONE | null | `https://console.firebase.google.com/` |
| PROJECT_ID_TAKEN | step PROJECT and (`ALREADY_EXISTS` or `already exists`) | NONE | null | null |
| PROJECT_QUOTA | step PROJECT and `quota` | NONE | null | `https://support.google.com/code/contact/project_quota_increase` |
| NO_PROJECT_CREATOR | step PROJECT and (`resourcemanager.projects.create` or `HTTP Error: 403`) | NONE | null | null |
| DATASTORE_MODE | `Datastore Mode` (any case) | NONE | null | Firestore console |
| LOCATION_INVALID | step DATABASE and `location` with `invalid`, `not supported` or `already` | NONE | null | null |
| LOGIN_EXPIRED | `credentials are no longer valid`, `invalid_grant`, `Failed to authenticate`, `not logged in`, `firebase login` | NONE | RELOGIN | null |
| LOGIN_CODE_INVALID | step LOGIN and `Unable to authenticate using the provided code` | NONE | RELOGIN | null |
| SECRET_REFUSED | `secretmanager` and (`403` or `PERMISSION_DENIED`) | AFTER_REPAIR | ENABLE_APIS | null |
| NPM_INSTALL_FAILED | `npm ERR!` or `npm error` | WAIT_AND_RETRY | null | null |
| TRANSIENT | `Failed to list functions`, `HTTP Error: 5\d\d`, `unable to reach the following regions` | WAIT_AND_RETRY | null | null |
| QUOTA | `quota`, `HTTP Error: 429` | WAIT_AND_RETRY | null | null |
| PERMISSION_DENIED | `HTTP Error: 403`, `permission denied`, `PERMISSION_DENIED` | NONE | null | IAM page |
| NETWORK | today's network pattern | WAIT_AND_RETRY | null | null |
| UNKNOWN | anything else | NONE | null | null |

`TOOLS_BROKEN`, `DOWNLOAD_FAILED`, `FILE_LOCKED`, `DISK_FULL`, `TIMEOUT` and the `MAIL_*` ids are built directly by the code that knows them (Tasks 4, 10, 13), not matched from text. `headline` returns the text after the last `Error: ` line, else the last `"message": "..."` value, else the last non-empty line; trimmed to 300 characters.

- [ ] **Step 1: Write the failing tests.** One test per matched row, with real fixtures for: `workdir.txt` (the `logger.js:148` trace), `build-sa.txt` (the "Build failed with status: FAILURE ... build service account" block), `https-to-background.txt` (the europe-west6 deploy that ends with the "Changing from an HTTPS function" error), `list-functions.txt` (ends with `Error: Failed to list functions for noctuliste-cn`), and short synthetic strings for the rest. Plus:
  - `buildSaWinsOverPermissionDenied`: the build-sa fixture also contains `permission` → id `BUILD_SA_PERMISSION`.
  - `headlineIsTheRealErrorLine`: for `https-to-background.txt` the headline starts with `[noctulist:onMailOutboxWritten(europe-west6)] Changing from an HTTPS function`.
  - `unknownKeepsHeadline`: `"Error: Something new"` → `UNKNOWN`, headline `Something new`.
  - `apiReasonServiceDisabledIsApiNotEnabled`: empty output, `api = GoogleApiStatus(403, "SERVICE_DISABLED", "run.googleapis.com")` → `API_NOT_ENABLED`.
- [ ] **Step 2: Run, expect FAIL**: `./gradlew :shared:desktopTest --tests '*ErrorCatalogTest*'`
- [ ] **Step 3: Implement** and migrate callers (the runner's retry loop retries `WAIT_AND_RETRY`; `AFTER_REPAIR` is handled in Task 10, until then treated as `NONE`).
- [ ] **Step 4: Run all setup tests, expect PASS.**
- [ ] **Step 5: Commit** `git commit -m "Replace the setup error kinds with a catalogue of causes, repairs and links"`

---

### Task 9: Error screen and report

**Files:**
- Create: `shared/src/commonMain/kotlin/com/eventmanager/app/setup/SetupReport.kt`, `shared/src/commonMain/kotlin/com/eventmanager/app/ui/setup/SetupErrorTexts.kt`
- Modify: `ui/setup/StepCard.kt` (`ErrorBox`), `ui/setup/AutomaticTab.kt` (drop `errorMessage`), strings (en, fr)
- Test: `shared/src/commonTest/kotlin/com/eventmanager/app/setup/SetupReportTest.kt`, `shared/src/desktopTest/kotlin/com/eventmanager/app/ui/setup/SetupErrorTextsTest.kt`

**Interfaces:**
- Consumes: Task 8 `SetupError`; Task 10 `RepairNote` (the box shows `info.repairs[step]`, empty until Task 10).
- Produces:
  - `object SetupReport { fun build(step: SetupStepId, error: SetupError, repairs: List<RepairNote>, os: String, versions: String): String }`: plain text with step number, error id, headline, repairs, OS, versions, last 40 lines of `details`; passed through the existing redactor by the caller.
  - `fun errorTitle(id: SetupErrorId): StringResource`, `fun errorCause(id): StringResource`, `fun errorSteps(id): StringResource` (one `when` each; strings `setup_err_<id_lowercase>_title|_cause|_steps`).
  - `ErrorBox` layout, top to bottom: title (for `UNKNOWN`, the headline instead); cause; "Ce que l'app a déjà fait" with the repairs when any; numbered steps; "Ouvrir la page" when `link != null`; "Réessayer"; "Copier le rapport"; details folded.

- [ ] **Step 1: Write the failing tests**
  - `everyErrorIdHasThreeStringsInEnglishAndFrench`: for each `SetupErrorId`, the keys exist in `values/strings.xml` and `values-fr/strings.xml` (read the XML files).
  - `noErrorStringUsesEmDash`: none of those values contains `—`.
  - `reportHasStepIdHeadlineAndNoSecret`: report built from an error whose details contain `"pass: abcd efgh"` after redaction with that secret → contains `Step 9`, `BUILD_SA_PERMISSION`, the headline, not `abcd`.
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement**, writing the French and English texts from the spec's catalogue table (cause = why it happened, steps = numbered actions). Example for `BUILD_SA_PERMISSION` (fr): title "Google n'a pas pu préparer le serveur", cause "Le compte que Google utilise pour préparer le serveur n'a pas le rôle nécessaire. Les projets récents ne le reçoivent plus automatiquement.", steps "1. Cliquez sur Réessayer : l'app ajoute le rôle elle-même.\n2. Si l'erreur revient, ouvrez la page IAM et donnez le rôle Compte de service Cloud Build au compte <numéro>-compute@developer.gserviceaccount.com."
- [ ] **Step 4: Run, expect PASS.** Launch the app (`./gradlew :desktopApp:run`), force an error on step 4 with no network, and check the box layout.
- [ ] **Step 5: Commit** `git commit -m "Explain setup errors with their cause, what was tried and what to do"`

---

### Task 10: Repairs in the runner

**Files:**
- Modify: `SetupRunner.kt`, `SetupPorts.kt` (`SetupCli.deleteFunctions`), `FirebaseCli.kt`, `commonMain/.../setup/SetupAutomation.kt`, `SetupModel.kt`, `ui/setup/AutomaticTab.kt` (billing offer), strings
- Test: `SetupRunnerTest.kt` (fake `GoogleOps`), `FirebaseCliTest.kt`

**Interfaces:**
- Consumes: Tasks 7 and 8.
- Produces:
  - `SetupRunner(..., ops: (ToolPaths) -> GoogleOps)` new constructor parameter.
  - `data class RepairNote(val repair: Repair, val detail: String)`; `SetupInfo.repairs: Map<SetupStepId, List<RepairNote>>`
  - `StepState.NeedsBilling(val accounts: List<BillingAccountInfo>)` with commonMain `data class BillingAccountInfo(val name: String, val displayName: String)`
  - `SetupAutomation.confirmBilling(step: SetupStepId, account: String)`
  - `SetupCli.deleteFunctions(projectId: String, names: List<String>, region: String, onLine)` → `functions:delete <names...> --region <r> --force --project <id>`
  - `REQUIRED_APIS = listOf("firestore.googleapis.com", "firebaserules.googleapis.com", "firebasehosting.googleapis.com", "firebasestorage.googleapis.com", "storage.googleapis.com", "cloudfunctions.googleapis.com", "cloudbuild.googleapis.com", "artifactregistry.googleapis.com", "run.googleapis.com", "eventarc.googleapis.com", "pubsub.googleapis.com", "secretmanager.googleapis.com", "iamcredentials.googleapis.com", "iam.googleapis.com", "cloudbilling.googleapis.com", "cloudresourcemanager.googleapis.com")`
  - `BUILD_ROLE = "roles/cloudbuild.builds.builder"`, member `serviceAccount:<number>-compute@developer.gserviceaccount.com`

Behaviour:
- DATABASE, before anything: `ops.enableApis(pid, REQUIRED_APIS)`, note `ENABLE_APIS`.
- PHOTOS and SERVER, before deploying: `ops.billing(pid)`; not enabled → `StepState.NeedsBilling(accounts)` and stop. `confirmBilling` links the chosen account, then runs the step again. The UI offers the button only when there is exactly one account; otherwise it shows the `BILLING_REQUIRED` guidance.
- PHOTOS: `ops.storageBucket(pid) == null` → `STORAGE_NOT_READY` error.
- SERVER, before deploying: `ensureRole(BUILD_ROLE)`, note it when changed; `ops.functions(pid, "noctulist")` with state `FAILED` → `deleteFunctions` grouped by region, note `DELETE_FAILED_FUNCTIONS`.
- A step error with `AFTER_REPAIR`: run its repair once, note it, retry the step once; a second failure is shown.
- `CommandTimeoutException` → `TIMEOUT` error.
- TOOLS step: `DiskSpaceException` → `DISK_FULL`, `FileLockedException` → `FILE_LOCKED`, other `IOException` during install → `DOWNLOAD_FAILED`, status `Broken` after install → `TOOLS_BROKEN` (repair `REINSTALL_TOOLS`).
- `PERMISSION_DENIED` on RULES, SITE or PHOTOS: the runner calls `ops.missingPermissions(pid, listOf("firebaserules.releases.create", "firebasehosting.sites.update", "firebasestorage.buckets.get"))` and puts the missing ones in the error headline.

- [ ] **Step 1: Write the failing tests**
  - `databaseEnablesApisFirst`: `ops.calls` starts with `enableApis` with exactly `REQUIRED_APIS`.
  - `serverGrantsBuildRoleBeforeDeploy`: order `projectNumber`, `ensureRole(member, BUILD_ROLE)`, then cli `deploy`; repairs contain `GRANT_BUILD_ROLE`.
  - `serverDeletesFailedFunctionsBeforeDeploy`: fake functions `onSignupWritten FAILED europe-west6`, `onMailOutboxWritten ACTIVE` → one `deleteFunctions(pid, ["onSignupWritten"], "europe-west6")`.
  - `httpsToBackgroundErrorRepairsThenRetriesOnce`: first deploy throws the `https-to-background` fixture, second succeeds → deleteFunctions called, step Done.
  - `secondFailureAfterRepairIsShown`: both deploys fail → `StepState.Error` with `FUNCTIONS_FAILED_STATE`.
  - `billingNeverLinkedWithoutConfirmation`: billing disabled, one account → state `NeedsBilling`, `linkBilling` not called; after `confirmBilling(SERVER, "billingAccounts/1")` → called once and the step runs.
  - `timeoutBecomesTimeoutError`: cli throws `CommandTimeoutException(true)` → `TIMEOUT`.
  - `cancelDuringRepairReturnsToTodo`: cancel while `enableApis` suspends → DATABASE Todo, no later ops call.
  - `deleteFunctionsCommand` (FirebaseCliTest): exact argument list.
  - `diskSpaceBecomesDiskFull`: fake tools install throws `DiskSpaceException(600 MB)` → `DISK_FULL`.
  - `permissionDeniedNamesMissingPermission`: rules deploy fails with `HTTP Error: 403`, ops answers `["firebaserules.releases.create"]` → headline contains it.
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit** `git commit -m "Repair APIs, build role, billing and failed functions during setup"`

---

### Task 11: Ticks that are checked

**Files:**
- Modify: `SetupRunner.kt` (`refresh`), `commonMain/.../setup/SetupAutomation.kt` (`SetupInfo.checking: Boolean`), `ui/setup/AutomaticTab.kt` ("Tout vérifier" button), strings
- Test: `SetupRunnerTest.kt`

**Interfaces:**
- Consumes: Task 7 `rulesReleased`, `hostingLive`, `storageBucket`, `functions`; Task 4 `status()`.
- Produces: `refresh()` checks every step; `info.checking` is true while it runs.

Sign-in: `ops.checkAuth()` runs first; a `GoogleOpsException` with status 401 or reason `invalid_grant` → LOGIN Todo with `LOGIN_EXPIRED` and the other checks are skipped.

Rules: RULES done ⇔ Firestore rules released; SITE ⇔ `hostingLive`; PHOTOS ⇔ bucket and Storage rules released; SERVER ⇔ the three `SERVER_FUNCTIONS` all `ACTIVE` and the last test mail succeeded (Task 13 stores it; until then only the functions). A check that fails with a Google error leaves the tick as it is and sets nothing.

- [ ] **Step 1: Write the failing tests**
  - `refreshUnticksServerWhenAFunctionIsFailed`.
  - `refreshUnticksSiteWhenNoLiveVersion`.
  - `refreshKeepsTickWhenCheckErrors` (ops throws `GoogleOpsException`).
  - `refreshTicksRulesWhenReleased` (progress had nothing).
  - `refreshShowsExpiredSignIn`: `checkAuth` throws status 401 → LOGIN state `Error(LOGIN_EXPIRED)`, no other ops call.
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit** `git commit -m "Check every setup step against the project when the page opens"`

---

### Task 12: Workspace transport in the Cloud Functions

**Files:**
- Create: `webform/functions/src/workspace.ts`, `webform/functions/test/workspace.test.mjs`
- Modify: `webform/functions/src/mail.ts`, `webform/functions/src/index.ts`, `webform/functions/src/outbox.ts` (writes `failure`), `webform/functions/package.json` (firebase-functions and firebase-admin upgraded and pinned exact), `webform/functions/test/mail.test.mjs`, `webform/functions/test/outbox.test.mjs`
- Then: `./gradlew :shared:syncWebFormResources`

**Interfaces:**
- Produces:
  - `export async function delegatedToken(sa: string, subject: string, deps: { fetch: typeof fetch; now: () => number }): Promise<string>`: metadata server token for the runtime account, IAM Credentials `projects/-/serviceAccounts/<sa>:signJwt` with claims `{iss: sa, sub: subject, scope: "https://www.googleapis.com/auth/gmail.send", aud: "https://oauth2.googleapis.com/token", iat, exp: iat + 3600}`, then `grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer`; cached per `sa|subject` until 60 s before expiry.
  - `export async function gmailSendRaw(token: string, user: string, raw: Buffer, f: typeof fetch): Promise<void>`: POST `https://gmail.googleapis.com/gmail/v1/users/<user>/messages/send` with `{ raw: base64url }`; throws `MailSendError(code)` where code is `gmail:<status>:<reason>`.
  - `MailTransport` gains `mode: () => "app_password" | "workspace"`, `from: () => string`, `sa: () => string`, `workspace?: { token: typeof delegatedToken; send: typeof gmailSendRaw }` (tests pass fakes).
  - `sendMail` and `sendRawMail` return `{ sent: boolean; failure: string }`; failure codes: `smtp:<responseCode>`, `gmail:<status>:<reason>`, `delegation:unauthorized_client`, `""` when sent. Callers updated; outbox writes `failure` next to `reason`.
  - index.ts params: `MAIL_MODE` default `"app_password"`, `MAIL_FROM` default `""`, `MAIL_SA` default `""`.

- [ ] **Step 1: Write the failing tests**
  - `workspaceModeSendsThroughGmailApiWithFromAddress`: mode workspace, from `no-reply@example.org`, user `admin@example.org` → fake `send` called with user `admin@example.org` and a raw message whose `From:` header holds `no-reply@example.org`; SMTP fake never called.
  - `appPasswordModeUnchanged`: existing SMTP tests still pass with mode `app_password`.
  - `delegationNotAuthorizedGivesFailureCode`: token fake throws with OAuth error `unauthorized_client` → `{sent:false, failure:"delegation:unauthorized_client"}`.
  - `tokenIsCachedUntilNearExpiry`: two sends 10 min apart → one `signJwt` call; at 59 min 30 s → a second one.
  - `signJwtClaimsAreExact`: decoded payload has the five claims above, `exp - iat == 3600`.
  - `outboxStoresFailureCode`: REJECTED document gets `failure: "smtp:535"`.
- [ ] **Step 2: Run, expect FAIL**: `cd webform/functions && npm test`
- [ ] **Step 3: Implement.** Raw message for workspace mode built with nodemailer's `MailComposer` from the same fields as the SMTP path. Upgrade `firebase-functions` and `firebase-admin` (`npm install --save-exact firebase-functions@latest firebase-admin@latest`), fix what `npm run typecheck` reports.
- [ ] **Step 4: Run, expect PASS**: `npm test && npm run typecheck && npm run build`, then `./gradlew :shared:syncWebFormResources`; emulator capture tests still pass (`firebase/tests/emulators.sh`).
- [ ] **Step 5: Commit** `git commit -m "Send site mail from a Workspace address without a stored password"`

---

### Task 13: Mail step in the assistant

**Files:**
- Create: `shared/src/commonMain/kotlin/com/eventmanager/app/setup/MailSetup.kt`
- Modify: `SetupRunner.kt` (`runServer`), `SiteWorkspace.kt` (`writeFunctionParams`), `commonMain/.../setup/SetupAutomation.kt` (`StepInput`, `SetupInfo`), `email/SiteMailRelay.kt` (`SiteMailOutbox.send` returns the failure code), `ui/setup/AutomaticTab.kt`, `ManualTab.kt` (step 9 text), strings
- Test: `shared/src/commonTest/kotlin/com/eventmanager/app/setup/MailSetupTest.kt`, `SetupRunnerTest.kt`, `SiteWorkspaceTest.kt`, existing `SiteMailOutbox` tests

**Interfaces:**
- Consumes: Task 7 `ensureMailServiceAccount`, Task 12 params and `failure` field.
- Produces:
  - `enum class MailMode { APP_PASSWORD, WORKSPACE }`
  - `object MailSetup { fun suggestedMode(address: String): MailMode` (`gmail.com`, `googlemail.com` → `APP_PASSWORD`, else `WORKSPACE`); `fun failureToError(code: String): SetupErrorId` (`delegation:unauthorized_client` → `MAIL_DELEGATION_MISSING`; `smtp:535`, `smtp:534` → `MAIL_AUTH_REFUSED`; anything else non-empty → `MAIL_TEST_FAILED`) `}`
  - `StepInput` gains `mailMode: MailMode?`, `mailFrom: String?`; `SetupInfo` gains `mailServiceAccount: MailServiceAccountInfo?` (`email`, `clientId`) and `mailTestOk: Boolean`
  - `SetupAutomation.sendTestMail()`; `SetupSettings.mailTestOk: Boolean` (persisted)
  - `SiteWorkspace.writeFunctionParams(dir, projectId, user, region, mode: MailMode, from: String, sa: String)` writes `GMAIL_USER`, `REGION`, `MAIL_MODE`, `MAIL_FROM`, `MAIL_SA`
  - `SiteMailOutbox.send(...)` returns `SiteMailOutcome(val result: SiteMailResult, val failure: String)`; `SiteMailRelay.send` keeps returning `SiteMailResult` for its current callers.

Behaviour of step 9 in Workspace mode: `ensureMailServiceAccount` → show client ID and scope with copy buttons and the link `https://admin.google.com/ac/owl/domainwidedelegation`; secret set to `unused`; deploy as before; then "Envoyer un e-mail de test" to the signed-in admin; step Done only when the test answers `Sent`. `Unavailable` → `MAIL_TEST_UNAVAILABLE` (organization not created yet on this device). When `mailFrom` differs from the sending account, the step shows the one-time Gmail alias instructions before the test.

- [ ] **Step 1: Write the failing tests**
  - `suggestedModeByDomain`: `a@gmail.com` → APP_PASSWORD, `a@collectif-nocturne.ch` → WORKSPACE.
  - `failureCodesMapToErrors`: the three mappings above.
  - `workspaceModeWritesUnusedSecretAndParams`: runner with WORKSPACE → `setSecret` value `unused`, params file has `MAIL_MODE=workspace`, `MAIL_FROM=no-reply@collectif-nocturne.ch`, `MAIL_SA=noctulist-mail@<pid>.iam.gserviceaccount.com`.
  - `appPasswordModeKeepsPassword`: unchanged secret value, `MAIL_MODE=app_password`.
  - `serverNotDoneUntilTestMailSent`: deploy ok, no test → SERVER not Done; test `Sent` → Done and `mailTestOk` saved.
  - `delegationMissingKeepsStepOpen`: test answers Rejected with `delegation:unauthorized_client` → `StepState.Error(MAIL_DELEGATION_MISSING)`, `setSecret` not called again.
  - `writeFunctionParamsRejectsBadFrom`: `mailFrom = "a@b.c\nX=1"` → `IllegalArgumentException`.
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit** `git commit -m "Choose the sending address and test mail at the end of the server step"`

---

### Task 14: Manual check on clean machines

**Files:**
- Create: `docs/superpowers/checklists/2026-11-19-setup-assistant-manual-check.md`

**Interfaces:** none.

- [ ] **Step 1: Write the checklist**, one line per item with the expected result: packaged app launched from Finder and from the Start menu (not Gradle); second launch; install tools behind no proxy and behind a proxy; delete `<appData>/setup` tools folder then open the page (step 0 shows "à réinstaller"); sign in, sign out, switch account; new project on a Workspace domain through step 10 (role, APIs, billing offer, FAILED functions from a previous attempt, test mail in Workspace mode with and without the delegation); same with a gmail.com account and an app password; home folder with a space and an accent on Windows; Defender on.
- [ ] **Step 2: Run it on macOS and on Windows**, add each new error to the catalogue (Task 8 pattern, Task 9 texts) before closing.
- [ ] **Step 3: Commit** `git commit -m "Add the manual check for the setup assistant"`
