package com.eventmanager.app.data.models

import com.eventmanager.app.data.sync.InstitutionLogoStore
import com.eventmanager.app.utils.ProfilePhotoCodec

/**
 * Public-form logos travel as Firestore string fields. A single field cannot exceed ~1 MiB,
 * and a high-res association PNG on disk routinely does. Shrink (or drop) before publish.
 */
object GuestFormLogoCodec {
    /** Stay well under the Firestore field cap so two logos still fit in one document. */
    const val MAX_DATA_URI_CHARS = 350_000

    fun fitForFirestore(raw: String): String {
        val uri = toDataUri(raw)
        if (uri.isEmpty()) return ""
        if (uri.length <= MAX_DATA_URI_CHARS) return uri
        val bytes = decodeBytes(uri) ?: return ""
        val jpeg = ProfilePhotoCodec.compressToGuestFormLogoJpeg(bytes) ?: run {
            println("Guest form logo dropped: could not compress a ${uri.length}-char payload")
            return ""
        }
        val out = "data:image/jpeg;base64,${InstitutionLogoStore.encode(jpeg)}"
        return if (out.length <= MAX_DATA_URI_CHARS) {
            out
        } else {
            println("Guest form logo dropped: compressed URI still ${out.length} chars")
            ""
        }
    }

    fun toDataUri(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.startsWith("data:", ignoreCase = true)) return trimmed
        return "data:image/png;base64,$trimmed"
    }

    fun decodeBytes(raw: String): ByteArray? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val base64 = if (trimmed.startsWith("data:", ignoreCase = true)) {
            trimmed.substringAfter(',', missingDelimiterValue = "").trim()
        } else {
            trimmed
        }
        if (base64.isEmpty()) return null
        return InstitutionLogoStore.decode(base64)
    }
}
