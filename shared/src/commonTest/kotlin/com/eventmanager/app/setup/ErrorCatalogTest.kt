package com.eventmanager.app.setup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ErrorCatalogTest {
    private fun check(
        id: SetupErrorId,
        output: String,
        step: SetupStepId = SetupStepId.SERVER,
        retry: RetryPolicy = RetryPolicy.NONE,
        repair: Repair? = null,
        link: String? = null,
        projectId: String = "demo-1",
        api: GoogleApiStatus? = null,
    ) {
        val e = ErrorCatalog.classify(output, step, projectId, api)
        assertEquals(id, e.id)
        assertEquals(retry, e.retry)
        assertEquals(repair, e.repair)
        assertEquals(link, e.link)
        assertEquals(step, e.step)
        assertEquals(output, e.details)
    }

    private val functionsConsole = "https://console.cloud.google.com/functions/list?project=demo-1"
    private val iam = "https://console.cloud.google.com/iam-admin/iam?project=demo-1"

    @Test fun workdir() = check(SetupErrorId.WORKDIR_NOT_WRITABLE, SetupErrorFixtures.WORKDIR)

    @Test fun httpsToBackground() = check(
        SetupErrorId.FUNCTIONS_FAILED_STATE, SetupErrorFixtures.HTTPS_TO_BACKGROUND,
        retry = RetryPolicy.AFTER_REPAIR, repair = Repair.DELETE_FAILED_FUNCTIONS, link = functionsConsole,
    )

    @Test fun buildSa() = check(
        SetupErrorId.BUILD_SA_PERMISSION, SetupErrorFixtures.BUILD_SA,
        retry = RetryPolicy.AFTER_REPAIR, repair = Repair.GRANT_BUILD_ROLE, link = iam,
    )

    @Test fun buildSaWinsOverPermissionDenied() {
        assertTrue(SetupErrorFixtures.BUILD_SA.contains("permission"))
        assertEquals(SetupErrorId.BUILD_SA_PERMISSION, ErrorCatalog.classify(SetupErrorFixtures.BUILD_SA + "\nHTTP Error: 403", SetupStepId.SERVER).id)
    }

    @Test fun eventarc() = check(
        SetupErrorId.EVENTARC_NOT_READY, "Permission denied while using the Eventarc Service Agent.",
        retry = RetryPolicy.WAIT_AND_RETRY,
    )

    @Test fun eventarcFirstTime() = check(
        SetupErrorId.EVENTARC_NOT_READY, "Since this is your first time using 2nd gen functions, retry",
        retry = RetryPolicy.WAIT_AND_RETRY,
    )

    @Test fun billing() = check(
        SetupErrorId.BILLING_REQUIRED, "Error: Your project must be on the Blaze (pay-as-you-go) plan",
        link = "https://console.firebase.google.com/project/demo-1/usage/details",
    )

    @Test fun billingWithoutProjectHasNoLink() = check(
        SetupErrorId.BILLING_REQUIRED, "Blaze plan needed", projectId = "", link = null,
    )

    @Test fun apiNotEnabled() = check(
        SetupErrorId.API_NOT_ENABLED, "Cloud Run API has not been used in project 1 before or it is disabled.",
        retry = RetryPolicy.AFTER_REPAIR, repair = Repair.ENABLE_APIS,
    )

    @Test fun apiReasonServiceDisabledIsApiNotEnabled() = check(
        SetupErrorId.API_NOT_ENABLED, "",
        retry = RetryPolicy.AFTER_REPAIR, repair = Repair.ENABLE_APIS,
        api = GoogleApiStatus(403, "SERVICE_DISABLED", "run.googleapis.com"),
    )

    @Test fun storageNotReady() = check(
        SetupErrorId.STORAGE_NOT_READY, "Error: Firebase Storage has not been set up on project 'x'.",
        link = "https://console.firebase.google.com/project/demo-1/storage",
    )

    @Test fun noHostingSite() = check(
        SetupErrorId.NO_HOSTING_SITE, "Error: Failed to get Hosting site: there is no Hosting site",
        retry = RetryPolicy.AFTER_REPAIR, repair = Repair.CREATE_HOSTING_SITE,
    )

    @Test fun terms() = check(
        SetupErrorId.TERMS_NOT_ACCEPTED, "You must accept the Terms of Service first",
        link = "https://console.firebase.google.com/",
    )

    @Test fun projectIdTaken() = check(SetupErrorId.PROJECT_ID_TAKEN, "ALREADY_EXISTS: project", step = SetupStepId.PROJECT)

    @Test fun projectIdTakenOnlyInProjectStep() {
        assertEquals(SetupErrorId.UNKNOWN, ErrorCatalog.classify("thing already exists", SetupStepId.RULES).id)
    }

    @Test fun projectQuota() = check(
        SetupErrorId.PROJECT_QUOTA, "Error: project quota reached", step = SetupStepId.PROJECT,
        link = "https://support.google.com/code/contact/project_quota_increase",
    )

    @Test fun noProjectCreator() = check(
        SetupErrorId.NO_PROJECT_CREATOR, "caller lacks resourcemanager.projects.create", step = SetupStepId.PROJECT,
    )

    @Test fun noProjectCreator403() = check(SetupErrorId.NO_PROJECT_CREATOR, "HTTP Error: 403", step = SetupStepId.PROJECT)

    @Test fun datastoreMode() = check(
        SetupErrorId.DATASTORE_MODE, "The database is in datastore mode",
        link = "https://console.firebase.google.com/project/demo-1/firestore",
    )

    @Test fun locationInvalid() = check(
        SetupErrorId.LOCATION_INVALID, "Error: location is not supported", step = SetupStepId.DATABASE,
    )

    @Test fun loginExpired() {
        for (text in listOf(
            "Your credentials are no longer valid", "invalid_grant", "Error: Failed to authenticate, have you run firebase login?",
            "Error: not logged in",
        )) check(SetupErrorId.LOGIN_EXPIRED, text, repair = Repair.RELOGIN)
    }

    @Test fun loginCodeInvalid() = check(
        SetupErrorId.LOGIN_CODE_INVALID, "Unable to authenticate using the provided code", step = SetupStepId.LOGIN,
        repair = Repair.RELOGIN,
    )

    @Test fun secretRefused() = check(
        SetupErrorId.SECRET_REFUSED, "secretmanager.googleapis.com returned 403",
        retry = RetryPolicy.AFTER_REPAIR, repair = Repair.ENABLE_APIS,
    )

    @Test fun npmInstall() {
        check(SetupErrorId.NPM_INSTALL_FAILED, "npm ERR! code E404", retry = RetryPolicy.WAIT_AND_RETRY)
        check(SetupErrorId.NPM_INSTALL_FAILED, "npm error code E404", retry = RetryPolicy.WAIT_AND_RETRY)
    }

    @Test fun transientListFunctions() = check(
        SetupErrorId.TRANSIENT, SetupErrorFixtures.LIST_FUNCTIONS, retry = RetryPolicy.WAIT_AND_RETRY,
    )

    @Test fun transient5xx() = check(SetupErrorId.TRANSIENT, "had HTTP Error: 503", retry = RetryPolicy.WAIT_AND_RETRY)

    @Test fun transientRegions() = check(
        SetupErrorId.TRANSIENT, "unable to reach the following regions: x", retry = RetryPolicy.WAIT_AND_RETRY,
    )

    @Test fun quota() {
        check(SetupErrorId.QUOTA, "Quota exceeded", retry = RetryPolicy.WAIT_AND_RETRY)
        check(SetupErrorId.QUOTA, "HTTP Error: 429", retry = RetryPolicy.WAIT_AND_RETRY)
    }

    @Test fun permissionDenied() {
        check(SetupErrorId.PERMISSION_DENIED, "HTTP Error: 403, nope", link = iam)
        check(SetupErrorId.PERMISSION_DENIED, "permission denied", link = iam)
        check(SetupErrorId.PERMISSION_DENIED, "PERMISSION_DENIED", link = iam)
    }

    @Test fun network() = check(SetupErrorId.NETWORK, "getaddrinfo ENOTFOUND x", retry = RetryPolicy.WAIT_AND_RETRY)

    @Test fun unknownKeepsHeadline() {
        val e = ErrorCatalog.classify("Error: Something new", SetupStepId.RULES)
        assertEquals(SetupErrorId.UNKNOWN, e.id)
        assertEquals("Something new", e.headline)
        assertNull(e.link)
    }

    @Test fun headlineIsTheRealErrorLine() {
        assertTrue(
            ErrorCatalog.headline(SetupErrorFixtures.HTTPS_TO_BACKGROUND)
                .startsWith("[noctulist:onMailOutboxWritten(europe-west6)] Changing from an HTTPS function"),
        )
    }

    @Test fun headlineFallsBackToJsonMessageThenLastLine() {
        assertEquals("Bad thing", ErrorCatalog.headline("""{"error": {"code": 400, "message": "Bad thing"}}"""))
        assertEquals("last", ErrorCatalog.headline("first\n\nlast\n\n"))
        assertEquals(300, ErrorCatalog.headline("Error: " + "x".repeat(500)).length)
    }

    @Test fun linksDropTheProjectWhenBlank() {
        assertEquals(
            "https://console.cloud.google.com/functions/list",
            ErrorCatalog.classify(SetupErrorFixtures.HTTPS_TO_BACKGROUND, SetupStepId.SERVER).link,
        )
    }
}
