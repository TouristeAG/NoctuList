package com.eventmanager.app.setup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File

/**
 * Runs the setup steps one at a time and keeps their state for the Automatic tab.
 * Order and retry rules follow the spec (§3, §4).
 */
class SetupRunner(
    private val scope: CoroutineScope,
    private val tools: SetupTools,
    private val cliFactory: (ToolPaths) -> SetupCli,
    private val workspace: SiteWorkspace,
    private val settings: SetupSettings,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val secrets: ActiveSecrets = ActiveSecrets(),
) : SetupAutomation {
    override val available = true

    private val _progress = MutableStateFlow(SetupProgress.decode(settings.loadProgress()))
    override val progress: StateFlow<SetupProgress> = _progress.asStateFlow()

    private val _states = MutableStateFlow(initialStates(_progress.value))
    override val states: StateFlow<Map<SetupStepId, StepState>> = _states.asStateFlow()

    private val _info = MutableStateFlow(SetupInfo())
    override val info: StateFlow<SetupInfo> = _info.asStateFlow()

    private var job: Job? = null
    private val jobs = mutableListOf<Job>()
    private var cli: SetupCli? = null

    /** Re-checks the steps it can see from here; a tick that is no longer true goes away. */
    override suspend fun refresh() {
        val paths = when (val status = tools.status()) {
            is ToolsStatus.Ready -> status.paths
            ToolsStatus.Missing -> return noTools(ToolsState.MISSING)
            is ToolsStatus.Broken -> return noTools(ToolsState.BROKEN)
            is ToolsStatus.Outdated -> return noTools(ToolsState.OUTDATED)
        }
        _info.update { it.copy(tools = ToolsState.READY) }
        markDone(SetupStepId.TOOLS)
        val c = cliFactory(paths).also { cli = it }
        val account = runCatching { c.loginList().firstOrNull() }.getOrNull() ?: return unmark(SetupStepId.LOGIN)
        _info.update { it.copy(account = account) }
        markDone(SetupStepId.LOGIN)
        val projects = runCatching { c.projectsList() }.getOrDefault(emptyList())
        _info.update { it.copy(projects = projects) }
        val pid = settings.projectId
        if (pid.isBlank() || projects.none { it.id == pid }) return unmark(SetupStepId.PROJECT)
        markDone(SetupStepId.PROJECT)
        val db = runCatching { c.databases(pid) }.getOrDefault(emptyList()).firstOrNull { it.name == DEFAULT_DB }
        if (db != null) {
            markDone(SetupStepId.DATABASE)
            _info.update { it.copy(region = RegionMapper.functionsRegion(db.locationId)) }
        } else {
            unmark(SetupStepId.DATABASE)
            val locations = runCatching { c.firestoreLocations(pid) }.getOrDefault(emptyList())
            _info.update { it.copy(locations = RegionMapper.orderLocations(locations)) }
        }
        if (settings.hasWebClient()) markDone(SetupStepId.CONNECT) else unmark(SetupStepId.CONNECT)
        val functions = runCatching { c.functionsList(pid) }.getOrNull()?.map { it.name }
        if (functions != null) {
            if (functions.containsAll(SERVER_FUNCTIONS)) markDone(SetupStepId.SERVER) else unmark(SetupStepId.SERVER)
        }
    }

    override fun run(step: SetupStepId, input: StepInput) = launchStep(step, input, reinstall = false)

    /** Throws the app's own tools away and installs them again, whatever state they are in. */
    override fun reinstallTools() {
        val tools = _states.value[SetupStepId.TOOLS]
        if (tools is StepState.Running || tools is StepState.WaitingRetry) return
        // Running commands hold the tools open (Windows cannot delete node.exe then): stop them and wait first.
        val running = synchronized(jobs) { jobs.toList().also { jobs.clear() } }
        running.forEach { it.cancel() }
        launchStep(SetupStepId.TOOLS, StepInput(), reinstall = true, stopFirst = running)
    }

    private fun launchStep(step: SetupStepId, input: StepInput, reinstall: Boolean, stopFirst: List<Job> = emptyList()) {
        if (_states.value[step] is StepState.Running || _states.value[step] is StepState.WaitingRetry) return
        // Steps run one after the other: a step started while the previous one is finishing waits for it.
        val previous = job
        val next = scope.launch {
            stopFirst.joinAll()
            previous?.join()
            setState(step, StepState.Running(emptyList()))
            try {
                execute(step, input, reinstall)
            } catch (e: CancellationException) {
                setState(step, StepState.Todo)
                throw e
            } catch (e: CliException) {
                setState(step, StepState.Error(e.error))
            } catch (e: Exception) {
                setState(step, StepState.Error(CliError(CliErrorKind.UNKNOWN, false, step, e.message.orEmpty())))
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

    override fun confirm(step: SetupStepId) {
        saveProgress { it.copy(confirmed = it.confirmed + step) }
        if (step.kind == StepKind.OPEN) markDone(step)
    }

    override fun setExtra(step: SetupStepId, enabled: Boolean) {
        if (!step.optional) return
        saveProgress { it.copy(extras = if (enabled) it.extras + step else it.extras - step) }
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
                        if (status == ToolsStatus.Missing) tools.install(progress) else tools.reinstall(progress)
                        // Installed means answers when run, not just files on disk.
                        (tools.status() as? ToolsStatus.Ready)?.paths
                            ?: throw CliException(CliError(CliErrorKind.UNKNOWN, false, step, "tools do not respond after install"))
                    }
                } catch (e: Throwable) {
                    cli = null
                    withContext(NonCancellable) {
                        val fresh = runCatching { tools.status() }.getOrNull()
                        _info.update { it.copy(tools = toolsStateOf(fresh)) }
                    }
                    throw e
                } finally {
                    _info.update { it.copy(toolsProgress = null) }
                }
                cli = cliFactory(paths)
                _info.update { it.copy(tools = ToolsState.READY) }
                markDone(step)
            }
            SetupStepId.LOGIN -> {
                val c = requireCli()
                val code = input.authCode?.trim().orEmpty()
                if (code.isNotEmpty()) c.loginFinish(code)
                val account = c.loginList().firstOrNull()
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
                deploy(step, "storage")
                settings.enableProfilePhotos()
                markDone(step)
            }
            SetupStepId.SERVER -> runServer(input)
        }
    }

    /** Spec §4: region, params file, secret, install, deploy with retries, cleanup policy. */
    private suspend fun runServer(input: StepInput) {
        val step = SetupStepId.SERVER
        val c = requireCli()
        val pid = projectId()
        val gmail = input.gmailUser?.trim().orEmpty()
        require(SetupValidators.gmail(gmail)) { "invalid gmail address" }
        val password = input.appPassword ?: throw IllegalArgumentException("missing app password")
        require(password.isNotEmpty()) { "missing app password" }
        secrets.add(String(password))
        val db = c.databases(pid).firstOrNull { it.name == DEFAULT_DB } ?: throw IllegalStateException("no database")
        val region = RegionMapper.functionsRegion(db.locationId)
        _info.update { it.copy(region = region) }
        val dir = workspace.prepare(pid, withStorage = storageOn())
        workspace.writeFunctionParams(dir, pid, gmail, region)
        c.setSecret(pid, dir, SECRET_NAME, password) { log(step, it) }
        c.npmInstall(dir.resolve("functions")) { log(step, it) }
        deployTo(step, c, pid, dir, "hosting,functions")
        c.setArtifactPolicy(pid, region) { log(step, it) }
        markDone(step)
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
                if (!e.error.retryable || attempt >= MAX_RETRIES) throw e
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
        ?: (tools.status() as? ToolsStatus.Ready)?.let { ready -> cliFactory(ready.paths).also { cli = it } }
        ?: throw CliException(CliError(CliErrorKind.UNKNOWN, false, SetupStepId.TOOLS, "tools not ready"))

    /** Ticks belong to one project: choosing another keeps only the computer-wide steps. */
    private fun switchProject(id: String) {
        saveProgress { it.copy(projectId = id, done = it.done.filter { s -> s in MACHINE_STEPS }.toSet(), confirmed = emptySet()) }
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
        cli = null
        _info.update { it.copy(tools = state) }
        unmark(SetupStepId.TOOLS)
    }

    private fun unmark(step: SetupStepId) {
        val state = _states.value[step]
        if (state is StepState.Running || state is StepState.WaitingRetry) return
        if (state == StepState.Done) setState(step, StepState.Todo)
        if (step in _progress.value.done) saveProgress { it.copy(done = it.done - step) }
    }

    private fun projectId(): String = settings.projectId.also {
        require(SetupValidators.projectId(it)) { "no project selected" }
    }

    private fun notLoggedIn(step: SetupStepId) =
        CliException(CliError(CliErrorKind.NOT_LOGGED_IN, false, step, ""))

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
        const val MAX_RETRIES = 5
        const val RETRY_SECONDS = 120
        const val MAX_LOG_LINES = 200
        val SERVER_FUNCTIONS = listOf("onSignupWritten", "onPortalRequestWritten", "onMailOutboxWritten")
        val MACHINE_STEPS = setOf(SetupStepId.TOOLS, SetupStepId.LOGIN)
        val HOSTING_URL = Regex("Hosting URL:\\s*(https://\\S+)")
        val NO_SITE = Regex("no Hosting site", RegexOption.IGNORE_CASE)
        val ANSI = Regex("\u001B\\[[0-9;]*m")

        fun initialStates(progress: SetupProgress): Map<SetupStepId, StepState> =
            SetupStepId.entries.associateWith { step ->
                val confirmedOpen = step.kind == StepKind.OPEN && step in progress.confirmed
                if (step in progress.done || confirmedOpen) StepState.Done else StepState.Todo
            }
    }
}
