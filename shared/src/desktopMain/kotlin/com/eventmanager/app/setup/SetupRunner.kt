package com.eventmanager.app.setup

import com.eventmanager.app.email.SiteMailOutcome
import com.eventmanager.app.email.SiteMailResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/**
 * Google APIs a project on the free Spark plan can turn on: enabled before the database step and when a
 * free step (rules, site, photos) finds one off. Idempotent on Google's side.
 */
val FREE_APIS = listOf(
    "firestore.googleapis.com", "firebaserules.googleapis.com", "firebasehosting.googleapis.com",
    "firebasestorage.googleapis.com", "storage.googleapis.com", "iam.googleapis.com", "iamcredentials.googleapis.com",
    "cloudresourcemanager.googleapis.com", "cloudbilling.googleapis.com",
)

/**
 * APIs Google only turns on for a project with billing (the server functions need them). Asking for them on a
 * Spark project fails the whole batch, so they wait until the server step found billing ready.
 */
val BILLED_APIS = listOf(
    "cloudfunctions.googleapis.com", "cloudbuild.googleapis.com", "artifactregistry.googleapis.com",
    "run.googleapis.com", "eventarc.googleapis.com", "pubsub.googleapis.com", "secretmanager.googleapis.com",
)

/** Role the default compute account needs to build 2nd gen functions. */
const val BUILD_ROLE = "roles/cloudbuild.builds.builder"

/**
 * Roles of the default compute account, which builds the functions and also runs them. New projects leave it
 * without any of these: without Firestore and Storage, every portal request fails with PERMISSION_DENIED.
 */
val COMPUTE_ROLES = listOf(BUILD_ROLE, "roles/datastore.user", "roles/storage.objectAdmin")

/**
 * Runs the setup steps one at a time and keeps their state for the Automatic tab.
 * Order and retry rules follow the spec (§3, §4).
 */
class SetupRunner(
    private val scope: CoroutineScope,
    private val tools: SetupTools,
    private val cliFactory: (ToolPaths) -> SetupCli,
    private val opsFactory: suspend (ToolPaths) -> GoogleOps,
    private val workspace: SiteWorkspace,
    private val settings: SetupSettings,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val secrets: ActiveSecrets = ActiveSecrets(),
    private val redactor: OutputRedactor = OutputRedactor { secrets.current() },
    private val mailTester: MailTester = MailTester { SiteMailOutcome(SiteMailResult.Unavailable) },
) : SetupAutomation {
    override val available = true

    private val _progress = MutableStateFlow(SetupProgress.decode(settings.loadProgress()))
    override val progress: StateFlow<SetupProgress> = _progress.asStateFlow()

    private val _states = MutableStateFlow(initialStates(_progress.value))
    override val states: StateFlow<Map<SetupStepId, StepState>> = _states.asStateFlow()

    private val _info = MutableStateFlow(SetupInfo(mailTestOk = mailTestOk()))
    override val info: StateFlow<SetupInfo> = _info.asStateFlow()

    private var job: Job? = null
    private val jobs = mutableListOf<Job>()
    private var cli: SetupCli? = null
    private var paths: ToolPaths? = null
    private var ops: GoogleOps? = null

    /** Keeps a refresh from re-ticking the sign-in while it is being signed out. */
    private val accountLock = Mutex()

    /**
     * Project the running refresh checks. A step may switch the project while a refresh waits on Google: from then on
     * its answers are about the old project, so they change nothing (see [stale], [tick], [drop]).
     */
    @Volatile
    private var refreshProject: String? = null

    private fun stale(): Boolean = refreshProject?.let { it != settings.projectId } == true

    /** Re-checks every step it can see from here; a tick that is no longer true goes away. */
    override suspend fun refresh() = accountLock.withLock {
        _info.update { it.copy(checking = true) }
        val pid = settings.projectId
        refreshProject = pid
        try {
            refreshLocked(pid)
        } catch (_: SignInExpired) {
            // Already shown on the sign-in step; the other checks need a valid sign-in.
        } finally {
            refreshProject = null
            _info.update { it.copy(checking = false) }
        }
    }

    private class SignInExpired : Exception()

    private suspend fun refreshLocked(pid: String) {
        // Tools are being installed or replaced: probing or spawning them now would hold their files open.
        val toolsStep = _states.value[SetupStepId.TOOLS]
        if (toolsStep is StepState.Running || toolsStep is StepState.WaitingRetry) return
        val paths = when (val status = tools.status()) {
            is ToolsStatus.Ready -> status.paths
            ToolsStatus.Missing -> return noTools(ToolsState.MISSING)
            is ToolsStatus.Broken -> return noTools(ToolsState.BROKEN)
            is ToolsStatus.Outdated -> return noTools(ToolsState.OUTDATED)
        }
        // The tools may have started installing while they were probed.
        val toolsNow = _states.value[SetupStepId.TOOLS]
        if (toolsNow is StepState.Running || toolsNow is StepState.WaitingRetry) return
        _info.update { it.copy(tools = ToolsState.READY) }
        tick(SetupStepId.TOOLS, true)
        val c = useTools(paths)
        val account = orNull { c.loginList().firstOrNull() } ?: return drop(SetupStepId.LOGIN)
        _info.update { it.copy(account = account) }
        // The CLI lists an account whose token Google may have revoked: ask Google itself before ticking it.
        val signedIn = check(SetupStepId.LOGIN) { requireOps(SetupStepId.LOGIN).checkAuth() } != null
        if (signedIn) tick(SetupStepId.LOGIN, true)
        // A failed list says nothing about the project: its tick and the list shown stay, the checks that need it wait.
        val projects = orNull { c.projectsList() } ?: return
        if (stale()) return
        _info.update { it.copy(projects = projects) }
        if (pid.isBlank() || projects.none { it.id == pid }) return drop(SetupStepId.PROJECT)
        tick(SetupStepId.PROJECT, true)
        // A failed call says nothing about the database: ticks stay and the checks that need it are skipped.
        val listed = orNull { c.databases(pid) }
        if (stale()) return
        val db = listed?.firstOrNull { it.name == DEFAULT_DB }
        if (db != null) {
            tick(SetupStepId.DATABASE, true)
            _info.update { it.copy(region = RegionMapper.functionsRegion(db.locationId)) }
        } else if (listed != null) {
            tick(SetupStepId.DATABASE, false)
            // Rules and photos cannot be in place without Firestore.
            tick(SetupStepId.RULES, false)
            tick(SetupStepId.PHOTOS, false)
            val locations = orNull { c.firestoreLocations(pid) }.orEmpty()
            if (stale()) return
            _info.update { it.copy(locations = RegionMapper.orderLocations(locations)) }
        }
        tick(SetupStepId.CONNECT, settings.hasWebClient())
        // Without a reachable Google account the checks below cannot say anything: ticks stay as they are.
        if (!signedIn) return
        if (db != null) {
            val rules = check(SetupStepId.RULES) { requireOps(SetupStepId.RULES).rulesReleased(pid) }
            if (stale()) return
            if (rules != null) tick(SetupStepId.RULES, rules.first)
            if (rules != null) {
                val photos = if (!rules.second) false else check(SetupStepId.PHOTOS) {
                    requireOps(SetupStepId.PHOTOS).storageBucket(pid) != null
                }
                if (photos != null) tick(SetupStepId.PHOTOS, photos)
            }
        }
        if (stale()) return
        check(SetupStepId.SITE) { requireOps(SetupStepId.SITE).hostingLive(pid) }?.let { tick(SetupStepId.SITE, it) }
        if (stale()) return
        check(SetupStepId.SERVER) { requireOps(SetupStepId.SERVER).functions(pid, CODEBASE) }?.let { functions ->
            if (stale()) return
            val active = functionsActive(functions)
            _info.update { it.copy(serverDeployed = active, mailTestOk = mailTestOk()) }
            tick(SetupStepId.SERVER, active && mailTestOk())
        }
        // After a restart the client ID box needs the service account again. Read only: a check writes nothing.
        if (settings.mailWorkspaceProject == pid && _info.value.mailServiceAccount == null) {
            check(SetupStepId.SERVER) { listOfNotNull(requireOps(SetupStepId.SERVER).mailServiceAccount(pid)) }
                ?.firstOrNull()
                ?.takeIf { it.email.isNotBlank() && !stale() }
                ?.let { sa -> _info.update { it.copy(mailServiceAccount = MailServiceAccountInfo(sa.email, sa.clientId)) } }
        }
    }

    /** Null when [block] failed for any reason but cancellation, which goes on up. */
    private suspend fun <T> orNull(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** A test mail only counts for the project it was sent from. */
    private fun mailTestOk(): Boolean = settings.projectId.isNotBlank() && settings.mailTestProject == settings.projectId

    private fun functionsActive(functions: List<FunctionState>): Boolean =
        SERVER_FUNCTIONS.all { name -> functions.any { it.name == name && it.state == "ACTIVE" } }

    /** A step that is running owns its own state: a check must not flip it, nor one about a project left meanwhile. */
    private fun tick(step: SetupStepId, done: Boolean) {
        if (stale()) return
        val state = _states.value[step]
        if (state is StepState.Running || state is StepState.WaitingRetry) return
        if (done) markDone(step) else unmark(step)
    }

    /**
     * Runs one Google check. A failed check returns null and changes nothing; only an expired sign-in
     * (HTTP 401 / NOT_LOGGED_IN / invalid_grant) un-ticks the sign-in and stops the refresh.
     */
    private suspend fun <T : Any> check(step: SetupStepId, block: suspend () -> T): T? {
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: GoogleOpsException) {
            val err = e.error
            if (signInRefused(err)) {
                expireSignIn(err)
                throw SignInExpired()
            }
            return null
        } catch (e: Exception) {
            return null
        }
    }

    /** False when Google refuses the listed account's token; other failures (network) are thrown as they are. */
    private suspend fun tokenAccepted(step: SetupStepId): Boolean = try {
        requireOps(step).checkAuth()
        true
    } catch (e: GoogleOpsException) {
        if (!signInRefused(e.error)) throw e
        false
    }

    private fun signInRefused(err: GoogleApiError) =
        err.status == 401 || err.reason == "NOT_LOGGED_IN" || err.reason == "invalid_grant"

    private fun expireSignIn(err: GoogleApiError) {
        drop(SetupStepId.LOGIN)
        val api = GoogleApiStatus(err.status, err.reason, err.service)
        val error = ErrorCatalog.classify(err.message, SetupStepId.LOGIN, settings.projectId, api)
        val current = _states.value[SetupStepId.LOGIN]
        if (current !is StepState.Running && current !is StepState.WaitingRetry) setState(SetupStepId.LOGIN, StepState.Error(error))
    }

    override fun run(step: SetupStepId, input: StepInput) = launchStep(step, input, reinstall = false)

    override fun sendTestMail() =
        launchStep(SetupStepId.SERVER, StepInput(), reinstall = false, work = { testMail() })

    override fun confirmBilling(step: SetupStepId, account: String, input: StepInput) {
        val offered = _states.value[step] as? StepState.NeedsBilling ?: return
        if (step !in BILLED_STEPS || offered.accounts.none { it.name == account }) return
        launchStep(step, input, reinstall = false, before = { requireOps(step).linkBilling(projectId(), account) })
    }

    /** Throws the app's own tools away and installs them again, whatever state they are in. */
    override fun reinstallTools() {
        val tools = _states.value[SetupStepId.TOOLS]
        if (tools is StepState.Running || tools is StepState.WaitingRetry) return
        // Running commands hold the tools open (Windows cannot delete node.exe then): stop them and wait first.
        val running = synchronized(jobs) { jobs.toList().also { jobs.clear() } }
        running.forEach { it.cancel() }
        launchStep(SetupStepId.TOOLS, StepInput(), reinstall = true, stopFirst = running)
    }

    private fun launchStep(
        step: SetupStepId,
        input: StepInput,
        reinstall: Boolean,
        stopFirst: List<Job> = emptyList(),
        before: (suspend () -> Unit)? = null,
        work: suspend () -> Unit = { executeWithRepair(step, input, reinstall) },
    ) {
        if (_states.value[step] is StepState.Running || _states.value[step] is StepState.WaitingRetry) return
        // Shown as running from the click on (queued steps too), so a second click is ignored.
        setState(step, StepState.Running(emptyList()))
        // Steps run one after the other: a step started while the previous one is finishing waits for it.
        val previous = job
        // ATOMIC: a step cancelled while queued still runs its catch below and goes back to Todo.
        val next = scope.launch(start = CoroutineStart.ATOMIC) {
            try {
                try {
                    stopFirst.joinAll()
                    previous?.join()
                    _info.update { it.copy(repairs = it.repairs - step) }
                    before?.let { attempt(step, it) }
                    work()
                } catch (e: CliException) {
                    throw CliException(explain(step, e.error))
                }
            } catch (e: CancellationException) {
                setState(step, StepState.Todo)
                throw e
            } catch (e: CliException) {
                setState(step, StepState.Error(clean(e.error)))
            } catch (e: Exception) {
                setState(step, StepState.Error(clean(unknown(step, e.message.orEmpty()))))
            } finally {
                secrets.clear()
                input.appPassword?.fill('\u0000')
            }
        }
        job = next
        synchronized(jobs) {
            jobs.removeAll { !it.isActive }
            jobs += next
        }
    }

    /**
     * Runs the step; an error whose repair the app can make alone is repaired once and the step runs once more.
     * Signing in again and reinstalling the tools need the admin, so those errors are shown as they are.
     */
    private suspend fun executeWithRepair(step: SetupStepId, input: StepInput, reinstall: Boolean) {
        try {
            attempt(step) { execute(step, input, reinstall) }
        } catch (e: CliException) {
            val repair = e.error.repair
            if (e.error.retry != RetryPolicy.AFTER_REPAIR || repair !in AUTOMATIC_REPAIRS) throw e
            var repaired = false
            attempt(step) { repaired = repair(step, repair!!, e.error) }
            if (!repaired) throw e // nothing the app may fix: running again would only fail the same way
            setState(step, StepState.Running(emptyList()))
            attempt(step) { execute(step, input, reinstall) }
        }
    }

    /** Google helper errors and timeouts become catalogue errors, so they get a cause and a repair like CLI ones. */
    private suspend fun attempt(step: SetupStepId, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: GoogleOpsException) {
            val api = GoogleApiStatus(e.error.status, e.error.reason, e.error.service)
            throw CliException(ErrorCatalog.classify(e.error.message, step, settings.projectId, api))
        } catch (e: CommandTimeoutException) {
            val message = e.message.orEmpty()
            throw CliException(SetupError(SetupErrorId.TIMEOUT, step, RetryPolicy.NONE, null, null, message, message))
        }
    }

    /** Returns false when the repair found nothing to change. */
    private suspend fun repair(step: SetupStepId, repair: Repair, error: SetupError): Boolean = when (repair) {
        Repair.ENABLE_APIS -> { enableApis(step); true }
        Repair.GRANT_BUILD_ROLE -> { grantBuildRole(step, force = true); true }
        Repair.DELETE_FAILED_FUNCTIONS -> deleteFailedFunctions(step, error.details)
        // The Hosting site is created inside the deploy (see deployTo); the other two need the admin.
        Repair.CREATE_HOSTING_SITE, Repair.REINSTALL_TOOLS, Repair.RELOGIN -> false
    }

    /** The server step may turn on billed APIs (its billing was checked first); every other step only the free ones. */
    private suspend fun enableApis(step: SetupStepId, apis: List<String> = if (step == SetupStepId.SERVER) FREE_APIS + BILLED_APIS else FREE_APIS) {
        requireOps(step).enableApis(projectId(), apis)
        note(step, Repair.ENABLE_APIS, "${apis.size} API")
    }

    /** 2nd gen functions are built and run as the default compute account: it gets [COMPUTE_ROLES]. */
    private suspend fun grantBuildRole(step: SetupStepId, force: Boolean) {
        val pid = projectId()
        val number = requireOps(step).projectNumber(pid)
        if (!PROJECT_NUMBER.matches(number)) {
            val message = "Google returned an invalid project number for $pid: \"${number.take(40)}\""
            throw CliException(SetupError(SetupErrorId.UNKNOWN, step, RetryPolicy.NONE, null, null, message, message))
        }
        val member = "serviceAccount:$number-compute@developer.gserviceaccount.com"
        val changed = COMPUTE_ROLES.filter { requireOps(step).ensureRole(pid, member, it) }
        if (changed.isNotEmpty() || force) note(step, Repair.GRANT_BUILD_ROLE, (changed.ifEmpty { COMPUTE_ROLES }).joinToString(", "))
    }

    /**
     * A function left FAILED by an earlier deploy, or one that cannot change type (HTTPS to background),
     * blocks the deploy until it is deleted. [failure] is the deploy output naming the latter. Only functions
     * Google lists in the NoctuList codebase are deleted. Returns false when there was nothing to delete.
     */
    private suspend fun deleteFailedFunctions(step: SetupStepId, failure: String = ""): Boolean {
        val pid = projectId()
        val ours = requireOps(step).functions(pid, CODEBASE)
        val (located, noRegion) = ours.partition { it.region.isNotBlank() }
        noRegion.forEach { log(step, "Function ${it.name} has no region, not deleted") }
        val known = located.map { it.name to it.region }.toSet()
        val failed = located.filter { it.state == "FAILED" }.map { it.name to it.region }
        val blocked = TYPE_CHANGE.findAll(failure).map { it.groupValues[1] to it.groupValues[2] }.filter { it in known }.toList()
        val targets = (failed + blocked).distinct()
        if (targets.isEmpty()) return false
        val c = requireCli()
        targets.groupBy({ it.second }, { it.first }).forEach { (region, names) ->
            c.deleteFunctions(pid, names, region) { log(step, it) }
        }
        note(step, Repair.DELETE_FAILED_FUNCTIONS, targets.joinToString(", ") { it.first })
        return true
    }

    /** Nothing is linked here: the admin chooses the account (see [confirmBilling]). Returns false when billing is off. */
    private suspend fun billingReady(step: SetupStepId): Boolean {
        val status = requireOps(step).billing(projectId())
        if (status.enabled) return true
        setState(step, StepState.NeedsBilling(status.accounts.map { BillingAccountInfo(it.name, it.displayName) }))
        return false
    }

    /** A refused permission on the deploy steps: say which ones the account lacks, when Google can tell. */
    private suspend fun explain(step: SetupStepId, error: SetupError): SetupError {
        if (error.id != SetupErrorId.PERMISSION_DENIED || step !in PERMISSION_STEPS) return error
        val missing = try {
            requireOps(step).missingPermissions(projectId(), CHECKED_PERMISSIONS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return error
        }
        if (missing.isEmpty()) return error
        val names = "Missing permissions: ${missing.joinToString(", ")}"
        return error.copy(headline = if (error.headline.isBlank()) names else "$names. ${error.headline}")
    }

    private fun note(step: SetupStepId, repair: Repair, detail: String) {
        _info.update { it.copy(repairs = it.repairs + (step to (it.repairs[step].orEmpty() + RepairNote(repair, detail)))) }
    }

    /** The stored error must not hold a secret: they are cleared as soon as the step ends. */
    private fun clean(error: SetupError) =
        error.copy(headline = redactor.redact(error.headline), details = redactor.redact(error.details))

    override fun report(step: SetupStepId): String {
        val error = (_states.value[step] as? StepState.Error)?.error ?: return ""
        val os = "${System.getProperty("os.name")} ${System.getProperty("os.version")}"
        val versions = "Node.js ${ToolsManifest.NODE_VERSION}, firebase-tools ${ToolsManifest.FIREBASE_TOOLS_VERSION}"
        val raw = SetupReport.build(step, error, _info.value.repairs[step].orEmpty(), os, versions)
        return redactor.redact(raw)
    }

    override fun confirm(step: SetupStepId) {
        saveProgress { it.copy(confirmed = it.confirmed + step) }
        if (step.kind == StepKind.OPEN) markDone(step)
    }

    override fun setExtra(step: SetupStepId, enabled: Boolean) {
        if (!step.optional) return
        saveProgress { it.copy(extras = if (enabled) it.extras + step else it.extras - step) }
    }

    override suspend fun signOut() {
        signOutOnce()
    }

    /** Returns true when the CLI signed out; the local sign-in is forgotten either way. */
    private suspend fun signOutOnce(): Boolean {
        val all = synchronized(jobs) { jobs.toList().also { jobs.clear() } }
        all.forEach { it.cancel() }
        all.joinAll()
        // A cancelled caller must not leave the sign-out half done.
        return withContext(NonCancellable) {
            accountLock.withLock {
                var failure: CliException? = null
                try {
                    requireCli().logout()
                } catch (e: CliException) {
                    failure = e
                } catch (e: Exception) {
                    failure = CliException(unknown(SetupStepId.LOGIN, e.message.orEmpty()))
                }
                // Project ticks stay until the next refresh checks them.
                _info.update { it.copy(account = "", loginChallenge = null, projects = emptyList()) }
                unmark(SetupStepId.LOGIN)
                failure?.let { setState(SetupStepId.LOGIN, StepState.Error(it.error)) }
                failure == null
            }
        }
    }

    override suspend fun switchAccount() {
        if (signOutOnce()) run(SetupStepId.LOGIN)
    }

    /** Stops the running step (its process is destroyed) and drops the queued ones. */
    override fun cancel() {
        val all = synchronized(jobs) { jobs.toList().also { jobs.clear() } }
        all.forEach { it.cancel() }
    }

    override fun close() {
        cancel()
        scope.cancel()
    }

    private suspend fun execute(step: SetupStepId, input: StepInput, reinstall: Boolean) {
        when (step) {
            SetupStepId.TOOLS -> {
                val progress: (Float, String) -> Unit = { fraction, label ->
                    _info.update { it.copy(toolsProgress = fraction) }
                    log(step, "$label ${(fraction * 100).toInt()}%")
                }
                val paths = try {
                    val status = if (reinstall) null else tools.status()
                    if (status is ToolsStatus.Ready) {
                        status.paths
                    } else {
                        // A refresh may be probing the tools: wait for it before replacing them. Later ones see
                        // this step running and leave the tools alone.
                        accountLock.withLock { }
                        if (status == ToolsStatus.Missing) tools.install(progress) else tools.reinstall(progress)
                        // Installed means answers when run, not just files on disk.
                        (tools.status() as? ToolsStatus.Ready)?.paths
                            ?: throw CliException(toolsBroken(step, "tools do not respond after install"))
                    }
                } catch (e: Throwable) {
                    forgetTools()
                    withContext(NonCancellable) {
                        val fresh = runCatching { tools.status() }.getOrNull()
                        _info.update { it.copy(tools = toolsStateOf(fresh)) }
                    }
                    throw toolsError(step, e)
                } finally {
                    _info.update { it.copy(toolsProgress = null) }
                }
                useTools(paths)
                _info.update { it.copy(tools = ToolsState.READY) }
                markDone(step)
                // Errors that asked for new tools have them now: those steps can run again.
                _states.update { states ->
                    states.mapValues { (_, state) ->
                        if (state is StepState.Error && state.error.repair == Repair.REINSTALL_TOOLS) StepState.Todo else state
                    }
                }
            }
            SetupStepId.LOGIN -> {
                val c = requireCli()
                val code = input.authCode?.trim().orEmpty()
                if (code.isNotEmpty()) c.loginFinish(code)
                var account = c.loginList().firstOrNull()
                // The CLI keeps listing an account whose token Google revoked: ask Google, and sign in again if so.
                if (account != null && code.isEmpty() && !tokenAccepted(step)) {
                    c.logout()
                    _info.update { it.copy(account = "") }
                    account = null
                }
                if (account != null) {
                    _info.update { it.copy(account = account, loginChallenge = null) }
                    markDone(step)
                } else {
                    if (code.isNotEmpty()) throw notLoggedIn(step)
                    val challenge = c.loginStart()
                    _info.update { it.copy(loginChallenge = challenge) }
                    setState(step, StepState.Todo)
                }
            }
            SetupStepId.PROJECT -> {
                val c = requireCli()
                val id = input.projectId?.trim().orEmpty()
                require(SetupValidators.projectId(id)) { "invalid project id" }
                val name = input.newProjectName?.trim()
                if (!name.isNullOrBlank()) c.projectsCreate(id, name) { log(step, it) }
                val projects = c.projectsList()
                _info.update { it.copy(projects = projects) }
                require(projects.any { it.id == id }) { "project not found" }
                if (id != _progress.value.projectId) switchProject(id)
                settings.projectId = id
                markDone(step)
            }
            SetupStepId.DATABASE -> {
                val c = requireCli()
                val pid = projectId()
                enableApis(step)
                if (c.databases(pid).none { it.name == DEFAULT_DB }) {
                    val allowed = c.firestoreLocations(pid)
                    _info.update { it.copy(locations = RegionMapper.orderLocations(allowed)) }
                    val location = input.location ?: run {
                        setState(step, StepState.Todo)
                        return
                    }
                    c.createDatabase(pid, location, allowed) { log(step, it) }
                }
                markDone(step)
            }
            SetupStepId.RULES -> {
                deploy(step, "firestore:rules")
                markDone(step)
            }
            SetupStepId.PROVIDERS, SetupStepId.GOOGLE -> {
                confirm(step)
            }
            SetupStepId.CONNECT -> {
                input.webClientSecret?.trim()?.takeIf { it.isNotBlank() }?.let { secrets.add(it) }
                val c = requireCli()
                val pid = projectId()
                val appId = c.webApps(pid).firstOrNull() ?: c.createWebApp(pid) { log(step, it) }
                val config = c.sdkConfig(pid, appId)
                settings.setWebConfig(config.apiKey, config.appId)
                val clientId = input.webClientId?.trim().orEmpty()
                val clientSecret = input.webClientSecret?.trim().orEmpty()
                if (clientId.isNotBlank() && clientSecret.isNotBlank()) settings.setWebClient(clientId, clientSecret)
                if (settings.hasWebClient()) markDone(step) else setState(step, StepState.Todo)
            }
            SetupStepId.SITE -> {
                deploy(step, "hosting")
                if (_info.value.siteUrl.isBlank()) _info.update { it.copy(siteUrl = "https://${projectId()}.web.app") }
                markDone(step)
            }
            SetupStepId.PHOTOS -> {
                if (!billingReady(step)) return
                if (requireOps(step).storageBucket(projectId()) == null) {
                    throw CliException(ErrorCatalog.classify(STORAGE_MISSING, step, projectId()))
                }
                deploy(step, "storage")
                settings.enableProfilePhotos()
                markDone(step)
            }
            SetupStepId.SERVER -> runServer(input)
        }
    }

    /**
     * Spec §4: region, params file, secret, install, deploy with retries, cleanup policy. In Workspace mode the
     * sending service account is made first and the secret holds a placeholder. The step stays open after the
     * deploy: it is done once [sendTestMail] went through, and every deploy needs a new test.
     */
    private suspend fun runServer(input: StepInput) {
        val step = SetupStepId.SERVER
        val c = requireCli()
        val pid = projectId()
        val mode = input.mailMode ?: MailMode.APP_PASSWORD
        val gmail = input.gmailUser?.trim().orEmpty()
        require(SetupValidators.gmail(gmail)) { "invalid gmail address" }
        val from = if (mode == MailMode.WORKSPACE) input.mailFrom?.trim().orEmpty().ifEmpty { gmail } else ""
        require(from.isEmpty() || SetupValidators.gmail(from)) { "invalid from address" }
        val secret = when (mode) {
            MailMode.APP_PASSWORD -> {
                val password = input.appPassword ?: throw IllegalArgumentException("missing app password")
                require(password.isNotEmpty()) { "missing app password" }
                secrets.add(String(password))
                password
            }
            // The functions still declare the secret; Workspace mode never reads it.
            MailMode.WORKSPACE -> UNUSED_SECRET.toCharArray()
        }
        if (!billingReady(step)) return
        enableApis(step, BILLED_APIS)
        val db = c.databases(pid).firstOrNull { it.name == DEFAULT_DB } ?: throw IllegalStateException("no database")
        val region = RegionMapper.functionsRegion(db.locationId)
        _info.update { it.copy(region = region) }
        settings.mailTestProject = ""
        _info.update { it.copy(mailTestOk = false, serverDeployed = false) }
        val sa = if (mode == MailMode.WORKSPACE) {
            val ops = requireOps(step)
            ops.enableApis(pid, listOf(GMAIL_API))
            val account = ops.ensureMailServiceAccount(pid)
            _info.update { it.copy(mailServiceAccount = MailServiceAccountInfo(account.email, account.clientId)) }
            account.email
        } else {
            ""
        }
        val dir = workspace.prepare(pid, withStorage = storageOn())
        workspace.writeFunctionParams(dir, pid, gmail, region, mode, from, sa)
        settings.mailWorkspaceProject = if (mode == MailMode.WORKSPACE) pid else ""
        if (mode == MailMode.APP_PASSWORD) _info.update { it.copy(mailServiceAccount = null) }
        c.setSecret(pid, dir, SECRET_NAME, secret) { log(step, it) }
        c.npmInstall(dir.resolve("functions")) { log(step, it) }
        grantBuildRole(step, force = false)
        deleteFailedFunctions(step)
        deployTo(step, c, pid, dir, "hosting,functions")
        c.setArtifactPolicy(pid, region) { log(step, it) }
        forgetDone(step)
        _info.update { it.copy(serverDeployed = true) }
        setState(step, StepState.Todo)
    }

    /** One test mail to the signed-in admin through the site's outbox; only `Sent` ticks the server step. */
    private suspend fun testMail() {
        val step = SetupStepId.SERVER
        val to = _info.value.account.trim()
        require(SetupValidators.gmail(to)) { "no signed-in account to send the test mail to" }
        log(step, "Sending a test e-mail to the signed-in account")
        val outcome = mailTester.sendTest(to)
        val ok = outcome.result == SiteMailResult.Sent
        settings.mailTestProject = if (ok) projectId() else ""
        _info.update { it.copy(mailTestOk = ok) }
        if (ok) return markDone(step)
        forgetDone(step)
        val (id, headline) = when (outcome.result) {
            SiteMailResult.Unavailable -> SetupErrorId.MAIL_TEST_NO_ORGANIZATION to "No organization or signed-in team account on this device"
            SiteMailResult.NoAnswer -> SetupErrorId.MAIL_TEST_UNAVAILABLE to "The server did not answer in time"
            else -> {
                val code = outcome.failure.trim()
                MailSetup.failureToError(code) to if (code.isEmpty()) "Refused by the server" else "Mail failure: $code"
            }
        }
        throw CliException(SetupError(id, step, RetryPolicy.NONE, null, null, headline, headline))
    }

    private suspend fun deploy(step: SetupStepId, only: String) {
        val c = requireCli()
        val pid = projectId()
        val dir = workspace.prepare(pid, withStorage = storageOn() || step == SetupStepId.PHOTOS)
        deployTo(step, c, pid, dir, only)
    }

    /** Deploys with retries; creates the Hosting site once if the project has none; reads the site URL. */
    private suspend fun deployTo(step: SetupStepId, c: SetupCli, pid: String, dir: File, only: String) {
        val onLine: (String) -> Unit = { line ->
            log(step, line)
            HOSTING_URL.find(line.replace(ANSI, ""))?.let { m -> _info.update { it.copy(siteUrl = m.groupValues[1]) } }
        }
        try {
            withRetry(step) { c.deploy(pid, dir, only, onLine) }
        } catch (e: CliException) {
            if ("hosting" !in only || !NO_SITE.containsMatchIn(e.error.details)) throw e
            c.createHostingSite(pid, onLine)
            note(step, Repair.CREATE_HOSTING_SITE, "")
            withRetry(step) { c.deploy(pid, dir, only, onLine) }
        }
    }

    private suspend fun withRetry(step: SetupStepId, block: suspend () -> Unit) {
        var attempt = 0
        while (true) {
            try {
                block()
                return
            } catch (e: CliException) {
                if (e.error.retry != RetryPolicy.WAIT_AND_RETRY || attempt >= MAX_RETRIES) throw e
                attempt++
                for (secondsLeft in RETRY_SECONDS downTo 1) {
                    setState(step, StepState.WaitingRetry(attempt, secondsLeft))
                    sleep(1_000)
                }
                setState(step, StepState.Running(emptyList()))
            }
        }
    }

    private fun storageOn(): Boolean {
        val p = _progress.value
        return SetupStepId.PHOTOS in p.done || SetupStepId.PHOTOS in p.confirmed
    }

    private suspend fun requireCli(): SetupCli = cli
        ?: (tools.status() as? ToolsStatus.Ready)?.let { ready -> useTools(ready.paths) }
        ?: throw CliException(toolsBroken(SetupStepId.TOOLS, "tools not ready"))

    /** The Google helper runs with the same tools as the CLI; its output goes to [step]'s log. */
    private suspend fun requireOps(step: SetupStepId): GoogleOps {
        requireCli()
        val o = ops ?: opsFactory(paths ?: throw CliException(toolsBroken(SetupStepId.TOOLS, "tools not ready"))).also { ops = it }
        return o.forStep(step)
    }

    private fun useTools(found: ToolPaths): SetupCli {
        if (found != paths) ops = null
        paths = found
        return cliFactory(found).also { cli = it }
    }

    private fun forgetTools() {
        cli = null
        paths = null
        ops = null
    }

    /** What went wrong while installing the tools, by cause. */
    private fun toolsError(step: SetupStepId, e: Throwable): Throwable {
        val id = when (e) {
            is DiskSpaceException -> SetupErrorId.DISK_FULL
            is FileLockedException -> SetupErrorId.FILE_LOCKED
            is IOException -> SetupErrorId.DOWNLOAD_FAILED
            else -> return e
        }
        val message = e.message.orEmpty()
        return CliException(SetupError(id, step, RetryPolicy.NONE, null, null, message, message))
    }

    /** Ticks belong to one project: choosing another keeps only the computer-wide steps. */
    private fun switchProject(id: String) {
        saveProgress { it.copy(projectId = id, done = it.done.filter { s -> s in MACHINE_STEPS }.toSet(), confirmed = emptySet()) }
        // Mail state belongs to the old project's server: the new one shows its own after a refresh.
        _info.update { it.copy(mailServiceAccount = null, mailTestOk = false, serverDeployed = false) }
        _states.update { states ->
            states.mapValues { (step, state) ->
                if (step in MACHINE_STEPS || state is StepState.Running || state is StepState.WaitingRetry) state else StepState.Todo
            }
        }
    }

    private fun toolsStateOf(status: ToolsStatus?) = when (status) {
        is ToolsStatus.Ready -> ToolsState.READY
        is ToolsStatus.Broken -> ToolsState.BROKEN
        is ToolsStatus.Outdated -> ToolsState.OUTDATED
        else -> ToolsState.MISSING
    }

    private fun noTools(state: ToolsState) {
        val running = _states.value[SetupStepId.TOOLS]
        if (running is StepState.Running || running is StepState.WaitingRetry) return // the install is judged when it ends
        forgetTools()
        _info.update { it.copy(tools = state) }
        drop(SetupStepId.TOOLS)
    }

    /** [unmark] for a refresh: nothing changes once the project it checks was left. */
    private fun drop(step: SetupStepId) {
        if (!stale()) unmark(step)
    }

    private fun unmark(step: SetupStepId) {
        val state = _states.value[step]
        if (state is StepState.Running || state is StepState.WaitingRetry) return
        if (state == StepState.Done) setState(step, StepState.Todo)
        if (step in _progress.value.done) saveProgress { it.copy(done = it.done - step) }
    }

    /** Drops [step]'s saved tick while it runs ([unmark] leaves running steps alone). */
    private fun forgetDone(step: SetupStepId) {
        if (step in _progress.value.done) saveProgress { it.copy(done = it.done - step) }
    }

    private fun projectId(): String = settings.projectId.also {
        require(SetupValidators.projectId(it)) { "no project selected" }
    }

    private fun notLoggedIn(step: SetupStepId) =
        CliException(SetupError(SetupErrorId.LOGIN_EXPIRED, step, RetryPolicy.NONE, Repair.RELOGIN, null, "", ""))

    private fun toolsBroken(step: SetupStepId, message: String) =
        SetupError(SetupErrorId.TOOLS_BROKEN, step, RetryPolicy.NONE, Repair.REINSTALL_TOOLS, null, message, message)

    private fun unknown(step: SetupStepId, message: String) =
        SetupError(SetupErrorId.UNKNOWN, step, RetryPolicy.NONE, null, null, message, message)

    private fun log(step: SetupStepId, line: String) {
        _states.update { states ->
            val current = states[step]
            if (current is StepState.Running) states + (step to StepState.Running((current.log + line).takeLast(MAX_LOG_LINES)))
            else states
        }
    }

    private fun setState(step: SetupStepId, state: StepState) {
        _states.update { it + (step to state) }
    }

    private fun markDone(step: SetupStepId) {
        setState(step, StepState.Done)
        saveProgress { it.copy(done = it.done + step) }
    }

    private fun saveProgress(change: (SetupProgress) -> SetupProgress) {
        _progress.update(change)
        settings.saveProgress(_progress.value.encode())
    }

    private companion object {
        const val DEFAULT_DB = "(default)"
        const val SECRET_NAME = "GMAIL_APP_PASSWORD"
        const val UNUSED_SECRET = "unused"
        const val GMAIL_API = "gmail.googleapis.com"
        const val MAX_RETRIES = 5
        const val RETRY_SECONDS = 120
        const val MAX_LOG_LINES = 200
        val SERVER_FUNCTIONS = listOf("onSignupWritten", "onPortalRequestWritten", "onMailOutboxWritten")
        val MACHINE_STEPS = setOf(SetupStepId.TOOLS, SetupStepId.LOGIN)
        val HOSTING_URL = Regex("Hosting URL:\\s*(https://\\S+)")
        val NO_SITE = Regex("no Hosting site", RegexOption.IGNORE_CASE)
        val ANSI = Regex("\u001B\\[[0-9;]*m")
        const val CODEBASE = "noctulist"
        const val STORAGE_MISSING = "Firebase Storage has not been set up on this project."
        val BILLED_STEPS = setOf(SetupStepId.PHOTOS, SetupStepId.SERVER)
        val PERMISSION_STEPS = setOf(SetupStepId.RULES, SetupStepId.SITE, SetupStepId.PHOTOS)
        val CHECKED_PERMISSIONS = listOf("firebaserules.releases.create", "firebasehosting.sites.update", "firebasestorage.buckets.get")
        // Signing in again and reinstalling the tools need the admin; the Hosting site is made by deployTo.
        val AUTOMATIC_REPAIRS = setOf(Repair.ENABLE_APIS, Repair.GRANT_BUILD_ROLE, Repair.DELETE_FAILED_FUNCTIONS)
        // "[noctulist:onMailOutboxWritten(europe-west6)] Changing from an HTTPS function ..."
        val PROJECT_NUMBER = Regex("^[0-9]+$")
        val TYPE_CHANGE = Regex("""\[noctulist:([A-Za-z][A-Za-z0-9_-]*)\(([a-z]+-[a-z]+[0-9]+)\)] Changing from an HTTPS function""")

        fun initialStates(progress: SetupProgress): Map<SetupStepId, StepState> =
            SetupStepId.entries.associateWith { step ->
                val confirmedOpen = step.kind == StepKind.OPEN && step in progress.confirmed
                if (step in progress.done || confirmedOpen) StepState.Done else StepState.Todo
            }
    }
}
