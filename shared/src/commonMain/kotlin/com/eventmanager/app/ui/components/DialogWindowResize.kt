package com.eventmanager.app.ui.components

import androidx.compose.runtime.Composable

/**
 * Call inside the content of a `Dialog(properties = DialogProperties(usePlatformDefaultWidth = false))`.
 *
 * On Android, Compose pins such a dialog window to the size measured at first layout. Because the
 * activities handle rotation themselves (`configChanges`), nothing re-measures the dialog after a
 * rotation: it kept its portrait size in landscape (overflowing the bottom) and vice versa
 * (overflowing the right edge). This forces a re-layout whenever the screen size changes.
 * No-op on desktop.
 */
@Composable
expect fun DialogWindowFollowsScreenSize()
