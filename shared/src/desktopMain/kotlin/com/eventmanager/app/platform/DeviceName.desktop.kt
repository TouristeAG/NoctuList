package com.eventmanager.app.platform

actual fun localDeviceName(): String =
    System.getenv("COMPUTERNAME")?.takeIf { it.isNotBlank() }
        ?: System.getenv("HOSTNAME")?.takeIf { it.isNotBlank() }
        ?: runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: ""
