package com.eventmanager.app.portal

import com.eventmanager.app.data.models.Guest
import com.eventmanager.app.email.PortalAccountBlock

sealed class TemporaryGroupAccessResult {
    /** A new account was created; [password] is shown once to the admin. */
    data class Created(val uid: String, val password: String) : TemporaryGroupAccessResult()
    /** Another temporary group already has an account for this address, which the group now shares. */
    data class Reused(val uid: String) : TemporaryGroupAccessResult()
    /** The address has an account we do not know yet; the group is linked when its owner signs in. */
    data object ExistingAccount : TemporaryGroupAccessResult()
    /**
     * [password] is set when the account was created but the group could not be saved: the admin
     * still needs it, as it is shown only once.
     */
    data class Failed(val code: String, val password: String? = null) : TemporaryGroupAccessResult()
}

/** Opens and closes the web space of a temporary guest group (one artist, date and venue). Sends no e-mail. */
class TemporaryGuestPortalAccess(
    private val orgId: String,
    private val host: VolunteerPortalHost,
    private val authClientFactory: (String) -> PortalAuthApi,
    private val republish: suspend () -> Unit,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    suspend fun open(batch: List<Guest>): TemporaryGroupAccessResult {
        if (!host.temporaryGuestPortalEnabled()) return TemporaryGroupAccessResult.Failed("TEMP_SPACE_OFF")
        if (batch.isEmpty()) return TemporaryGroupAccessResult.Failed("EMPTY_BATCH")
        val email = batch.map { it.temporaryContactEmail.trim() }.firstOrNull { it.isNotEmpty() }
            ?.let { PortalInputValidator.cleanEmail(it) }
            ?: return TemporaryGroupAccessResult.Failed("NO_CONTACT_EMAIL")

        var result: TemporaryGroupAccessResult
        val known = knownUid(email)
        if (known != null) {
            result = TemporaryGroupAccessResult.Reused(known)
        } else {
            val password = PortalPasswordGenerator.generate()
            result = when (val created = authClientFactory(host.apiKey()).createAccount(email, password)) {
                is PortalAuthResult.Created -> TemporaryGroupAccessResult.Created(created.uid, password)
                // Another device may have opened this very group meanwhile: look again before settling.
                PortalAuthResult.EmailExists -> knownUid(email)?.let { TemporaryGroupAccessResult.Reused(it) }
                    ?: TemporaryGroupAccessResult.ExistingAccount
                is PortalAuthResult.Failed -> return TemporaryGroupAccessResult.Failed(created.code)
                else -> return TemporaryGroupAccessResult.Failed("UNEXPECTED")
            }
        }
        val uid = when (val r = result) {
            is TemporaryGroupAccessResult.Created -> r.uid
            is TemporaryGroupAccessResult.Reused -> r.uid
            else -> ""
        }
        val current = host.guests().associateBy { it.nanoId }
        var ok = true
        for (given in batch) {
            val g = current[given.nanoId] ?: given
            // A uid already recorded for this very address is never blanked.
            val keep = uid.isBlank() && g.portalUid.isNotBlank() && g.temporaryPortalEmail.trim().equals(email, true)
            ok = host.saveGuest(
                g.copy(
                    portalEnabled = true,
                    portalUid = if (keep) g.portalUid else uid,
                    temporaryPortalEmail = email,
                    lastModified = bumped(g),
                ),
            ) && ok
        }
        republish()
        if (!ok) return TemporaryGroupAccessResult.Failed("SAVE_FAILED", (result as? TemporaryGroupAccessResult.Created)?.password)
        return result
    }

    /**
     * Cuts the web space of this batch only. The uid and the address stay, so reopening the same
     * group finds its account again; nothing is published while [Guest.portalEnabled] is off.
     */
    suspend fun close(batch: List<Guest>): Boolean {
        if (batch.isEmpty()) return false
        val current = host.guests().associateBy { it.nanoId }
        var ok = true
        for (given in batch) {
            val g = current[given.nanoId] ?: given
            ok = host.saveGuest(g.copy(portalEnabled = false, lastModified = bumped(g))) && ok
        }
        republish()
        return ok
    }

    /** The address the group's account was opened for, or null when the space is not open. */
    fun openedFor(batch: List<Guest>): String? = openedForAddress(batch)

    private fun bumped(g: Guest) = maxOf(clock(), g.lastModified + 1)

    /**
     * The uid of another temporary group opened for [email] (dormant or opened by another device
     * meanwhile), matched on the address its account was opened for, not the editable contact.
     * Such a uid was created by a device or linked by a verified CLAIM. A volunteer's or permanent
     * guest's uid is never reused here: it can come from an unverified registration, and the
     * unverified temporary read would then expose the group's QR codes. Those cases go through
     * createAccount (EMAIL_EXISTS) and are linked by the owner's verified CLAIM.
     */
    private suspend fun knownUid(email: String): String? {
        fun inOrg(firebaseOrgId: String) = firebaseOrgId.isBlank() || firebaseOrgId == orgId
        return host.guests()
            .firstOrNull {
                it.isTemporaryGuest && inOrg(it.firebaseOrgId) && it.portalUid.isNotBlank() &&
                    it.temporaryPortalEmail.trim().equals(email, true)
            }?.portalUid
    }

    companion object {
        /** First address recorded on an open member of the group. */
        fun openedForAddress(batch: List<Guest>): String? =
            batch.filter { it.portalEnabled }.map { it.temporaryPortalEmail.trim() }.firstOrNull { it.isNotEmpty() }
    }
}

/**
 * Account block of a group mail: only while the group's space is open ([spaceOn] and an opened
 * address) and the mail goes to that very address, so another person never gets this sign-in.
 * [password] is set only when the mail is sent from the window that just opened the space; that
 * window also gives [knownLogin], the address it opened the space for, which wins over [batch]
 * (the batch may not show the new state yet, and the password must not drop out of the mail).
 */
fun temporaryGroupMailAccount(
    batch: List<Guest>,
    recipient: String,
    portalUrl: String,
    password: String?,
    spaceOn: Boolean,
    knownLogin: String? = null,
): PortalAccountBlock? {
    if (!spaceOn) return null
    val openedFor = knownLogin?.trim()?.takeIf { it.isNotEmpty() }
        ?: TemporaryGuestPortalAccess.openedForAddress(batch) ?: return null
    if (!recipient.trim().equals(openedFor, ignoreCase = true)) return null
    return PortalAccountBlock(portalUrl = portalUrl, loginEmail = openedFor, password = password)
}
