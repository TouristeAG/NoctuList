package com.eventmanager.app.setup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliErrorClassifierTest {
    private val secretManager403 = "Error: Request to https://secretmanager.googleapis.com/v1/projects/noctuliste---lethal-tempo/secrets/GMAIL_APP_PASSWORD had HTTP Error: 403, Secret Manager API has not been used in project noctuliste---lethal-tempo before or it is disabled. Enable it by visiting https://console.developers.google.com/apis/api/secretmanager.googleapis.com/overview?project=noctuliste---lethal-tempo then retry. If you enabled this API recently, wait a few minutes for the action to propagate to our systems and retry."

    private val eventarc = "⚠  functions: Request to https://cloudfunctions.googleapis.com/v2/projects/noctuliste---lethal-tempo/locations/europe-west6/functions?functionId=onPortalRequestWritten had HTTP Error: 400, Validation failed for trigger projects/noctuliste---lethal-tempo/locations/europe-west6/triggers/onportalrequestwritten-822679: Invalid resource state for \"\": Permission denied while using the Eventarc Service Agent. If you recently started to use Eventarc, it may take a few minutes before all necessary permissions are propagated to the Service Agent. Otherwise, verify that it has Eventarc Service Agent role.\n⚠  functions: Since this is your first time using 2nd gen functions, we need a little bit longer to finish setting everything up. Retry the deployment in a few minutes."

    @Test
    fun secretManagerDisabledIsRetryableApiNotEnabled() {
        val e = CliErrorClassifier.classify(secretManager403, SetupStepId.SERVER)
        assertEquals(CliErrorKind.API_NOT_ENABLED, e.kind)
        assertTrue(e.retryable)
        assertEquals(SetupStepId.SERVER, e.manualAnchor)
    }

    @Test
    fun eventarcPropagationIsRetryable() {
        val e = CliErrorClassifier.classify(eventarc, SetupStepId.SERVER)
        assertEquals(CliErrorKind.EVENTARC_PROPAGATION, e.kind)
        assertTrue(e.retryable)
    }

    @Test
    fun billingIsNotRetryable() {
        val e = CliErrorClassifier.classify(
            "Error: Your project must be on the Blaze (pay-as-you-go) plan to complete this command. A billing account is required.",
            SetupStepId.SERVER,
        )
        assertEquals(CliErrorKind.BILLING_REQUIRED, e.kind)
        assertFalse(e.retryable)
    }

    @Test
    fun notLoggedIn() {
        assertEquals(
            CliErrorKind.NOT_LOGGED_IN,
            CliErrorClassifier.classify("Error: Failed to authenticate, have you run firebase login?", SetupStepId.PROJECT).kind,
        )
        assertEquals(
            CliErrorKind.NOT_LOGGED_IN,
            CliErrorClassifier.classify("Error: not logged in", SetupStepId.PROJECT).kind,
        )
    }

    @Test
    fun unknownKeepsDetails() {
        val e = CliErrorClassifier.classify("something odd", SetupStepId.RULES)
        assertEquals(CliErrorKind.UNKNOWN, e.kind)
        assertFalse(e.retryable)
        assertEquals("something odd", e.details)
    }
}

class RegionMapperTest {
    @Test
    fun mapsMultiRegionsAndKeepsSingleRegions() {
        assertEquals("europe-west1", RegionMapper.functionsRegion("eur3"))
        assertEquals("us-central1", RegionMapper.functionsRegion("nam5"))
        assertEquals("europe-west6", RegionMapper.functionsRegion("europe-west6"))
    }

    @Test
    fun ordersZurichThenEur3ThenRest() {
        assertEquals(
            listOf("europe-west6", "eur3", "nam5", "asia-east1"),
            RegionMapper.orderLocations(listOf("nam5", "eur3", "asia-east1", "europe-west6")),
        )
    }
}

class SetupValidatorsTest {
    @Test
    fun projectIds() {
        assertTrue(SetupValidators.projectId("noctuliste---lethal-tempo"))
        assertFalse(SetupValidators.projectId("Bad_ID"))
        assertFalse(SetupValidators.projectId("ab"))
        assertFalse(SetupValidators.projectId("x; rm -rf /"))
    }

    @Test
    fun gmailAndNames() {
        assertTrue(SetupValidators.gmail("info.lethaltempo@gmail.com"))
        assertFalse(SetupValidators.gmail("a@b"))
        assertFalse(SetupValidators.gmail("a b@gmail.com"))
        assertTrue(SetupValidators.displayName("Lethal Tempo"))
        assertFalse(SetupValidators.displayName(""))
        assertFalse(SetupValidators.displayName("a\nb"))
    }

    @Test
    fun locationMustBeAllowed() {
        assertTrue(SetupValidators.location("eur3", listOf("eur3", "nam5")))
        assertFalse(SetupValidators.location("mars1", listOf("eur3")))
    }
}

class OutputRedactorTest {
    @Test
    fun hidesSecretsWithSpecialCharacters() {
        val secret = "pa\$\$ \"word\""
        val r = OutputRedactor { listOf(secret, "") }
        assertEquals("value is •••• ok", r.redact("value is $secret ok"))
        assertEquals("nothing here", r.redact("nothing here"))
    }

    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    @Test
    fun hidesBase64FormsOfSecrets() {
        val secret = "abcd efgh?>>"
        val r = OutputRedactor { listOf(secret) }
        val b = secret.encodeToByteArray()
        for (enc in listOf(
            kotlin.io.encoding.Base64.Default.encode(b),
            kotlin.io.encoding.Base64.Default.encode(b).trimEnd('='),
            kotlin.io.encoding.Base64.UrlSafe.encode(b),
            kotlin.io.encoding.Base64.UrlSafe.encode(b).trimEnd('='),
        )) {
            assertEquals("data: ••••", r.redact("data: $enc"))
        }
    }
}

class SetupProgressTest {
    @Test
    fun roundTrips() {
        val p = SetupProgress(
            projectId = "demo-project",
            done = setOf(SetupStepId.TOOLS, SetupStepId.LOGIN),
            confirmed = setOf(SetupStepId.PROVIDERS),
            extras = setOf(SetupStepId.SERVER),
        )
        assertEquals(p, SetupProgress.decode(p.encode()))
        assertEquals(SetupProgress(), SetupProgress.decode("not json"))
    }

    @Test
    fun stepKinds() {
        assertEquals(StepKind.OPEN, SetupStepId.PROVIDERS.kind)
        assertEquals(StepKind.RUN_THEN_OPEN, SetupStepId.CONNECT.kind)
        assertEquals(StepKind.OPEN_THEN_RUN, SetupStepId.SERVER.kind)
        assertEquals(StepKind.RUN, SetupStepId.TOOLS.kind)
        assertEquals(9, SetupStepId.SERVER.number)
        assertTrue(SetupStepId.SITE.optional)
    }
}
