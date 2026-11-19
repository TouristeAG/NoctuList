package com.eventmanager.app.setup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    @Test
    fun hidesGoogleTokensWithoutBeingTold() {
        val r = OutputRedactor { emptyList() }
        val line = "access ya29.a0AfB_x-y.z refresh 1//0gAbC-d_e.f id eyJhbGciOi.eyJzdWIiOi.c2ln-bmF_0 done"
        val out = r.redact(line)
        assertFalse("ya29." in out, out)
        assertFalse("1//" in out, out)
        assertFalse("eyJ" in out, out)
        assertEquals("access •••• refresh •••• id •••• done", out)
        assertEquals("see https://example.org/a and 2//x", r.redact("see https://example.org/a and 2//x"))
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
