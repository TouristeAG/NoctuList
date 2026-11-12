package com.eventmanager.app.setup

import com.eventmanager.app.ui.guestform.GuestFormSiteBundle
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every file of the public site must be exported and deployed: a page that imports a file left out
 * of [GuestFormSiteBundle.files] stays stuck on "Loading…" once published.
 */
class GuestFormSiteBundleTest {
    private fun webformDir(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "webform")
            if (File(candidate, "portal.html").isFile) return candidate
            dir = dir.parentFile
        }
        error("webform folder not found")
    }

    @Test
    fun everySiteFileIsExported() {
        val root = webformDir()
        val siteFiles = root.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            // Functions ship prebuilt (lib/index.js, package files); sources, tests and local
            // emulator files stay out, like hidden files.
            .filterNot { it.startsWith("functions/") || it.split('/').any { part -> part.startsWith(".") } }
            .toSet()
        val missing = siteFiles - GuestFormSiteBundle.files.toSet()
        assertTrue(missing.isEmpty(), "Site files missing from GuestFormSiteBundle.files: $missing")
    }
}
