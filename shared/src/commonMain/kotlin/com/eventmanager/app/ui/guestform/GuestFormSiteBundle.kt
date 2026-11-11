package com.eventmanager.app.ui.guestform

import com.eventmanager.app.platform.PlatformFileManager
import com.eventmanager.app.resources.Res
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.jetbrains.compose.resources.ExperimentalResourceApi

/**
 * The static public form site, shipped inside the app so an admin can export it and deploy it
 * once to Firebase Hosting. The Gradle `syncWebFormResources` task keeps these bytes aligned
 * with the `webform/` folder at the repo root.
 */
object GuestFormSiteBundle {

    val files: List<String> = listOf(
        "index.html",
        "styles.css",
        "app.js",
        "i18n.js",
        "topo.js",
        "photo.html",
        "photo.js",
        "photo-i18n.js",
        "firebase.json",
        // Shared Sunset Mist theme (light / dark) and self-hosted font.
        "theme.css",
        "theme.js",
        "firebase-env.js",
        "fonts/nunito-400.ttf",
        "fonts/nunito-600.ttf",
        "fonts/nunito-700.ttf",
        "fonts/nunito-800.ttf",
        "fonts/OFL.txt",
        "icons/icon-192.png",
        "icons/icon-512.png",
        "icons/icon-maskable-512.png",
        "icons/apple-touch-icon.png",
        // Volunteer portal: registration (/s/) and personal space (/v/).
        "signup.html",
        "signup.js",
        "portal.html",
        "portal.js",
        "portal.css",
        "portal-i18n.js",
        "portal-common.js",
        "manifest.webmanifest",
        // Cloud Functions (portal server): sources are prebuilt into lib/index.js.
        "functions/package.json",
        "functions/package-lock.json",
        "functions/lib/index.js",
    )

    @OptIn(ExperimentalResourceApi::class)
    suspend fun exportZip(fileManager: PlatformFileManager): Boolean {
        val zip = File(fileManager.getCacheDirectory(), "noctulist-guestform-site.zip")
        zip.parentFile?.mkdirs()
        return runCatching {
            ZipOutputStream(zip.outputStream().buffered()).use { out ->
                for (name in files) {
                    val bytes = Res.readBytes("files/webform/$name")
                    out.putNextEntry(ZipEntry(name))
                    out.write(bytes)
                    out.closeEntry()
                }
            }
            fileManager.saveFileToUserLocation(
                zip,
                "noctulist-guestform-site.zip",
                "application/zip",
            )
        }.getOrDefault(false)
    }
}
