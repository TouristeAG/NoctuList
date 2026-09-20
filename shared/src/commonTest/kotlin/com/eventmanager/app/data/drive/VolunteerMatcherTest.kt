package com.eventmanager.app.data.drive

import com.eventmanager.app.data.models.Volunteer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VolunteerMatcherTest {

    private fun volunteer(
        id: String,
        name: String,
        abbreviation: String = "",
        email: String = "",
        phone: String = "",
        birthday: String = "",
    ) = Volunteer(
        id = id,
        name = name,
        lastNameAbbreviation = abbreviation,
        email = email,
        phoneNumber = phone,
        dateOfBirth = birthday,
    )

    private fun chip(name: String, email: String? = null, birthday: String? = null, phone: String? = null) =
        DocumentMention(
            rawLabel = "@$name",
            displayName = name,
            email = email,
            phone = phone,
            birthday = birthday,
            source = if (email != null) MentionSource.CONTACT_CHIP else MentionSource.PLAIN_TEXT,
        )

    @Test
    fun uniqueEmailIsDecisiveEvenWhenTheNameDiffers() {
        val target = volunteer("v1", "Robert Dupont", email = "bob.dupont@example.org")
        val others = listOf(volunteer("v2", "Bob Martin", email = "bob.martin@example.org"))

        val match = VolunteerMatcher.match(
            chip("Bobby D", email = "Bob.Dupont+asso@example.org"),
            others + target,
        )

        assertEquals(MatchConfidence.CONFIRMED, match.confidence)
        assertEquals("v1", match.selectedVolunteer?.id)
    }

    /** A roster typo puts the same address on two people, so the email can no longer decide. */
    @Test
    fun duplicatedEmailFallsBackToOtherEvidence() {
        val shared = "contact@asso.ch"
        val volunteers = listOf(
            volunteer("v1", "Alice Bernard", email = shared),
            volunteer("v2", "Marc Rossi", email = shared, birthday = "1994-03-12"),
        )

        val match = VolunteerMatcher.match(
            chip("Marc Rossi", email = shared, birthday = "1994-03-12"),
            volunteers,
        )

        assertEquals("v2", match.selectedVolunteer?.id)
        assertEquals(MatchConfidence.CONFIRMED, match.confidence)
        val reasons = match.candidates.first { it.volunteer.id == "v2" }.reasons
        assertTrue(MatchReason.EMAIL_SHARED in reasons, "expected the email to be demoted: $reasons")
        assertTrue(MatchReason.BIRTHDAY in reasons)
    }

    @Test
    fun severalVolunteersShareAFirstNameSoTheMatchIsAmbiguous() {
        val volunteers = listOf(
            volunteer("v1", "Bob Jean Dupont"),
            volunteer("v2", "Bob Perrin"),
            volunteer("v3", "Bob Meier"),
        )

        val match = VolunteerMatcher.match(chip("Bob"), volunteers)

        assertEquals(MatchConfidence.AMBIGUOUS, match.confidence)
        assertNull(match.selectedVolunteer, "an ambiguous row must not preselect a volunteer")
        assertTrue(match.candidates.size >= 3)
    }

    @Test
    fun aSharedBirthdayBreaksTheFirstNameTie() {
        val volunteers = listOf(
            volunteer("v1", "Bob Jean Dupont", birthday = "1998-07-04"),
            volunteer("v2", "Bob Perrin"),
            volunteer("v3", "Bob Meier"),
        )

        val match = VolunteerMatcher.match(chip("Bob", birthday = "--07-04"), volunteers)

        assertEquals("v1", match.selectedVolunteer?.id)
        assertTrue(
            match.confidence == MatchConfidence.LIKELY || match.confidence == MatchConfidence.CONFIRMED,
            "birthday evidence should lift the match above ambiguous, got ${match.confidence}",
        )
    }

    @Test
    fun accentsAndPunctuationDoNotPreventAFullNameMatch() {
        val volunteers = listOf(
            volunteer("v1", "Jean-Luc Béro"),
            volunteer("v2", "Sarah Klein"),
        )

        val match = VolunteerMatcher.match(chip("Jean Luc Bero"), volunteers)

        assertEquals("v1", match.selectedVolunteer?.id)
        assertTrue(MatchReason.FULL_NAME in match.candidates.first().reasons)
    }

    @Test
    fun anUnknownPersonProducesNoCandidates() {
        val volunteers = listOf(volunteer("v1", "Alice Bernard"), volunteer("v2", "Marc Rossi"))

        val match = VolunteerMatcher.match(chip("Zbigniew Kowalczyk"), volunteers)

        assertEquals(MatchConfidence.UNMATCHED, match.confidence)
        assertNull(match.selectedVolunteer)
        assertTrue(match.candidates.isEmpty())
    }

    @Test
    fun swissPhoneNumbersCompareAcrossFormats() {
        val volunteers = listOf(
            volunteer("v1", "Lea Favre", phone = "079 123 45 67"),
            volunteer("v2", "Tom Blanc", phone = "078 000 00 00"),
        )

        val match = VolunteerMatcher.match(chip("L. Favre", phone = "+41 79 123 45 67"), volunteers)

        assertEquals("v1", match.selectedVolunteer?.id)
        assertEquals(MatchConfidence.CONFIRMED, match.confidence)
    }

    @Test
    fun aTypoInTheFirstNameStillFindsTheVolunteer() {
        val volunteers = listOf(
            volunteer("v1", "Matthieu Girard"),
            volunteer("v2", "Sophie Laurent"),
        )

        val match = VolunteerMatcher.match(chip("Mathieu Girard"), volunteers)

        assertEquals("v1", match.selectedVolunteer?.id)
    }

    @Test
    fun mentionsCollapseOnEmailRatherThanRepeatingAPerson() {
        val merged = listOf(
            chip("Bob", email = "bob@example.org"),
            chip("Bob Dupont", email = "BOB@example.org"),
            chip("Alice", email = "alice@example.org"),
        ).mergeDuplicates()

        assertEquals(2, merged.size)
        val bob = merged.first { it.email?.lowercase() == "bob@example.org" }
        assertEquals(2, bob.occurrences)
    }

    @Test
    fun plainTextMentionsRequireACapitalisedName() {
        val found = PlainTextMentionParser.parse("Shift de @Bob et @Marie Claire, mail bob@a.ch, @x")

        assertEquals(listOf("@Bob", "@Marie Claire"), found.map { it.rawLabel })
    }

    @Test
    fun theAbbreviationConfirmsASpelledOutSurname() {
        val volunteers = listOf(
            volunteer("v1", "Bob", abbreviation = "Dup"),
            volunteer("v2", "Bob", abbreviation = "Mei"),
        )

        val match = VolunteerMatcher.match(chip("Bob Dupont"), volunteers)

        assertEquals("v1", match.selectedVolunteer?.id)
        assertNotNull(match.selectedVolunteer)
    }

    @Test
    fun aUniqueFirstNameIsPreselectedWithLowConfidence() {
        val volunteers = listOf(
            volunteer("v1", "Leonardo", abbreviation = "MONDADA"),
            volunteer("v2", "Luca", abbreviation = "PASINI"),
        )

        val match = VolunteerMatcher.match(chip("Leonardo"), volunteers)

        assertEquals("v1", match.selectedVolunteer?.id)
        assertEquals(MatchConfidence.POSSIBLE, match.confidence)
    }

    @Test
    fun camelCaseMentionMatchesFirstNamePlusLastNameAbbreviation() {
        val volunteers = listOf(
            volunteer("v1", "Luca", abbreviation = "PASINI"),
            volunteer("v2", "Leonardo", abbreviation = "MONDADA"),
        )

        val match = VolunteerMatcher.match(chip("LucaPasini"), volunteers)

        assertEquals("v1", match.selectedVolunteer?.id)
        assertEquals(MatchConfidence.LIKELY, match.confidence)
    }

    @Test
    fun aConcatenatedMentionWithoutCamelCaseStillMatchesTheFullName() {
        val volunteers = listOf(
            volunteer("v1", "Luca", abbreviation = "PASINI"),
            volunteer("v2", "Leonardo", abbreviation = "MONDADA"),
        )

        val match = VolunteerMatcher.match(chip("Lucapasini"), volunteers)

        assertEquals("v1", match.selectedVolunteer?.id)
        assertTrue(
            match.confidence == MatchConfidence.LIKELY || match.confidence == MatchConfidence.CONFIRMED,
            "first name + last name should be at least probable, got ${match.confidence}",
        )
    }

    @Test
    fun splitGivenAndFamilyNamesFromAChipMatchTheRosterAbbreviation() {
        val volunteers = listOf(
            volunteer("v1", "Luca", abbreviation = "PASINI"),
            volunteer("v2", "Leonardo", abbreviation = "MONDADA"),
        )
        val mention = DocumentMention(
            rawLabel = "@LucaPasini",
            displayName = "LucaPasini",
            givenName = "Luca",
            familyName = "Pasini",
            source = MentionSource.CONTACT_CHIP,
        )

        val match = VolunteerMatcher.match(mention, volunteers)

        assertEquals("v1", match.selectedVolunteer?.id)
        assertTrue(
            match.confidence == MatchConfidence.LIKELY || match.confidence == MatchConfidence.CONFIRMED,
            "given + family should be at least probable, got ${match.confidence}",
        )
    }
}
