package com.eventmanager.app.platform.hardware

import kotlin.test.Test
import kotlin.test.assertEquals

class MacCameraAuthorizationTest {
    @Test
    fun mapsNativeTccStatusCodes() {
        assertEquals(MacCameraAccessResult.Authorized, MacCameraAccessResult.fromNative(0))
        assertEquals(MacCameraAccessResult.Denied, MacCameraAccessResult.fromNative(1))
        assertEquals(MacCameraAccessResult.Skipped, MacCameraAccessResult.fromNative(2))
        assertEquals(MacCameraAccessResult.Skipped, MacCameraAccessResult.fromNative(-1))
    }

    @Test
    fun skippedOnNonMacWithoutLoadingNativeLib() {
        val os = System.getProperty("os.name").orEmpty()
        if (os.startsWith("Mac OS")) return
        assertEquals(MacCameraAccessResult.Skipped, MacCameraAuthorization.ensureAccess())
        assertEquals(0, MacCameraAuthorization.deviceCount())
    }
}
