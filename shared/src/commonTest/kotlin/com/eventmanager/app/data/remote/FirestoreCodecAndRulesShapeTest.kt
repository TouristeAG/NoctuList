package com.eventmanager.app.data.remote

import com.eventmanager.app.data.models.AccountTransfer
import com.eventmanager.app.data.models.AccountTransferSyncState
import com.eventmanager.app.data.models.AccountHolderType
import com.eventmanager.app.data.models.AccountTransferType
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class FirestoreCodecAndRulesShapeTest {
    @Test
    fun ruleCompatibleMap_exposesFlatBalanceAndJsonEnvelope() {
        val mapped = ruleCompatibleFirestoreMap(mapOf("balance" to 12.5, "updatedAt" to 1L))
        assertEquals(12.5, mapped["balance"])
        assertTrue((mapped["json"] as? String)?.contains("balance") == true)
    }

    @Test
    fun ruleCompatibleMap_keepsOversizedStringsFlatOnly() {
        val logo = "x".repeat(FIRESTORE_ENVELOPE_MAX_STRING_CHARS + 50)
        val mapped = ruleCompatibleFirestoreMap(
            mapOf(
                "institutionLogoDataUri" to logo,
                "artistName" to "Ada",
            ),
        )
        assertEquals(logo, mapped["institutionLogoDataUri"])
        val envelope = mapped["json"] as String
        assertTrue(envelope.contains("Ada"))
        assertFalse(envelope.contains(logo))
    }

    @Test
    fun encodeDecodeRoundTrip_preservesStringsWithCommas() {
        val original = mapOf(
            "description" to "Beer, wine, and snacks",
            "amount" to -4.5,
            "ok" to true,
        )
        val encoded = FirestoreJsonCodec.toEnvelope(original).json
        val decoded = FirestoreJsonCodec.fromEnvelope(FirestoreJsonEnvelope(encoded))
        assertEquals("Beer, wine, and snacks", decoded["description"])
        assertEquals(-4.5, decoded["amount"])
        assertEquals(true, decoded["ok"])
    }

    @Test
    fun transferToMap_usesSourceReferenceAsIdempotentKey() {
        val transfer = AccountTransfer(
            transferId = "t1",
            sourceReference = "POS:abc",
            holderType = AccountHolderType.GUEST,
            holderId = "g1",
            holderName = "Ada",
            amount = -10.0,
            type = AccountTransferType.POS_SALE,
            syncState = AccountTransferSyncState.PENDING,
        )
        val gateway = GitLiveFirestoreGateway()
        val map = gateway.transferToMap(transfer)
        assertEquals("POS:abc", map["sourceReference"])
        assertEquals(AccountTransferSyncState.PENDING.name, map["syncState"])
    }

    @Test
    fun ruleCompatibleMap_preservesNestedAllowedEmailDomains() {
        val mapped = ruleCompatibleFirestoreMap(
            mapOf(
                "allowedEmailDomains" to mapOf("gmail.com" to true, "example.org" to true),
            ),
        )
        val domains = mapped["allowedEmailDomains"] as? Map<*, *>
        assertEquals(true, domains?.get("gmail.com"))
        assertEquals(true, domains?.get("example.org"))
    }

    @Test
    fun toFirestoreFieldMap_usesPlainMapsNotJsonObject() {
        val fields = toFirestoreFieldMap(
            mapOf(
                "role" to "admin",
                "balance" to 12.5,
                "allowedEmailDomains" to mapOf("gmail.com" to true),
            ),
        )
        assertEquals("admin", fields["role"])
        assertEquals(12.5, fields["balance"])
        val nested = fields["allowedEmailDomains"] as? Map<*, *>
        assertEquals(true, nested?.get("gmail.com"))
    }

    @Test
    fun guestFormToMap_writesMillisAsDoubleSoRulesSeeANumber() {
        val form = com.eventmanager.app.data.models.GuestForm(
            formId = "f1",
            eventDateMillis = 1_700_000_000_000L,
            expiresAtMillis = 1_700_086_399_999L,
            status = "OPEN",
        )
        val mapped = GitLiveFirestoreGateway().guestFormToMap(form)
        assertEquals(1_700_086_399_999.0, mapped["expiresAtMillis"])
        assertEquals(1_700_000_000_000.0, mapped["eventDateMillis"])
        val stamped = ruleCompatibleFirestoreMap(mapped)
        assertTrue(stamped["expiresAtMillis"] is Double)
    }

    @Test
    fun fromJsonObject_mergesFlatFieldsWithEnvelope() {
        val envelope = FirestoreJsonCodec.toEnvelope(
            mapOf("name" to "Ada", "lastModified" to 10L),
        ).json
        val obj = JsonObject(
            mapOf(
                "json" to JsonPrimitive(envelope),
                "amount" to JsonPrimitive(10.0),
                "lastModified" to JsonPrimitive(99L),
            ),
        )
        val decoded = FirestoreJsonCodec.fromJsonObject(obj)
        assertEquals("Ada", decoded["name"])
        assertEquals(10.0, decoded["amount"])
        // A public writer only touches flat fields; those must beat the stale envelope.
        assertEquals(99L, decoded["lastModified"])
    }

    @Test
    fun fromJsonObject_publicGuestFormAnswerBeatsStaleEnvelope() {
        val envelope = FirestoreJsonCodec.toEnvelope(
            mapOf(
                "status" to "OPEN",
                "submissionJson" to "",
                "lastModified" to 10L,
            ),
        ).json
        val obj = JsonObject(
            mapOf(
                "json" to JsonPrimitive(envelope),
                "status" to JsonPrimitive("PENDING_REVIEW"),
                "submissionJson" to JsonPrimitive("""{"people":[{"name":"Ada"}]}"""),
                "submittedAt" to JsonPrimitive(99L),
                "lastModified" to JsonPrimitive(99L),
            ),
        )
        val decoded = FirestoreJsonCodec.fromJsonObject(obj)
        assertEquals("PENDING_REVIEW", decoded["status"])
        assertTrue((decoded["submissionJson"] as? String)?.contains("Ada") == true)
        assertEquals(99L, decoded["lastModified"])
    }

    @Test
    fun firestoreRulesShape_enforcesMemberReadRoleWhitelistAndTransferCatchAllExclusion() = runBlocking {
        // Prefer compose resource (in-app clipboard) so deploy file and app stay in sync.
        val fromResource = runCatching { FirestoreRulesClipboardContent.load() }.getOrNull()
            ?.takeIf { it.contains("service cloud.firestore") }
        val fromRepo = sequenceOf(
            File("firebase/firestore.rules"),
            File("../firebase/firestore.rules"),
        ).firstOrNull { it.exists() }?.readText()
        val rules = fromResource ?: fromRepo
            ?: error("firestore.rules not found via resource or repo path")

        assertTrue(rules.contains("function emailDomainAllowed(orgId)"))
        assertTrue(rules.contains("allowedEmailDomains"))
        assertTrue(rules.contains("function isValidMemberRole(role)"))
        assertTrue(rules.contains("role in ['admin', 'member', 'door', 'pos']"))

        val membersBlock = Regex(
            """match /orgs/\{orgId\}/members/\{uid\} \{([\s\S]*?)\n    \}""",
        ).find(rules)?.groupValues?.get(1)
            ?: error("members match block missing")
        assertTrue(
            membersBlock.contains("allow list: if isMember(orgId)"),
            "listing members must require org membership",
        )
        assertTrue(
            membersBlock.contains("request.auth.uid == uid || isMember(orgId)"),
            "reading your own member doc must be allowed so the client can tell absent from denied",
        )
        assertFalse(
            membersBlock.contains("allow read: if isSignedIn()"),
            "members must not be readable by any signed-in user",
        )
        assertTrue(membersBlock.contains("request.resource.data.role == 'admin'"))
        assertFalse(
            membersBlock.contains("!exists(memberPath(orgId))"),
            "self-admin create must be gated on the org config, not on a missing member doc, " +
                "otherwise anyone knowing an orgId can become its admin",
        )
        assertTrue(
            membersBlock.contains("!exists(configPath(orgId))"),
            "first-admin create is only for organizations that have no config yet",
        )
        assertTrue(
            membersBlock.contains("request.resource.data.role == resource.data.role"),
            "an existing member must be able to refresh its own doc (idempotent re-join)",
        )
        assertTrue(
            membersBlock.contains(
                ".hasOnly(['email', 'updatedAt', 'json', 'bootstrapCode'])",
            ),
            "the member self-update path must be restricted to non-privileged fields",
        )
        assertTrue(membersBlock.contains("isOrgAdmin(orgId)"))

        assertTrue(
            rules.contains("NOCTULIST_RULES_VERSION = $NOCTULIST_FIRESTORE_RULES_VERSION"),
            "the rules file revision must match NOCTULIST_FIRESTORE_RULES_VERSION",
        )
        assertTrue(
            rules.contains("allowedEmailDomains is map"),
            "a malformed allowedEmailDomains value must not turn into a blanket denial",
        )

        assertTrue(
            rules.contains("document[0] != 'transfers'"),
            "catch-all must exclude transfers writes",
        )
        assertTrue(
            rules.contains("document[0] != 'institutionSettings'"),
            "catch-all must exclude institutionSettings writes (admin-only collection)",
        )
        val institutionSettingsBlock = Regex(
            """match /orgs/\{orgId\}/institutionSettings/\{key\} \{([\s\S]*?)\n    \}""",
        ).find(rules)?.groupValues?.get(1)
            ?: error("institutionSettings match block missing")
        assertTrue(
            institutionSettingsBlock.contains("allow write: if isOrgAdmin(orgId)"),
            "institutionSettings writes must require Firebase org admin",
        )
        assertFalse(
            institutionSettingsBlock.contains("allow write: if isMember(orgId)"),
            "institutionSettings must not be writable by every member",
        )
        assertTrue(rules.contains("allow update, delete: if false;"))
        assertTrue(
            rules.contains("match /orgs/{orgId}/guestForms/{formId}"),
            "artist guest list forms need their own match block",
        )
        assertTrue(
            rules.contains("function guestFormIsOpen"),
            "public get must treat numeric strings as millis, not only `is number`",
        )
        assertTrue(
            rules.contains("allow get: if guestFormIsOpen(resource.data)"),
            "anonymous get must use guestFormIsOpen so a string expiry does not look closed",
        )
        assertTrue(
            rules.contains("isPublicMultiGuestFormCreate"),
            "multi-response public create must be defined",
        )
        assertTrue(
            rules.contains("allowMultipleResponses"),
            "multi-response flag must appear in guest form rules",
        )
        assertTrue(
            rules.contains("request.resource.data.status == 'PENDING_REVIEW'"),
            "a public answer may only move the form to PENDING_REVIEW",
        )
        assertTrue(
            rules.contains(
                ".hasOnly(['status', 'submissionJson', 'submittedAt', 'lastModified', 'sourceDeviceId'])",
            ),
            "a public answer must not rewrite venue, accesses or logos",
        )
    }

    @Test
    fun storageRulesAllowMemberDeleteWithoutRequestResource() = runBlocking {
        val fromResource = runCatching { StorageRulesClipboardContent.load() }.getOrNull()
            ?.takeIf { it.contains("service firebase.storage") }
        val fromRepo = sequenceOf(
            File("firebase/storage.rules"),
            File("../firebase/storage.rules"),
        ).firstOrNull { it.exists() }?.readText()
        val rules = fromResource ?: fromRepo
            ?: error("storage.rules not found via resource or repo path")
        assertTrue(rules.contains("allow delete: if isMember(orgId)"))
        assertTrue(rules.contains("allow create, update:"))
        assertFalse(
            Regex("""allow write:[\s\S]*request\.resource\.contentType""").containsMatchIn(rules),
            "a write+contentType rule denies Storage deletes because request.resource is null",
        )
    }

    @Test
    fun storageBucketCandidates_preferStoredThenFirebaseDefaults() {
        assertEquals(
            listOf("custom.appspot.com"),
            firebaseStorageBucketCandidates("gs://custom.appspot.com/", ""),
        )
        assertEquals(
            listOf("proj.firebasestorage.app", "proj.appspot.com"),
            firebaseStorageBucketCandidates("", "proj"),
        )
        assertEquals(
            listOf("stored.appspot.com", "proj.firebasestorage.app", "proj.appspot.com"),
            firebaseStorageBucketCandidates("stored.appspot.com", "proj"),
        )
    }
}