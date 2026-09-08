package com.eventmanager.app.data.sync

import com.eventmanager.app.data.security.SecureCredentialKeys
import com.eventmanager.app.data.security.SecureCredentialStoreHolder
import com.eventmanager.app.data.security.crypto.DefaultOrgCryptoService
import com.eventmanager.app.data.security.crypto.OrgCryptoRegistry
import com.eventmanager.app.data.models.PosSubcategory
import com.eventmanager.app.data.models.PosSubcategoryCatalog
import com.eventmanager.app.data.models.VenueAccess
import com.eventmanager.app.data.models.VenueAccessCatalog
import com.eventmanager.app.data.utils.AppIconStyles
import com.eventmanager.app.data.utils.NanoIdGenerator
import com.eventmanager.app.platform.AppBuildInfo
import com.eventmanager.app.platform.AppStorage
import com.eventmanager.app.platform.PlatformContext
import com.eventmanager.app.platform.createAppStorage
import com.eventmanager.app.ui.components.BackgroundAnimationStyle

/**
 * Manages app settings persistence using cross-platform key-value storage.
 */
class SettingsManager(private val storage: AppStorage) {

    constructor(platformContext: PlatformContext) : this(createAppStorage(platformContext)) {
        ensureSecureCredentialsMigrated()
        installOrgCryptoFromSecureStore()
    }

    private fun ensureSecureCredentialsMigrated() {
        SecureCredentialStoreHolder.migratePlaintextFrom(
            storage,
            listOf(
                KEY_FIREBASE_API_KEY to SecureCredentialKeys.FIREBASE_API_KEY,
                KEY_FIREBASE_WEB_CLIENT_SECRET to SecureCredentialKeys.FIREBASE_WEB_CLIENT_SECRET,
                KEY_WALLET_PASS_CERT_PASSWORD to SecureCredentialKeys.WALLET_PASS_CERT_PASSWORD,
                KEY_EMAIL_GMAIL_AUTH_TOKEN to SecureCredentialKeys.GMAIL_AUTH_TOKEN,
            ),
        )
    }

    private fun installOrgCryptoFromSecureStore() {
        OrgCryptoRegistry.install(
            DefaultOrgCryptoService { orgId ->
                SecureCredentialStoreHolder.get()
                    ?.getSecret(SecureCredentialKeys.ORG_CRYPTO_PASSPHRASE_PREFIX + orgId.trim())
            },
        )
    }

    fun getOrgCryptoPassphrase(orgId: String): String =
        SecureCredentialStoreHolder.get()
            ?.getSecret(SecureCredentialKeys.ORG_CRYPTO_PASSPHRASE_PREFIX + orgId.trim())
            .orEmpty()

    fun setOrgCryptoPassphrase(orgId: String, passphrase: String) {
        val trimmed = orgId.trim()
        if (trimmed.isBlank()) return
        SecureCredentialStoreHolder.get()?.putSecret(
            SecureCredentialKeys.ORG_CRYPTO_PASSPHRASE_PREFIX + trimmed,
            passphrase,
        )
        OrgCryptoRegistry.invalidateCachedKey(trimmed)
    }
    
    companion object {
        private const val KEY_SPREADSHEET_ID = "spreadsheet_id"
        private const val KEY_SPREADSHEET_ID_PRE_MIRROR = "spreadsheet_id_pre_mirror_backup"
        private const val KEY_GUEST_LIST_SHEET = "guest_list_sheet"
        private const val KEY_VOLUNTEER_SHEET = "volunteer_sheet"
        private const val KEY_JOBS_SHEET = "jobs_sheet"
        private const val KEY_JOB_TYPES_SHEET = "job_types_sheet"
        private const val KEY_VOLUNTEER_GUEST_LIST_SHEET = "volunteer_guest_list_sheet"
        private const val KEY_VENUES_SHEET = "venues_sheet"
        private const val KEY_SALES_ITEMS_SHEET = "sales_items_sheet"
        private const val KEY_TRANSFERS_SHEET = "transfers_sheet"
        private const val KEY_TEMP_GUEST_LIST_SHEET = "temp_guest_list_sheet"
        private const val KEY_SETTINGS_SHEET = "settings_sheet"
        private const val KEY_CURRENCY_CODE = "currency_code"
        private const val KEY_PURCHASE_CREDIT_BUFFER = "purchase_credit_buffer"
        private const val KEY_INSTITUTION_SETTING_LM_PREFIX = "institution_setting_lm_"
        private const val KEY_INSTITUTION_SETTING_PUSHED_LM_PREFIX = "institution_setting_pushed_lm_"
        private const val KEY_SYNC_ENABLED = "sync_enabled"
        private const val KEY_AUTO_SYNC = "auto_sync"
        private const val KEY_SYNC_INTERVAL = "sync_interval"
        private const val KEY_DEBUG_MODE = "debug_mode"
        private const val KEY_ANIMATED_BACKGROUND = "animated_background"
        private const val KEY_BACKGROUND_ANIMATION_STYLE = "background_animation_style"
        private const val KEY_BACKGROUND_ANIMATION_OPACITY = "background_animation_opacity"
        private const val KEY_BILLETTERIE_BACKGROUND_ANIMATION_STYLE = "billeterie_background_animation_style"
        private const val KEY_BILLETTERIE_BACKGROUND_ANIMATION_OPACITY = "billeterie_background_animation_opacity"
        private const val KEY_POS_BACKGROUND_ANIMATION_STYLE = "pos_background_animation_style"
        private const val KEY_POS_BACKGROUND_ANIMATION_OPACITY = "pos_background_animation_opacity"
        private const val KEY_POS_SELECTED_CATEGORY = "pos_selected_category"
        private const val KEY_POS_SELECTED_VENUE = "pos_selected_venue"
        private const val KEY_PAGE_ANIMATIONS = "page_animations"
        /** Legacy: was also updated on upload-only paths; still read for migration. */
        private const val KEY_LAST_SYNC_TIME = "last_sync_time"
        /** Last successful read of Google Sheets data into the app (any tab / scope). */
        private const val KEY_LAST_SHEETS_PULL_AT = "last_sheets_pull_at_ms"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_COLOR_THEME = "color_theme"
        private const val KEY_CUSTOM_THEME_COLOR_PREFIX = "custom_theme_color"
        private const val KEY_SKIP_NEXT_STARTUP_SYNC = "skip_next_startup_sync"
        private const val KEY_OPEN_WELCOME_AFTER_SETUP = "open_welcome_after_setup"
        private const val KEY_RESOLUTION_SCALE = "resolution_scale"
        private const val KEY_DATE_FORMAT = "date_format"
        private const val KEY_TIME_FORMAT = "time_format"
        private const val KEY_DATE_CHANGE_OFFSET_HOURS = "date_change_offset_hours"
        private const val KEY_SEASONAL_FUN = "seasonal_fun"
        private const val KEY_SELECTED_GRAPH_TIME_PERIOD = "selected_graph_time_period"
        private const val KEY_DASHBOARD_GRAPH_CATEGORY_EXPANDED = "dashboard_graph_category_expanded_"
        private const val KEY_APP_ICON_STYLE = "app_icon_style"
        private const val KEY_APP_ICON_AUTO_ADAPT = "app_icon_auto_adapt"
        private const val KEY_PEOPLE_COUNTER_VISIBLE = "people_counter_visible"
        private const val KEY_BILLETERIE_CLOCK_VISIBLE = "billeterie_clock_visible"
        private const val KEY_APP_DEVICE_NANOID = "app_device_nano_id"
        private const val KEY_PEOPLE_COUNTER_SELECTED_VENUE_ID = "people_counter_selected_venue_id"
        /** @deprecated Migrated into [KEY_PEOPLE_COUNTER_PRIORITY_VENUE_IDS]. */
        private const val KEY_PEOPLE_COUNTER_PRIORITY = "people_counter_priority"
        /** Venue ids (as strings) where this device wants people-counter upload priority. */
        private const val KEY_PEOPLE_COUNTER_PRIORITY_VENUE_IDS = "people_counter_priority_venue_ids"
        /** Legacy key — migrated once to [KEY_PEOPLE_COUNTER_PRIORITY] (inverted meaning). */
        private const val KEY_PEOPLE_COUNTER_USER_READ_ONLY_LEGACY = "people_counter_user_read_only"
        private const val KEY_STATISTICS_VISIBLE = "statistics_visible"
        private const val KEY_PAGE_SCROLL_BEHAVIOR = "page_scroll_behavior"
        private const val KEY_DESKTOP_ADMIN_NAV_LAYOUT = "desktop_admin_nav_layout"
        private const val KEY_DESKTOP_ADMIN_NAV_RAIL_EXPANDED = "desktop_admin_nav_rail_expanded"
        private const val KEY_UPDATE_MANIFEST_URL = "update_manifest_url"
        private const val KEY_UPDATE_STORE_URL = "update_store_url"
        
        // Settings Category Expansion State Keys
        private const val KEY_CATEGORY_SYNC_EXPANDED = "category_sync_expanded"
        private const val KEY_CATEGORY_APPEARANCE_EXPANDED = "category_appearance_expanded"
        private const val KEY_CATEGORY_LOCALIZATION_EXPANDED = "category_localization_expanded"
        private const val KEY_CATEGORY_ANIMATION_EXPANDED = "category_animation_expanded"
        private const val KEY_CATEGORY_DEVELOPER_EXPANDED = "category_developer_expanded"
        private const val KEY_CATEGORY_MAINTENANCE_EXPANDED = "category_maintenance_expanded"
        private const val KEY_SETUP_WIZARD_COMPLETED = "setup_wizard_completed"
        private const val KEY_BIOMETRIC_ADMIN_LOGIN = "biometric_admin_login"
        private const val KEY_BIOMETRIC_ADMIN_PROFILE_LINK = "biometric_admin_profile_link"
        private const val KEY_BIOMETRIC_ADMIN_ENROLLMENTS = "biometric_admin_enrollments"

        // External Bluetooth NFC reader (ACR1255U-J1) pairing
        private const val KEY_EXTERNAL_BLE_READER_MAC = "external_ble_reader_mac"
        private const val KEY_EXTERNAL_BLE_READER_NAME = "external_ble_reader_name"
        private const val KEY_CATEGORY_EXTERNAL_READER_EXPANDED = "category_external_reader_expanded"
        
        // Announcements Settings Keys
        private const val KEY_ANNOUNCEMENTS_RECEPTION_ENABLED = "announcements_reception_enabled"
        private const val KEY_ANNOUNCEMENTS_TRACKED_VENUE_IDS = "announcements_tracked_venue_ids"
        private const val KEY_ANNOUNCEMENTS_VALIDITY_MINUTES = "announcements_validity_minutes"
        private const val KEY_ANNOUNCEMENTS_NON_ADMIN_SEND_ENABLED = "announcements_non_admin_send_enabled"
        private const val KEY_CATEGORY_ANNOUNCEMENTS_EXPANDED = "category_announcements_expanded"
        private const val KEY_ANNOUNCEMENTS_LAST_SEEN_TIMESTAMPS = "announcements_last_seen_timestamps"

        // Email Settings Keys
        private const val KEY_EMAIL_SUBJECT = "email_qr_subject"
        private const val KEY_EMAIL_CONTENT_BEFORE = "email_qr_content_before"
        private const val KEY_EMAIL_INCLUDE_QR = "email_include_qr"
        private const val KEY_EMAIL_CONTENT_AFTER = "email_qr_content_after"
        private const val KEY_EMAIL_SIGNATURE = "email_signature"
        private const val KEY_EMAIL_INCLUDE_LOGO = "email_include_logo"
        private const val KEY_EMAIL_INCLUDE_DIGITAL_WALLET_PASS = "email_include_digital_wallet_pass"
        private const val KEY_WALLET_PASS_CERT_PASSWORD = "wallet_pass_cert_password"
        private const val KEY_WALLET_PASS_TYPE_IDENTIFIER = "wallet_pass_type_identifier"
        private const val KEY_WALLET_PASS_TEAM_IDENTIFIER = "wallet_pass_team_identifier"
        private const val KEY_EMAIL_ASSOCIATION_NAME = "email_association_name"
        private const val KEY_EMAIL_LOGO_URI = "email_logo_uri"
        private const val KEY_EMAIL_GMAIL_ACCOUNT = "email_gmail_account"
        private const val KEY_EMAIL_GMAIL_AUTH_TOKEN = "email_gmail_auth_token"
        private const val KEY_EMAIL_GMAIL_USE_SERVICE_ACCOUNT = "email_gmail_use_service_account"
        private const val KEY_EMAIL_GMAIL_SERVICE_ACCOUNT_SENDER = "email_gmail_service_account_sender"
        private const val KEY_CATEGORY_EMAIL_EXPANDED = "category_email_expanded"
        
        // Guest Email Settings Keys
        private const val KEY_GUEST_EMAIL_SUBJECT = "guest_email_subject"
        private const val KEY_GUEST_EMAIL_CONTENT_BEFORE = "guest_email_content_before"
        private const val KEY_GUEST_EMAIL_INCLUDE_QR = "guest_email_include_qr"
        private const val KEY_GUEST_EMAIL_CONTENT_AFTER = "guest_email_content_after"
        private const val KEY_GUEST_EMAIL_SIGNATURE = "guest_email_signature"

        // Backend selection (local device) + Firebase config — Sheets path unchanged when SHEETS
        private const val KEY_BACKEND_TYPE = "backend_type"
        private const val KEY_FIREBASE_PROJECT_ID = "firebase_project_id"
        private const val KEY_FIREBASE_ORG_ID = "firebase_org_id"
        private const val KEY_FIREBASE_ORG_VIEW_MODE = "firebase_org_view_mode"
        private const val KEY_FIREBASE_LAST_SINGLE_ORG_ID = "firebase_last_single_org_id"
        private const val KEY_FIREBASE_CONFIGURED_ORGS = "firebase_configured_orgs"
        private const val KEY_FIREBASE_BOOTSTRAP_CODE = "firebase_bootstrap_code"
        private const val KEY_FIREBASE_JOIN_IMPORTED = "firebase_join_imported"
        private const val KEY_FIREBASE_AUTH_EMAIL = "firebase_auth_email"
        private const val KEY_FIREBASE_API_KEY = "firebase_api_key"
        private const val KEY_FIREBASE_APPLICATION_ID = "firebase_application_id"
        private const val KEY_FIREBASE_GCM_SENDER_ID = "firebase_gcm_sender_id"
        private const val KEY_FIREBASE_STORAGE_BUCKET = "firebase_storage_bucket"
        private const val KEY_PROFILE_PHOTOS_ENABLED = "profile_photos_enabled"
        private const val KEY_POS_SUBCATEGORIES = "pos_subcategories"
        private const val KEY_TEMP_GUEST_VENUE_ACCESSES = "temp_guest_venue_accesses"
        private const val KEY_TEMP_GUEST_CREDITS_ENABLED = "temp_guest_credits_enabled"
        private const val KEY_GUEST_FORMS_ENABLED = "guest_forms_enabled"
        private const val KEY_GUEST_FORM_BASE_URL = "guest_form_base_url"
        private const val KEY_INSTITUTION_LOGO_PNG = "institution_logo_png"
        /** Prefs only holds this marker — the PNG bytes live on disk (Preferences max ~8 KiB). */
        private const val LOGO_ON_DISK_MARKER = "disk"
        private const val KEY_TEMP_GUEST_EMAIL_SUBJECT = "temp_guest_email_subject"
        private const val KEY_TEMP_GUEST_EMAIL_CONTENT_BEFORE = "temp_guest_email_content_before"
        private const val KEY_TEMP_GUEST_EMAIL_CONTENT_AFTER = "temp_guest_email_content_after"
        private const val KEY_TEMP_GUEST_EMAIL_INCLUDE_QR = "temp_guest_email_include_qr"
        private const val KEY_FIREBASE_WEB_CLIENT_ID = "firebase_web_client_id"
        private const val KEY_FIREBASE_WEB_CLIENT_SECRET = "firebase_web_client_secret"
        private const val KEY_ALLOWED_EMAIL_DOMAINS = "allowed_email_domains"
        private const val KEY_FOLLOWED_BACKEND_MIGRATION_ID = "followed_backend_migration_id"
        private const val KEY_SHEETS_MIRROR_ENABLED = "sheets_mirror_enabled"
        private const val KEY_SHEETS_MIRROR_SPREADSHEET_ID = "sheets_mirror_spreadsheet_id"
        private const val KEY_SHEETS_MIRROR_LAST_EXPORT_AT = "sheets_mirror_last_export_at"
        private const val KEY_SHEETS_MIRROR_INTERVAL_MINUTES = "sheets_mirror_interval_minutes"
        private const val KEY_LOCAL_CRYPTO_MIGRATION_PREFIX = "local_crypto_migration_done_"
        
        // Page Scroll Behavior Configuration Constants
        const val HEADER_PINNED = "header_pinned"
        const val FULL_SCROLL = "full_scroll"
        const val STICKY_FILTERS = "sticky_filters"
    }
    
    // Google Sheets Configuration
    fun getSpreadsheetId(): String {
        // Crash recovery: if a mirror export was interrupted, restore the primary ID.
        val backup = storage.getString(KEY_SPREADSHEET_ID_PRE_MIRROR, "") ?: ""
        if (backup.isNotBlank()) {
            storage.putString(KEY_SPREADSHEET_ID, backup)
            storage.putString(KEY_SPREADSHEET_ID_PRE_MIRROR, "")
        }
        return storage.getString(KEY_SPREADSHEET_ID, GoogleSheetsConfig.SPREADSHEET_ID) ?: GoogleSheetsConfig.SPREADSHEET_ID
    }
    
    fun saveSpreadsheetId(id: String) {
        storage.putString(KEY_SPREADSHEET_ID, id)
    }

    /**
     * Temporarily point Sheets APIs at [overrideId] (e.g. mirror export), then restore.
     * Persists a crash-recovery backup so [getSpreadsheetId] can heal after a kill mid-export.
     */
    suspend fun <T> withSpreadsheetIdOverride(overrideId: String, block: suspend () -> T): T {
        val previous = storage.getString(KEY_SPREADSHEET_ID, GoogleSheetsConfig.SPREADSHEET_ID)
            ?: GoogleSheetsConfig.SPREADSHEET_ID
        storage.putString(KEY_SPREADSHEET_ID_PRE_MIRROR, previous)
        storage.putString(KEY_SPREADSHEET_ID, overrideId)
        return try {
            block()
        } finally {
            storage.putString(KEY_SPREADSHEET_ID, previous)
            storage.putString(KEY_SPREADSHEET_ID_PRE_MIRROR, "")
        }
    }

    // --- Remote backend (local device) ---

    fun getBackendType(): com.eventmanager.app.data.remote.BackendType =
        com.eventmanager.app.data.remote.BackendType.fromStorage(storage.getString(KEY_BACKEND_TYPE, ""))

    fun setBackendType(type: com.eventmanager.app.data.remote.BackendType) {
        storage.putString(KEY_BACKEND_TYPE, type.name)
        // Keep institution announcement in sync when this device intentionally chooses a backend
        // (wizard / migration). Does not overwrite a newer remote migration already applied.
        val announced = storage.getString("inst_val_" + InstitutionSettingsKeys.BACKEND_TYPE, "").orEmpty()
        val announcedLm = getInstitutionSettingLastModified(InstitutionSettingsKeys.BACKEND_TYPE)
        if (announced.isBlank() || (announced.equals(type.name, ignoreCase = true) && announcedLm <= 0L)) {
            seedInstitutionBackendType(type)
        }
    }

    /**
     * Ensures the shared Settings sheet has an institution [backend_type] row.
     * Call before Sheets upload so peers always see SHEETS (or FIREBASE after migration).
     */
    fun seedInstitutionBackendType(
        type: com.eventmanager.app.data.remote.BackendType = getBackendType(),
        at: Long = System.currentTimeMillis(),
    ) {
        applyInstitutionSettingFromRemote(InstitutionSettingsKeys.BACKEND_TYPE, type.name, at)
    }

    /**
     * If institution backend_type was never written (legacy installs), seed the active local type.
     * Does not clobber a value already present in inst_val_*.
     */
    fun ensureInstitutionBackendTypeSeeded() {
        val existing = storage.getString("inst_val_" + InstitutionSettingsKeys.BACKEND_TYPE, "").orEmpty()
        if (existing.isNotBlank() && getInstitutionSettingLastModified(InstitutionSettingsKeys.BACKEND_TYPE) > 0L) {
            return
        }
        seedInstitutionBackendType(getBackendType())
    }

    fun getFirebaseProjectId(): String = storage.getString(KEY_FIREBASE_PROJECT_ID, "") ?: ""
    fun setFirebaseProjectId(id: String) = storage.putString(KEY_FIREBASE_PROJECT_ID, id)

    fun getFirebaseApiKey(): String =
        SecureCredentialStoreHolder.get()?.getSecret(SecureCredentialKeys.FIREBASE_API_KEY)
            ?: storage.getString(KEY_FIREBASE_API_KEY, "") ?: ""
    fun setFirebaseApiKey(value: String) {
        var s = value.trim().replace("\uFEFF", "")
        if (s.contains("apiKey") && (s.contains('{') || s.contains(':'))) {
            Regex("""(?i)["']?apiKey["']?\s*[:=]\s*["'](AIza[^"'\s]+)["']""")
                .find(s)?.groupValues?.getOrNull(1)?.let { s = it }
        }
        Regex("""AIza[0-9A-Za-z_-]{30,}""").find(s)?.value?.let { s = it }
        s = s.trim().trim('"', '\'', ',', '}', ']', ' ').replace(Regex("\\s+"), "")
        val secure = SecureCredentialStoreHolder.get()
        if (s.isBlank()) {
            secure?.removeSecret(SecureCredentialKeys.FIREBASE_API_KEY)
            storage.remove(KEY_FIREBASE_API_KEY)
            return
        }
        if (secure != null) {
            secure.putSecret(SecureCredentialKeys.FIREBASE_API_KEY, s)
            storage.remove(KEY_FIREBASE_API_KEY)
        } else {
            storage.putString(KEY_FIREBASE_API_KEY, s)
        }
    }

    fun getFirebaseApplicationId(): String = storage.getString(KEY_FIREBASE_APPLICATION_ID, "") ?: ""
    fun setFirebaseApplicationId(value: String) = storage.putString(KEY_FIREBASE_APPLICATION_ID, value)

    fun getFirebaseGcmSenderId(): String = storage.getString(KEY_FIREBASE_GCM_SENDER_ID, "") ?: ""
    fun setFirebaseGcmSenderId(value: String) = storage.putString(KEY_FIREBASE_GCM_SENDER_ID, value)

    fun getFirebaseStorageBucket(): String = storage.getString(KEY_FIREBASE_STORAGE_BUCKET, "") ?: ""
    fun setFirebaseStorageBucket(value: String) = storage.putString(KEY_FIREBASE_STORAGE_BUCKET, value)

    /**
     * Opt-in for profile photo uploads (Firebase Storage). Default off so skipping Storage
     * keeps the app fully usable on the free Spark plan.
     */
    fun isProfilePhotosEnabled(): Boolean = storage.getBoolean(KEY_PROFILE_PHOTOS_ENABLED, false)
    fun setProfilePhotosEnabled(enabled: Boolean) {
        storage.putBoolean(KEY_PROFILE_PHOTOS_ENABLED, enabled)
        touchInstitutionSettingLastModified(InstitutionSettingsKeys.PROFILE_PHOTOS_ENABLED)
    }

    /** Admin-defined POS sub-categories, serialized by [PosSubcategoryCatalog]. */
    fun getPosSubcategories(): List<PosSubcategory> =
        PosSubcategoryCatalog.decode(storage.getString(KEY_POS_SUBCATEGORIES, "") ?: "")

    fun savePosSubcategories(subcategories: List<PosSubcategory>) {
        storage.putString(KEY_POS_SUBCATEGORIES, PosSubcategoryCatalog.encode(subcategories))
        touchInstitutionSettingLastModified(InstitutionSettingsKeys.POS_SUBCATEGORIES)
    }

    /** Admin-defined special accesses per venue, serialized by [VenueAccessCatalog]. */
    fun getTemporaryGuestVenueAccesses(): List<VenueAccess> =
        VenueAccessCatalog.decode(storage.getString(KEY_TEMP_GUEST_VENUE_ACCESSES, "") ?: "")

    fun saveTemporaryGuestVenueAccesses(accesses: List<VenueAccess>) {
        storage.putString(KEY_TEMP_GUEST_VENUE_ACCESSES, VenueAccessCatalog.encode(accesses))
        touchInstitutionSettingLastModified(InstitutionSettingsKeys.TEMP_GUEST_VENUE_ACCESSES)
    }

    /** Opt-in: off by default, so temporary guests get no credit account unless the admin says so. */
    fun isTemporaryGuestCreditsEnabled(): Boolean =
        storage.getBoolean(KEY_TEMP_GUEST_CREDITS_ENABLED, false)

    fun setTemporaryGuestCreditsEnabled(enabled: Boolean) {
        storage.putBoolean(KEY_TEMP_GUEST_CREDITS_ENABLED, enabled)
        touchInstitutionSettingLastModified(InstitutionSettingsKeys.TEMP_GUEST_CREDITS_ENABLED)
    }

    /** Opt-in: off by default, so no public form surface exists unless the admin turns it on. */
    fun isGuestFormsEnabled(): Boolean = storage.getBoolean(KEY_GUEST_FORMS_ENABLED, false)

    fun setGuestFormsEnabled(enabled: Boolean) {
        storage.putBoolean(KEY_GUEST_FORMS_ENABLED, enabled)
        touchInstitutionSettingLastModified(InstitutionSettingsKeys.GUEST_FORMS_ENABLED)
    }

    /** Empty means the origin is derived from the Firebase project ID. */
    fun getGuestFormBaseUrl(): String = storage.getString(KEY_GUEST_FORM_BASE_URL, "") ?: ""

    fun saveGuestFormBaseUrl(url: String) {
        storage.putString(KEY_GUEST_FORM_BASE_URL, url.trim().trimEnd('/'))
        touchInstitutionSettingLastModified(InstitutionSettingsKeys.GUEST_FORM_BASE_URL)
    }

    /**
     * Where the site lives once deployed. Firebase Hosting serves every project at
     * `<projectId>.web.app`, so nothing has to be configured for the default setup.
     */
    fun resolveGuestFormBaseUrl(): String {
        val override = getGuestFormBaseUrl()
        if (override.isNotBlank()) return override
        val projectId = getFirebaseProjectId().trim()
        return if (projectId.isBlank()) "" else "https://$projectId.web.app"
    }

    /** Public URL an artist opens. Empty when the site origin is unknown. */
    fun guestFormUrl(orgId: String, formId: String): String {
        val base = resolveGuestFormBaseUrl()
        if (base.isBlank() || orgId.isBlank() || formId.isBlank()) return ""
        return "$base/g/${orgId.trim()}/${formId.trim()}"
    }

    /** Base64 PNG shared by every device and by the public forms; empty when unset. */
    fun getInstitutionLogoPng(): String {
        // Disk is the source of truth. Java Preferences (desktop) rejects values over ~8 KiB,
        // and a logo PNG base64 is routinely far larger — never read the payload from prefs.
        val fromDisk = InstitutionLogoStore.readFromDisk()
        if (fromDisk != null && fromDisk.isNotEmpty()) {
            return InstitutionLogoStore.encode(fromDisk)
        }
        val legacy = storage.getString(KEY_INSTITUTION_LOGO_PNG, "").orEmpty().trim()
        // Older builds stored a tiny base64 blob in prefs; ignore the "disk" presence marker.
        return if (legacy == LOGO_ON_DISK_MARKER || legacy.isEmpty()) "" else legacy
    }

    fun saveInstitutionLogoPng(base64Png: String) {
        val trimmed = base64Png.trim()
        InstitutionLogoStore.mirrorToDisk(trimmed)
        // Preferences only keeps a presence marker — never the image bytes.
        if (trimmed.isEmpty()) {
            storage.remove(KEY_INSTITUTION_LOGO_PNG)
        } else {
            storage.putString(KEY_INSTITUTION_LOGO_PNG, LOGO_ON_DISK_MARKER)
        }
        touchInstitutionSettingLastModified(InstitutionSettingsKeys.INSTITUTION_LOGO_PNG)
    }

    /**
     * Moves a legacy Preferences-stored logo onto disk and replaces the prefs value with a
     * tiny marker. Safe to call on every launch — a no-op once migrated.
     */
    fun migrateInstitutionLogoOffPreferences() {
        val raw = storage.getString(KEY_INSTITUTION_LOGO_PNG, "").orEmpty().trim()
        if (raw.isEmpty() || raw == LOGO_ON_DISK_MARKER) return
        // Real base64 payload left by an older build.
        InstitutionLogoStore.mirrorToDisk(raw)
        storage.putString(KEY_INSTITUTION_LOGO_PNG, LOGO_ON_DISK_MARKER)
    }

    /**
     * Publishes a disk logo into the sync pipeline when Preferences has no marker yet
     * (first launch after the e-mail logo existed but the institution logo setting did not).
     */
    fun ensureInstitutionLogoPublishedFromDisk() {
        val marker = storage.getString(KEY_INSTITUTION_LOGO_PNG, "").orEmpty().trim()
        if (marker == LOGO_ON_DISK_MARKER) return
        val bytes = InstitutionLogoStore.readFromDisk()?.takeIf { it.isNotEmpty() } ?: return
        val encoded = InstitutionLogoStore.encode(bytes)
        if (!InstitutionLogoStore.isWithinSizeLimit(encoded)) return
        saveInstitutionLogoPng(encoded)
    }

    fun getTemporaryGuestEmailSubject(): String =
        storage.getString(KEY_TEMP_GUEST_EMAIL_SUBJECT, "") ?: ""

    fun saveTemporaryGuestEmailSubject(subject: String) {
        storage.putString(KEY_TEMP_GUEST_EMAIL_SUBJECT, subject)
        touchInstitutionSettingLastModified(InstitutionSettingsKeys.TEMP_GUEST_EMAIL_SUBJECT)
    }

    fun getTemporaryGuestEmailContentBefore(): String =
        storage.getString(KEY_TEMP_GUEST_EMAIL_CONTENT_BEFORE, "") ?: ""

    fun saveTemporaryGuestEmailContentBefore(content: String) {
        storage.putString(KEY_TEMP_GUEST_EMAIL_CONTENT_BEFORE, content)
        touchInstitutionSettingLastModified(InstitutionSettingsKeys.TEMP_GUEST_EMAIL_CONTENT_BEFORE)
    }

    fun getTemporaryGuestEmailContentAfter(): String =
        storage.getString(KEY_TEMP_GUEST_EMAIL_CONTENT_AFTER, "") ?: ""

    fun saveTemporaryGuestEmailContentAfter(content: String) {
        storage.putString(KEY_TEMP_GUEST_EMAIL_CONTENT_AFTER, content)
        touchInstitutionSettingLastModified(InstitutionSettingsKeys.TEMP_GUEST_EMAIL_CONTENT_AFTER)
    }

    fun isTemporaryGuestEmailIncludeQrEnabled(): Boolean =
        storage.getBoolean(KEY_TEMP_GUEST_EMAIL_INCLUDE_QR, true)

    fun setTemporaryGuestEmailIncludeQrEnabled(enabled: Boolean) {
        storage.putBoolean(KEY_TEMP_GUEST_EMAIL_INCLUDE_QR, enabled)
        touchInstitutionSettingLastModified(InstitutionSettingsKeys.TEMP_GUEST_EMAIL_INCLUDE_QR)
    }

    /** OAuth Web client ID used to request Google ID tokens for Firebase Auth (Android). */
    fun getFirebaseWebClientId(): String = storage.getString(KEY_FIREBASE_WEB_CLIENT_ID, "") ?: ""
    fun setFirebaseWebClientId(value: String) = storage.putString(KEY_FIREBASE_WEB_CLIENT_ID, value)

    /** OAuth client secret for institution Web client (Desktop + Android browser OAuth code exchange). */
    fun getFirebaseWebClientSecret(): String =
        SecureCredentialStoreHolder.get()?.getSecret(SecureCredentialKeys.FIREBASE_WEB_CLIENT_SECRET)
            ?: storage.getString(KEY_FIREBASE_WEB_CLIENT_SECRET, "") ?: ""
    fun setFirebaseWebClientSecret(value: String) {
        val s = value.trim()
        val secure = SecureCredentialStoreHolder.get()
        if (s.isBlank()) {
            secure?.removeSecret(SecureCredentialKeys.FIREBASE_WEB_CLIENT_SECRET)
            storage.remove(KEY_FIREBASE_WEB_CLIENT_SECRET)
            return
        }
        if (secure != null) {
            secure.putSecret(SecureCredentialKeys.FIREBASE_WEB_CLIENT_SECRET, s)
            storage.remove(KEY_FIREBASE_WEB_CLIENT_SECRET)
        } else {
            storage.putString(KEY_FIREBASE_WEB_CLIENT_SECRET, s)
        }
    }

    fun getFirebaseOrgId(): String = storage.getString(KEY_FIREBASE_ORG_ID, "") ?: ""
    fun setFirebaseOrgId(id: String) = storage.putString(KEY_FIREBASE_ORG_ID, id)

    fun getFirebaseOrgViewMode(): com.eventmanager.app.data.remote.FirebaseOrgViewMode =
        com.eventmanager.app.data.remote.FirebaseOrgViewMode.fromStorage(
            storage.getString(KEY_FIREBASE_ORG_VIEW_MODE, ""),
        )

    fun setFirebaseOrgViewMode(mode: com.eventmanager.app.data.remote.FirebaseOrgViewMode) {
        storage.putString(KEY_FIREBASE_ORG_VIEW_MODE, mode.name)
    }

    fun getFirebaseLastSingleOrgId(): String =
        storage.getString(KEY_FIREBASE_LAST_SINGLE_ORG_ID, "")?.trim().orEmpty()

    fun setFirebaseLastSingleOrgId(id: String) =
        storage.putString(KEY_FIREBASE_LAST_SINGLE_ORG_ID, id.trim())

    fun resolveWritableFirebaseOrgId(): String =
        com.eventmanager.app.data.remote.resolveWritableFirebaseOrgId(
            activeOrgId = getFirebaseOrgId(),
            lastSingleOrgId = getFirebaseLastSingleOrgId(),
            configuredOrgIds = getFirebaseConfiguredOrgs().map { it.orgId },
        )

    fun getFirebaseConfiguredOrgs(): List<com.eventmanager.app.data.remote.FirebaseConfiguredOrg> {
        val stored = storage.getString(KEY_FIREBASE_CONFIGURED_ORGS, "").orEmpty()
        val decoded = com.eventmanager.app.data.remote.FirebaseConfiguredOrgCodec.decode(stored)
        if (decoded.isNotEmpty()) {
            return runCatching {
                com.eventmanager.app.data.remote.FirebaseConfiguredOrgCodec.normalize(decoded)
            }.getOrElse { decoded }
        }
        val legacyOrgId = getFirebaseOrgId().trim()
        if (legacyOrgId.isNotBlank()) {
            val migrated = com.eventmanager.app.data.remote.FirebaseConfiguredOrgCodec.migrateFromSingleOrgId(legacyOrgId)
            storage.putString(
                KEY_FIREBASE_CONFIGURED_ORGS,
                com.eventmanager.app.data.remote.FirebaseConfiguredOrgCodec.encode(migrated),
            )
            touchInstitutionSettingLastModified(InstitutionSettingsKeys.FIREBASE_CONFIGURED_ORGS)
            return migrated
        }
        return emptyList()
    }

    fun setFirebaseConfiguredOrgs(orgs: List<com.eventmanager.app.data.remote.FirebaseConfiguredOrg>) {
        val normalized = com.eventmanager.app.data.remote.FirebaseConfiguredOrgCodec.normalize(orgs)
        storage.putString(
            KEY_FIREBASE_CONFIGURED_ORGS,
            com.eventmanager.app.data.remote.FirebaseConfiguredOrgCodec.encode(normalized),
        )
        touchInstitutionSettingLastModified(InstitutionSettingsKeys.FIREBASE_CONFIGURED_ORGS)
        val activeId = getFirebaseOrgId().trim()
        if (activeId.isBlank() || normalized.none { it.orgId == activeId }) {
            setFirebaseOrgId(normalized.first().orgId)
        }
    }

    fun getFirebaseOrgColorArgb(orgId: String): Long {
        val trimmed = orgId.trim()
        return getFirebaseConfiguredOrgs()
            .firstOrNull { it.orgId == trimmed }
            ?.colorArgb
            ?: com.eventmanager.app.data.remote.FirebaseConfiguredOrgCodec.defaultColorForIndex(0)
    }

    /** Add org from QR/join if not already present; sets active org to the joined one. */
    fun addFirebaseConfiguredOrgFromJoin(orgId: String) {
        val trimmed = orgId.trim()
        if (trimmed.isBlank()) return
        val current = getFirebaseConfiguredOrgs()
        if (current.any { it.orgId == trimmed }) {
            setFirebaseOrgId(trimmed)
            return
        }
        val color = com.eventmanager.app.data.remote.FirebaseConfiguredOrgCodec.nextAvailableColor(
            current.map { it.colorArgb },
        )
        setFirebaseConfiguredOrgs(current + com.eventmanager.app.data.remote.FirebaseConfiguredOrg(trimmed, color))
        setFirebaseOrgId(trimmed)
    }

    /** Org invitation code — stored locally; also embedded in full v1 join QR. */
    fun getFirebaseBootstrapCode(): String = storage.getString(KEY_FIREBASE_BOOTSTRAP_CODE, "") ?: ""
    fun setFirebaseBootstrapCode(code: String) = storage.putString(KEY_FIREBASE_BOOTSTRAP_CODE, code.trim())

    /**
     * True when the Firebase config came from a scanned QR / pasted join code rather than typed
     * fields. Join UIs must not echo those values back: the member never needs to see them, and
     * the invitation code is what lets any device join the organization.
     */
    fun isFirebaseJoinImported(): Boolean = storage.getBoolean(KEY_FIREBASE_JOIN_IMPORTED, false)
    fun setFirebaseJoinImported(imported: Boolean) =
        storage.putBoolean(KEY_FIREBASE_JOIN_IMPORTED, imported)

    fun getFirebaseAuthEmail(): String = storage.getString(KEY_FIREBASE_AUTH_EMAIL, "") ?: ""
    fun setFirebaseAuthEmail(email: String) = storage.putString(KEY_FIREBASE_AUTH_EMAIL, email)

    fun getAllowedEmailDomains(): List<String> =
        com.eventmanager.app.data.remote.FirebaseEmailDomainPolicy.parseStoredList(
            storage.getString(KEY_ALLOWED_EMAIL_DOMAINS, "").orEmpty(),
        )

    fun setAllowedEmailDomains(domains: List<String>) {
        val serialized = com.eventmanager.app.data.remote.FirebaseEmailDomainPolicy.serialize(domains)
        storage.putString(KEY_ALLOWED_EMAIL_DOMAINS, serialized)
        touchInstitutionSettingLastModified(InstitutionSettingsKeys.ALLOWED_EMAIL_DOMAINS)
    }

    fun getFollowedBackendMigrationId(): String =
        storage.getString(KEY_FOLLOWED_BACKEND_MIGRATION_ID, "") ?: ""

    fun setFollowedBackendMigrationId(id: String) =
        storage.putString(KEY_FOLLOWED_BACKEND_MIGRATION_ID, id)

    fun isSheetsMirrorEnabled(): Boolean = storage.getBoolean(KEY_SHEETS_MIRROR_ENABLED, false)
    fun setSheetsMirrorEnabled(enabled: Boolean) {
        storage.putBoolean(KEY_SHEETS_MIRROR_ENABLED, enabled)
        touchInstitutionSettingLastModified(InstitutionSettingsKeys.SHEETS_MIRROR_ENABLED)
    }

    fun getSheetsMirrorSpreadsheetId(): String =
        storage.getString(KEY_SHEETS_MIRROR_SPREADSHEET_ID, "") ?: ""

    fun setSheetsMirrorSpreadsheetId(id: String) {
        storage.putString(KEY_SHEETS_MIRROR_SPREADSHEET_ID, id)
        touchInstitutionSettingLastModified(InstitutionSettingsKeys.SHEETS_MIRROR_SPREADSHEET_ID)
    }

    fun getSheetsMirrorLastExportAt(): Long = storage.getLong(KEY_SHEETS_MIRROR_LAST_EXPORT_AT, 0L)
    fun setSheetsMirrorLastExportAt(ms: Long) {
        storage.putLong(KEY_SHEETS_MIRROR_LAST_EXPORT_AT, ms)
        touchInstitutionSettingLastModified(InstitutionSettingsKeys.SHEETS_MIRROR_LAST_EXPORT_AT)
    }

    /** 0 = manual only; otherwise minutes between automatic one-way mirror exports. */
    fun isLocalCryptoMigrationDone(orgId: String): Boolean =
        storage.getBoolean(KEY_LOCAL_CRYPTO_MIGRATION_PREFIX + orgId.trim(), false)

    fun markLocalCryptoMigrationDone(orgId: String) {
        storage.putBoolean(KEY_LOCAL_CRYPTO_MIGRATION_PREFIX + orgId.trim(), true)
    }

    fun getSheetsMirrorIntervalMinutes(): Int = storage.getInt(KEY_SHEETS_MIRROR_INTERVAL_MINUTES, 0)
    fun setSheetsMirrorIntervalMinutes(minutes: Int) {
        storage.putInt(KEY_SHEETS_MIRROR_INTERVAL_MINUTES, minutes.coerceAtLeast(0))
        touchInstitutionSettingLastModified(InstitutionSettingsKeys.SHEETS_MIRROR_INTERVAL_MINUTES)
    }

    fun isFirebaseConfigured(): Boolean =
        getBackendType() == com.eventmanager.app.data.remote.BackendType.FIREBASE &&
            getFirebaseOrgId().isNotBlank()

    fun getLocalInstitutionBackendAnnouncement(): com.eventmanager.app.data.remote.InstitutionBackendAnnouncement? {
        val rows = getInstitutionSettingRows().associate { it.key to it.value }
        val typeRaw = rows[InstitutionSettingsKeys.BACKEND_TYPE] ?: getBackendType().name
        return com.eventmanager.app.data.remote.InstitutionBackendAnnouncement(
            backendType = com.eventmanager.app.data.remote.BackendType.fromStorage(typeRaw),
            migrationId = rows[InstitutionSettingsKeys.BACKEND_MIGRATION_ID].orEmpty(),
            migratedAt = rows[InstitutionSettingsKeys.BACKEND_MIGRATION_AT]?.toLongOrNull() ?: 0L,
            migratedBy = rows[InstitutionSettingsKeys.BACKEND_MIGRATION_BY].orEmpty(),
            firebaseOrgId = rows[InstitutionSettingsKeys.FIREBASE_ORG_ID]?.takeIf { it.isNotBlank() }
                ?: getFirebaseOrgId().takeIf { it.isNotBlank() },
            sheetsSpreadsheetIdHint = rows[InstitutionSettingsKeys.SHEETS_SPREADSHEET_ID_HINT]
                ?.takeIf { it.isNotBlank() },
            firebaseProjectId = rows[InstitutionSettingsKeys.FIREBASE_PROJECT_ID]?.takeIf { it.isNotBlank() }
                ?: getFirebaseProjectId().takeIf { it.isNotBlank() },
            firebaseApplicationId = rows[InstitutionSettingsKeys.FIREBASE_APPLICATION_ID]?.takeIf { it.isNotBlank() }
                ?: getFirebaseApplicationId().takeIf { it.isNotBlank() },
            firebaseWebClientId = rows[InstitutionSettingsKeys.FIREBASE_WEB_CLIENT_ID]?.takeIf { it.isNotBlank() }
                ?: getFirebaseWebClientId().takeIf { it.isNotBlank() },
        )
    }

    /**
     * Persist institution announcement keys and switch this device's active backend.
     * Call only after successful migration initiate/follow — never from a plain Settings pull.
     * Firebase project options are applied silently to local settings (UI must not display them).
     */
    fun applyLocalInstitutionBackendAnnouncement(
        announcement: com.eventmanager.app.data.remote.InstitutionBackendAnnouncement,
    ) {
        val now = announcement.migratedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
        applyInstitutionSettingFromRemote(InstitutionSettingsKeys.BACKEND_TYPE, announcement.backendType.name, now)
        applyInstitutionSettingFromRemote(InstitutionSettingsKeys.BACKEND_MIGRATION_ID, announcement.migrationId, now)
        applyInstitutionSettingFromRemote(InstitutionSettingsKeys.BACKEND_MIGRATION_AT, now.toString(), now)
        applyInstitutionSettingFromRemote(InstitutionSettingsKeys.BACKEND_MIGRATION_BY, announcement.migratedBy, now)
        announcement.firebaseOrgId?.let {
            applyInstitutionSettingFromRemote(InstitutionSettingsKeys.FIREBASE_ORG_ID, it, now)
            setFirebaseOrgId(it)
        }
        announcement.sheetsSpreadsheetIdHint?.let {
            applyInstitutionSettingFromRemote(InstitutionSettingsKeys.SHEETS_SPREADSHEET_ID_HINT, it, now)
        }
        announcement.firebaseProjectId?.takeIf { it.isNotBlank() }?.let {
            applyInstitutionSettingFromRemote(InstitutionSettingsKeys.FIREBASE_PROJECT_ID, it, now)
            setFirebaseProjectId(it)
        }
        announcement.firebaseApplicationId?.takeIf { it.isNotBlank() }?.let {
            applyInstitutionSettingFromRemote(InstitutionSettingsKeys.FIREBASE_APPLICATION_ID, it, now)
            setFirebaseApplicationId(it)
        }
        announcement.firebaseWebClientId?.takeIf { it.isNotBlank() }?.let {
            applyInstitutionSettingFromRemote(InstitutionSettingsKeys.FIREBASE_WEB_CLIENT_ID, it, now)
            setFirebaseWebClientId(it)
        }
        setBackendType(announcement.backendType)
    }

    /** Apply a QR/clipboard join payload without exposing values in the UI. */
    fun applyFirebaseJoinPayload(payload: com.eventmanager.app.data.remote.FirebaseJoinPayload) {
        val previousProject = getFirebaseProjectId().trim()
        val nextProject = payload.projectId.trim()
        val switchingProject = previousProject.isNotBlank() && previousProject != nextProject
        setFirebaseJoinImported(true)
        addFirebaseConfiguredOrgFromJoin(payload.orgId.trim())
        setFirebaseProjectId(nextProject)
        setFirebaseApplicationId(payload.applicationId.trim())
        setFirebaseApiKey(payload.apiKey.trim())
        setFirebaseWebClientId(payload.webClientId.trim())
        when {
            payload.webClientSecret.isNotBlank() -> setFirebaseWebClientSecret(payload.webClientSecret.trim())
            switchingProject -> setFirebaseWebClientSecret("")
        }
        when {
            payload.bootstrapCode.isNotBlank() -> setFirebaseBootstrapCode(payload.bootstrapCode.trim())
            switchingProject -> setFirebaseBootstrapCode("")
        }
    }

    /**
     * Copy Firebase project options from an announcement into local settings without switching backend.
     * Used by follow / join UIs before Sign-In so secrets never need to be typed or shown.
     */
    fun applySilentFirebaseOptionsFromAnnouncement(
        announcement: com.eventmanager.app.data.remote.InstitutionBackendAnnouncement,
    ) {
        announcement.firebaseOrgId?.takeIf { it.isNotBlank() }?.let { setFirebaseOrgId(it) }
        announcement.firebaseProjectId?.takeIf { it.isNotBlank() }?.let { setFirebaseProjectId(it) }
        announcement.firebaseApplicationId?.takeIf { it.isNotBlank() }?.let { setFirebaseApplicationId(it) }
        announcement.firebaseWebClientId?.takeIf { it.isNotBlank() }?.let { setFirebaseWebClientId(it) }
    }

    fun buildFirebaseJoinPayloadOrNull(): com.eventmanager.app.data.remote.FirebaseJoinPayload? {
        val payload = com.eventmanager.app.data.remote.FirebaseJoinPayload(
            orgId = getFirebaseOrgId().trim(),
            projectId = getFirebaseProjectId().trim(),
            applicationId = getFirebaseApplicationId().trim(),
            apiKey = getFirebaseApiKey().trim(),
            webClientId = getFirebaseWebClientId().trim(),
            webClientSecret = getFirebaseWebClientSecret().trim(),
            bootstrapCode = getFirebaseBootstrapCode().trim(),
        )
        return payload.takeIf { it.isComplete() }
    }
    
    fun getGuestListSheet(): String {
        return storage.getString(KEY_GUEST_LIST_SHEET, GoogleSheetsConfig.GUEST_LIST_SHEET) ?: GoogleSheetsConfig.GUEST_LIST_SHEET
    }
    
    fun saveGuestListSheet(sheet: String) {
        storage.putString(KEY_GUEST_LIST_SHEET, sheet)
        touchInstitutionSettingLastModified(InstitutionSettingsKeys.GUEST_LIST_SHEET)
    }
    
    fun getVolunteerSheet(): String {
        return storage.getString(KEY_VOLUNTEER_SHEET, GoogleSheetsConfig.VOLUNTEER_SHEET) ?: GoogleSheetsConfig.VOLUNTEER_SHEET
    }
    
    fun saveVolunteerSheet(sheet: String) {
        storage.putString(KEY_VOLUNTEER_SHEET, sheet)
        touchInstitutionSettingLastModified(InstitutionSettingsKeys.VOLUNTEER_SHEET)
    }
    
    fun getJobsSheet(): String {
        return storage.getString(KEY_JOBS_SHEET, GoogleSheetsConfig.JOBS_SHEET) ?: GoogleSheetsConfig.JOBS_SHEET
    }
    
    fun saveJobsSheet(sheet: String) {
        storage.putString(KEY_JOBS_SHEET, sheet)
        touchInstitutionSettingLastModified(InstitutionSettingsKeys.JOBS_SHEET)
    }
    
    fun getVolunteersSheet(): String {
        return storage.getString(KEY_VOLUNTEER_SHEET, GoogleSheetsConfig.VOLUNTEER_SHEET) ?: GoogleSheetsConfig.VOLUNTEER_SHEET
    }
    
    fun getJobTypesSheet(): String {
        val stored = storage.getString(KEY_JOB_TYPES_SHEET, GoogleSheetsConfig.JOB_TYPES_SHEET)
            ?: GoogleSheetsConfig.JOB_TYPES_SHEET
        return migrateLegacyJobTypesSheetNameToCanonical(stored)
    }

    /**
     * Older builds / docs used "JobTypes" or "Job Types" while the spreadsheet (and current default)
     * uses "Shift Types". Keeping the legacy string in prefs made structure validation create a new
     * empty "Job Types" tab while the app read/wrote "Shift Types" from [GoogleSheetsConfig].
     */
    private fun migrateLegacyJobTypesSheetNameToCanonical(stored: String): String {
        val trimmed = stored.trim()
        val canonical = GoogleSheetsConfig.JOB_TYPES_SHEET.trim()
        if (trimmed.isEmpty()) {
            storage.putString(KEY_JOB_TYPES_SHEET, canonical)
            return canonical
        }
        val isDeprecatedEnglishTabName =
            trimmed.equals("JobTypes", ignoreCase = true) ||
                trimmed.equals("Job Types", ignoreCase = true)
        if (isDeprecatedEnglishTabName && !trimmed.equals(canonical, ignoreCase = true)) {
            storage.putString(KEY_JOB_TYPES_SHEET, canonical)
            return canonical
        }
        return trimmed
    }
    
    fun saveJobTypesSheet(sheet: String) {
        storage.putString(KEY_JOB_TYPES_SHEET, sheet)
        touchInstitutionSettingLastModified(InstitutionSettingsKeys.JOB_TYPES_SHEET)
    }

    fun getVolunteerGuestListSheet(): String {
        return storage.getString(KEY_VOLUNTEER_GUEST_LIST_SHEET, GoogleSheetsConfig.VOLUNTEER_GUEST_LIST_SHEET) ?: GoogleSheetsConfig.VOLUNTEER_GUEST_LIST_SHEET
    }

    fun saveVolunteerGuestListSheet(sheet: String) {
        storage.putString(KEY_VOLUNTEER_GUEST_LIST_SHEET, sheet)
    }

    fun getVenuesSheet(): String {
        return storage.getString(KEY_VENUES_SHEET, GoogleSheetsConfig.VENUES_SHEET) ?: GoogleSheetsConfig.VENUES_SHEET
    }

    fun saveVenuesSheet(sheet: String) {
        storage.putString(KEY_VENUES_SHEET, sheet)
        touchInstitutionSettingLastModified(InstitutionSettingsKeys.VENUES_SHEET)
    }

    fun getSalesItemsSheet(): String {
        return storage.getString(KEY_SALES_ITEMS_SHEET, GoogleSheetsConfig.SALES_ITEMS_SHEET)
            ?: GoogleSheetsConfig.SALES_ITEMS_SHEET
    }

    fun saveSalesItemsSheet(sheet: String) {
        storage.putString(KEY_SALES_ITEMS_SHEET, sheet)
        touchInstitutionSettingLastModified(InstitutionSettingsKeys.SALES_ITEMS_SHEET)
    }

    fun getTransfersSheet(): String {
        return storage.getString(KEY_TRANSFERS_SHEET, GoogleSheetsConfig.TRANSFERS_SHEET)
            ?: GoogleSheetsConfig.TRANSFERS_SHEET
    }

    fun saveTransfersSheet(sheet: String) {
        storage.putString(KEY_TRANSFERS_SHEET, sheet)
        touchInstitutionSettingLastModified(InstitutionSettingsKeys.TRANSFERS_SHEET)
    }

    fun getCurrencyCode(): String {
        return storage.getString(KEY_CURRENCY_CODE, "CHF") ?: "CHF"
    }

    fun saveCurrencyCode(code: String) {
        storage.putString(KEY_CURRENCY_CODE, code.trim().uppercase())
        touchInstitutionSettingLastModified(InstitutionSettingsKeys.CURRENCY_CODE)
    }

    /** Max shortfall (absolute, >= 0) that can be absorbed into credit, allowing a temporary negative balance. */
    fun getPurchaseCreditBuffer(): Double {
        return storage.getString(KEY_PURCHASE_CREDIT_BUFFER, "0")?.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
    }

    fun savePurchaseCreditBuffer(amount: Double) {
        val normalized = amount.coerceAtLeast(0.0)
        storage.putString(KEY_PURCHASE_CREDIT_BUFFER, normalized.toString())
        touchInstitutionSettingLastModified(InstitutionSettingsKeys.PURCHASE_CREDIT_BUFFER)
    }

    fun getTempGuestListSheet(): String {
        return storage.getString(KEY_TEMP_GUEST_LIST_SHEET, GoogleSheetsConfig.TEMP_GUEST_LIST_SHEET)
            ?: GoogleSheetsConfig.TEMP_GUEST_LIST_SHEET
    }

    fun saveTempGuestListSheet(sheet: String) {
        storage.putString(KEY_TEMP_GUEST_LIST_SHEET, sheet)
        touchInstitutionSettingLastModified(InstitutionSettingsKeys.TEMP_GUEST_LIST_SHEET)
    }

    fun getSettingsSheet(): String {
        return storage.getString(KEY_SETTINGS_SHEET, GoogleSheetsConfig.SETTINGS_SHEET)
            ?: GoogleSheetsConfig.SETTINGS_SHEET
    }

    fun saveSettingsSheet(sheet: String) {
        storage.putString(KEY_SETTINGS_SHEET, sheet)
        touchInstitutionSettingLastModified(InstitutionSettingsKeys.SETTINGS_SHEET)
    }

    fun getInstitutionSettingLastModified(key: String): Long {
        return storage.getLong(KEY_INSTITUTION_SETTING_LM_PREFIX + key, 0L)
    }

    fun setInstitutionSettingLastModified(key: String, timestamp: Long) {
        storage.putLong(KEY_INSTITUTION_SETTING_LM_PREFIX + key, timestamp)
    }

    fun touchInstitutionSettingLastModified(key: String) {
        setInstitutionSettingLastModified(key, System.currentTimeMillis())
    }

    /** Snapshot of all institution-synced settings for Google Sheets upload. */
    fun getInstitutionSettingRows(): List<InstitutionSettingRow> {
        seedLegacySheetsMirrorInstitutionTimestamps()
        return InstitutionSettingsKeys.ALL.map { key ->
            InstitutionSettingRow(
                key = key,
                value = readInstitutionSettingValue(key),
                lastModified = getInstitutionSettingLastModified(key),
            )
        }
    }

    fun getInstitutionSettingRowsPendingRemotePush(): List<InstitutionSettingRow> {
        return getInstitutionSettingRows().filter { row ->
            row.lastModified != getPushedInstitutionSettingLastModified(row.key)
        }
    }

    fun markInstitutionSettingRowPushed(row: InstitutionSettingRow) {
        storage.putLong(KEY_INSTITUTION_SETTING_PUSHED_LM_PREFIX + row.key, row.lastModified)
    }

    fun markAllInstitutionSettingsPendingRemotePush() {
        InstitutionSettingsKeys.ALL.forEach { key ->
            storage.putLong(KEY_INSTITUTION_SETTING_PUSHED_LM_PREFIX + key, -1L)
        }
    }

    private fun getPushedInstitutionSettingLastModified(key: String): Long {
        return storage.getLong(KEY_INSTITUTION_SETTING_PUSHED_LM_PREFIX + key, 0L)
    }

    /**
     * Apply a remote institution setting and its last-modified timestamp
     * without bumping the local timestamp to "now".
     */
    fun applyInstitutionSettingFromRemote(key: String, value: String, lastModified: Long) {
        writeInstitutionSettingValue(key, value)
        setInstitutionSettingLastModified(key, lastModified)
        storage.putLong(KEY_INSTITUTION_SETTING_PUSHED_LM_PREFIX + key, lastModified)
    }

    /**
     * Mirror prefs saved before institution sync existed may have values but lastModified=0.
     * Stamp them once so the first Firestore push wins over empty peers.
     */
    private fun seedLegacySheetsMirrorInstitutionTimestamps() {
        val now = System.currentTimeMillis()
        fun touchIfUnset(key: String, hasValue: Boolean) {
            if (hasValue && getInstitutionSettingLastModified(key) == 0L) {
                setInstitutionSettingLastModified(key, now)
            }
        }
        touchIfUnset(InstitutionSettingsKeys.PROFILE_PHOTOS_ENABLED, isProfilePhotosEnabled())
        touchIfUnset(InstitutionSettingsKeys.ANNOUNCEMENTS_NON_ADMIN_SEND_ENABLED, isAnnouncementsNonAdminSendEnabled())
        touchIfUnset(InstitutionSettingsKeys.SHEETS_MIRROR_ENABLED, isSheetsMirrorEnabled())
        touchIfUnset(
            InstitutionSettingsKeys.SHEETS_MIRROR_SPREADSHEET_ID,
            getSheetsMirrorSpreadsheetId().isNotBlank(),
        )
        touchIfUnset(
            InstitutionSettingsKeys.SHEETS_MIRROR_INTERVAL_MINUTES,
            getSheetsMirrorIntervalMinutes() > 0,
        )
        touchIfUnset(
            InstitutionSettingsKeys.SHEETS_MIRROR_LAST_EXPORT_AT,
            getSheetsMirrorLastExportAt() > 0L,
        )
    }

    private fun readInstitutionSettingValue(key: String): String {
        return when (key) {
            InstitutionSettingsKeys.CURRENCY_CODE -> getCurrencyCode()
            InstitutionSettingsKeys.DATE_CHANGE_OFFSET_HOURS -> getDateChangeOffsetHours().toString()
            InstitutionSettingsKeys.PURCHASE_CREDIT_BUFFER -> getPurchaseCreditBuffer().toString()
            InstitutionSettingsKeys.EMAIL_QR_SUBJECT -> getEmailSubject()
            InstitutionSettingsKeys.EMAIL_QR_CONTENT_BEFORE -> getEmailContentBefore()
            InstitutionSettingsKeys.EMAIL_QR_CONTENT_AFTER -> getEmailContentAfter()
            InstitutionSettingsKeys.EMAIL_INCLUDE_QR -> isEmailIncludeQrEnabled().toString()
            InstitutionSettingsKeys.GUEST_EMAIL_SUBJECT -> getGuestEmailSubject()
            InstitutionSettingsKeys.GUEST_EMAIL_CONTENT_BEFORE -> getGuestEmailContentBefore()
            InstitutionSettingsKeys.GUEST_EMAIL_CONTENT_AFTER -> getGuestEmailContentAfter()
            InstitutionSettingsKeys.GUEST_EMAIL_INCLUDE_QR -> isGuestEmailIncludeQrEnabled().toString()
            InstitutionSettingsKeys.EMAIL_SIGNATURE -> getEmailSignature()
            InstitutionSettingsKeys.EMAIL_ASSOCIATION_NAME -> getEmailAssociationName()
            InstitutionSettingsKeys.BACKEND_TYPE ->
                storage.getString("inst_val_" + InstitutionSettingsKeys.BACKEND_TYPE, "").ifBlank { getBackendType().name }
            InstitutionSettingsKeys.BACKEND_MIGRATION_ID ->
                storage.getString("inst_val_" + InstitutionSettingsKeys.BACKEND_MIGRATION_ID, "") ?: ""
            InstitutionSettingsKeys.BACKEND_MIGRATION_AT ->
                storage.getString("inst_val_" + InstitutionSettingsKeys.BACKEND_MIGRATION_AT, "") ?: ""
            InstitutionSettingsKeys.BACKEND_MIGRATION_BY ->
                storage.getString("inst_val_" + InstitutionSettingsKeys.BACKEND_MIGRATION_BY, "") ?: ""
            InstitutionSettingsKeys.FIREBASE_ORG_ID ->
                storage.getString("inst_val_" + InstitutionSettingsKeys.FIREBASE_ORG_ID, "").ifBlank { getFirebaseOrgId() }
            InstitutionSettingsKeys.FIREBASE_CONFIGURED_ORGS ->
                storage.getString("inst_val_" + InstitutionSettingsKeys.FIREBASE_CONFIGURED_ORGS, "").ifBlank {
                    storage.getString(KEY_FIREBASE_CONFIGURED_ORGS, "").orEmpty()
                }
            InstitutionSettingsKeys.SHEETS_SPREADSHEET_ID_HINT ->
                storage.getString("inst_val_" + InstitutionSettingsKeys.SHEETS_SPREADSHEET_ID_HINT, "") ?: ""
            InstitutionSettingsKeys.ALLOWED_EMAIL_DOMAINS ->
                com.eventmanager.app.data.remote.FirebaseEmailDomainPolicy.serialize(getAllowedEmailDomains())
            InstitutionSettingsKeys.PROFILE_PHOTOS_ENABLED -> isProfilePhotosEnabled().toString()
            InstitutionSettingsKeys.ANNOUNCEMENTS_NON_ADMIN_SEND_ENABLED ->
                isAnnouncementsNonAdminSendEnabled().toString()
            InstitutionSettingsKeys.POS_SUBCATEGORIES ->
                storage.getString(KEY_POS_SUBCATEGORIES, "") ?: ""
            InstitutionSettingsKeys.TEMP_GUEST_VENUE_ACCESSES ->
                storage.getString(KEY_TEMP_GUEST_VENUE_ACCESSES, "") ?: ""
            InstitutionSettingsKeys.TEMP_GUEST_CREDITS_ENABLED ->
                isTemporaryGuestCreditsEnabled().toString()
            InstitutionSettingsKeys.GUEST_FORMS_ENABLED -> isGuestFormsEnabled().toString()
            InstitutionSettingsKeys.GUEST_FORM_BASE_URL -> getGuestFormBaseUrl()
            InstitutionSettingsKeys.INSTITUTION_LOGO_PNG -> getInstitutionLogoPng()
            InstitutionSettingsKeys.TEMP_GUEST_EMAIL_SUBJECT -> getTemporaryGuestEmailSubject()
            InstitutionSettingsKeys.TEMP_GUEST_EMAIL_CONTENT_BEFORE -> getTemporaryGuestEmailContentBefore()
            InstitutionSettingsKeys.TEMP_GUEST_EMAIL_CONTENT_AFTER -> getTemporaryGuestEmailContentAfter()
            InstitutionSettingsKeys.TEMP_GUEST_EMAIL_INCLUDE_QR ->
                isTemporaryGuestEmailIncludeQrEnabled().toString()
            InstitutionSettingsKeys.SHEETS_MIRROR_ENABLED -> isSheetsMirrorEnabled().toString()
            InstitutionSettingsKeys.SHEETS_MIRROR_SPREADSHEET_ID -> getSheetsMirrorSpreadsheetId()
            InstitutionSettingsKeys.SHEETS_MIRROR_INTERVAL_MINUTES ->
                getSheetsMirrorIntervalMinutes().toString()
            InstitutionSettingsKeys.SHEETS_MIRROR_LAST_EXPORT_AT ->
                getSheetsMirrorLastExportAt().toString()
            InstitutionSettingsKeys.GUEST_LIST_SHEET -> getGuestListSheet()
            InstitutionSettingsKeys.VOLUNTEER_SHEET -> getVolunteerSheet()
            InstitutionSettingsKeys.JOBS_SHEET -> getJobsSheet()
            InstitutionSettingsKeys.JOB_TYPES_SHEET -> getJobTypesSheet()
            InstitutionSettingsKeys.VENUES_SHEET -> getVenuesSheet()
            InstitutionSettingsKeys.SALES_ITEMS_SHEET -> getSalesItemsSheet()
            InstitutionSettingsKeys.TRANSFERS_SHEET -> getTransfersSheet()
            InstitutionSettingsKeys.TEMP_GUEST_LIST_SHEET -> getTempGuestListSheet()
            InstitutionSettingsKeys.SETTINGS_SHEET -> getSettingsSheet()
            else -> ""
        }
    }

    private fun writeInstitutionSettingValue(key: String, value: String) {
        when (key) {
            InstitutionSettingsKeys.CURRENCY_CODE ->
                storage.putString(KEY_CURRENCY_CODE, value.trim().uppercase().ifEmpty { "CHF" })
            InstitutionSettingsKeys.DATE_CHANGE_OFFSET_HOURS ->
                storage.putInt(KEY_DATE_CHANGE_OFFSET_HOURS, value.trim().toIntOrNull() ?: 0)
            InstitutionSettingsKeys.PURCHASE_CREDIT_BUFFER ->
                storage.putString(
                    KEY_PURCHASE_CREDIT_BUFFER,
                    (value.trim().toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0).toString(),
                )
            InstitutionSettingsKeys.EMAIL_QR_SUBJECT ->
                storage.putString(KEY_EMAIL_SUBJECT, value)
            InstitutionSettingsKeys.EMAIL_QR_CONTENT_BEFORE ->
                storage.putString(KEY_EMAIL_CONTENT_BEFORE, value)
            InstitutionSettingsKeys.EMAIL_QR_CONTENT_AFTER ->
                storage.putString(KEY_EMAIL_CONTENT_AFTER, value)
            InstitutionSettingsKeys.EMAIL_INCLUDE_QR ->
                storage.putBoolean(KEY_EMAIL_INCLUDE_QR, value.trim().equals("true", ignoreCase = true))
            InstitutionSettingsKeys.GUEST_EMAIL_SUBJECT ->
                storage.putString(KEY_GUEST_EMAIL_SUBJECT, value)
            InstitutionSettingsKeys.GUEST_EMAIL_CONTENT_BEFORE ->
                storage.putString(KEY_GUEST_EMAIL_CONTENT_BEFORE, value)
            InstitutionSettingsKeys.GUEST_EMAIL_CONTENT_AFTER ->
                storage.putString(KEY_GUEST_EMAIL_CONTENT_AFTER, value)
            InstitutionSettingsKeys.GUEST_EMAIL_INCLUDE_QR ->
                storage.putBoolean(KEY_GUEST_EMAIL_INCLUDE_QR, value.trim().equals("true", ignoreCase = true))
            InstitutionSettingsKeys.EMAIL_SIGNATURE ->
                storage.putString(KEY_EMAIL_SIGNATURE, value)
            InstitutionSettingsKeys.EMAIL_ASSOCIATION_NAME ->
                storage.putString(
                    KEY_EMAIL_ASSOCIATION_NAME,
                    value.ifBlank { "Collectif Nocturne" },
                )
            InstitutionSettingsKeys.BACKEND_TYPE -> {
                // Institution announcement only — do NOT switch local backend_type here.
                // Mismatch is detected by SyncCoordinator.refreshInstitutionBackendGuard → follow flow.
                // Ignore blank remote values so we never wipe a known SHEETS/FIREBASE announcement.
                if (value.isNotBlank()) {
                    storage.putString("inst_val_" + InstitutionSettingsKeys.BACKEND_TYPE, value)
                }
            }
            InstitutionSettingsKeys.BACKEND_MIGRATION_ID,
            InstitutionSettingsKeys.BACKEND_MIGRATION_AT,
            InstitutionSettingsKeys.BACKEND_MIGRATION_BY,
            InstitutionSettingsKeys.SHEETS_SPREADSHEET_ID_HINT,
            -> storage.putString("inst_val_$key", value)
            InstitutionSettingsKeys.FIREBASE_ORG_ID -> {
                storage.putString("inst_val_" + InstitutionSettingsKeys.FIREBASE_ORG_ID, value)
                // Do not override the locally selected active org from remote institution settings.
            }
            InstitutionSettingsKeys.FIREBASE_CONFIGURED_ORGS -> {
                storage.putString("inst_val_" + InstitutionSettingsKeys.FIREBASE_CONFIGURED_ORGS, value)
                if (value.isNotBlank()) {
                    val parsed = com.eventmanager.app.data.remote.FirebaseConfiguredOrgCodec.decode(value)
                    if (parsed.isNotEmpty()) {
                        val normalized = com.eventmanager.app.data.remote.FirebaseConfiguredOrgCodec.normalize(parsed)
                        storage.putString(
                            KEY_FIREBASE_CONFIGURED_ORGS,
                            com.eventmanager.app.data.remote.FirebaseConfiguredOrgCodec.encode(normalized),
                        )
                    }
                }
            }
            InstitutionSettingsKeys.ALLOWED_EMAIL_DOMAINS -> {
                storage.putString(
                    KEY_ALLOWED_EMAIL_DOMAINS,
                    com.eventmanager.app.data.remote.FirebaseEmailDomainPolicy.serialize(
                        com.eventmanager.app.data.remote.FirebaseEmailDomainPolicy.parseStoredList(value),
                    ),
                )
            }
            InstitutionSettingsKeys.FIREBASE_PROJECT_ID -> {
                storage.putString("inst_val_$key", value)
                if (value.isNotBlank()) setFirebaseProjectId(value)
            }
            InstitutionSettingsKeys.FIREBASE_APPLICATION_ID -> {
                storage.putString("inst_val_$key", value)
                if (value.isNotBlank()) setFirebaseApplicationId(value)
            }
            InstitutionSettingsKeys.FIREBASE_WEB_CLIENT_ID -> {
                storage.putString("inst_val_$key", value)
                if (value.isNotBlank()) setFirebaseWebClientId(value)
            }
            InstitutionSettingsKeys.FIREBASE_API_KEY,
            InstitutionSettingsKeys.FIREBASE_WEB_CLIENT_SECRET,
            -> {
                // Secrets are device-local only — never apply from remote institution settings.
            }
            InstitutionSettingsKeys.PROFILE_PHOTOS_ENABLED ->
                storage.putBoolean(KEY_PROFILE_PHOTOS_ENABLED, value.trim().equals("true", ignoreCase = true))
            InstitutionSettingsKeys.ANNOUNCEMENTS_NON_ADMIN_SEND_ENABLED ->
                storage.putBoolean(
                    KEY_ANNOUNCEMENTS_NON_ADMIN_SEND_ENABLED,
                    value.trim().equals("true", ignoreCase = true),
                )
            // Re-encode so a malformed remote payload cannot poison the local catalogue.
            InstitutionSettingsKeys.POS_SUBCATEGORIES ->
                storage.putString(
                    KEY_POS_SUBCATEGORIES,
                    PosSubcategoryCatalog.encode(PosSubcategoryCatalog.decode(value)),
                )
            InstitutionSettingsKeys.TEMP_GUEST_VENUE_ACCESSES ->
                storage.putString(
                    KEY_TEMP_GUEST_VENUE_ACCESSES,
                    VenueAccessCatalog.encode(VenueAccessCatalog.decode(value)),
                )
            InstitutionSettingsKeys.TEMP_GUEST_CREDITS_ENABLED ->
                storage.putBoolean(
                    KEY_TEMP_GUEST_CREDITS_ENABLED,
                    value.trim().equals("true", ignoreCase = true),
                )
            InstitutionSettingsKeys.GUEST_FORMS_ENABLED ->
                storage.putBoolean(
                    KEY_GUEST_FORMS_ENABLED,
                    value.trim().equals("true", ignoreCase = true),
                )
            InstitutionSettingsKeys.GUEST_FORM_BASE_URL ->
                storage.putString(KEY_GUEST_FORM_BASE_URL, value.trim().trimEnd('/'))
            InstitutionSettingsKeys.INSTITUTION_LOGO_PNG -> {
                val logo = value.trim()
                // Never put the image payload in Preferences (desktop hard-caps at ~8 KiB).
                if (logo.isEmpty()) {
                    storage.remove(KEY_INSTITUTION_LOGO_PNG)
                } else {
                    storage.putString(KEY_INSTITUTION_LOGO_PNG, LOGO_ON_DISK_MARKER)
                }
                InstitutionLogoStore.mirrorToDisk(logo)
            }
            InstitutionSettingsKeys.TEMP_GUEST_EMAIL_SUBJECT ->
                storage.putString(KEY_TEMP_GUEST_EMAIL_SUBJECT, value)
            InstitutionSettingsKeys.TEMP_GUEST_EMAIL_CONTENT_BEFORE ->
                storage.putString(KEY_TEMP_GUEST_EMAIL_CONTENT_BEFORE, value)
            InstitutionSettingsKeys.TEMP_GUEST_EMAIL_CONTENT_AFTER ->
                storage.putString(KEY_TEMP_GUEST_EMAIL_CONTENT_AFTER, value)
            InstitutionSettingsKeys.TEMP_GUEST_EMAIL_INCLUDE_QR ->
                storage.putBoolean(
                    KEY_TEMP_GUEST_EMAIL_INCLUDE_QR,
                    value.trim().equals("true", ignoreCase = true),
                )
            InstitutionSettingsKeys.SHEETS_MIRROR_ENABLED ->
                storage.putBoolean(KEY_SHEETS_MIRROR_ENABLED, value.trim().equals("true", ignoreCase = true))
            InstitutionSettingsKeys.SHEETS_MIRROR_SPREADSHEET_ID ->
                storage.putString(KEY_SHEETS_MIRROR_SPREADSHEET_ID, value)
            InstitutionSettingsKeys.SHEETS_MIRROR_INTERVAL_MINUTES ->
                storage.putInt(
                    KEY_SHEETS_MIRROR_INTERVAL_MINUTES,
                    value.trim().toIntOrNull()?.coerceAtLeast(0) ?: 0,
                )
            InstitutionSettingsKeys.SHEETS_MIRROR_LAST_EXPORT_AT ->
                storage.putLong(
                    KEY_SHEETS_MIRROR_LAST_EXPORT_AT,
                    value.trim().toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
                )
            InstitutionSettingsKeys.GUEST_LIST_SHEET ->
                storage.putString(KEY_GUEST_LIST_SHEET, value)
            InstitutionSettingsKeys.VOLUNTEER_SHEET ->
                storage.putString(KEY_VOLUNTEER_SHEET, value)
            InstitutionSettingsKeys.JOBS_SHEET ->
                storage.putString(KEY_JOBS_SHEET, value)
            InstitutionSettingsKeys.JOB_TYPES_SHEET ->
                storage.putString(KEY_JOB_TYPES_SHEET, value)
            InstitutionSettingsKeys.VENUES_SHEET ->
                storage.putString(KEY_VENUES_SHEET, value)
            InstitutionSettingsKeys.SALES_ITEMS_SHEET ->
                storage.putString(KEY_SALES_ITEMS_SHEET, value)
            InstitutionSettingsKeys.TRANSFERS_SHEET ->
                storage.putString(KEY_TRANSFERS_SHEET, value)
            InstitutionSettingsKeys.TEMP_GUEST_LIST_SHEET ->
                storage.putString(KEY_TEMP_GUEST_LIST_SHEET, value)
            InstitutionSettingsKeys.SETTINGS_SHEET ->
                storage.putString(KEY_SETTINGS_SHEET, value)
        }
    }
    
    // Sync Configuration
    fun isSyncEnabled(): Boolean {
        return storage.getBoolean(KEY_SYNC_ENABLED, true)
    }
    
    fun setSyncEnabled(enabled: Boolean) {
        storage.putBoolean(KEY_SYNC_ENABLED, enabled)
    }
    
    fun isAutoSyncEnabled(): Boolean {
        return storage.getBoolean(KEY_AUTO_SYNC, true)
    }
    
    fun setAutoSyncEnabled(enabled: Boolean) {
        storage.putBoolean(KEY_AUTO_SYNC, enabled)
    }
    
    fun getSyncInterval(): Int {
        return storage.getInt(KEY_SYNC_INTERVAL, 5) // 5 minutes default
    }
    
    fun saveSyncInterval(intervalMinutes: Int) {
        storage.putInt(KEY_SYNC_INTERVAL, intervalMinutes)
    }
    
    fun getDebugMode(): Boolean {
        return storage.getBoolean(KEY_DEBUG_MODE, false)
    }
    
    fun saveDebugMode(enabled: Boolean) {
        storage.putBoolean(KEY_DEBUG_MODE, enabled)
    }
    
    fun getBackgroundAnimationStyle(): String {
        val stored = storage.getString(KEY_BACKGROUND_ANIMATION_STYLE, "")
        if (stored.isNotEmpty()) {
            return normalizeBackgroundAnimationStyle(stored, default = "topographic")
        }
        if (storage.contains(KEY_ANIMATED_BACKGROUND)) {
            return if (storage.getBoolean(KEY_ANIMATED_BACKGROUND, true)) {
                "arches"
            } else {
                "none"
            }
        }
        return "topographic"
    }

    fun setBackgroundAnimationStyle(style: String) {
        val normalized = normalizeBackgroundAnimationStyle(style, default = "topographic")
        storage.putString(KEY_BACKGROUND_ANIMATION_STYLE, normalized)
        storage.putBoolean(KEY_ANIMATED_BACKGROUND, normalized != "none")
    }

    fun getBackgroundAnimationOpacity(): Float {
        val default = BackgroundAnimationStyle.defaultOpacity(getBackgroundAnimationStyle())
        if (!storage.contains(KEY_BACKGROUND_ANIMATION_OPACITY)) {
            return default
        }
        return storage.getFloat(KEY_BACKGROUND_ANIMATION_OPACITY, default).coerceIn(0.05f, 1.0f)
    }

    fun setBackgroundAnimationOpacity(opacity: Float) {
        storage.putFloat(
            KEY_BACKGROUND_ANIMATION_OPACITY,
            opacity.coerceIn(0.05f, 1.0f),
        )
    }

    fun getBilleterieBackgroundAnimationStyle(): String {
        val stored = storage.getString(KEY_BILLETTERIE_BACKGROUND_ANIMATION_STYLE, "")
        if (stored.isNotEmpty()) {
            return normalizeBackgroundAnimationStyle(stored, default = "none")
        }
        return "none"
    }

    fun setBilleterieBackgroundAnimationStyle(style: String) {
        val normalized = normalizeBackgroundAnimationStyle(style, default = "none")
        storage.putString(KEY_BILLETTERIE_BACKGROUND_ANIMATION_STYLE, normalized)
    }

    fun getBilleterieBackgroundAnimationOpacity(): Float {
        val default = BackgroundAnimationStyle.defaultOpacity(getBilleterieBackgroundAnimationStyle())
        if (!storage.contains(KEY_BILLETTERIE_BACKGROUND_ANIMATION_OPACITY)) {
            return default
        }
        return storage.getFloat(KEY_BILLETTERIE_BACKGROUND_ANIMATION_OPACITY, default).coerceIn(0.05f, 1.0f)
    }

    fun setBilleterieBackgroundAnimationOpacity(opacity: Float) {
        storage.putFloat(
            KEY_BILLETTERIE_BACKGROUND_ANIMATION_OPACITY,
            opacity.coerceIn(0.05f, 1.0f),
        )
    }

    fun getPosBackgroundAnimationStyle(): String {
        val stored = storage.getString(KEY_POS_BACKGROUND_ANIMATION_STYLE, "")
        if (stored.isNotEmpty()) {
            return normalizeBackgroundAnimationStyle(stored, default = "none")
        }
        return "none"
    }

    fun setPosBackgroundAnimationStyle(style: String) {
        val normalized = normalizeBackgroundAnimationStyle(style, default = "none")
        storage.putString(KEY_POS_BACKGROUND_ANIMATION_STYLE, normalized)
    }

    fun getPosBackgroundAnimationOpacity(): Float {
        val default = BackgroundAnimationStyle.defaultOpacity(getPosBackgroundAnimationStyle())
        if (!storage.contains(KEY_POS_BACKGROUND_ANIMATION_OPACITY)) {
            return default
        }
        return storage.getFloat(KEY_POS_BACKGROUND_ANIMATION_OPACITY, default).coerceIn(0.05f, 1.0f)
    }

    fun setPosBackgroundAnimationOpacity(opacity: Float) {
        storage.putFloat(
            KEY_POS_BACKGROUND_ANIMATION_OPACITY,
            opacity.coerceIn(0.05f, 1.0f),
        )
    }

    fun getPosSelectedCategoryName(): String? {
        val stored = storage.getString(KEY_POS_SELECTED_CATEGORY, "").orEmpty()
        return stored.ifEmpty { null }
    }

    fun setPosSelectedCategoryName(name: String?) {
        storage.putString(KEY_POS_SELECTED_CATEGORY, name.orEmpty())
    }

    fun getPosSelectedVenue(): String {
        val stored = storage.getString(KEY_POS_SELECTED_VENUE, "").orEmpty()
        return stored.ifEmpty { com.eventmanager.app.data.models.PosVenueScope.GLOBAL }
    }

    fun setPosSelectedVenue(venue: String) {
        storage.putString(KEY_POS_SELECTED_VENUE, venue)
    }

    private fun normalizeBackgroundAnimationStyle(style: String, default: String): String {
        return when (style) {
            "none", "arches", "topographic" -> style
            else -> default
        }
    }

    fun isAnimatedBackgroundEnabled(): Boolean = getBackgroundAnimationStyle() != "none"

    fun setAnimatedBackgroundEnabled(enabled: Boolean) {
        setBackgroundAnimationStyle(if (enabled) "arches" else "none")
    }
    
    // UI Page Animations Configuration
    fun isPageAnimationsEnabled(): Boolean {
        return storage.getBoolean(KEY_PAGE_ANIMATIONS, true) // Enabled by default
    }
    
    fun setPageAnimationsEnabled(enabled: Boolean) {
        storage.putBoolean(KEY_PAGE_ANIMATIONS, enabled)
    }
    
    /**
     * Millis of the last time the app successfully **pulled** data from Google Sheets
     * (full sync, differential sync, tab-targeted pull, temp guest sheet, etc.).
     * Upload-only / backup-only operations must not advance this value.
     */
    fun getLastSheetsPullAt(): Long {
        val pull = storage.getLong(KEY_LAST_SHEETS_PULL_AT, 0L)
        if (pull > 0L) return pull
        return storage.getLong(KEY_LAST_SYNC_TIME, 0L)
    }

    fun recordSheetsPullAt(timestamp: Long = System.currentTimeMillis()) {
        storage.putLong(KEY_LAST_SHEETS_PULL_AT, timestamp)
    }

    /** @see getLastSheetsPullAt */
    fun getLastSyncTime(): Long = getLastSheetsPullAt()

    /** Call [recordSheetsPullAt] after a successful Sheets download; kept for older call sites. */
    fun saveLastSyncTime(timestamp: Long) = recordSheetsPullAt(timestamp)
    
    // Language Configuration
    fun getLanguage(): String {
        return storage.getString(KEY_LANGUAGE, "en") ?: "en" // Default to English
    }
    
    fun saveLanguage(language: String) {
        storage.putString(KEY_LANGUAGE, language)
    }
    
    // Theme Configuration
    fun getThemeMode(): String {
        return storage.getString(KEY_THEME_MODE, "default") ?: "default" // Default to system theme
    }
    
    fun saveThemeMode(themeMode: String) {
        storage.putString(KEY_THEME_MODE, themeMode)
    }
    
    // Color Theme Configuration
    fun getColorTheme(): String {
        return storage.getString(KEY_COLOR_THEME, "system") ?: "system" // Default to system colors
    }
    
    fun saveColorTheme(colorTheme: String) {
        storage.putString(KEY_COLOR_THEME, colorTheme)
    }

    fun getCustomThemeColor(isDark: Boolean, role: String, defaultArgb: Int): Int {
        val mode = if (isDark) "dark" else "light"
        val key = "${KEY_CUSTOM_THEME_COLOR_PREFIX}_${mode}_${role}"
        return storage.getInt(key, defaultArgb)
    }

    fun saveCustomThemeColor(isDark: Boolean, role: String, argb: Int) {
        val mode = if (isDark) "dark" else "light"
        val key = "${KEY_CUSTOM_THEME_COLOR_PREFIX}_${mode}_${role}"
        storage.putInt(key, argb)
    }

    /**
     * Mark that the next app recreation is visual-only (e.g. theme switch),
     * so startup sync should be skipped once to avoid unnecessary API calls.
     */
    fun markSkipNextStartupSync() {
        storage.putBoolean(KEY_SKIP_NEXT_STARTUP_SYNC, true)
    }

    /**
     * Consume and clear the one-shot "skip startup sync" marker.
     */
    fun consumeSkipNextStartupSync(): Boolean {
        val shouldSkip = storage.getBoolean(KEY_SKIP_NEXT_STARTUP_SYNC, false)
        if (shouldSkip) {
            storage.putBoolean(KEY_SKIP_NEXT_STARTUP_SYNC, false)
        }
        return shouldSkip
    }

    /**
     * After the setup wizard finishes, the next composition must land on Welcome
     * instead of restoring a first-admin gate from the pre-recreate frame.
     */
    fun markOpenWelcomeAfterSetup() {
        storage.putBoolean(KEY_OPEN_WELCOME_AFTER_SETUP, true)
    }

    fun consumeOpenWelcomeAfterSetup(): Boolean {
        val shouldOpen = storage.getBoolean(KEY_OPEN_WELCOME_AFTER_SETUP, false)
        if (shouldOpen) {
            storage.putBoolean(KEY_OPEN_WELCOME_AFTER_SETUP, false)
        }
        return shouldOpen
    }
    
    // Resolution Scale Configuration
    fun getResolutionScale(): Float {
        return storage.getFloat(KEY_RESOLUTION_SCALE, 1.0f) // Default to 100% (normal size)
    }
    
    fun saveResolutionScale(scale: Float) {
        storage.putFloat(KEY_RESOLUTION_SCALE, scale)
    }
    
    // Date Format Configuration
    fun getDateFormat(): String {
        return storage.getString(KEY_DATE_FORMAT, "dd/MM/yyyy") ?: "dd/MM/yyyy" // Swiss / European default
    }

    fun saveDateFormat(dateFormat: String) {
        storage.putString(KEY_DATE_FORMAT, dateFormat)
    }

    // Time Format Configuration
    fun getTimeFormat(): String {
        return storage.getString(KEY_TIME_FORMAT, "HH:mm") ?: "HH:mm" // Default to HH:mm
    }

    fun saveTimeFormat(timeFormat: String) {
        storage.putString(KEY_TIME_FORMAT, timeFormat)
    }
    
    // Date Change Offset Configuration
    fun getDateChangeOffsetHours(): Int {
        return storage.getInt(KEY_DATE_CHANGE_OFFSET_HOURS, 0) // Default to 0 (midnight)
    }
    
    fun saveDateChangeOffsetHours(hours: Int) {
        storage.putInt(KEY_DATE_CHANGE_OFFSET_HOURS, hours)
        touchInstitutionSettingLastModified(InstitutionSettingsKeys.DATE_CHANGE_OFFSET_HOURS)
    }
    
    // Seasonal Fun Configuration
    fun isSeasonalFunEnabled(): Boolean {
        return storage.getBoolean(KEY_SEASONAL_FUN, true) // Enabled by default
    }
    
    fun setSeasonalFunEnabled(enabled: Boolean) {
        storage.putBoolean(KEY_SEASONAL_FUN, enabled)
    }
    
    // Graph Time Period Configuration
    fun getSelectedGraphTimePeriod(): String {
        return storage.getString(KEY_SELECTED_GRAPH_TIME_PERIOD, "ONE_MONTH") ?: "ONE_MONTH" // Default to 1 Month
    }
    
    fun saveSelectedGraphTimePeriod(timePeriod: String) {
        storage.putString(KEY_SELECTED_GRAPH_TIME_PERIOD, timePeriod)
    }

    fun isDashboardGraphCategoryExpanded(categoryId: String): Boolean {
        return storage.getBoolean(KEY_DASHBOARD_GRAPH_CATEGORY_EXPANDED + categoryId, false)
    }

    fun setDashboardGraphCategoryExpanded(categoryId: String, expanded: Boolean) {
        storage.putBoolean(KEY_DASHBOARD_GRAPH_CATEGORY_EXPANDED + categoryId, expanded)
    }
    
    // App Icon Configuration
    fun getAppIconStyle(): String {
        val raw = storage.getString(KEY_APP_ICON_STYLE, "white") ?: "white"
        val migrated = migrateLegacyAppIconStyle(raw)
        if (migrated != raw) {
            storage.putString(KEY_APP_ICON_STYLE, migrated)
        }
        return migrated
    }

    private fun migrateLegacyAppIconStyle(raw: String): String {
        val mapped = when (raw) {
            "light" -> "white"
            "dark" -> "black"
            "deep_blue" -> "dark_blue"
            "blue_ocean" -> "dark_turquoise"
            "braun" -> "brown"
            "purple" -> "dark_violet"
            "violet" -> "light_violet"
            else -> raw
        }
        return if (mapped in AppIconStyles.ALL_ICON_STYLES) mapped else "white"
    }
    
    fun saveAppIconStyle(iconStyle: String) {
        storage.putString(KEY_APP_ICON_STYLE, iconStyle)
    }
    
    fun isAppIconAutoAdapt(): Boolean {
        return storage.getBoolean(KEY_APP_ICON_AUTO_ADAPT, false) // Disabled by default
    }
    
    fun setAppIconAutoAdapt(enabled: Boolean) {
        storage.putBoolean(KEY_APP_ICON_AUTO_ADAPT, enabled)
    }
    
    // People Counter Visibility Configuration
    fun isPeopleCounterVisible(): Boolean {
        return storage.getBoolean(KEY_PEOPLE_COUNTER_VISIBLE, true) // Visible by default (billeterie)
    }
    
    fun setPeopleCounterVisible(visible: Boolean) {
        storage.putBoolean(KEY_PEOPLE_COUNTER_VISIBLE, visible)
    }

    fun isBilleterieClockVisible(): Boolean {
        return storage.getBoolean(KEY_BILLETERIE_CLOCK_VISIBLE, true)
    }

    fun setBilleterieClockVisible(visible: Boolean) {
        storage.putBoolean(KEY_BILLETERIE_CLOCK_VISIBLE, visible)
    }

    /** Stable per-installation ID used for people-counter writer arbitration on Google Sheets. */
    fun getOrCreatePersistentDeviceId(): String {
        val existing = storage.getString(KEY_APP_DEVICE_NANOID, "").trim()
        if (existing.isNotEmpty()) return existing
        val created = NanoIdGenerator.generateGuestId()
        storage.putString(KEY_APP_DEVICE_NANOID, created)
        return created
    }

    fun getPeopleCounterSelectedVenueId(): Long {
        return storage.getLong(KEY_PEOPLE_COUNTER_SELECTED_VENUE_ID, 0L)
    }

    fun setPeopleCounterSelectedVenueId(id: Long) {
        storage.putLong(KEY_PEOPLE_COUNTER_SELECTED_VENUE_ID, id)
    }

    private fun ensurePeopleCounterPriorityVenuesMigrated() {
        if (storage.contains(KEY_PEOPLE_COUNTER_PRIORITY_VENUE_IDS)) {
            storage.remove(KEY_PEOPLE_COUNTER_USER_READ_ONLY_LEGACY)
            storage.remove(KEY_PEOPLE_COUNTER_PRIORITY)
            return
        }
        var venueIds: Set<String> = emptySet()
        when {
            storage.contains(KEY_PEOPLE_COUNTER_USER_READ_ONLY_LEGACY) -> {
                val legacyReadOnly = storage.getBoolean(KEY_PEOPLE_COUNTER_USER_READ_ONLY_LEGACY, true)
                if (!legacyReadOnly) {
                    val sel = storage.getLong(KEY_PEOPLE_COUNTER_SELECTED_VENUE_ID, 0L)
                    if (sel > 0L) venueIds = setOf(sel.toString())
                }
            }
            storage.contains(KEY_PEOPLE_COUNTER_PRIORITY) -> {
                if (storage.getBoolean(KEY_PEOPLE_COUNTER_PRIORITY, false)) {
                    val sel = storage.getLong(KEY_PEOPLE_COUNTER_SELECTED_VENUE_ID, 0L)
                    if (sel > 0L) venueIds = setOf(sel.toString())
                }
            }
        }
        storage.remove(KEY_PEOPLE_COUNTER_USER_READ_ONLY_LEGACY)
        storage.remove(KEY_PEOPLE_COUNTER_PRIORITY)
        storage.putStringSet(KEY_PEOPLE_COUNTER_PRIORITY_VENUE_IDS, HashSet(venueIds))
    }

    /**
     * When true, this device requests people-counter upload priority for [venueId] only.
     * Each venue is independent; default is off until the user enables priority for that venue.
     */
    fun isPeopleCounterPriority(venueId: Long): Boolean {
        if (venueId <= 0L) return false
        ensurePeopleCounterPriorityVenuesMigrated()
        val set = storage.getStringSet(KEY_PEOPLE_COUNTER_PRIORITY_VENUE_IDS, emptySet()) ?: emptySet()
        return set.contains(venueId.toString())
    }

    fun setPeopleCounterPriority(venueId: Long, enabled: Boolean) {
        if (venueId <= 0L) return
        ensurePeopleCounterPriorityVenuesMigrated()
        val raw = storage.getStringSet(KEY_PEOPLE_COUNTER_PRIORITY_VENUE_IDS, emptySet()) ?: emptySet()
        val next = HashSet(raw)
        if (enabled) next.add(venueId.toString()) else next.remove(venueId.toString())
        storage.putStringSet(KEY_PEOPLE_COUNTER_PRIORITY_VENUE_IDS, next)
    }
    
    // Statistics Visibility Configuration
    fun isStatisticsVisible(): Boolean {
        return storage.getBoolean(KEY_STATISTICS_VISIBLE, true) // Shown by default
    }
    
    fun setStatisticsVisible(visible: Boolean) {
        storage.putBoolean(KEY_STATISTICS_VISIBLE, visible)
    }

    // Announcements Configuration

    fun isAnnouncementsReceptionEnabled(): Boolean {
        return storage.getBoolean(KEY_ANNOUNCEMENTS_RECEPTION_ENABLED, true)
    }

    fun setAnnouncementsReceptionEnabled(enabled: Boolean) {
        storage.putBoolean(KEY_ANNOUNCEMENTS_RECEPTION_ENABLED, enabled)
    }

    fun getAnnouncementsTrackedVenueIds(): Set<String> {
        return storage.getStringSet(KEY_ANNOUNCEMENTS_TRACKED_VENUE_IDS, emptySet()) ?: emptySet()
    }

    fun setAnnouncementsTrackedVenueIds(venueIds: Set<String>) {
        storage.putStringSet(KEY_ANNOUNCEMENTS_TRACKED_VENUE_IDS, venueIds)
    }

    fun getAnnouncementsValidityMinutes(): Int {
        return storage.getInt(KEY_ANNOUNCEMENTS_VALIDITY_MINUTES, 60)
    }

    fun setAnnouncementsValidityMinutes(minutes: Int) {
        storage.putInt(KEY_ANNOUNCEMENTS_VALIDITY_MINUTES, minutes)
    }

    fun isAnnouncementsNonAdminSendEnabled(): Boolean {
        return storage.getBoolean(KEY_ANNOUNCEMENTS_NON_ADMIN_SEND_ENABLED, false)
    }

    fun setAnnouncementsNonAdminSendEnabled(enabled: Boolean) {
        storage.putBoolean(KEY_ANNOUNCEMENTS_NON_ADMIN_SEND_ENABLED, enabled)
        touchInstitutionSettingLastModified(InstitutionSettingsKeys.ANNOUNCEMENTS_NON_ADMIN_SEND_ENABLED)
    }

    fun isCategoryAnnouncementsExpanded(): Boolean {
        return storage.getBoolean(KEY_CATEGORY_ANNOUNCEMENTS_EXPANDED, false)
    }

    fun setCategoryAnnouncementsExpanded(expanded: Boolean) {
        storage.putBoolean(KEY_CATEGORY_ANNOUNCEMENTS_EXPANDED, expanded)
    }

    fun getAnnouncementsLastSeenTimestamps(): Map<String, Long> {
        val set = storage.getStringSet(KEY_ANNOUNCEMENTS_LAST_SEEN_TIMESTAMPS, emptySet()) ?: emptySet()
        return set.mapNotNull { entry ->
            val parts = entry.split(":", limit = 2)
            if (parts.size == 2) {
                parts[0] to (parts[1].toLongOrNull() ?: 0L)
            } else null
        }.toMap()
    }

    fun setAnnouncementLastSeenTimestamp(venueKey: String, timestamp: Long) {
        val set = storage.getStringSet(KEY_ANNOUNCEMENTS_LAST_SEEN_TIMESTAMPS, emptySet()) ?: emptySet()
        val mutable = HashSet(set)
        mutable.removeAll { it.startsWith("$venueKey:") }
        mutable.add("$venueKey:$timestamp")
        storage.putStringSet(KEY_ANNOUNCEMENTS_LAST_SEEN_TIMESTAMPS, mutable)
    }

    // Page Scroll Behavior Configuration
    // HEADER_PINNED   -> Only the list scrolls, header stays fixed
    // FULL_SCROLL     -> Header scrolls together with the list
    // STICKY_FILTERS  -> Header scrolls but filters become sticky at the top (default)
    fun getScrollBehavior(): String {
        // Check if new string setting exists
        val storedString = storage.getString("${KEY_PAGE_SCROLL_BEHAVIOR}_mode", "").ifEmpty { null }
        if (storedString != null) {
            return storedString
        }
        
        // Migrate old boolean setting if it was explicitly set
        // Note: storage.contains checks if the key was ever set by the user
        if (storage.contains(KEY_PAGE_SCROLL_BEHAVIOR)) {
            val oldValue = storage.getBoolean(KEY_PAGE_SCROLL_BEHAVIOR, true)
            return if (oldValue) HEADER_PINNED else FULL_SCROLL
        }
        
        // Default to STICKY_FILTERS for new users
        return STICKY_FILTERS
    }
    
    fun setScrollBehavior(behavior: String) {
        storage.putString("${KEY_PAGE_SCROLL_BEHAVIOR}_mode", behavior)
    }

    /** Desktop only: admin navigation placement (bottom, left, or right). */
    fun getDesktopAdminNavLayout(): String =
        storage.getString(KEY_DESKTOP_ADMIN_NAV_LAYOUT, "left") ?: "left"

    fun setDesktopAdminNavLayout(layout: String) {
        storage.putString(KEY_DESKTOP_ADMIN_NAV_LAYOUT, layout)
    }

    /** Desktop only: whether the side navigation rail shows labels (expanded) or icons only. */
    fun isDesktopAdminNavRailExpanded(): Boolean =
        storage.getBoolean(KEY_DESKTOP_ADMIN_NAV_RAIL_EXPANDED, true)

    fun setDesktopAdminNavRailExpanded(expanded: Boolean) {
        storage.putBoolean(KEY_DESKTOP_ADMIN_NAV_RAIL_EXPANDED, expanded)
    }
    
    // Update Manifest URL Configuration
    fun getUpdateManifestUrl(): String {
        return storage.getString(KEY_UPDATE_MANIFEST_URL, AppBuildInfo.UPDATE_MANIFEST_URL)
            ?: AppBuildInfo.UPDATE_MANIFEST_URL
    }

    fun saveUpdateManifestUrl(url: String) {
        storage.putString(KEY_UPDATE_MANIFEST_URL, url)
    }

    // Update Store URL Configuration
    fun getUpdateStoreUrl(): String {
        return storage.getString(KEY_UPDATE_STORE_URL, AppBuildInfo.UPDATE_FALLBACK_STORE_URL)
            ?: AppBuildInfo.UPDATE_FALLBACK_STORE_URL
    }

    fun saveUpdateStoreUrl(url: String) {
        storage.putString(KEY_UPDATE_STORE_URL, url)
    }
    
    /**
     * Returns animation intensity multiplier.
     * Always returns 1.0f - users can disable specific animations via their individual toggles.
     */
    fun getAnimationIntensityMultiplier(): Float {
        return 1.0f
    }
    
    // Email Settings Configuration
    // Note: Default values use resource IDs that should be resolved at runtime with context
    fun getEmailSubject(): String {
        return storage.getString(KEY_EMAIL_SUBJECT, "") ?: ""
    }
    
    fun saveEmailSubject(subject: String) {
        storage.putString(KEY_EMAIL_SUBJECT, subject)
        touchInstitutionSettingLastModified(InstitutionSettingsKeys.EMAIL_QR_SUBJECT)
    }
    
    fun getEmailContentBefore(): String {
        return storage.getString(KEY_EMAIL_CONTENT_BEFORE, "") ?: ""
    }
    
    fun saveEmailContentBefore(content: String) {
        storage.putString(KEY_EMAIL_CONTENT_BEFORE, content)
        touchInstitutionSettingLastModified(InstitutionSettingsKeys.EMAIL_QR_CONTENT_BEFORE)
    }
    
    fun isEmailIncludeQrEnabled(): Boolean {
        return storage.getBoolean(KEY_EMAIL_INCLUDE_QR, true) // Include QR by default
    }
    
    fun setEmailIncludeQrEnabled(enabled: Boolean) {
        storage.putBoolean(KEY_EMAIL_INCLUDE_QR, enabled)
        touchInstitutionSettingLastModified(InstitutionSettingsKeys.EMAIL_INCLUDE_QR)
    }
    
    fun getEmailContentAfter(): String {
        return storage.getString(KEY_EMAIL_CONTENT_AFTER, "") ?: ""
    }
    
    fun saveEmailContentAfter(content: String) {
        storage.putString(KEY_EMAIL_CONTENT_AFTER, content)
        touchInstitutionSettingLastModified(InstitutionSettingsKeys.EMAIL_QR_CONTENT_AFTER)
    }
    
    fun getEmailSignature(): String {
        return storage.getString(KEY_EMAIL_SIGNATURE, "") ?: ""
    }
    
    fun saveEmailSignature(signature: String) {
        storage.putString(KEY_EMAIL_SIGNATURE, signature)
        touchInstitutionSettingLastModified(InstitutionSettingsKeys.EMAIL_SIGNATURE)
    }
    
    fun isEmailIncludeLogoEnabled(): Boolean {
        return storage.getBoolean(KEY_EMAIL_INCLUDE_LOGO, false) // Logo disabled by default
    }
    
    fun setEmailIncludeLogoEnabled(enabled: Boolean) {
        storage.putBoolean(KEY_EMAIL_INCLUDE_LOGO, enabled)
    }

    fun isEmailIncludeDigitalWalletPassEnabled(): Boolean {
        return storage.getBoolean(KEY_EMAIL_INCLUDE_DIGITAL_WALLET_PASS, true)
    }

    fun setEmailIncludeDigitalWalletPassEnabled(enabled: Boolean) {
        storage.putBoolean(KEY_EMAIL_INCLUDE_DIGITAL_WALLET_PASS, enabled)
    }

    fun getWalletPassCertificatePassword(): String =
        SecureCredentialStoreHolder.get()?.getSecret(SecureCredentialKeys.WALLET_PASS_CERT_PASSWORD)
            ?: storage.getString(KEY_WALLET_PASS_CERT_PASSWORD, "") ?: ""

    fun saveWalletPassCertificatePassword(password: String) {
        SecureCredentialStoreHolder.get()?.putSecret(SecureCredentialKeys.WALLET_PASS_CERT_PASSWORD, password)
            ?: storage.putString(KEY_WALLET_PASS_CERT_PASSWORD, password)
        storage.remove(KEY_WALLET_PASS_CERT_PASSWORD)
    }

    fun getWalletPassTypeIdentifier(): String {
        return storage.getString(KEY_WALLET_PASS_TYPE_IDENTIFIER, "") ?: ""
    }

    fun saveWalletPassTypeIdentifier(identifier: String) {
        storage.putString(KEY_WALLET_PASS_TYPE_IDENTIFIER, identifier)
    }

    fun getWalletPassTeamIdentifier(): String {
        return storage.getString(KEY_WALLET_PASS_TEAM_IDENTIFIER, "") ?: ""
    }

    fun saveWalletPassTeamIdentifier(teamId: String) {
        storage.putString(KEY_WALLET_PASS_TEAM_IDENTIFIER, teamId)
    }

    fun getEmailAssociationName(): String {
        return storage.getString(KEY_EMAIL_ASSOCIATION_NAME, "Collectif Nocturne") ?: "Collectif Nocturne"
    }

    fun saveEmailAssociationName(name: String) {
        storage.putString(KEY_EMAIL_ASSOCIATION_NAME, name)
        touchInstitutionSettingLastModified(InstitutionSettingsKeys.EMAIL_ASSOCIATION_NAME)
    }
    
    fun getEmailLogoUri(): String {
        return storage.getString(KEY_EMAIL_LOGO_URI, "") ?: ""
    }
    
    fun saveEmailLogoUri(uri: String) {
        storage.putString(KEY_EMAIL_LOGO_URI, uri)
    }
    
    fun getGmailAccount(): String {
        return storage.getString(KEY_EMAIL_GMAIL_ACCOUNT, "") ?: ""
    }
    
    fun saveGmailAccount(account: String) {
        storage.putString(KEY_EMAIL_GMAIL_ACCOUNT, account)
    }
    
    fun getGmailAuthToken(): String =
        SecureCredentialStoreHolder.get()?.getSecret(SecureCredentialKeys.GMAIL_AUTH_TOKEN)
            ?: storage.getString(KEY_EMAIL_GMAIL_AUTH_TOKEN, "") ?: ""

    fun saveGmailAuthToken(token: String) {
        SecureCredentialStoreHolder.get()?.putSecret(SecureCredentialKeys.GMAIL_AUTH_TOKEN, token)
            ?: storage.putString(KEY_EMAIL_GMAIL_AUTH_TOKEN, token)
        storage.remove(KEY_EMAIL_GMAIL_AUTH_TOKEN)
    }
    
    fun clearGmailAuth() {
        storage.remove(KEY_EMAIL_GMAIL_ACCOUNT)
        SecureCredentialStoreHolder.get()?.removeSecret(SecureCredentialKeys.GMAIL_AUTH_TOKEN)
        storage.remove(KEY_EMAIL_GMAIL_AUTH_TOKEN)
    }

    fun isGmailUseServiceAccount(): Boolean {
        return storage.getBoolean(KEY_EMAIL_GMAIL_USE_SERVICE_ACCOUNT, false)
    }

    fun setGmailUseServiceAccount(enabled: Boolean) {
        storage.putBoolean(KEY_EMAIL_GMAIL_USE_SERVICE_ACCOUNT, enabled)
    }

    fun getGmailServiceAccountSenderEmail(): String {
        return storage.getString(KEY_EMAIL_GMAIL_SERVICE_ACCOUNT_SENDER, "") ?: ""
    }

    fun saveGmailServiceAccountSenderEmail(email: String) {
        storage.putString(KEY_EMAIL_GMAIL_SERVICE_ACCOUNT_SENDER, email.trim())
    }
    
    fun isCategoryEmailExpanded(): Boolean {
        return storage.getBoolean(KEY_CATEGORY_EMAIL_EXPANDED, false)
    }
    
    fun setCategoryEmailExpanded(expanded: Boolean) {
        storage.putBoolean(KEY_CATEGORY_EMAIL_EXPANDED, expanded)
    }
    
    // Guest Email Settings Configuration
    fun getGuestEmailSubject(): String {
        return storage.getString(KEY_GUEST_EMAIL_SUBJECT, "") ?: ""
    }
    
    fun saveGuestEmailSubject(subject: String) {
        storage.putString(KEY_GUEST_EMAIL_SUBJECT, subject)
        touchInstitutionSettingLastModified(InstitutionSettingsKeys.GUEST_EMAIL_SUBJECT)
    }
    
    fun getGuestEmailContentBefore(): String {
        return storage.getString(KEY_GUEST_EMAIL_CONTENT_BEFORE, "") ?: ""
    }
    
    fun saveGuestEmailContentBefore(content: String) {
        storage.putString(KEY_GUEST_EMAIL_CONTENT_BEFORE, content)
        touchInstitutionSettingLastModified(InstitutionSettingsKeys.GUEST_EMAIL_CONTENT_BEFORE)
    }
    
    fun isGuestEmailIncludeQrEnabled(): Boolean {
        return storage.getBoolean(KEY_GUEST_EMAIL_INCLUDE_QR, true) // Include QR by default
    }
    
    fun setGuestEmailIncludeQrEnabled(enabled: Boolean) {
        storage.putBoolean(KEY_GUEST_EMAIL_INCLUDE_QR, enabled)
        touchInstitutionSettingLastModified(InstitutionSettingsKeys.GUEST_EMAIL_INCLUDE_QR)
    }
    
    fun getGuestEmailContentAfter(): String {
        return storage.getString(KEY_GUEST_EMAIL_CONTENT_AFTER, "") ?: ""
    }
    
    fun saveGuestEmailContentAfter(content: String) {
        storage.putString(KEY_GUEST_EMAIL_CONTENT_AFTER, content)
        touchInstitutionSettingLastModified(InstitutionSettingsKeys.GUEST_EMAIL_CONTENT_AFTER)
    }
    
    fun getGuestEmailSignature(): String {
        return storage.getString(KEY_GUEST_EMAIL_SIGNATURE, "") ?: ""
    }
    
    fun saveGuestEmailSignature(signature: String) {
        storage.putString(KEY_GUEST_EMAIL_SIGNATURE, signature)
    }
    
    // Biometric Admin Login Configuration
    fun isBiometricAdminLoginEnabled(): Boolean {
        if (!storage.getBoolean(KEY_BIOMETRIC_ADMIN_LOGIN, false)) return false
        return if (getBackendType() == com.eventmanager.app.data.remote.BackendType.FIREBASE) {
            getBiometricEnrollments().isNotEmpty() || getBiometricAdminProfileLink() != null
        } else {
            getBiometricAdminProfileLink() != null
        }
    }

    fun isBiometricAdminLoginEnabledForOrg(orgId: String): Boolean {
        if (!storage.getBoolean(KEY_BIOMETRIC_ADMIN_LOGIN, false)) return false
        return if (getBackendType() == com.eventmanager.app.data.remote.BackendType.FIREBASE) {
            getBiometricEnrollmentForOrg(orgId) != null
        } else {
            getBiometricAdminProfileLink() != null
        }
    }

    fun getBiometricEnrollments(): List<BiometricAdminOrgEnrollment> {
        migrateLegacyBiometricEnrollmentIfNeeded()
        return BiometricAdminOrgEnrollment.decodeList(
            storage.getString(KEY_BIOMETRIC_ADMIN_ENROLLMENTS, "")
        )
    }

    fun getBiometricEnrollmentForOrg(orgId: String): BiometricAdminProfileLink? {
        val trimmed = orgId.trim()
        if (trimmed.isBlank()) return null
        if (getBackendType() != com.eventmanager.app.data.remote.BackendType.FIREBASE) {
            return getBiometricAdminProfileLink()
        }
        getBiometricEnrollments().firstOrNull { it.orgId == trimmed }?.link?.let { return it }
        if (getFirebaseConfiguredOrgs().size <= 1) {
            return getBiometricEnrollments().firstOrNull()?.link ?: getBiometricAdminProfileLink()
        }
        return getBiometricAdminProfileLink()
    }

    fun getBiometricAdminProfileLink(): BiometricAdminProfileLink? {
        val raw = storage.getString(KEY_BIOMETRIC_ADMIN_PROFILE_LINK, "")
        return BiometricAdminProfileLink.decode(raw.takeIf { it.isNotBlank() })
    }

    fun setBiometricAdminLoginEnabled(enabled: Boolean) {
        storage.putBoolean(KEY_BIOMETRIC_ADMIN_LOGIN, enabled)
        if (!enabled) {
            storage.remove(KEY_BIOMETRIC_ADMIN_PROFILE_LINK)
            storage.remove(KEY_BIOMETRIC_ADMIN_ENROLLMENTS)
        }
    }

    fun setBiometricEnrollment(orgId: String, link: BiometricAdminProfileLink) {
        val trimmed = orgId.trim()
        require(trimmed.isNotBlank()) { "Org ID required for biometric enrollment" }
        val updated = getBiometricEnrollments()
            .filter { it.orgId != trimmed } +
            BiometricAdminOrgEnrollment(trimmed, link)
        storage.putString(KEY_BIOMETRIC_ADMIN_ENROLLMENTS, BiometricAdminOrgEnrollment.encodeList(updated))
        storage.putBoolean(KEY_BIOMETRIC_ADMIN_LOGIN, true)
        storage.remove(KEY_BIOMETRIC_ADMIN_PROFILE_LINK)
    }

    fun setBiometricEnrollments(enrollments: List<BiometricAdminOrgEnrollment>) {
        storage.putString(KEY_BIOMETRIC_ADMIN_ENROLLMENTS, BiometricAdminOrgEnrollment.encodeList(enrollments))
        storage.putBoolean(KEY_BIOMETRIC_ADMIN_LOGIN, enrollments.isNotEmpty())
        if (enrollments.isNotEmpty()) {
            storage.remove(KEY_BIOMETRIC_ADMIN_PROFILE_LINK)
        }
    }

    fun removeBiometricEnrollment(orgId: String) {
        val trimmed = orgId.trim()
        val updated = getBiometricEnrollments().filter { it.orgId != trimmed }
        if (updated.isEmpty()) {
            setBiometricAdminLoginEnabled(false)
        } else {
            storage.putString(KEY_BIOMETRIC_ADMIN_ENROLLMENTS, BiometricAdminOrgEnrollment.encodeList(updated))
        }
    }

    fun setBiometricAdminProfileLink(link: BiometricAdminProfileLink) {
        if (getBackendType() == com.eventmanager.app.data.remote.BackendType.FIREBASE) {
            val orgId = getFirebaseOrgId().trim().ifBlank { getFirebaseLastSingleOrgId().trim() }
            if (orgId.isNotBlank()) {
                setBiometricEnrollment(orgId, link)
                return
            }
        }
        storage.putString(KEY_BIOMETRIC_ADMIN_PROFILE_LINK, link.encode())
        storage.putBoolean(KEY_BIOMETRIC_ADMIN_LOGIN, true)
    }

    private fun migrateLegacyBiometricEnrollmentIfNeeded() {
        val legacyRaw = storage.getString(KEY_BIOMETRIC_ADMIN_PROFILE_LINK, "")?.takeIf { it.isNotBlank() } ?: return
        val link = BiometricAdminProfileLink.decode(legacyRaw) ?: return
        val existing = BiometricAdminOrgEnrollment.decodeList(
            storage.getString(KEY_BIOMETRIC_ADMIN_ENROLLMENTS, "")
        )
        if (existing.isNotEmpty()) {
            storage.remove(KEY_BIOMETRIC_ADMIN_PROFILE_LINK)
            return
        }
        val orgId = getFirebaseOrgId().trim().ifBlank { getFirebaseLastSingleOrgId().trim() }
        if (orgId.isBlank()) return
        storage.putString(
            KEY_BIOMETRIC_ADMIN_ENROLLMENTS,
            BiometricAdminOrgEnrollment.encodeList(listOf(BiometricAdminOrgEnrollment(orgId, link)))
        )
        storage.remove(KEY_BIOMETRIC_ADMIN_PROFILE_LINK)
    }
    
    // Clear all settings
    fun clearAllSettings() {
        storage.clear()
        SecureCredentialStoreHolder.clearAll()
    }
    
    // Check if settings are configured
    fun isConfigured(): Boolean {
        val spreadsheetId = getSpreadsheetId()
        return spreadsheetId.isNotEmpty() && spreadsheetId != GoogleSheetsConfig.SPREADSHEET_ID
    }
    
    // Settings Category Expansion State
    fun isCategorySyncExpanded(): Boolean {
        return storage.getBoolean(KEY_CATEGORY_SYNC_EXPANDED, false)
    }
    
    fun setCategorySyncExpanded(expanded: Boolean) {
        storage.putBoolean(KEY_CATEGORY_SYNC_EXPANDED, expanded)
    }
    
    fun isCategoryAppearanceExpanded(): Boolean {
        return storage.getBoolean(KEY_CATEGORY_APPEARANCE_EXPANDED, false)
    }
    
    fun setCategoryAppearanceExpanded(expanded: Boolean) {
        storage.putBoolean(KEY_CATEGORY_APPEARANCE_EXPANDED, expanded)
    }
    
    fun isCategoryLocalizationExpanded(): Boolean {
        return storage.getBoolean(KEY_CATEGORY_LOCALIZATION_EXPANDED, false)
    }
    
    fun setCategoryLocalizationExpanded(expanded: Boolean) {
        storage.putBoolean(KEY_CATEGORY_LOCALIZATION_EXPANDED, expanded)
    }
    
    fun isCategoryAnimationExpanded(): Boolean {
        return storage.getBoolean(KEY_CATEGORY_ANIMATION_EXPANDED, false)
    }
    
    fun setCategoryAnimationExpanded(expanded: Boolean) {
        storage.putBoolean(KEY_CATEGORY_ANIMATION_EXPANDED, expanded)
    }
    
    fun isCategoryDeveloperExpanded(): Boolean {
        return storage.getBoolean(KEY_CATEGORY_DEVELOPER_EXPANDED, false)
    }
    
    fun setCategoryDeveloperExpanded(expanded: Boolean) {
        storage.putBoolean(KEY_CATEGORY_DEVELOPER_EXPANDED, expanded)
    }
    
    fun isCategoryMaintenanceExpanded(): Boolean {
        return storage.getBoolean(KEY_CATEGORY_MAINTENANCE_EXPANDED, false)
    }
    
    fun setCategoryMaintenanceExpanded(expanded: Boolean) {
        storage.putBoolean(KEY_CATEGORY_MAINTENANCE_EXPANDED, expanded)
    }

    fun isCategoryExternalReaderExpanded(): Boolean {
        return storage.getBoolean(KEY_CATEGORY_EXTERNAL_READER_EXPANDED, false)
    }

    fun setCategoryExternalReaderExpanded(expanded: Boolean) {
        storage.putBoolean(KEY_CATEGORY_EXTERNAL_READER_EXPANDED, expanded)
    }

    /**
     * MAC address of the BLE NFC reader (ACR1255U-J1) the user selected from the in-app scanner.
     * Empty string means "no reader chosen yet" — fall back to bonded-device lookup.
     */
    fun getExternalBleReaderMac(): String {
        return storage.getString(KEY_EXTERNAL_BLE_READER_MAC, "") ?: ""
    }

    fun saveExternalBleReaderMac(mac: String) {
        storage.putString(KEY_EXTERNAL_BLE_READER_MAC, mac)
    }

    fun getExternalBleReaderName(): String {
        return storage.getString(KEY_EXTERNAL_BLE_READER_NAME, "") ?: ""
    }

    fun saveExternalBleReaderName(name: String) {
        storage.putString(KEY_EXTERNAL_BLE_READER_NAME, name)
    }

    fun clearExternalBleReader() {
        storage.remove(KEY_EXTERNAL_BLE_READER_MAC)
        storage.remove(KEY_EXTERNAL_BLE_READER_NAME)
    }
    
    fun isSetupWizardCompleted(): Boolean {
        return storage.getBoolean(KEY_SETUP_WIZARD_COMPLETED, false)
    }
    
    fun setSetupWizardCompleted(completed: Boolean) {
        storage.putBoolean(KEY_SETUP_WIZARD_COMPLETED, completed)
    }
    
    fun shouldShowSetupWizard(): Boolean {
        return !isSetupWizardCompleted() && !isConfigured()
    }
}
