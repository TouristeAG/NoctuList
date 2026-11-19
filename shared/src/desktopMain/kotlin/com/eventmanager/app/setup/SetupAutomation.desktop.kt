package com.eventmanager.app.setup

import com.eventmanager.app.data.sync.SettingsManager
import com.eventmanager.app.platform.PlatformContext
import com.eventmanager.app.platform.appDataDir
import com.eventmanager.app.resources.Res
import com.eventmanager.app.ui.guestform.GuestFormSiteBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.jetbrains.compose.resources.ExperimentalResourceApi

/** Where the assistant keeps its files: inside the app data folder, so "Remove all app data" removes them. */
object SetupPaths {
    fun tools(root: java.io.File) = root.resolve("tools")
    fun site(root: java.io.File) = root.resolve("firebase-site")
}

@OptIn(ExperimentalResourceApi::class)
actual fun createSetupAutomation(context: PlatformContext, settings: SettingsManager): SetupAutomation {
    val root = context.appDataDir
    val runner = RealProcessRunner()
    val os = System.getProperty("os.name").orEmpty()
    val proxy = SystemProxy.detect()
    val environment = SetupEnvironment(root, proxy)
    val tools = ToolsInstaller(
        toolsDir = SetupPaths.tools(root),
        runner = runner,
        http = JdkToolsHttp(proxy),
        os = os,
        arch = System.getProperty("os.arch").orEmpty(),
        environment = environment,
    )
    val secrets = ActiveSecrets()
    val redactor = OutputRedactor { secrets.current() }
    val logs = SetupLogFiles(environment.logDir, redactor) { System.currentTimeMillis() }
    logs.prune()
    return SetupRunner(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        tools = tools,
        cliFactory = { paths -> FirebaseCli(runner, paths, redactor, environment, logs) },
        workspace = SiteWorkspace(SetupPaths.site(root), GuestFormSiteBundle.files) { Res.readBytes(it) },
        settings = SettingsManagerSetupSettings(settings),
        secrets = secrets,
    )
}

/**
 * Copies the bundled Google helper to `<appData>/setup/helper/` when it is missing or changed.
 * Called lazily on first use, not at app start.
 */
@OptIn(ExperimentalResourceApi::class)
suspend fun extractGoogleHelper(root: java.io.File): java.io.File {
    val target = root.resolve("setup/helper/noctulist-gcp.cjs")
    val bytes = Res.readBytes("files/setup/noctulist-gcp.cjs")
    if (!target.isFile || !target.readBytes().contentEquals(bytes)) {
        target.parentFile.mkdirs()
        target.writeBytes(bytes)
    }
    return target
}

/** Builds the Google operations for the runner (wired in by the runner task). */
fun googleOpsFactory(
    root: java.io.File,
    runner: ProcessRunner,
    environment: SetupEnvironment,
    logs: SetupLogFiles,
    redactor: OutputRedactor,
): suspend (ToolPaths) -> HelperGoogleOps = { paths ->
    HelperGoogleOps(runner, paths, extractGoogleHelper(root), environment, logs, redactor)
}

/** Adapts [SettingsManager] to what the assistant needs. */
class SettingsManagerSetupSettings(private val s: SettingsManager) : SetupSettings {
    override var projectId: String
        get() = s.getFirebaseProjectId()
        set(value) = s.setFirebaseProjectId(value)

    override fun setWebConfig(apiKey: String, appId: String) {
        s.setFirebaseApiKey(apiKey)
        s.setFirebaseApplicationId(appId)
    }

    override fun setWebClient(id: String, secret: String) {
        s.setFirebaseWebClientId(id)
        s.setFirebaseWebClientSecret(secret)
    }

    override fun hasWebClient(): Boolean =
        s.getFirebaseWebClientId().isNotBlank() && s.getFirebaseWebClientSecret().isNotBlank()

    override fun loadProgress(): String = s.getSetupProgressJson()
    override fun saveProgress(json: String) = s.saveSetupProgressJson(json)
    override fun enableProfilePhotos() = s.setProfilePhotosEnabled(true)
}
