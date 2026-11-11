package com.eventmanager.app.setup

/** Every value that reaches a command line goes through one of these first. */
object SetupValidators {
    private val PROJECT_ID = Regex("^[a-z][a-z0-9-]{4,28}[a-z0-9]$")
    private val EMAIL = Regex("^[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,190}\\.[A-Za-z]{2,}$")
    private val APP_ID = Regex("^[0-9]+:[0-9]+:web:[0-9a-f]+$")

    fun projectId(v: String): Boolean = PROJECT_ID.matches(v)

    fun gmail(v: String): Boolean = EMAIL.matches(v)

    fun location(v: String, allowed: List<String>): Boolean = v.isNotBlank() && v in allowed

    fun displayName(v: String): Boolean =
        v.length in 1..60 && v.isNotBlank() && v.none { it.isISOControl() }

    fun webAppId(v: String): Boolean = APP_ID.matches(v)
}
