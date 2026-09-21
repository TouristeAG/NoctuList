package com.eventmanager.app.ui

import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import com.eventmanager.app.platform.LocalPlatformContext
import com.eventmanager.app.platform.PlatformContext
import com.eventmanager.app.platform.getAdminSessionHost

@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun AppRoot(
    platformContext: PlatformContext,
    onThemeModeChanged: (String) -> Unit
) {
    CompositionLocalProvider(LocalPlatformContext provides platformContext) {
        InterceptPlatformTextInput(
            interceptor = { request, nextHandler ->
                nextHandler.startInputMethod(
                    PlatformTextInputMethodRequest { outAttributes ->
                        wrapAdminSessionInputConnection(
                            request.createInputConnection(outAttributes),
                            platformContext,
                        )
                    }
                )
            }
        ) {
            AppRootContent(platformContext = platformContext, onThemeModeChanged = onThemeModeChanged)
        }
    }
}

private fun wrapAdminSessionInputConnection(
    connection: InputConnection,
    platformContext: PlatformContext,
): InputConnection {
    val watchdog = getAdminSessionHost(platformContext)?.adminSessionWatchdog
        ?: return connection
    return object : InputConnectionWrapper(connection, true) {
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            watchdog.onUserInput()
            return super.commitText(text, newCursorPosition)
        }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            watchdog.onUserInput()
            return super.setComposingText(text, newCursorPosition)
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            watchdog.onUserInput()
            return super.deleteSurroundingText(beforeLength, afterLength)
        }

        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
            watchdog.onUserInput()
            return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
        }

        override fun sendKeyEvent(event: android.view.KeyEvent): Boolean {
            watchdog.onUserInput()
            return super.sendKeyEvent(event)
        }
    }
}
