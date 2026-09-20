package com.eventmanager.app.data.drive

import com.google.api.client.googleapis.json.GoogleJsonResponseException

/** Which Google API produced the failure — used to pick a localized explanation. */
enum class WorkspaceApi { DRIVE, DOCS, SHEETS, PEOPLE }

enum class WorkspaceApiErrorKind {
    /** The institution's Cloud project has not enabled this API. */
    SERVICE_DISABLED,
    PERMISSION_DENIED,
    NOT_FOUND,
    OTHER,
}

/**
 * A short, typed stand-in for the multi-kilobyte JSON Google puts on [Throwable.message].
 * The UI localizes [kind]; [message] is only a log/fallback line.
 */
class WorkspaceApiException(
    val kind: WorkspaceApiErrorKind,
    val api: WorkspaceApi,
    override val message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    val hidesRecentFilesList: Boolean
        get() = api == WorkspaceApi.DRIVE &&
            (kind == WorkspaceApiErrorKind.SERVICE_DISABLED || kind == WorkspaceApiErrorKind.PERMISSION_DENIED)
}

object GoogleWorkspaceApiErrors {

    fun map(throwable: Throwable, api: WorkspaceApi): WorkspaceApiException {
        if (throwable is WorkspaceApiException) return throwable
        val haystack = haystack(throwable)
        val kind = when {
            containsAny(
                haystack,
                "SERVICE_DISABLED",
                "accessNotConfigured",
                "has not been used in project",
                "it is disabled",
            ) -> WorkspaceApiErrorKind.SERVICE_DISABLED
            containsAny(haystack, "NOT_FOUND", "\"code\": 404", "HttpResponseException 404") ->
                WorkspaceApiErrorKind.NOT_FOUND
            containsAny(haystack, "PERMISSION_DENIED", "ACCESS_DENIED", "insufficientPermissions") ->
                WorkspaceApiErrorKind.PERMISSION_DENIED
            else -> WorkspaceApiErrorKind.OTHER
        }
        return WorkspaceApiException(
            kind = kind,
            api = api,
            message = shortMessage(kind, api),
            cause = throwable,
        )
    }

    private fun haystack(throwable: Throwable): String = buildString {
        var current: Throwable? = throwable
        while (current != null) {
            append(current.message.orEmpty())
            append('\n')
            val google = current as? GoogleJsonResponseException
            if (google != null) {
                append(google.statusCode)
                append(' ')
                append(google.statusMessage.orEmpty())
                append(' ')
                append(google.details?.message.orEmpty())
                google.details?.errors.orEmpty().forEach { error ->
                    append(' ')
                    append(error.reason.orEmpty())
                    append(' ')
                    append(error.message.orEmpty())
                }
            }
            current = current.cause
        }
    }

    private fun containsAny(haystack: String, vararg needles: String): Boolean =
        needles.any { haystack.contains(it, ignoreCase = true) }

    private fun shortMessage(kind: WorkspaceApiErrorKind, api: WorkspaceApi): String = when (kind) {
        WorkspaceApiErrorKind.SERVICE_DISABLED -> "${api.name} API is not enabled on this Cloud project"
        WorkspaceApiErrorKind.PERMISSION_DENIED -> "Google denied access to this ${api.name.lowercase()} resource"
        WorkspaceApiErrorKind.NOT_FOUND -> "${api.name} document was not found"
        WorkspaceApiErrorKind.OTHER -> "Google ${api.name.lowercase()} request failed"
    }
}
