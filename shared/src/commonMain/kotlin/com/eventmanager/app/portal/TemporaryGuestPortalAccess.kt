package com.eventmanager.app.portal

import com.eventmanager.app.data.models.Guest

sealed class TemporaryGroupAccessResult {
    /** A new account was created; [password] is shown once to the admin. */
    data class Created(val uid: String, val password: String) : TemporaryGroupAccessResult()
    /** The address already belongs to a known portal account, which the group now shares. */
    data class Reused(val uid: String) : TemporaryGroupAccessResult()
    /** The address has an account we do not know yet; the group is linked when its owner signs in. */
    data object ExistingAccount : TemporaryGroupAccessResult()
    data class Failed(val code: String) : TemporaryGroupAccessResult()
}

/** Opens and closes the web space of a temporary guest group (one artist, date and venue). Sends no e-mail. */
class TemporaryGuestPortalAccess(
    private val orgId: String,
    private val host: VolunteerPortalHost,
    private val authClientFactory: (String) -> PortalAuthApi,
    private val republish: suspend () -> Unit,
    @Suppress("unused") private val clock: () -> Long = { System.currentTimeMillis() },
) {
    suspend fun open(batch: List<Guest>): TemporaryGroupAccessResult {
        if (!host.temporaryGuestPortalEnabled()) return TemporaryGroupAccessResult.Failed("TEMP_SPACE_OFF")
        if (batch.isEmpty()) return TemporaryGroupAccessResult.Failed("EMPTY_BATCH")
        val email = batch.map { it.temporaryContactEmail.trim() }.firstOrNull { it.isNotEmpty() }
            ?.let { PortalInputValidator.cleanEmail(it) }
            ?: return TemporaryGroupAccessResult.Failed("NO_CONTACT_EMAIL")

        val known = knownUid(email)
        val result: TemporaryGroupAccessResult = if (known != null) {
            TemporaryGroupAccessResult.Reused(known)
        } else {
            val password = PortalPasswordGenerator.generate()
            when (val created = authClientFactory(host.apiKey()).createAccount(email, password)) {
                is PortalAuthResult.Created -> TemporaryGroupAccessResult.Created(created.uid, password)
                PortalAuthResult.EmailExists -> TemporaryGroupAccessResult.ExistingAccount
                is PortalAuthResult.Failed -> return TemporaryGroupAccessResult.Failed(created.code)
                else -> return TemporaryGroupAccessResult.Failed("UNEXPECTED")
            }
        }
        val uid = when (result) {
            is TemporaryGroupAccessResult.Created -> result.uid
            is TemporaryGroupAccessResult.Reused -> result.uid
            else -> ""
        }
        for (guest in batch) {
            host.saveGuest(guest.copy(portalEnabled = true, portalUid = uid, temporaryPortalEmail = email))
        }
        republish()
        return result
    }

    /** Cuts the web space of this batch only. */
    suspend fun close(batch: List<Guest>): Boolean {
        if (batch.isEmpty()) return false
        var ok = true
        for (guest in batch) {
            ok = host.saveGuest(guest.copy(portalEnabled = false, portalUid = "", temporaryPortalEmail = "")) && ok
        }
        republish()
        return ok
    }

    /** The address the group's account was opened for, or null when the space is not open. */
    fun openedFor(batch: List<Guest>): String? =
        batch.filter { it.portalEnabled }.map { it.temporaryPortalEmail.trim() }.firstOrNull { it.isNotEmpty() }

    private suspend fun knownUid(email: String): String? {
        fun inOrg(firebaseOrgId: String) = firebaseOrgId.isBlank() || firebaseOrgId == orgId
        host.volunteers()
            .firstOrNull { inOrg(it.firebaseOrgId) && it.portalUid.isNotBlank() && it.email.trim().equals(email, true) }
            ?.let { return it.portalUid }
        return host.guests()
            .filter { inOrg(it.firebaseOrgId) && it.portalUid.isNotBlank() }
            .firstOrNull {
                val address = if (it.isTemporaryGuest) it.temporaryContactEmail else it.email
                address.trim().equals(email, true)
            }?.portalUid
    }
}
