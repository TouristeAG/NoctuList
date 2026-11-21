package com.eventmanager.app.platform

import com.eventmanager.app.data.remote.FirebaseAuthBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.prefs.Preferences
import kotlin.system.exitProcess

/**
 * Nuclear uninstall helper: erases every file, directory, and OS-level preference
 * that NoctuList has ever written, then exits the process.
 *
 * Called from the "Remove all app data & quit" action in Settings. This is intentionally
 * separate from [com.eventmanager.app.data.sync.FactoryReset] which keeps the app running
 * after reset; this one is designed for permanent removal before an uninstall.
 *
 * What gets deleted:
 *   • ~/.noctulist/                         (DB, tokens, certs, cache, exports, …)
 *   • ~/Library/Logs/NoctuList/            (macOS crash reports)
 *   • %APPDATA%\NoctuList\logs\            (Windows crash reports)
 *   • Java Preferences node com/eventmanager/app/noctulist
 *     → macOS: ~/Library/Preferences/com.apple.java.util.prefs.plist (node removed)
 *     → Windows: HKCU\Software\JavaSoft\Prefs\com\eventmanager\app\noctulist (removed)
 *     → Linux: ~/.java/.userPrefs/com/eventmanager/app/noctulist/prefs.xml (removed)
 */
object DesktopAppEraser {

    suspend fun eraseAllAndExit() {
        withContext(Dispatchers.IO) {
            // Sign out before deleting tokens so the Firebase SDK doesn't race-write new ones.
            runCatching { FirebaseAuthBridge.signOut() }

            val home = File(System.getProperty("user.home"))

            // ── Primary data directory (all OSes) ────────────────────────────────────
            // ~/.noctulist contains the SQLite DB, encrypted secrets, Firebase / Gmail
            // OAuth tokens, logs, cache, exports, and every other file NoctuList writes.
            runCatching { File(home, ".noctulist").deleteRecursively() }

            // ── OS-specific crash report directories ─────────────────────────────────
            // macOS: ~/Library/Logs/NoctuList/ (written by Main.kt crash logger)
            runCatching { File(home, "Library/Logs/NoctuList").deleteRecursively() }

            // Windows: %APPDATA%\NoctuList\logs\ (written by Main.kt crash logger)
            System.getenv("APPDATA")?.let { appData ->
                runCatching { File(appData, "NoctuList").deleteRecursively() }
            }

            // ── Java Preferences (settings storage) ──────────────────────────────────
            // Stored outside ~/.noctulist; survives regular uninstall without this step.
            runCatching {
                val node = Preferences.userRoot().node(desktopPreferencesNode())
                node.removeNode()
                Preferences.userRoot().flush()
            }
        }
        exitProcess(0)
    }
}
