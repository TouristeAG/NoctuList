package com.eventmanager.app.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * One organisation of this device.
 *
 * [orgId] is also the Firestore path `orgs/{orgId}`, so it never changes once data lives there.
 * [displayName] is the name shown in the app; it travels with the synced org list, so a rename
 * reaches every device of the org. Blank means "show [orgId]".
 *
 * [localOnly] marks an org added on this device only: it is left out of the synced list and kept
 * when another device pushes its list. Older app versions ignore both extra fields.
 */
@Serializable
data class FirebaseConfiguredOrg(
    val orgId: String,
    val colorArgb: Long,
    val displayName: String = "",
    val localOnly: Boolean = false,
) {
    /** The name to show for this org: its display name, or its ID when none was set. */
    val label: String get() = displayName.trim().ifBlank { orgId.trim() }
}

object FirebaseConfiguredOrgCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Distinct accent colors for org switcher (ARGB). */
    val paletteArgb: List<Long> = listOf(
        0xFFE53935L, // red
        0xFF1E88E5L, // blue
        0xFF43A047L, // green
        0xFFFB8C00L, // orange
        0xFF8E24AAL, // purple
        0xFF00ACC1L, // cyan
        0xFFFDD835L, // yellow
        0xFF6D4C41L, // brown
        0xFF546E7AL, // blue grey
        0xFFD81B60L, // pink
    )

    fun defaultColorForIndex(index: Int): Long =
        paletteArgb[index % paletteArgb.size]

    fun nextAvailableColor(used: Collection<Long>): Long {
        val usedSet = used.toSet()
        return paletteArgb.firstOrNull { it !in usedSet } ?: defaultColorForIndex(usedSet.size)
    }

    fun encode(orgs: List<FirebaseConfiguredOrg>): String =
        json.encodeToString(ListSerializer(FirebaseConfiguredOrg.serializer()), orgs)

    fun decode(raw: String): List<FirebaseConfiguredOrg> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(FirebaseConfiguredOrg.serializer()), raw.trim())
        }.getOrDefault(emptyList())
    }

    fun normalize(orgs: List<FirebaseConfiguredOrg>): List<FirebaseConfiguredOrg> {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<FirebaseConfiguredOrg>()
        orgs.forEachIndexed { index, entry ->
            val id = entry.orgId.trim()
            if (id.isBlank() || id in seen) return@forEachIndexed
            seen += id
            val color = if (entry.colorArgb != 0L) {
                entry.colorArgb
            } else {
                defaultColorForIndex(index)
            }
            result += FirebaseConfiguredOrg(
                orgId = id,
                colorArgb = color,
                displayName = cleanDisplayName(entry.displayName),
                localOnly = entry.localOnly,
            )
        }
        require(result.isNotEmpty()) { "At least one Firebase org ID is required" }
        return result
    }

    const val DISPLAY_NAME_MAX_LENGTH = 60

    /** Trims, folds line breaks and caps the length; a blank result means "use the org ID". */
    fun cleanDisplayName(raw: String): String =
        raw.replace(Regex("\\s+"), " ").trim().take(DISPLAY_NAME_MAX_LENGTH).trim()

    /** The orgs every device of the org shares: local-only ones are left out. */
    fun sharedSubset(orgs: List<FirebaseConfiguredOrg>): List<FirebaseConfiguredOrg> =
        orgs.filterNot { it.localOnly }

    /** Encoded [sharedSubset], or null when this device has no shared org to publish. */
    fun encodeShared(orgs: List<FirebaseConfiguredOrg>): String? {
        val shared = sharedSubset(orgs).filter { it.orgId.isNotBlank() }
        if (shared.isEmpty()) return null
        return encode(normalize(shared))
    }

    /**
     * Applies the org list another device pushed. The pushed list is the truth for shared orgs
     * (IDs, colours, names, and removals); orgs added on this device only are kept after them.
     * An org that was local here and is now in the pushed list becomes shared.
     * An empty or unreadable pushed list changes nothing.
     */
    fun mergeRemote(
        local: List<FirebaseConfiguredOrg>,
        remote: List<FirebaseConfiguredOrg>,
    ): List<FirebaseConfiguredOrg> {
        val pushed = remote
            .filter { it.orgId.isNotBlank() }
            .map { it.copy(localOnly = false) }
        if (pushed.isEmpty()) return local
        val pushedIds = pushed.map { it.orgId.trim() }.toSet()
        val keptLocal = local.filter { it.localOnly && it.orgId.trim() !in pushedIds }
        return normalize(pushed + keptLocal)
    }

    /**
     * Whether the org list read from `orgs/{sourceOrgId}` may be merged here. Not when that org
     * is local-only on this device (its list belongs to its own devices), nor when the list does
     * not hold the org it was read from (merging it would drop that org). A blank source, from a
     * caller that does not know it, is accepted.
     */
    fun acceptsPushedList(
        local: List<FirebaseConfiguredOrg>,
        sourceOrgId: String,
        remote: List<FirebaseConfiguredOrg>,
    ): Boolean {
        val source = sourceOrgId.trim()
        if (source.isBlank()) return true
        if (local.any { it.orgId.trim() == source && it.localOnly }) return false
        return remote.any { it.orgId.trim() == source }
    }

    /**
     * The org list to write into `orgs/{targetOrgId}`, or null to leave it as it is.
     *
     * The org the admin acts from ([sourceOrgId]) gets this device's whole shared list: adds and
     * removals are for its devices. Any other org keeps its own list: it only takes the new names
     * of the orgs it already holds. Otherwise adding B "for every device of A" would also put A
     * on the devices of B, which are not members of A and could not read it.
     */
    fun listForTarget(
        targetOrgId: String,
        sourceOrgId: String,
        deviceShared: List<FirebaseConfiguredOrg>,
        targetRemote: List<FirebaseConfiguredOrg>,
    ): List<FirebaseConfiguredOrg>? {
        if (targetOrgId.trim() == sourceOrgId.trim()) return deviceShared
        if (targetRemote.isEmpty()) return null
        val names = deviceShared.associate { it.orgId.trim() to it.displayName }
        val updated = targetRemote.map { org -> names[org.orgId.trim()]?.let { org.copy(displayName = it) } ?: org }
        return updated.takeIf { it != targetRemote }
    }

    /** Sets the display name of [orgId]; a blank or ID-equal name clears it back to the ID. */
    fun rename(
        orgs: List<FirebaseConfiguredOrg>,
        orgId: String,
        newName: String,
    ): List<FirebaseConfiguredOrg> {
        val id = orgId.trim()
        val cleaned = cleanDisplayName(newName).let { if (it == id) "" else it }
        return orgs.map { if (it.orgId.trim() == id) it.copy(displayName = cleaned) else it }
    }

    fun migrateFromSingleOrgId(orgId: String): List<FirebaseConfiguredOrg> {
        val id = orgId.trim()
        require(id.isNotBlank())
        return listOf(FirebaseConfiguredOrg(orgId = id, colorArgb = defaultColorForIndex(0)))
    }
}
