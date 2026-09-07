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

    private val files = listOf(
        "index.html",
        "styles.css",
        "app.js",
        "i18n.js",
        "topo.js",
        "firebase.json",
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
