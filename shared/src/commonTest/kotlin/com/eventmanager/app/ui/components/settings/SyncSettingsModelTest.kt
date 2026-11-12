package com.eventmanager.app.ui.components.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class SyncSettingsModelTest {
    @Test
    fun exactMinutesMapToTheirOption() {
        assertEquals(MirrorFrequency.HOURLY, MirrorFrequency.fromMinutes(60))
        assertEquals(MirrorFrequency.DAILY, MirrorFrequency.fromMinutes(1440))
        assertEquals(MirrorFrequency.MANUAL, MirrorFrequency.fromMinutes(0))
    }

    @Test
    fun negativeMinutesMeanManual() {
        assertEquals(MirrorFrequency.MANUAL, MirrorFrequency.fromMinutes(-5))
    }

    @Test
    fun largeIntervalShowsAsDaily() {
        assertEquals(MirrorFrequency.DAILY, MirrorFrequency.fromMinutes(2000))
    }

    @Test
    fun unknownIntervalKeepsStoredValue() {
        // The page reads the stored value through this pure mapping and only writes
        // when the user picks an option, so an odd stored value is displayed as its
        // nearest option without being rewritten.
        val stored = 45
        val shown = MirrorFrequency.fromMinutes(stored)
        assertEquals(MirrorFrequency.HOURLY, shown)
        assertEquals(MirrorFrequency.HOURLY, MirrorFrequency.fromMinutes(stored))
        assertEquals(45, stored)
    }

    @Test
    fun sheetsSyncMinutesMenu() {
        assertEquals(listOf(1, 5, 15, 30, 60), SHEETS_SYNC_MINUTES)
    }

    @Test
    fun connectionStateReadyWhenEverythingIsSet() {
        assertEquals(SyncConnectionState.READY, syncConnectionState(orgReady = true, projectReady = true, signedIn = true))
    }

    @Test
    fun connectionStateNeedsSignIn() {
        assertEquals(SyncConnectionState.NEEDS_SIGN_IN, syncConnectionState(orgReady = true, projectReady = true, signedIn = false))
    }

    @Test
    fun connectionStateNeedsConfigWhenOrgOrProjectMissing() {
        assertEquals(SyncConnectionState.NEEDS_CONFIG, syncConnectionState(orgReady = false, projectReady = true, signedIn = true))
        assertEquals(SyncConnectionState.NEEDS_CONFIG, syncConnectionState(orgReady = true, projectReady = false, signedIn = true))
        assertEquals(SyncConnectionState.NEEDS_CONFIG, syncConnectionState(orgReady = false, projectReady = false, signedIn = false))
    }
}
