package com.eventmanager.app.data.security

import com.eventmanager.app.data.models.Guest
import com.eventmanager.app.data.models.Volunteer
import com.eventmanager.app.data.remote.BackendType
import com.eventmanager.app.data.remote.MemberRole

enum class LocalAdminTargetKind { GUEST, VOLUNTEER }

sealed class LocalAdminGrantResult {
    data object Success : LocalAdminGrantResult()
    data object NotFirebase : LocalAdminGrantResult()
    data object SoftLocked : LocalAdminGrantResult()
    data object TargetNotEligible : LocalAdminGrantResult()
    data object TargetNotFound : LocalAdminGrantResult()
    data object GrantorNotLocalAdmin : LocalAdminGrantResult()
    data object FirebaseNotSignedIn : LocalAdminGrantResult()
    data object FirebaseNotAdmin : LocalAdminGrantResult()
    data object LastLocalAdmin : LocalAdminGrantResult()
    data class Error(val message: String) : LocalAdminGrantResult()
}

fun guestEligibleForLocalAdminRights(guest: Guest): Boolean =
    !guest.isTemporaryGuest && !guest.isVolunteerBenefit

fun volunteerEligibleForLocalAdminRights(volunteer: Volunteer): Boolean =
    volunteer.id.isNotBlank()

fun shouldShowLocalAdminRightsSection(
    backendType: BackendType,
    readOnly: Boolean,
    guest: Guest? = null,
    volunteer: Volunteer? = null,
): Boolean {
    if (readOnly) return false
    if (backendType != BackendType.FIREBASE) return false
    return when {
        guest != null -> guestEligibleForLocalAdminRights(guest)
        volunteer != null -> volunteerEligibleForLocalAdminRights(volunteer)
        else -> false
    }
}

fun sameLocalAdminOrg(profileOrgId: String, orgId: String): Boolean {
    if (orgId.isBlank()) return true
    if (profileOrgId.isBlank()) return true
    return profileOrgId.trim() == orgId.trim()
}

fun isFirebaseStrictMultiOrg(configuredOrgCount: Int): Boolean = configuredOrgCount > 1

/** Whether a profile belongs to the target org for admin authentication. */
fun profileBelongsToAdminOrg(
    profileOrgId: String,
    targetOrgId: String,
    strictMultiOrg: Boolean,
): Boolean {
    if (targetOrgId.isBlank()) return true
    if (strictMultiOrg) {
        return profileOrgId.trim().isNotBlank() && profileOrgId.trim() == targetOrgId.trim()
    }
    return sameLocalAdminOrg(profileOrgId, targetOrgId)
}

fun institutionHasLocalAdmin(guests: List<Guest>, volunteers: List<Volunteer>): Boolean =
    guests.any { it.isAdmin } || volunteers.any { it.isAdmin }

/** Member count used for admin-setup security gates (guests + volunteers). */
fun memberRosterCount(guests: List<Guest>, volunteers: List<Volunteer>): Int =
    guests.size + volunteers.size

/**
 * Safe to offer first-admin setup only when sync succeeded, no admin is visible,
 * and the roster is provably empty (brand-new institution).
 */
fun shouldOfferFirstAdminSetupAfterSync(
    syncSucceeded: Boolean,
    hasLocalAdmin: Boolean,
    memberCount: Int,
): Boolean = syncSucceeded && !hasLocalAdmin && memberCount == 0

/**
 * A skipped startup sync (wizard replay, theme/locale recreate) cannot prove the
 * remote roster is empty. Local Room often looks empty right after joining an
 * existing Firebase org — never open the passwordless first-admin wizard then.
 */
fun shouldOfferFirstAdminAfterSkippedStartupSync(): Boolean = false

/**
 * Members are present locally but none is admin — usually incomplete sync, not a missing admin.
 */
fun isSuspiciousMissingAdminAfterSync(
    syncSucceeded: Boolean,
    hasLocalAdmin: Boolean,
    memberCount: Int,
): Boolean = syncSucceeded && !hasLocalAdmin && memberCount > 0

fun institutionHasLocalAdminInOrg(
    guests: List<Guest>,
    volunteers: List<Volunteer>,
    orgId: String,
    strictMultiOrg: Boolean,
): Boolean {
    if (orgId.isBlank() && !strictMultiOrg) {
        return institutionHasLocalAdmin(guests, volunteers)
    }
    return guests.any { it.isAdmin && profileBelongsToAdminOrg(it.firebaseOrgId, orgId, strictMultiOrg) } ||
        volunteers.any { it.isAdmin && profileBelongsToAdminOrg(it.firebaseOrgId, orgId, strictMultiOrg) }
}

fun countLocalAdminsInOrg(
    guests: List<Guest>,
    volunteers: List<Volunteer>,
    orgId: String,
): Int {
    val guestCount = guests.count { it.isAdmin && sameLocalAdminOrg(it.firebaseOrgId, orgId) }
    val volunteerCount = volunteers.count { it.isAdmin && sameLocalAdminOrg(it.firebaseOrgId, orgId) }
    return guestCount + volunteerCount
}

fun wouldRemoveLastLocalAdmin(
    makeAdmin: Boolean,
    targetCurrentlyAdmin: Boolean,
    guests: List<Guest>,
    volunteers: List<Volunteer>,
    orgId: String,
): Boolean = !makeAdmin && targetCurrentlyAdmin && countLocalAdminsInOrg(guests, volunteers, orgId) <= 1

fun firebaseRoleIsOrgAdmin(role: String?): Boolean =
    MemberRole.fromStorage(role) == MemberRole.ADMIN

/**
 * Settings Full is already a local-admin session. Stored Firebase project secrets
 * (API key, app id, OAuth client) are shown only when the signed-in Google account
 * is also a Firestore org admin. Empty/unconfigured fields may still be filled so
 * first-time setup from Settings works.
 */
fun canRevealFirebaseProjectSecrets(
    isFirebaseOrgAdmin: Boolean,
    projectConfigured: Boolean,
    oauthCredentialsReady: Boolean = true,
): Boolean = isFirebaseOrgAdmin || !projectConfigured || !oauthCredentialsReady

fun firebaseOAuthCredentialsReady(webClientId: String, webClientSecret: String): Boolean =
    webClientId.isNotBlank() && webClientSecret.isNotBlank()

/**
 * Synced institution settings (currency, emails, buffer, photos flag, …) may be written to
 * Firestore only by Firebase org admins. Sheets mode keeps local-admin editing.
 */
fun canEditSyncedInstitutionSettings(
    backendType: BackendType,
    isFirebaseOrgAdmin: Boolean,
): Boolean = backendType != BackendType.FIREBASE || isFirebaseOrgAdmin

/** Billeterie announcement send is an institution-wide flag; only Firebase org admins may change it. */
fun canEditAnnouncementsNonAdminSendSetting(
    backendType: BackendType,
    isFirebaseOrgAdmin: Boolean,
): Boolean = backendType == BackendType.FIREBASE && isFirebaseOrgAdmin
