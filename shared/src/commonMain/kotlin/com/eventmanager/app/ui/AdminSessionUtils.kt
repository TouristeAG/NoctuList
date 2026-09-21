package com.eventmanager.app.ui

import com.eventmanager.app.platform.elapsedRealtimeMs
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal const val ADMIN_SESSION_IDLE_TIMEOUT_MS = 10 * 60 * 1000L
internal const val ADMIN_SESSION_POLL_INTERVAL_MS = 1_000L

interface AdminSessionHost {
    val adminSessionWatchdog: AdminSessionWatchdog
    var adminSessionAutoLogout: (() -> Unit)?
}

class AdminSessionWatchdog(
    private val clockMs: () -> Long = { elapsedRealtimeMs() },
) {
    @Volatile
    var monitoring: Boolean = false
        private set

    internal val lastInteractionElapsedMs = AtomicLong(clockMs())
    private val logoutAfterSleepPending = AtomicBoolean(false)
    private val logoutAfterLockPending = AtomicBoolean(false)

    fun startMonitoring() {
        lastInteractionElapsedMs.set(clockMs())
        logoutAfterSleepPending.set(false)
        logoutAfterLockPending.set(false)
        monitoring = true
    }

    fun stopMonitoring() {
        monitoring = false
        logoutAfterSleepPending.set(false)
        logoutAfterLockPending.set(false)
    }

    fun onUserInput() {
        if (monitoring) lastInteractionElapsedMs.set(clockMs())
    }

    fun onDisplayTurnedOff() {
        if (monitoring) logoutAfterSleepPending.set(true)
    }

    fun onDeviceLocked() {
        if (monitoring) logoutAfterLockPending.set(true)
    }

    fun shouldEndSession(nowMs: Long = clockMs()): Boolean {
        if (!monitoring) return false
        if (logoutAfterSleepPending.get() || logoutAfterLockPending.get()) return true
        return nowMs - lastInteractionElapsedMs.get() >= ADMIN_SESSION_IDLE_TIMEOUT_MS
    }

    /**
     * True when a lock/sleep logout is pending or the idle timeout has elapsed.
     * Consumes lock/sleep flags so a second caller does not double-fire.
     */
    fun consumeShouldEndSession(nowMs: Long = clockMs()): Boolean {
        if (!monitoring) {
            logoutAfterSleepPending.set(false)
            logoutAfterLockPending.set(false)
            return false
        }
        val sleep = logoutAfterSleepPending.compareAndSet(true, false)
        val lock = logoutAfterLockPending.compareAndSet(true, false)
        if (sleep || lock) return true
        return nowMs - lastInteractionElapsedMs.get() >= ADMIN_SESSION_IDLE_TIMEOUT_MS
    }
}
