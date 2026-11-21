package com.eventmanager.app.portal

import com.eventmanager.app.data.models.Guest

sealed class TemporaryGroupAccessResult {
    /** A new account was created; [password] is shown once to the admin. */
    data class Created(val uid: String, val password: String) : TemporaryGroupAccessResult()
    /** The address already belongs to a known portal account, which the group now shares. */
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

    private suspend fun knownUid(email: String): String? {
        fun inOrg(firebaseOrgId: String) = firebaseOrgId.isBlank() || firebaseOrgId == orgId
        host.volunteers()
            .firstOrNull { inOrg(it.firebaseOrgId) && it.portalUid.isNotBlank() && it.email.trim().equals(email, true) }
            ?.let { return it.portalUid }
        // A temporary group matches on the address its account was opened for, not the editable contact.
        return host.guests()
            .filter { inOrg(it.firebaseOrgId) && it.portalUid.isNotBlank() }
            .firstOrNull {
                val address = if (it.isTemporaryGuest) it.temporaryPortalEmail else it.email
                address.trim().equals(email, true)
            }?.portalUid
    }

    companion object {
        /** First address recorded on an open member of the group. */
        fun openedForAddress(batch: List<Guest>): String? =
            batch.filter { it.portalEnabled }.map { it.temporaryPortalEmail.trim() }.firstOrNull { it.isNotEmpty() }
    }
}
