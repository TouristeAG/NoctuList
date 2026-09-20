package com.eventmanager.app.data.drive

import com.eventmanager.app.data.sync.AppLogger
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.people.v1.PeopleService
import com.google.api.services.people.v1.model.Person
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "PeopleEnricher"
private const val READ_MASK = "names,emailAddresses,phoneNumbers,birthdays"

/**
 * Fills in phone number, birthday and split given/family names from the signed-in user's Google
 * Contacts. A smart chip only yields a name and an email, and those extra fields are what let the
 * matcher disambiguate several volunteers who share a first name.
 *
 * Strictly best-effort: every failure leaves the mentions untouched so the import still runs.
 */
class PeopleEnricher(private val requestInitializer: HttpRequestInitializer) {

    private val people: PeopleService by lazy {
        PeopleService.Builder(
            GoogleNetHttpTransport.newTrustedTransport(),
            GsonFactory.getDefaultInstance(),
            requestInitializer,
        ).setApplicationName(DriveDocumentBrowser.APPLICATION_NAME).build()
    }

    suspend fun enrich(mentions: List<DocumentMention>): List<DocumentMention> = withContext(Dispatchers.IO) {
        if (mentions.isEmpty()) return@withContext mentions
        // The search endpoints need their cache warmed once per session or the first few
        // queries come back empty. This is the documented behaviour, not a workaround.
        runCatching { warmUpCache() }
        mentions.map { mention ->
            runCatching { enrichOne(mention) }
                .onFailure { AppLogger.w(TAG, "Contact lookup failed for ${mention.rawLabel}: ${it.message}") }
                .getOrDefault(mention)
        }
    }

    private fun warmUpCache() {
        people.people().searchContacts().setQuery("").setReadMask(READ_MASK).execute()
        people.otherContacts().search().setQuery("").setReadMask(READ_MASK).execute()
    }

    private fun enrichOne(mention: DocumentMention): DocumentMention {
        val query = mention.email ?: mention.displayName.takeIf { it.isNotBlank() } ?: return mention
        val person = search(query, mention) ?: return mention

        val name = person.names?.firstOrNull()
        val phone = person.phoneNumbers?.firstOrNull()?.value?.trim()
        val birthday = person.birthdays?.firstNotNullOfOrNull { entry ->
            entry.date?.let { d ->
                val month = d.month ?: return@let null
                val day = d.day ?: return@let null
                d.year?.let { "%04d-%02d-%02d".format(it, month, day) } ?: "--%02d-%02d".format(month, day)
            } ?: TextNormalization.normalizeBirthday(entry.text)
        }
        val email = mention.email ?: person.emailAddresses?.firstOrNull()?.value?.trim()

        return mention.copy(
            displayName = name?.displayName?.trim()?.takeIf { it.isNotBlank() } ?: mention.displayName,
            email = email?.takeIf { it.isNotBlank() },
            phone = phone?.takeIf { it.isNotBlank() } ?: mention.phone,
            birthday = birthday ?: mention.birthday,
            givenName = name?.givenName?.trim()?.takeIf { it.isNotBlank() } ?: mention.givenName,
            familyName = name?.familyName?.trim()?.takeIf { it.isNotBlank() } ?: mention.familyName,
        )
    }

    /** Looks in saved contacts first, then in "other contacts" (people only ever emailed). */
    private fun search(query: String, mention: DocumentMention): Person? {
        val contacts = people.people().searchContacts()
            .setQuery(query)
            .setReadMask(READ_MASK)
            .setPageSize(10)
            .execute()
            .results.orEmpty()
            .mapNotNull { it.person }

        pick(contacts, mention)?.let { return it }

        val others = people.otherContacts().search()
            .setQuery(query)
            .setReadMask(READ_MASK)
            .setPageSize(10)
            .execute()
            .results.orEmpty()
            .mapNotNull { it.person }

        return pick(others, mention)
    }

    /**
     * A name query can return several people, and picking the wrong one would inject misleading
     * evidence into the matcher. Only accept an email-verified hit, or a single unambiguous
     * name result.
     */
    private fun pick(candidates: List<Person>, mention: DocumentMention): Person? {
        if (candidates.isEmpty()) return null
        mention.email?.let { wanted ->
            val target = TextNormalization.normalizeEmail(wanted)
            return candidates.firstOrNull { person ->
                person.emailAddresses.orEmpty().any {
                    it.value?.let { v -> TextNormalization.normalizeEmail(v) == target } == true
                }
            }
        }
        val wantedName = TextNormalization.normalizeName(mention.displayName)
        if (wantedName.isBlank()) return null
        val exact = candidates.filter { person ->
            person.names.orEmpty().any {
                TextNormalization.normalizeName(it.displayName.orEmpty()) == wantedName
            }
        }
        return exact.singleOrNull()
    }
}
