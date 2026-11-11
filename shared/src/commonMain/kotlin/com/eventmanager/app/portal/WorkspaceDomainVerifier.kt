package com.eventmanager.app.portal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

enum class DomainCheck { GOOGLE, OTHER, NOT_FOUND, ERROR }

/**
 * Tells whether a domain's mail is handled by Google Workspace, by looking at its MX records
 * through Google's public DNS-over-HTTPS endpoint. Only the domain name leaves the device.
 */
object WorkspaceDomainVerifier {
    private const val RESOLVER = "https://dns.google/resolve"
    private const val MX_TYPE = 15
    private const val NX_DOMAIN = 3
    private val consumerDomains = setOf("gmail.com", "googlemail.com")

    /** A Workspace domain is a real domain (with a dot) and never the consumer Gmail ones. */
    fun isAcceptedDomain(domain: String): Boolean {
        val d = domain.trim().lowercase()
        return d.contains('.') && d !in consumerDomains
    }

    fun parse(json: String): DomainCheck {
        val root = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull()
            ?: return DomainCheck.ERROR
        val status = runCatching { root["Status"]?.jsonPrimitive?.int }.getOrNull()
            ?: return DomainCheck.ERROR
        if (status == NX_DOMAIN) return DomainCheck.NOT_FOUND
        if (status != 0) return DomainCheck.ERROR
        val answers = root["Answer"] as? JsonArray ?: return DomainCheck.OTHER
        val google = answers.any { element ->
            val record = runCatching { element.jsonObject }.getOrNull() ?: return@any false
            val type = runCatching { record["type"]?.jsonPrimitive?.int }.getOrNull()
            if (type != MX_TYPE) return@any false
            val host = runCatching { record["data"]?.jsonPrimitive?.content }.getOrNull()
                ?.trim()?.lowercase()?.trimEnd('.')?.substringAfterLast(' ')
                ?: return@any false
            host == "google.com" || host.endsWith(".google.com") ||
                host == "googlemail.com" || host.endsWith(".googlemail.com")
        }
        return if (google) DomainCheck.GOOGLE else DomainCheck.OTHER
    }

    suspend fun check(domain: String, fetch: suspend (String) -> String? = ::httpGet): DomainCheck {
        val name = URLEncoder.encode(domain.trim().lowercase(), "UTF-8")
        val body = runCatching { fetch("$RESOLVER?name=$name&type=MX") }.getOrNull()
            ?: return DomainCheck.ERROR
        return parse(body)
    }

    private suspend fun httpGet(url: String): String? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Accept", "application/dns-json")
            }
            if (connection.responseCode !in 200..299) null
            else connection.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
