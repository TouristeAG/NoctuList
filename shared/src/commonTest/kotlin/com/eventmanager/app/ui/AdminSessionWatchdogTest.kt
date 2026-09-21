package com.eventmanager.app.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdminSessionWatchdogTest {

    @Test
    fun startMonitoring_resetsIdleClock() {
        var now = 20 * 60 * 1000L
        val watchdog = AdminSessionWatchdog { now }
        watchdog.startMonitoring()
        assertFalse(watchdog.shouldEndSession())
        now += ADMIN_SESSION_IDLE_TIMEOUT_MS - 1
        assertFalse(watchdog.shouldEndSession())
        now += 1
        assertTrue(watchdog.shouldEndSession())
    }

    @Test
    fun userInput_resetsIdleTimeout() {
        var now = 0L
        val watchdog = AdminSessionWatchdog { now }
        watchdog.startMonitoring()
        now = ADMIN_SESSION_IDLE_TIMEOUT_MS - 1
        watchdog.onUserInput()
        now += ADMIN_SESSION_IDLE_TIMEOUT_MS - 1
        assertFalse(watchdog.shouldEndSession())
        now += 1
        assertTrue(watchdog.shouldEndSession())
    }

    @Test
    fun userInput_ignoredWhenNotMonitoring() {
        var now = 0L
        val watchdog = AdminSessionWatchdog { now }
        watchdog.onUserInput()
        watchdog.startMonitoring()
        now = ADMIN_SESSION_IDLE_TIMEOUT_MS
        assertTrue(watchdog.shouldEndSession())
    }

    @Test
    fun displayOff_endsSessionImmediately() {
        var now = 0L
        val watchdog = AdminSessionWatchdog { now }
        watchdog.startMonitoring()
        watchdog.onDisplayTurnedOff()
        assertTrue(watchdog.shouldEndSession())
        assertTrue(watchdog.consumeShouldEndSession())
        assertFalse(watchdog.consumeShouldEndSession())
    }

    @Test
    fun deviceLock_endsSessionImmediately() {
        var now = 0L
        val watchdog = AdminSessionWatchdog { now }
        watchdog.startMonitoring()
        watchdog.onDeviceLocked()
        assertTrue(watchdog.shouldEndSession())
        assertTrue(watchdog.consumeShouldEndSession())
        assertFalse(watchdog.consumeShouldEndSession())
    }

    @Test
    fun stopMonitoring_clearsPendingLogoutAndIgnoresSignals() {
        var now = 0L
        val watchdog = AdminSessionWatchdog { now }
        watchdog.startMonitoring()
        watchdog.onDeviceLocked()
        watchdog.stopMonitoring()
        assertFalse(watchdog.shouldEndSession())
        assertFalse(watchdog.consumeShouldEndSession())
        watchdog.onDisplayTurnedOff()
        watchdog.onUserInput()
        assertFalse(watchdog.monitoring)
        watchdog.startMonitoring()
        now += ADMIN_SESSION_IDLE_TIMEOUT_MS - 1
        assertFalse(watchdog.shouldEndSession())
    }

    @Test
    fun consumeShouldEndSession_idleTimeoutWithoutLock() {
        var now = 0L
        val watchdog = AdminSessionWatchdog { now }
        watchdog.startMonitoring()
        now = ADMIN_SESSION_IDLE_TIMEOUT_MS
        assertTrue(watchdog.consumeShouldEndSession())
    }
}
