package com.eventmanager.app.portal

import com.eventmanager.app.data.models.Gender
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PortalInputValidatorTest {
    private val org = "org-a"
    private val form = "signupFormAAAA0001"
    private val uid = "AbCdEf0123456789xyzUID01"

    private fun submission(overrides: Map<String, Any?> = emptyMap()): Map<String, Any?> = mapOf(
        "status" to "SUBMITTED",
        "submittedUid" to uid,
        "email" to "Lea@Example.org",
        "firstName" to "  Léa  ",
        "lastName" to "Martin",
        "phone" to "+41 79 123 45 67",
        "dateOfBirth" to "1998-04-12",
        "gender" to "FEMALE",
        "photoPath" to "",
        "consent" to true,
        "lang" to "de",
    ) + overrides

    @Test
    fun parsesAValidRegistration() {
        val parsed = assertNotNull(PortalInputValidator.parseSignup(form, org, submission()))
        assertEquals(uid, parsed.uid)
        assertEquals("lea@example.org", parsed.email)
        assertEquals("Léa", parsed.firstName)
        assertEquals(Gender.FEMALE, parsed.gender)
        assertEquals("de", parsed.lang)
        assertEquals("", parsed.photoPath)
    }

    @Test
    fun onlyTrustsTheFormsOwnPhotoPath() {
        val own = "orgs/$org/portalSignupPhotos/$form.jpg"
        assertEquals(own, PortalInputValidator.parseSignup(form, org, submission(mapOf("photoPath" to own)))!!.photoPath)
        val foreign = "orgs/$org/profilePhotos/volunteers/someone.jpg"
        assertEquals("", PortalInputValidator.parseSignup(form, org, submission(mapOf("photoPath" to foreign)))!!.photoPath)
    }

    @Test
    fun rejectsInvalidRegistrations() {
        val bad = listOf(
            mapOf("status" to "OPEN"),
            mapOf("submittedUid" to "../../etc"),
            mapOf("email" to "not-an-email"),
            mapOf("firstName" to "<script>"),
            mapOf("lastName" to ""),
            mapOf("phone" to "call me"),
            mapOf("dateOfBirth" to "12.04.1998"),
            mapOf("gender" to "ROBOT"),
            mapOf("consent" to false),
        )
        for (override in bad) {
            assertNull(PortalInputValidator.parseSignup(form, org, submission(override)), override.toString())
        }
    }

    @Test
    fun parsesRequestsOnlyWhenTheIdMatches() {
        val profile = mapOf(
            "uid" to uid, "type" to "PROFILE", "status" to "PENDING", "email" to "lea@example.org",
            "firstName" to "Léa", "lastName" to "Martin", "phone" to "079 123 45 67",
        )
        val parsed = assertNotNull(PortalInputValidator.parseRequest("${uid}_PROFILE", profile))
        assertEquals(PortalRequestType.PROFILE, parsed.type)
        assertNull(PortalInputValidator.parseRequest("someoneElse_PROFILE", profile))
        assertNull(PortalInputValidator.parseRequest("${uid}_PROFILE", profile + ("firstName" to "{x}")))
        val qr = mapOf("uid" to uid, "type" to "QR_EMAIL", "status" to "PENDING", "email" to "lea@example.org")
        assertEquals(PortalRequestType.QR_EMAIL, PortalInputValidator.parseRequest("${uid}_QR_EMAIL", qr)!!.type)
        assertNull(PortalInputValidator.parseRequest("${uid}_QR_EMAIL", qr + ("type" to "ADMIN")))
    }

    @Test
    fun parsesGuestRequestsLikeTheVolunteerOnes() {
        val profile = mapOf(
            "uid" to uid, "type" to "GUEST_PROFILE", "status" to "PENDING", "email" to "lea@example.org",
            "firstName" to " Léa ", "lastName" to "Martin", "phone" to "079 123 45 67", "photo" to true,
        )
        val parsed = assertNotNull(PortalInputValidator.parseRequest("${uid}_GUEST_PROFILE", profile))
        assertEquals(PortalRequestType.GUEST_PROFILE, parsed.type)
        assertEquals("Léa", parsed.firstName)
        assertEquals("Martin", parsed.lastName)
        assertEquals("079 123 45 67", parsed.phone)
        assertTrue(parsed.photo)
        assertNull(PortalInputValidator.parseRequest("${uid}_PROFILE", profile))
        assertNull(PortalInputValidator.parseRequest("${uid}_GUEST_PROFILE", profile - "phone"))
        val qr = mapOf("uid" to uid, "type" to "GUEST_QR_EMAIL", "status" to "PENDING", "email" to "lea@example.org")
        assertEquals(PortalRequestType.GUEST_QR_EMAIL, PortalInputValidator.parseRequest("${uid}_GUEST_QR_EMAIL", qr)!!.type)
        assertNull(PortalInputValidator.parseRequest("${uid}_QR_EMAIL", qr))
    }

    @Test
    fun generatedPasswordsAreStrongAndUnambiguous() {
        val random = SecureRandom()
        val seen = mutableSetOf<String>()
        repeat(200) {
            val password = PortalPasswordGenerator.generate(random = random)
            assertEquals(PortalPasswordGenerator.DEFAULT_LENGTH, password.length)
            assertTrue(password.any { it.isUpperCase() })
            assertTrue(password.any { it.isLowerCase() })
            assertTrue(password.any { it.isDigit() })
            assertTrue(password.any { !it.isLetterOrDigit() })
            assertTrue(password.none { it in "0O1lI" }, password)
            seen += password
        }
        assertEquals(200, seen.size)
    }

    @Test
    fun parsesFirebaseAuthErrorCodes() {
        assertEquals("EMAIL_EXISTS", PortalAuthClient.parseErrorCode("""{"error":{"code":400,"message":"EMAIL_EXISTS"}}"""))
        assertEquals(
            "WEAK_PASSWORD",
            PortalAuthClient.parseErrorCode("""{"error":{"message":"WEAK_PASSWORD : Password should be at least 6 characters"}}"""),
        )
        assertEquals("", PortalAuthClient.parseErrorCode("<html>"))
    }

    @Test
    fun readsFirestoreTimestampsOfAnyShape() {
        assertEquals(1234L, firestoreMillisOf(1234L))
        assertEquals(1234L, firestoreMillisOf(1234.0))
        assertEquals(5000L, firestoreMillisOf(java.util.Date(5000)))
        assertNull(firestoreMillisOf(null))
    }
}
