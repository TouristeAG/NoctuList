package com.eventmanager.app.portal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

sealed class PortalAuthResult {
    data class Created(val uid: String) : PortalAuthResult()
    /** The e-mail already has a portal account; it gets linked when its owner signs in. */
    data object EmailExists : PortalAuthResult()
    data object Sent : PortalAuthResult()
    data class Failed(val code: String) : PortalAuthResult()
}

/** Account operations the portal needs from Firebase Authentication. */
interface PortalAuthApi {
    suspend fun createAccount(email: String, password: String): PortalAuthResult
    suspend fun sendPasswordReset(email: String, languageCode: String = "fr"): PortalAuthResult
}

/**
 * Firebase Authentication REST calls the admin device makes for volunteer accounts. Uses only the
 * public Web API key, so the admin's own Google session is never touched or replaced.
 */
class PortalAuthClient(
    private val apiKey: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val timeoutMs: Int = 15_000,
) : PortalAuthApi {
    /** Creates an e-mail/password account. The password never leaves this call. */
    override suspend fun createAccount(email: String, password: String): PortalAuthResult {
        val body = buildJsonObject {
            put("email", email.trim().lowercase())
            put("password", password)
            put("returnSecureToken", false)
        }
        return when (val response = post("accounts:signUp", body, locale = null)) {
            is HttpOutcome.Ok -> {
                val uid = response.json["localId"]?.jsonPrimitive?.content.orEmpty()
                if (uid.isBlank()) PortalAuthResult.Failed("NO_UID") else PortalAuthResult.Created(uid)
            }
            is HttpOutcome.Error -> if (response.code == "EMAIL_EXISTS") {
                PortalAuthResult.EmailExists
            } else {
                PortalAuthResult.Failed(response.code)
            }
        }
    }

    /**
     * Sends Firebase's "reset your password" e-mail. Used both for an admin reset and as the
     * "set your password" mail after an account was created from the app.
     */
    override suspend fun sendPasswordReset(email: String, languageCode: String): PortalAuthResult {
        val body = buildJsonObject {
            put("requestType", "PASSWORD_RESET")
            put("email", email.trim().lowercase())
        }
        return when (val response = post("accounts:sendOobCode", body, locale = languageCode)) {
            is HttpOutcome.Ok -> PortalAuthResult.Sent
            is HttpOutcome.Error -> PortalAuthResult.Failed(response.code)
        }
    }

    private sealed class HttpOutcome {
        data class Ok(val json: JsonObject) : HttpOutcome()
        data class Error(val code: String) : HttpOutcome()
    }

    private suspend fun post(method: String, body: JsonObject, locale: String?): HttpOutcome =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) return@withContext HttpOutcome.Error("MISSING_API_KEY")
            val url = URL("${baseUrl.trimEnd('/')}/$method?key=${URLEncoder.encode(apiKey, "UTF-8")}")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                locale?.takeIf { it.isNotBlank() }?.let { setRequestProperty("X-Firebase-Locale", it) }
            }
            try {
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (status in 200..299) {
                    HttpOutcome.Ok(runCatching { Json.parseToJsonElement(text).jsonObject }.getOrDefault(JsonObject(emptyMap())))
                } else {
                    HttpOutcome.Error(parseErrorCode(text).ifBlank { "HTTP_$status" })
                }
            } catch (e: java.io.IOException) {
                HttpOutcome.Error("NETWORK")
            } finally {
                connection.disconnect()
            }
        }

    companion object {
        const val DEFAULT_BASE_URL = "https://identitytoolkit.googleapis.com/v1"

        /** `{"error":{"message":"EMAIL_EXISTS"}}` → `EMAIL_EXISTS`; `"WEAK_PASSWORD : …"` → `WEAK_PASSWORD`. */
        internal fun parseErrorCode(body: String): String = runCatching {
            Json.parseToJsonElement(body).jsonObject["error"]!!.jsonObject["message"]!!
                .jsonPrimitive.content.substringBefore(":").trim()
        }.getOrDefault("")
    }
}
