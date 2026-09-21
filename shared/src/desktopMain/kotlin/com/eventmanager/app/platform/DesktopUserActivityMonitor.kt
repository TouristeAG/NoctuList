package com.eventmanager.app.platform

import com.eventmanager.app.ui.AdminSessionWatchdog
import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.event.InputMethodEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent

internal object DesktopUserActivityMonitor {
    @Volatile
    private var installed = false

    fun install(watchdog: AdminSessionWatchdog) {
        if (installed) return
        val mask = AWTEvent.KEY_EVENT_MASK or
            AWTEvent.MOUSE_EVENT_MASK or
            AWTEvent.MOUSE_MOTION_EVENT_MASK or
            AWTEvent.MOUSE_WHEEL_EVENT_MASK or
            AWTEvent.INPUT_METHOD_EVENT_MASK
        Toolkit.getDefaultToolkit().addAWTEventListener({ event ->
            if (!watchdog.monitoring) return@addAWTEventListener
            if (isUserInputEvent(event)) watchdog.onUserInput()
        }, mask)
        installed = true
    }

    private fun isUserInputEvent(event: AWTEvent): Boolean = when (event) {
        is MouseWheelEvent -> true
        is MouseEvent -> when (event.id) {
            MouseEvent.MOUSE_PRESSED,
            MouseEvent.MOUSE_RELEASED,
            MouseEvent.MOUSE_CLICKED,
            MouseEvent.MOUSE_MOVED,
            MouseEvent.MOUSE_DRAGGED,
            MouseEvent.MOUSE_WHEEL -> true
            else -> false
        }
        is KeyEvent -> when (event.id) {
            KeyEvent.KEY_PRESSED,
            KeyEvent.KEY_RELEASED,
            KeyEvent.KEY_TYPED -> true
            else -> false
        }
        is InputMethodEvent -> true
        else -> false
    }
}
