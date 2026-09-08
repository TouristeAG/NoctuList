package com.eventmanager.app.data.security.crypto

import com.eventmanager.app.data.models.AccountTransfer
import com.eventmanager.app.data.models.Guest
import com.eventmanager.app.data.models.Volunteer
import com.eventmanager.app.data.nfc.NfcUid

/**
 * Single boundary for encrypting/decrypting sensitive fields before persistence or sync.
 * UI and ViewModels always see decrypted domain objects.
 */
object SensitiveFieldCodec {
    private val crypto: OrgCryptoService get() = OrgCryptoRegistry.get()

    fun isConfigured(orgId: String): Boolean = crypto.isConfigured(orgId)

    fun anyOrgConfigured(orgIds: Iterable<String>): Boolean {
        val seen = HashSet<String>()
        for (raw in orgIds) {
            val id = raw.trim()
            if (id.isEmpty() || !seen.add(id)) continue
            if (crypto.isConfigured(id)) return true
        }
        return false
    }

    fun encryptGuestFields(guest: Guest): Guest {
        val orgId = guest.firebaseOrgId
        if (!crypto.isConfigured(orgId)) return guest
        return guest.copy(
            email = crypto.encrypt(guest.email, orgId),
            phoneNumber = crypto.encrypt(guest.phoneNumber, orgId),
            temporaryContactPhone = crypto.encrypt(guest.temporaryContactPhone, orgId),
            temporaryContactEmail = crypto.encrypt(guest.temporaryContactEmail, orgId),
            notes = crypto.encrypt(guest.notes, orgId),
            nfcCardUid = crypto.encrypt(guest.nfcCardUid, orgId),
            nfcCardUidHash = nfcLookupHash(guest.nfcCardUid, orgId),
        )
    }

    fun decryptGuestFields(guest: Guest): Guest {
        val orgId = guest.firebaseOrgId
        if (!crypto.isConfigured(orgId)) return guest
        return guest.copy(
            email = crypto.decrypt(guest.email, orgId),
            phoneNumber = crypto.decrypt(guest.phoneNumber, orgId),
            temporaryContactPhone = crypto.decrypt(guest.temporaryContactPhone, orgId),
            temporaryContactEmail = crypto.decrypt(guest.temporaryContactEmail, orgId),
            notes = crypto.decrypt(guest.notes, orgId),
            nfcCardUid = crypto.decrypt(guest.nfcCardUid, orgId),
        )
    }

    fun encryptVolunteerFields(volunteer: Volunteer): Volunteer {
        val orgId = volunteer.firebaseOrgId
        if (!crypto.isConfigured(orgId)) return volunteer
        return volunteer.copy(
            email = crypto.encrypt(volunteer.email, orgId),
            phoneNumber = crypto.encrypt(volunteer.phoneNumber, orgId),
            dateOfBirth = crypto.encrypt(volunteer.dateOfBirth, orgId),
            nfcCardUid = crypto.encrypt(volunteer.nfcCardUid, orgId),
            nfcCardUidHash = nfcLookupHash(volunteer.nfcCardUid, orgId),
        )
    }

    fun decryptVolunteerFields(volunteer: Volunteer): Volunteer {
        val orgId = volunteer.firebaseOrgId
        if (!crypto.isConfigured(orgId)) return volunteer
        return volunteer.copy(
            email = crypto.decrypt(volunteer.email, orgId),
            phoneNumber = crypto.decrypt(volunteer.phoneNumber, orgId),
            dateOfBirth = crypto.decrypt(volunteer.dateOfBirth, orgId),
            nfcCardUid = crypto.decrypt(volunteer.nfcCardUid, orgId),
        )
    }

    fun encryptTransferFields(transfer: AccountTransfer): AccountTransfer {
        val orgId = transfer.firebaseOrgId
        if (!crypto.isConfigured(orgId)) return transfer
        return transfer.copy(
            holderName = crypto.encrypt(transfer.holderName, orgId),
            description = crypto.encrypt(transfer.description, orgId),
            posItemsJson = crypto.encrypt(transfer.posItemsJson, orgId),
        )
    }

    fun decryptTransferFields(transfer: AccountTransfer): AccountTransfer {
        val orgId = transfer.firebaseOrgId
        if (!crypto.isConfigured(orgId)) return transfer
        return transfer.copy(
            holderName = crypto.decrypt(transfer.holderName, orgId),
            description = crypto.decrypt(transfer.description, orgId),
            posItemsJson = crypto.decrypt(transfer.posItemsJson, orgId),
        )
    }

    fun nfcLookupHash(uid: String, orgId: String): String =
        crypto.hashForLookup(NfcUid.normalize(uid), orgId)

    fun matchesNfcUid(storedUid: String, storedHash: String, scannedUid: String, orgId: String): Boolean {
        if (scannedUid.isBlank()) return false
        val normalized = NfcUid.normalize(scannedUid)
        if (storedUid.isNotBlank()) {
            val decrypted = crypto.decrypt(storedUid, orgId)
            if (NfcUid.matches(decrypted, normalized)) return true
            if (NfcUid.matches(storedUid, normalized)) return true
        }
        if (storedHash.isNotBlank()) {
            if (storedHash == nfcLookupHash(normalized, orgId)) return true
            // Truncated ACS reads store a 4-byte hash; phone/PC later scan the full UID.
            if (normalized.length == 14 || normalized.length == 20) {
                if (storedHash == nfcLookupHash(normalized.take(8), orgId)) return true
            }
        }
        return false
    }

    fun matchesNfcUid(guest: Guest, scannedUid: String): Boolean =
        matchesNfcUid(guest.nfcCardUid, guest.nfcCardUidHash, scannedUid, guest.firebaseOrgId)

    fun matchesNfcUid(volunteer: Volunteer, scannedUid: String): Boolean =
        matchesNfcUid(volunteer.nfcCardUid, volunteer.nfcCardUidHash, scannedUid, volunteer.firebaseOrgId)

    /** Firestore map: add *_enc fields and nfc hash; keep operational fields plaintext. */
    fun encryptGuestMap(data: Map<String, Any?>, orgId: String): Map<String, Any?> {
        if (!crypto.isConfigured(orgId)) return data
        val m = data.toMutableMap()
        stringField(data, "email")?.let { m["email_enc"] = crypto.encrypt(it, orgId); m.remove("email") }
        stringField(data, "phone")?.let { m["phone_enc"] = crypto.encrypt(it, orgId); m.remove("phone") }
        stringField(data, "temporaryContactPhone")?.let {
            m["temporaryContactPhone_enc"] = crypto.encrypt(it, orgId); m.remove("temporaryContactPhone")
        }
        stringField(data, "temporaryContactEmail")?.let {
            m["temporaryContactEmail_enc"] = crypto.encrypt(it, orgId); m.remove("temporaryContactEmail")
        }
        stringField(data, "notes")?.let { m["notes_enc"] = crypto.encrypt(it, orgId); m.remove("notes") }
        stringField(data, "nfcCardUid")?.let {
            m["nfcCardUid_enc"] = crypto.encrypt(it, orgId)
            m["nfcCardUidHash"] = nfcLookupHash(it, orgId)
            m.remove("nfcCardUid")
        }
        m["schemaVersion"] = 2
        return m
    }

    fun decryptGuestMap(data: Map<String, Any?>, orgId: String): Map<String, Any?> {
        val m = data.toMutableMap()
        encOrPlain(m, "email_enc", "email", orgId)
        encOrPlain(m, "phone_enc", "phone", orgId)
        encOrPlain(m, "temporaryContactPhone_enc", "temporaryContactPhone", orgId)
        encOrPlain(m, "temporaryContactEmail_enc", "temporaryContactEmail", orgId)
        encOrPlain(m, "notes_enc", "notes", orgId)
        encOrPlain(m, "nfcCardUid_enc", "nfcCardUid", orgId)
        return m
    }

    fun encryptVolunteerMap(data: Map<String, Any?>, orgId: String): Map<String, Any?> {
        if (!crypto.isConfigured(orgId)) return data
        val m = data.toMutableMap()
        stringField(data, "email")?.let { m["email_enc"] = crypto.encrypt(it, orgId); m.remove("email") }
        stringField(data, "phone")?.let { m["phone_enc"] = crypto.encrypt(it, orgId); m.remove("phone") }
        stringField(data, "dateOfBirth")?.let { m["dateOfBirth_enc"] = crypto.encrypt(it, orgId); m.remove("dateOfBirth") }
        stringField(data, "gender")?.let { m["gender_enc"] = crypto.encrypt(it, orgId); m.remove("gender") }
        stringField(data, "nfcCardUid")?.let {
            m["nfcCardUid_enc"] = crypto.encrypt(it, orgId)
            m["nfcCardUidHash"] = nfcLookupHash(it, orgId)
            m.remove("nfcCardUid")
        }
        m["schemaVersion"] = 2
        return m
    }

    fun decryptVolunteerMap(data: Map<String, Any?>, orgId: String): Map<String, Any?> {
        val m = data.toMutableMap()
        encOrPlain(m, "email_enc", "email", orgId)
        encOrPlain(m, "phone_enc", "phone", orgId)
        encOrPlain(m, "dateOfBirth_enc", "dateOfBirth", orgId)
        encOrPlain(m, "gender_enc", "gender", orgId)
        encOrPlain(m, "nfcCardUid_enc", "nfcCardUid", orgId)
        return m
    }

    fun encryptTransferMap(data: Map<String, Any?>, orgId: String): Map<String, Any?> {
        if (!crypto.isConfigured(orgId)) return data
        val m = data.toMutableMap()
        stringField(data, "holderName")?.let { m["holderName_enc"] = crypto.encrypt(it, orgId); m.remove("holderName") }
        stringField(data, "description")?.let { m["description_enc"] = crypto.encrypt(it, orgId); m.remove("description") }
        stringField(data, "posItemsJson")?.let { m["posItemsJson_enc"] = crypto.encrypt(it, orgId); m.remove("posItemsJson") }
        m["schemaVersion"] = 2
        return m
    }

    fun decryptTransferMap(data: Map<String, Any?>, orgId: String): Map<String, Any?> {
        val m = data.toMutableMap()
        encOrPlain(m, "holderName_enc", "holderName", orgId)
        encOrPlain(m, "description_enc", "description", orgId)
        encOrPlain(m, "posItemsJson_enc", "posItemsJson", orgId)
        return m
    }

    fun encryptPayloadJson(json: String, orgId: String): String {
        if (!crypto.isConfigured(orgId) || json.isBlank()) return json
        return crypto.encrypt(json, orgId)
    }

    fun decryptPayloadJson(json: String, orgId: String): String {
        if (!crypto.isConfigured(orgId) || json.isBlank()) return json
        return crypto.decrypt(json, orgId)
    }

    private fun stringField(data: Map<String, Any?>, key: String): String? =
        data[key]?.toString()?.takeIf { it.isNotEmpty() }

    private fun encOrPlain(m: MutableMap<String, Any?>, encKey: String, plainKey: String, orgId: String) {
        val enc = stringField(m, encKey)
        if (enc != null) {
            m[plainKey] = crypto.decrypt(enc, orgId)
        }
    }
}
