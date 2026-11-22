package com.eventmanager.app.ui.components.settings.certificate

/** Decodes a PNG or JPEG, shrinks it to [maxWidth] pixels wide when wider, and returns it as PNG (transparency kept). Null when unreadable. */
internal expect fun resizeSignatureToPng(bytes: ByteArray, maxWidth: Int): ByteArray?
