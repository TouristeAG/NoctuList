package com.eventmanager.app.setup

import com.eventmanager.app.platform.appDataDir
import com.eventmanager.app.platform.createPlatformContext
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** "Remove all app data" deletes ~/.noctulist: the downloaded tools and the site folder must live there. */
class EraserCoversToolsTest {
    @Test
    fun toolsAndSiteLiveUnderTheErasedFolder() {
        val erased = File(System.getProperty("user.home"), ".noctulist").canonicalFile
        val root = createPlatformContext().appDataDir.canonicalFile
        assertEquals(erased, root)
        assertTrue(SetupPaths.tools(root).canonicalPath.startsWith(erased.path + File.separator))
        assertTrue(SetupPaths.site(root).canonicalPath.startsWith(erased.path + File.separator))
    }
}
