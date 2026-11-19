package com.eventmanager.app.setup

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SetupEnvironmentTest {
    private fun env(proxy: ProxyInfo? = null) =
        SetupEnvironment(java.nio.file.Files.createTempDirectory("setup env").toFile(), proxy)

    @Test
    fun childEnvSetsConfigHomeAndQuietFlags() {
        val e = env()
        val child = e.childEnv(File("/t/bin"), "/usr/bin")
        assertEquals(e.configDir.path, child["XDG_CONFIG_HOME"])
        assertEquals("1", child["NO_UPDATE_NOTIFIER"])
        assertEquals("1", child["NO_COLOR"])
        assertEquals("0", child["FORCE_COLOR"])
        assertTrue(child.getValue("PATH").startsWith("/t/bin"))
        assertTrue(child.getValue("PATH").endsWith("/usr/bin"))
        assertTrue(e.logDir.path.endsWith("logs" + File.separator + "setup"))
        assertTrue(e.workDir.isDirectory && e.configDir.isDirectory && e.logDir.isDirectory)
    }

    @Test
    fun childEnvAddsProxyWhenKnown() {
        val child = env(ProxyInfo("proxy.local", 3128)).childEnv(File("/t/bin"), "/usr/bin")
        assertEquals("http://proxy.local:3128", child["HTTPS_PROXY"])
        assertEquals("http://proxy.local:3128", child["HTTP_PROXY"])
        // Node's own fetch (the Google helper) ignores HTTPS_PROXY unless told to read it.
        assertEquals("1", child["NODE_USE_ENV_PROXY"])
        assertTrue("HTTPS_PROXY" !in env().childEnv(File("/t/bin"), "/usr/bin"))
        assertTrue("NODE_USE_ENV_PROXY" !in env().childEnv(File("/t/bin"), "/usr/bin"))
    }
}
