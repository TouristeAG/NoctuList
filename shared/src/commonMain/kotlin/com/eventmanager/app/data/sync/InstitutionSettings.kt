package com.eventmanager.app.data.sync

/**
 * Keys and row model for the shared institution [GoogleSheetsConfig.SETTINGS_SHEET] tab.
 * Synced across devices of the same spreadsheet; logo stays device-local.
 */
object InstitutionSettingsKeys {
    const val CURRENCY_CODE = "currency_code"
    const val DATE_CHANGE_OFFSET_HOURS = "date_change_offset_hours"
    const val PURCHASE_CREDIT_BUFFER = "purchase_credit_buffer"
    const val EMAIL_QR_SUBJECT = "email_qr_subject"
    const val EMAIL_QR_CONTENT_BEFORE = "email_qr_content_before"
    const val EMAIL_QR_CONTENT_AFTER = "email_qr_content_after"
    const val EMAIL_INCLUDE_QR = "email_include_qr"
    const val GUEST_EMAIL_SUBJECT = "guest_email_subject"
    const val GUEST_EMAIL_CONTENT_BEFORE = "guest_email_content_before"
    const val GUEST_EMAIL_CONTENT_AFTER = "guest_email_content_after"
    const val GUEST_EMAIL_INCLUDE_QR = "guest_email_include_qr"
    const val EMAIL_SIGNATURE = "email_signature"
    const val EMAIL_ASSOCIATION_NAME = "email_association_name"

    /** Institution-wide remote backend — synced so all devices stay on the same database. */
    const val BACKEND_TYPE = "backend_type"
    const val BACKEND_MIGRATION_ID = "backend_migration_id"
    const val BACKEND_MIGRATION_AT = "backend_migration_at"
    const val BACKEND_MIGRATION_BY = "backend_migration_by"
    const val FIREBASE_ORG_ID = "firebase_org_id"
    /** JSON list of [{orgId, colorArgb}] — multi-org Firebase config (synced across devices). */
    const val FIREBASE_CONFIGURED_ORGS = "firebase_configured_orgs"
    const val SHEETS_SPREADSHEET_ID_HINT = "sheets_spreadsheet_id_hint"
    /** Client Firebase options for peer follow (includes institution Web OAuth client; not Gmail). */
    const val FIREBASE_PROJECT_ID = "firebase_project_id"
    const val FIREBASE_APPLICATION_ID = "firebase_application_id"
    const val FIREBASE_API_KEY = "firebase_api_key"
    const val FIREBASE_WEB_CLIENT_ID = "firebase_web_client_id"
    /** Institution Web OAuth client secret (Desktop Firebase Sign-In code exchange). Not Gmail OAuth. */
    const val FIREBASE_WEB_CLIENT_SECRET = "firebase_web_client_secret"
    /** Comma-separated email domains allowed for Google Sign-In (e.g. `school.ch,asso.ch`). Empty = any. */
    const val ALLOWED_EMAIL_DOMAINS = "allowed_email_domains"

    /** Opt-in Firebase Storage profile photos — synced so every org device shows the same upload UI. */
    const val PROFILE_PHOTOS_ENABLED = "profile_photos_enabled"

    /** Allow billeterie/ticket-check interfaces to send venue announcements (synced across org devices). */
    const val ANNOUNCEMENTS_NON_ADMIN_SEND_ENABLED = "announcements_non_admin_send_enabled"

    /** JSON list of [{category, name}] — admin-defined POS sub-categories (Firebase backend only). */
    const val POS_SUBCATEGORIES = "pos_subcategories"

    /** JSON list of [{id, venue, name}] — special accesses a temporary guest can get (Firebase only). */
    const val TEMP_GUEST_VENUE_ACCESSES = "temp_guest_venue_accesses"

    /** Opt-in credit accounts for temporary guests; off by default (Firebase backend only). */
    const val TEMP_GUEST_CREDITS_ENABLED = "temp_guest_credits_enabled"

    /** Mail template sent to the artist/manager with every QR of their guest list (Firebase only). */
    const val TEMP_GUEST_EMAIL_SUBJECT = "temp_guest_email_subject"
    const val TEMP_GUEST_EMAIL_CONTENT_BEFORE = "temp_guest_email_content_before"
    const val TEMP_GUEST_EMAIL_CONTENT_AFTER = "temp_guest_email_content_after"
    const val TEMP_GUEST_EMAIL_INCLUDE_QR = "temp_guest_email_include_qr"

    /** Opt-in public guest list forms filled in by artists; off by default (Firebase backend only). */
    const val GUEST_FORMS_ENABLED = "guest_forms_enabled"

    /** Overrides the derived `https://<projectId>.web.app` origin when a custom domain is used. */
    const val GUEST_FORM_BASE_URL = "guest_form_base_url"

    /**
     * Base64 PNG of the institution logo. Lives here rather than on each device so every device
     * — and every public guest form — shows the same logo without a re-upload.
     */
    const val INSTITUTION_LOGO_PNG = "institution_logo_png"

    /** Everything driving the Firebase-only artist guest list forms. */
    val GUEST_FORM_KEYS: List<String> = listOf(
        GUEST_FORMS_ENABLED,
        GUEST_FORM_BASE_URL,
        INSTITUTION_LOGO_PNG,
    )

    /** Everything driving the Firebase-only temporary guest features. */
    val TEMP_GUEST_KEYS: List<String> = listOf(
        TEMP_GUEST_VENUE_ACCESSES,
        TEMP_GUEST_CREDITS_ENABLED,
        TEMP_GUEST_EMAIL_SUBJECT,
        TEMP_GUEST_EMAIL_CONTENT_BEFORE,
        TEMP_GUEST_EMAIL_CONTENT_AFTER,
        TEMP_GUEST_EMAIL_INCLUDE_QR,
    )

    /** Optional one-way Sheets mirror while Firebase is the live backend (synced across admin devices). */
    const val SHEETS_MIRROR_ENABLED = "sheets_mirror_enabled"
    const val SHEETS_MIRROR_SPREADSHEET_ID = "sheets_mirror_spreadsheet_id"
    const val SHEETS_MIRROR_INTERVAL_MINUTES = "sheets_mirror_interval_minutes"
    const val SHEETS_MIRROR_LAST_EXPORT_AT = "sheets_mirror_last_export_at"

    /** Tab names used by the Sheets mirror export (same keys as local sheet prefs). */
    const val GUEST_LIST_SHEET = "guest_list_sheet"
    const val VOLUNTEER_SHEET = "volunteer_sheet"
    const val JOBS_SHEET = "jobs_sheet"
    const val JOB_TYPES_SHEET = "job_types_sheet"
    const val VENUES_SHEET = "venues_sheet"
    const val SALES_ITEMS_SHEET = "sales_items_sheet"
    const val TRANSFERS_SHEET = "transfers_sheet"
    const val TEMP_GUEST_LIST_SHEET = "temp_guest_list_sheet"
    const val SETTINGS_SHEET = "settings_sheet"

    val SHEETS_MIRROR_KEYS: List<String> = listOf(
        SHEETS_MIRROR_ENABLED,
        SHEETS_MIRROR_SPREADSHEET_ID,
        SHEETS_MIRROR_INTERVAL_MINUTES,
        SHEETS_MIRROR_LAST_EXPORT_AT,
        GUEST_LIST_SHEET,
        VOLUNTEER_SHEET,
        JOBS_SHEET,
        JOB_TYPES_SHEET,
        VENUES_SHEET,
        SALES_ITEMS_SHEET,
        TRANSFERS_SHEET,
        TEMP_GUEST_LIST_SHEET,
        SETTINGS_SHEET,
    )

    val BACKEND_KEYS: List<String> = listOf(
        BACKEND_TYPE,
        BACKEND_MIGRATION_ID,
        BACKEND_MIGRATION_AT,
        BACKEND_MIGRATION_BY,
        FIREBASE_ORG_ID,
        SHEETS_SPREADSHEET_ID_HINT,
        FIREBASE_PROJECT_ID,
        FIREBASE_APPLICATION_ID,
        FIREBASE_WEB_CLIENT_ID,
    )

    val ALL: List<String> = listOf(
        CURRENCY_CODE,
        DATE_CHANGE_OFFSET_HOURS,
        PURCHASE_CREDIT_BUFFER,
        EMAIL_QR_SUBJECT,
        EMAIL_QR_CONTENT_BEFORE,
        EMAIL_QR_CONTENT_AFTER,
        EMAIL_INCLUDE_QR,
        GUEST_EMAIL_SUBJECT,
        GUEST_EMAIL_CONTENT_BEFORE,
        GUEST_EMAIL_CONTENT_AFTER,
        GUEST_EMAIL_INCLUDE_QR,
        EMAIL_SIGNATURE,
        EMAIL_ASSOCIATION_NAME,
        ALLOWED_EMAIL_DOMAINS,
        FIREBASE_CONFIGURED_ORGS,
        PROFILE_PHOTOS_ENABLED,
        ANNOUNCEMENTS_NON_ADMIN_SEND_ENABLED,
        POS_SUBCATEGORIES,
    ) + TEMP_GUEST_KEYS + GUEST_FORM_KEYS + BACKEND_KEYS + SHEETS_MIRROR_KEYS

    /**
     * Settings that exist only on the Firebase backend. They are still synced between Firebase
     * devices, but never written to (nor read back from) the Sheets Settings tab: a Sheets
     * round-trip would blank them out for an organization that switches backends.
     */
    val FIREBASE_ONLY_KEYS: Set<String> = setOf(
        POS_SUBCATEGORIES,
    ) + TEMP_GUEST_KEYS + GUEST_FORM_KEYS

    fun isSyncedToSheets(key: String): Boolean = key !in FIREBASE_ONLY_KEYS
}

data class InstitutionSettingRow(
    val key: String,
    val value: String,
    val lastModified: Long,
)
