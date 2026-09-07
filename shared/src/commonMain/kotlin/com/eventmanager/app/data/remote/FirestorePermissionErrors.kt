package com.eventmanager.app.data.remote

/**
 * PERMISSION_DENIED detection and human-readable causes for `orgs/{orgId}/members/{uid}` writes.
 *
 * Firestore reports the same opaque "Missing or insufficient permissions" for every refusal,
 * so the app has to reconstruct the reason from what it already knows about the attempt.
 */

/** True when [error] (or any cause in its chain) is a Firestore permission refusal. */
fun isFirestorePermissionDenied(error: Throwable?): Boolean {
    var current = error
    var depth = 0
    while (current != null && depth < 6) {
        val text = current.message.orEmpty()
        if (text.contains("PERMISSION_DENIED", ignoreCase = true) ||
            text.contains("permission-denied", ignoreCase = true) ||
            text.contains("insufficient permissions", ignoreCase = true)
        ) {
            return true
        }
        current = current.cause
        depth++
    }
    return false
}

/**
 * Why a member write was refused, as far as the client can tell.
 *
 * [probe] is the membership state observed just before the write; [hasInvitationCode] whether an
 * org invitation code was available locally.
 */
fun firebaseMemberWriteDenialMessage(
    orgId: String,
    probe: MembershipProbe,
    hasInvitationCode: Boolean,
): String = when {
    probe is MembershipProbe.Member -> "This account is already a member of \"$orgId\" as " +
        "\"${probe.role}\", but the organization refused to refresh its member entry. " +
        "Republish firestore.rules (version $NOCTULIST_FIRESTORE_RULES_VERSION) in the Firebase " +
        "console, then try again."
    probe is MembershipProbe.Denied -> "Organization \"$orgId\" refused to read your membership. " +
        "Either your Google account email domain is not in the allowed list, or firestore.rules " +
        "(version $NOCTULIST_FIRESTORE_RULES_VERSION) has not been republished."
    !hasInvitationCode -> "Joining \"$orgId\" needs the organization invitation code. Scan the " +
        "join QR code from an administrator device."
    else -> "Organization \"$orgId\" rejected the invitation code. Ask an administrator for a " +
        "fresh join QR code, and make sure firestore.rules " +
        "(version $NOCTULIST_FIRESTORE_RULES_VERSION) is published."
}

/**
 * Why the Sheets → Firebase migration could not claim org admin.
 *
 * Unlike a join, the migration always writes `role: 'admin'`, which the rules only accept while
 * `metadata/config` does not exist yet. So a refusal on an [MembershipProbe.Absent] probe means
 * the org was already set up by another account, not that an invitation code is missing.
 */
fun firebaseMigrationAdminDenialMessage(orgId: String, probe: MembershipProbe): String =
    when (probe) {
        is MembershipProbe.Denied ->
            "Organization \"$orgId\" refused to read your membership. Publish firestore.rules " +
                "(version $NOCTULIST_FIRESTORE_RULES_VERSION) in Firebase Console → Firestore → " +
                "Rules — publishing needs Owner, Editor or Firebase Rules Admin on the Google " +
                "Cloud project — and check that your Google account domain is in the " +
                "organization's allowed email domains."
        else ->
            "Organization \"$orgId\" already exists in Firestore and was set up by another " +
                "account, so this one cannot claim admin. Migrate from the account that created " +
                "it, or ask that account to promote you in Admin → Firebase team first."
    }

/**
 * Rules revision this build expects to be published. Must match `NOCTULIST_RULES_VERSION` in
 * [firebase/firestore.rules] — a mismatch is the most common cause of PERMISSION_DENIED.
 */
const val NOCTULIST_FIRESTORE_RULES_VERSION: Int = 9
