package com.eventmanager.app.data.remote

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopFirestoreTransportTest {
    @Test
    fun linuxUsesRestUnlessOverridden() {
        assertTrue(desktopFirestorePrefersRest("Linux", null))
        assertTrue(desktopFirestorePrefersRest("Linux", " "))
        assertFalse(desktopFirestorePrefersRest("Linux", "gitlive"))
        assertFalse(desktopFirestorePrefersRest("Linux", "gRPC"))
    }

    @Test
    fun otherDesktopsKeepGitLiveUnlessRestIsForced() {
        assertFalse(desktopFirestorePrefersRest("Mac OS X", null))
        assertFalse(desktopFirestorePrefersRest("Windows 11", null))
        assertTrue(desktopFirestorePrefersRest("Mac OS X", "rest"))
    }
}
