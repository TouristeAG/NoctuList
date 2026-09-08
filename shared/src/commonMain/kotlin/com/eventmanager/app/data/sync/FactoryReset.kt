package com.eventmanager.app.data.sync

import com.eventmanager.app.data.remote.FirebaseAuthBridge
import com.eventmanager.app.data.remote.FirebaseBootstrap
import com.eventmanager.app.data.repository.EventManagerRepository
import com.eventmanager.app.platform.PlatformFileManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Clears local DB tables, preference storage, and credential/cache files so the app
 * returns to a first-launch (setup wizard) state. Does not uninstall the binary.
 *
 * This is the *in-app* factory reset (app keeps running afterwards). For a full
 * pre-uninstall wipe that also exits the process, see `DesktopAppEraser` (desktop only).
 */
object FactoryReset {
    suspend fun perform(
        repository: EventManagerRepository,
        settingsManager: SettingsManager,
        fileManager: PlatformFileManager,
    ) {
        withContext(Dispatchers.IO) {
            // The Firebase SDK persists its session outside our preference storage, so without
            // this the app restarts signed in to an org it no longer has any settings for.
            runCatching { FirebaseAuthBridge.signOut() }
            runCatching { FirebaseBootstrap.release() }
            runCatching { repository.clearAllData() }
            // Wipe prefs + EncryptedSharedPreferences before deleting leftover files so
            // in-memory editors cannot recreate the old API keys on disk.
            settingsManager.clearAllSettings()

            // Known credential / config files
            runCatching { fileManager.getServiceAccountFile()?.delete() }
            runCatching { fileManager.getGmailOAuthClientFile()?.delete() }
            runCatching { fileManager.clearEmailLogoFile() }
            runCatching { fileManager.getWalletPassCertificateFile()?.delete() }

            // Auth token files & directories that the Firebase / Gmail OAuth stores write.
            // These survive sign-out unless explicitly deleted, so a factory-reset user
            // would find themselves auto-signed-back-in on next launch without this step.
            fileManager.getAuthRelatedFilesToErase().forEach { f ->
                runCatching { if (f.isDirectory) f.deleteRecursively() else f.delete() }
            }

            // Transient / generated data directories
            runCatching {
                fileManager.getUpdatesDirectory().listFiles()?.forEach { child ->
                    if (child.isDirectory) child.deleteRecursively() else child.delete()
                }
            }
            runCatching {
                fileManager.getCacheDirectory().listFiles()?.forEach { child ->
                    if (child.isDirectory) child.deleteRecursively() else child.delete()
                }
            }
            runCatching {
                fileManager.getLogsDirectory().listFiles()?.forEach { child ->
                    if (child.isDirectory) child.deleteRecursively() else child.delete()
                }
            }
        }
    }
}
