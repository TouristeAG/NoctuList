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
    val installer = ToolsInstaller(
        toolsDir = SetupPaths.tools(root),
        runner = runner,
        http = JdkToolsHttp(),
        os = os,
        arch = System.getProperty("os.arch").orEmpty(),
    )
    val tools = object : SetupTools {
        override suspend fun detect() = installer.detect(System.getenv("PATH").orEmpty())
        override suspend fun install(onProgress: (Float, String) -> Unit) = installer.install(onProgress)
    }
    val secrets = ActiveSecrets()
    return SetupRunner(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        tools = tools,
        cliFactory = { paths -> FirebaseCli(runner, paths, OutputRedactor { secrets.current() }) },
        workspace = SiteWorkspace(SetupPaths.site(root), GuestFormSiteBundle.files) { Res.readBytes(it) },
        settings = SettingsManagerSetupSettings(settings),
        secrets = secrets,
    )
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
