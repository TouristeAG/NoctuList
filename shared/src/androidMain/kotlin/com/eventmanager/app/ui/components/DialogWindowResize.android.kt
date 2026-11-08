package com.eventmanager.app.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

@Composable
actual fun DialogWindowFollowsScreenSize() {
    val view = LocalView.current
    DisposableEffect(view) {
        // The dialog's own view does not receive configuration changes (the activities declare
        // configChanges). Instead, follow the host activity's window: once it has been laid out
        // with the new size, the resources hold the new screen size, so re-measuring lets
        // DialogLayout re-apply window.setLayout(...) with the new content size.
        val hostRoot = view.context.findActivity()?.window?.decorView
        val listener = View.OnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) {
                var current: View? = view
                while (current != null) {
                    current.requestLayout()
                    current = current.parent as? View
                }
            }
        }
        hostRoot?.addOnLayoutChangeListener(listener)
        onDispose { hostRoot?.removeOnLayoutChangeListener(listener) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
