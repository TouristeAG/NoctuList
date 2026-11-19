package com.eventmanager.app.ui.setup

import com.eventmanager.app.resources.*
import com.eventmanager.app.setup.Repair
import com.eventmanager.app.setup.SetupErrorId
import com.eventmanager.app.setup.SetupStepId
import org.jetbrains.compose.resources.StringResource

fun errorTitle(id: SetupErrorId): StringResource = when (id) {
    SetupErrorId.WORKDIR_NOT_WRITABLE -> Res.string.setup_err_workdir_not_writable_title
    SetupErrorId.TOOLS_BROKEN -> Res.string.setup_err_tools_broken_title
    SetupErrorId.DOWNLOAD_FAILED -> Res.string.setup_err_download_failed_title
    SetupErrorId.FILE_LOCKED -> Res.string.setup_err_file_locked_title
    SetupErrorId.DISK_FULL -> Res.string.setup_err_disk_full_title
    SetupErrorId.LOGIN_CODE_INVALID -> Res.string.setup_err_login_code_invalid_title
    SetupErrorId.LOGIN_EXPIRED -> Res.string.setup_err_login_expired_title
    SetupErrorId.PROJECT_ID_TAKEN -> Res.string.setup_err_project_id_taken_title
    SetupErrorId.PROJECT_QUOTA -> Res.string.setup_err_project_quota_title
    SetupErrorId.TERMS_NOT_ACCEPTED -> Res.string.setup_err_terms_not_accepted_title
    SetupErrorId.NO_PROJECT_CREATOR -> Res.string.setup_err_no_project_creator_title
    SetupErrorId.API_NOT_ENABLED -> Res.string.setup_err_api_not_enabled_title
    SetupErrorId.DATASTORE_MODE -> Res.string.setup_err_datastore_mode_title
    SetupErrorId.LOCATION_INVALID -> Res.string.setup_err_location_invalid_title
    SetupErrorId.PERMISSION_DENIED -> Res.string.setup_err_permission_denied_title
    SetupErrorId.NO_HOSTING_SITE -> Res.string.setup_err_no_hosting_site_title
    SetupErrorId.STORAGE_NOT_READY -> Res.string.setup_err_storage_not_ready_title
    SetupErrorId.BILLING_REQUIRED -> Res.string.setup_err_billing_required_title
    SetupErrorId.BUILD_SA_PERMISSION -> Res.string.setup_err_build_sa_permission_title
    SetupErrorId.EVENTARC_NOT_READY -> Res.string.setup_err_eventarc_not_ready_title
    SetupErrorId.TRANSIENT -> Res.string.setup_err_transient_title
    SetupErrorId.FUNCTIONS_FAILED_STATE -> Res.string.setup_err_functions_failed_state_title
    SetupErrorId.NPM_INSTALL_FAILED -> Res.string.setup_err_npm_install_failed_title
    SetupErrorId.SECRET_REFUSED -> Res.string.setup_err_secret_refused_title
    SetupErrorId.MAIL_AUTH_REFUSED -> Res.string.setup_err_mail_auth_refused_title
    SetupErrorId.MAIL_DELEGATION_MISSING -> Res.string.setup_err_mail_delegation_missing_title
    SetupErrorId.MAIL_TEST_FAILED -> Res.string.setup_err_mail_test_failed_title
    SetupErrorId.MAIL_TEST_UNAVAILABLE -> Res.string.setup_err_mail_test_unavailable_title
    SetupErrorId.MAIL_TEST_NO_ORGANIZATION -> Res.string.setup_err_mail_test_no_organization_title
    SetupErrorId.TIMEOUT -> Res.string.setup_err_timeout_title
    SetupErrorId.QUOTA -> Res.string.setup_err_quota_title
    SetupErrorId.NETWORK -> Res.string.setup_err_network_title
    SetupErrorId.UNKNOWN -> Res.string.setup_err_unknown_title
}

fun errorCause(id: SetupErrorId): StringResource = when (id) {
    SetupErrorId.WORKDIR_NOT_WRITABLE -> Res.string.setup_err_workdir_not_writable_cause
    SetupErrorId.TOOLS_BROKEN -> Res.string.setup_err_tools_broken_cause
    SetupErrorId.DOWNLOAD_FAILED -> Res.string.setup_err_download_failed_cause
    SetupErrorId.FILE_LOCKED -> Res.string.setup_err_file_locked_cause
    SetupErrorId.DISK_FULL -> Res.string.setup_err_disk_full_cause
    SetupErrorId.LOGIN_CODE_INVALID -> Res.string.setup_err_login_code_invalid_cause
    SetupErrorId.LOGIN_EXPIRED -> Res.string.setup_err_login_expired_cause
    SetupErrorId.PROJECT_ID_TAKEN -> Res.string.setup_err_project_id_taken_cause
    SetupErrorId.PROJECT_QUOTA -> Res.string.setup_err_project_quota_cause
    SetupErrorId.TERMS_NOT_ACCEPTED -> Res.string.setup_err_terms_not_accepted_cause
    SetupErrorId.NO_PROJECT_CREATOR -> Res.string.setup_err_no_project_creator_cause
    SetupErrorId.API_NOT_ENABLED -> Res.string.setup_err_api_not_enabled_cause
    SetupErrorId.DATASTORE_MODE -> Res.string.setup_err_datastore_mode_cause
    SetupErrorId.LOCATION_INVALID -> Res.string.setup_err_location_invalid_cause
    SetupErrorId.PERMISSION_DENIED -> Res.string.setup_err_permission_denied_cause
    SetupErrorId.NO_HOSTING_SITE -> Res.string.setup_err_no_hosting_site_cause
    SetupErrorId.STORAGE_NOT_READY -> Res.string.setup_err_storage_not_ready_cause
    SetupErrorId.BILLING_REQUIRED -> Res.string.setup_err_billing_required_cause
    SetupErrorId.BUILD_SA_PERMISSION -> Res.string.setup_err_build_sa_permission_cause
    SetupErrorId.EVENTARC_NOT_READY -> Res.string.setup_err_eventarc_not_ready_cause
    SetupErrorId.TRANSIENT -> Res.string.setup_err_transient_cause
    SetupErrorId.FUNCTIONS_FAILED_STATE -> Res.string.setup_err_functions_failed_state_cause
    SetupErrorId.NPM_INSTALL_FAILED -> Res.string.setup_err_npm_install_failed_cause
    SetupErrorId.SECRET_REFUSED -> Res.string.setup_err_secret_refused_cause
    SetupErrorId.MAIL_AUTH_REFUSED -> Res.string.setup_err_mail_auth_refused_cause
    SetupErrorId.MAIL_DELEGATION_MISSING -> Res.string.setup_err_mail_delegation_missing_cause
    SetupErrorId.MAIL_TEST_FAILED -> Res.string.setup_err_mail_test_failed_cause
    SetupErrorId.MAIL_TEST_UNAVAILABLE -> Res.string.setup_err_mail_test_unavailable_cause
    SetupErrorId.MAIL_TEST_NO_ORGANIZATION -> Res.string.setup_err_mail_test_no_organization_cause
    SetupErrorId.TIMEOUT -> Res.string.setup_err_timeout_cause
    SetupErrorId.QUOTA -> Res.string.setup_err_quota_cause
    SetupErrorId.NETWORK -> Res.string.setup_err_network_cause
    SetupErrorId.UNKNOWN -> Res.string.setup_err_unknown_cause
}

/** What to do, as shown on [step]'s card: on the sign-in step itself, Retry is what signs in again. */
fun errorSteps(id: SetupErrorId, step: SetupStepId): StringResource =
    if (id == SetupErrorId.LOGIN_EXPIRED && step == SetupStepId.LOGIN) Res.string.setup_err_login_expired_steps_login else errorSteps(id)

fun errorSteps(id: SetupErrorId): StringResource = when (id) {
    SetupErrorId.WORKDIR_NOT_WRITABLE -> Res.string.setup_err_workdir_not_writable_steps
    SetupErrorId.TOOLS_BROKEN -> Res.string.setup_err_tools_broken_steps
    SetupErrorId.DOWNLOAD_FAILED -> Res.string.setup_err_download_failed_steps
    SetupErrorId.FILE_LOCKED -> Res.string.setup_err_file_locked_steps
    SetupErrorId.DISK_FULL -> Res.string.setup_err_disk_full_steps
    SetupErrorId.LOGIN_CODE_INVALID -> Res.string.setup_err_login_code_invalid_steps
    SetupErrorId.LOGIN_EXPIRED -> Res.string.setup_err_login_expired_steps
    SetupErrorId.PROJECT_ID_TAKEN -> Res.string.setup_err_project_id_taken_steps
    SetupErrorId.PROJECT_QUOTA -> Res.string.setup_err_project_quota_steps
    SetupErrorId.TERMS_NOT_ACCEPTED -> Res.string.setup_err_terms_not_accepted_steps
    SetupErrorId.NO_PROJECT_CREATOR -> Res.string.setup_err_no_project_creator_steps
    SetupErrorId.API_NOT_ENABLED -> Res.string.setup_err_api_not_enabled_steps
    SetupErrorId.DATASTORE_MODE -> Res.string.setup_err_datastore_mode_steps
    SetupErrorId.LOCATION_INVALID -> Res.string.setup_err_location_invalid_steps
    SetupErrorId.PERMISSION_DENIED -> Res.string.setup_err_permission_denied_steps
    SetupErrorId.NO_HOSTING_SITE -> Res.string.setup_err_no_hosting_site_steps
    SetupErrorId.STORAGE_NOT_READY -> Res.string.setup_err_storage_not_ready_steps
    SetupErrorId.BILLING_REQUIRED -> Res.string.setup_err_billing_required_steps
    SetupErrorId.BUILD_SA_PERMISSION -> Res.string.setup_err_build_sa_permission_steps
    SetupErrorId.EVENTARC_NOT_READY -> Res.string.setup_err_eventarc_not_ready_steps
    SetupErrorId.TRANSIENT -> Res.string.setup_err_transient_steps
    SetupErrorId.FUNCTIONS_FAILED_STATE -> Res.string.setup_err_functions_failed_state_steps
    SetupErrorId.NPM_INSTALL_FAILED -> Res.string.setup_err_npm_install_failed_steps
    SetupErrorId.SECRET_REFUSED -> Res.string.setup_err_secret_refused_steps
    SetupErrorId.MAIL_AUTH_REFUSED -> Res.string.setup_err_mail_auth_refused_steps
    SetupErrorId.MAIL_DELEGATION_MISSING -> Res.string.setup_err_mail_delegation_missing_steps
    SetupErrorId.MAIL_TEST_FAILED -> Res.string.setup_err_mail_test_failed_steps
    SetupErrorId.MAIL_TEST_UNAVAILABLE -> Res.string.setup_err_mail_test_unavailable_steps
    SetupErrorId.MAIL_TEST_NO_ORGANIZATION -> Res.string.setup_err_mail_test_no_organization_steps
    SetupErrorId.TIMEOUT -> Res.string.setup_err_timeout_steps
    SetupErrorId.QUOTA -> Res.string.setup_err_quota_steps
    SetupErrorId.NETWORK -> Res.string.setup_err_network_steps
    SetupErrorId.UNKNOWN -> Res.string.setup_err_unknown_steps
}

/** Past tense, one short line: what the app already did for the admin. */
fun repairDone(repair: Repair): StringResource = when (repair) {
    Repair.ENABLE_APIS -> Res.string.setup_repair_enable_apis
    Repair.GRANT_BUILD_ROLE -> Res.string.setup_repair_grant_build_role
    Repair.DELETE_FAILED_FUNCTIONS -> Res.string.setup_repair_delete_failed_functions
    Repair.CREATE_HOSTING_SITE -> Res.string.setup_repair_create_hosting_site
    Repair.REINSTALL_TOOLS -> Res.string.setup_repair_reinstall_tools
    Repair.RELOGIN -> Res.string.setup_repair_relogin
}
