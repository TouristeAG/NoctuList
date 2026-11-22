package com.eventmanager.app.portal

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Access to the whole temporary guest list of one date (and optionally one venue), given by an
 * admin to a profile with a portal account (spec 2026-11-21 guest list access §5.1).
 */
@Serializable
data class GuestListGrant(
    val id: String,
    val eventDateMillis: Long,
    /** Empty: every venue of the date. */
    val venueName: String = "",
    val grantedBy: String = "",
    val grantedAt: Long = 0L,
    /** 0 while the grant is not revoked. */
    val revokedAt: Long = 0L,
    val revokedBy: String = "",
) {
    /** Until the event day ends (synced day-change offset), unless revoked. */
    fun isActive(now: Long, offsetHours: Int): Boolean =
        revokedAt == 0L && now < TemporaryGuestPortalProjectionBuilder.visibleUntil(eventDateMillis, offsetHours)
}

/** The JSON list a profile carries in `guestListGrantsJson`. */
object GuestListGrants {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(GuestListGrant.serializer())

    fun decode(raw: String): List<GuestListGrant> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyList()
        return runCatching { json.decodeFromString(serializer, trimmed) }.getOrElse { emptyList() }
            .filter { it.id.isNotBlank() && it.eventDateMillis > 0L }
    }

    fun encode(list: List<GuestListGrant>): String = if (list.isEmpty()) "" else json.encodeToString(serializer, list)

    fun add(raw: String, grant: GuestListGrant): String =
        encode(decode(raw).filterNot { it.id == grant.id } + grant)

    /** One list from several rows of one account; a revocation on any row wins. */
    fun merge(raws: List<String>): String =
        encode(
            raws.flatMap { decode(it) }.groupBy { it.id }.values.map { same ->
                same.firstOrNull { it.revokedAt != 0L } ?: same.first()
            },
        )

    /** Marks [id] revoked; a grant already revoked keeps its first revocation. */
    fun revoke(raw: String, id: String, by: String, now: Long): String =
        encode(decode(raw).map { if (it.id == id && it.revokedAt == 0L) it.copy(revokedAt = now, revokedBy = by) else it })

    fun active(raw: String, now: Long, offsetHours: Int): List<GuestListGrant> =
        decode(raw).filter { it.isActive(now, offsetHours) }.sortedBy { it.eventDateMillis }

    /** Past or revoked grants, newest event first. */
    fun history(raw: String, now: Long, offsetHours: Int): List<GuestListGrant> =
        decode(raw).filter { !it.isActive(now, offsetHours) }.sortedByDescending { it.eventDateMillis }
}
