package com.eventmanager.app.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * An admin of [sourceOrgId] added [orgId] "on every device of the org": every device of
 * [sourceOrgId] is invited to join it.
 *
 * Travels in `orgs/{sourceOrgId}/institutionSettings/firebase_org_invitations`, which only members
 * of [sourceOrgId] can read. [joinSecret] is the value the Firestore rules compare a joining
 * member document against (`metadata/config.bootstrapCodeHash` of [orgId]): it lets those
 * members join [orgId] without scanning its QR code, as the QR code would.
 */
@Serializable
data class FirebaseOrgInvitation(
    val orgId: String,
    val sourceOrgId: String,
    val joinSecret: String,
    val displayName: String = "",
    /** The admin's note to the other devices; may be blank. */
    val message: String = "",
    val sentAt: Long = 0L,
    /** E-mail of the admin who sent it; may be blank. */
    val sentBy: String = "",
) {
    val label: String get() = displayName.trim().ifBlank { orgId.trim() }

    /** Identifies this invitation in the per-device "joined" and "declined" sets. */
    val key: String get() = "${orgId.trim()}|${sourceOrgId.trim()}|$sentAt"
}

object FirebaseOrgInvitationCodec {
    const val MESSAGE_MAX_LENGTH = 1000
    private const val MAX_KEPT = 20

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(invitations: List<FirebaseOrgInvitation>): String =
        if (invitations.isEmpty()) "" else json.encodeToString(ListSerializer(FirebaseOrgInvitation.serializer()), invitations)

    fun decode(raw: String): List<FirebaseOrgInvitation> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(FirebaseOrgInvitation.serializer()), raw.trim())
        }.getOrDefault(emptyList())
            .filter { it.orgId.isNotBlank() && it.joinSecret.isNotBlank() }
    }

    fun cleanMessage(raw: String): String = raw.trim().take(MESSAGE_MAX_LENGTH).trim()

    /** Adds [invitation], replacing an older one for the same org from the same source. */
    fun upsert(
        invitations: List<FirebaseOrgInvitation>,
        invitation: FirebaseOrgInvitation,
    ): List<FirebaseOrgInvitation> =
        (invitations.filterNot { it.orgId == invitation.orgId && it.sourceOrgId == invitation.sourceOrgId } + invitation)
            .sortedBy { it.sentAt }
            .takeLast(MAX_KEPT)

    /** What this device publishes into `orgs/{sourceOrgId}`: only that org's own invitations. */
    fun forSource(invitations: List<FirebaseOrgInvitation>, sourceOrgId: String): List<FirebaseOrgInvitation> =
        invitations.filter { it.sourceOrgId.trim() == sourceOrgId.trim() }

    /**
     * Applies the invitations read from `orgs/{sourceOrgId}`: they replace this device's copy of
     * that org's invitations and leave the other orgs' alone. A list entry claiming another source
     * is not trusted. A blank [sourceOrgId] (caller does not know it) replaces per entry source.
     */
    fun mergeFromSource(
        local: List<FirebaseOrgInvitation>,
        sourceOrgId: String,
        remote: List<FirebaseOrgInvitation>,
    ): List<FirebaseOrgInvitation> {
        val source = sourceOrgId.trim()
        val incoming = if (source.isBlank()) remote else remote.filter { it.sourceOrgId.trim() == source }
        val replaced = if (source.isBlank()) incoming.map { it.sourceOrgId.trim() }.toSet() else setOf(source)
        return (local.filterNot { it.sourceOrgId.trim() in replaced } + incoming)
            .sortedBy { it.sentAt }
            .takeLast(MAX_KEPT)
    }

    /**
     * Invitations this device should offer, newest first: sent from an org this device works
     * with, not answered here yet. The device's own org list decides, not the sender.
     */
    fun pending(
        invitations: List<FirebaseOrgInvitation>,
        configuredOrgIds: Collection<String>,
        answeredKeys: Set<String>,
    ): List<FirebaseOrgInvitation> {
        val configured = configuredOrgIds.map { it.trim() }.toSet()
        return invitations
            .filter { it.sourceOrgId.trim() in configured && it.orgId.trim() != it.sourceOrgId.trim() }
            .filter { it.key !in answeredKeys }
            .sortedByDescending { it.sentAt }
            .distinctBy { it.orgId.trim() }
    }
}

/** Sending and answering [FirebaseOrgInvitation]s against Firestore. */
object FirebaseOrgInvitationActions {

    sealed interface ShareResult {
        data object Sent : ShareResult

        /** The signed-in account cannot read the new org's invitation code (not its member). */
        data object NoAccessToTarget : ShareResult

        data class Failed(val message: String) : ShareResult
    }

    sealed interface JoinResult {
        data object Joined : JoinResult
        data object NotSignedIn : JoinResult

        /** The rules refused: e-mail outside the allowed domains, or the code was replaced. */
        data object Denied : JoinResult

        /** Offline or not reachable: nothing was written, try again later. */
        data object Unavailable : JoinResult

        data class Failed(val message: String) : JoinResult
    }

    /**
     * The value a joining member document must carry for [orgId], as the rules compute it:
     * `bootstrapCodeHash`, or the legacy `bootstrapCode`. Null when the org has none.
     */
    internal fun joinSecretFromConfig(config: Map<String, Any?>?): String? {
        if (config == null) return null
        val hashed = config["bootstrapCodeHash"]?.toString()?.trim().orEmpty()
        if (hashed.isNotEmpty()) return hashed
        return config["bootstrapCode"]?.toString()?.trim()?.ifEmpty { null }
    }

    /**
     * Builds the invitation to [targetOrgId] for every device of [sourceOrgId] and records it in
     * [settings]; the caller then pushes institution settings. Gives the target org an invitation
     * code first when it has none (a fresh org always has one).
     */
    suspend fun prepareInvitation(
        gateway: FirestoreGateway,
        settings: com.eventmanager.app.data.sync.SettingsManager,
        sourceOrgId: String,
        targetOrgId: String,
        message: String,
        sentBy: String?,
    ): ShareResult {
        val source = sourceOrgId.trim()
        val target = targetOrgId.trim()
        if (source.isBlank() || target.isBlank() || source == target) return ShareResult.Failed("NO_ORG")
        return try {
            if (!gateway.isAvailable()) return ShareResult.Failed("NOT_READY")
            var secret = joinSecretFromConfig(gateway.getDocumentFromServer(target, "metadata", "config"))
            if (secret == null) {
                MemberRoleAdmin.rotateBootstrapCode(gateway, target, settings.getAllowedEmailDomains())
                secret = joinSecretFromConfig(gateway.getDocumentFromServer(target, "metadata", "config"))
            }
            if (secret == null) return ShareResult.Failed("NO_CODE")
            settings.addFirebaseOrgInvitation(
                FirebaseOrgInvitation(
                    orgId = target,
                    sourceOrgId = source,
                    joinSecret = secret,
                    displayName = settings.getFirebaseOrgLabel(target).takeIf { it != target }.orEmpty(),
                    message = FirebaseOrgInvitationCodec.cleanMessage(message),
                    sentAt = System.currentTimeMillis(),
                    sentBy = sentBy?.trim().orEmpty(),
                ),
            )
            ShareResult.Sent
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isFirestorePermissionDenied(e)) ShareResult.NoAccessToTarget
            else ShareResult.Failed(e.message ?: e::class.simpleName.orEmpty())
        }
    }

    /** Writes this account's member document into the invited org, as a QR join would. */
    suspend fun join(
        gateway: FirestoreGateway,
        invitation: FirebaseOrgInvitation,
        uid: String?,
        email: String?,
    ): JoinResult {
        val memberUid = uid?.trim().orEmpty()
        if (memberUid.isBlank()) return JoinResult.NotSignedIn
        val org = invitation.orgId.trim()
        return try {
            if (!gateway.isAvailable()) return JoinResult.Unavailable
            when (gateway.probeMembership(org, memberUid)) {
                is MembershipProbe.Member -> return JoinResult.Joined
                MembershipProbe.Unavailable -> return JoinResult.Unavailable
                else -> Unit
            }
            val data = mutableMapOf<String, Any?>(
                "role" to MemberRole.MEMBER.storageValue(),
                "updatedAt" to System.currentTimeMillis(),
                "bootstrapCode" to invitation.joinSecret,
            )
            email?.trim()?.takeIf { it.isNotBlank() }?.let { data["email"] = it }
            gateway.upsertDocument(org, "members", memberUid, data)
            JoinResult.Joined
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isFirestorePermissionDenied(e)) JoinResult.Denied
            else JoinResult.Failed(e.message ?: e::class.simpleName.orEmpty())
        }
    }
}
