package com.eventmanager.app.data.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicBoolean

internal sealed class DesktopLoopbackOAuthResult {
    data class Success(val code: String, val redirectUri: String) : DesktopLoopbackOAuthResult()
    data class OAuthError(val error: String, val description: String?) : DesktopLoopbackOAuthResult()
    data object TimedOut : DesktopLoopbackOAuthResult()
    data object Cancelled : DesktopLoopbackOAuthResult()
}

/**
 * Localhost OAuth callback server using JDK [ServerSocket] only.
 *
 * Replaces Jetty [com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver],
 * which fails to start in jpackage release builds on macOS, Windows, and Linux (trimmed jlink runtime).
 * Same ports and redirect URIs as [AndroidOAuthLoopbackReceiver].
 */
internal class DesktopOAuthLoopbackReceiver(
    private val ports: List<Int> = InstitutionGoogleWebOAuth.LOOPBACK_PORTS,
) {
    private var serverSocket: ServerSocket? = null
    private var redirectUri: String = ""
    private val resultDeferred = CompletableDeferred<DesktopLoopbackOAuthResult>()
    private val stopped = AtomicBoolean(false)
    private var acceptThread: Thread? = null

    val activeRedirectUri: String
        get() = redirectUri

    fun start(): String {
        check(serverSocket == null) { "Loopback receiver already started" }
        for (port in ports) {
            val opened = runCatching {
                ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).apply {
                    soTimeout = ACCEPT_TIMEOUT_MS
                }
            }.getOrNull()
            if (opened != null) {
                serverSocket = opened
                redirectUri = InstitutionGoogleWebOAuth.loopbackRedirectUri(port)
                break
            }
        }
        val socket = serverSocket
            ?: error(
                "Could not start OAuth callback on localhost ports " +
                    ports.joinToString() +
                    ". Close other NoctuList sign-in attempts and retry.",
            )
        acceptThread = Thread(
            {
                try {
                    val client = socket.accept()
                    client.soTimeout = 10_000
                    val requestLine = BufferedReader(InputStreamReader(client.getInputStream()))
                        .readLine()
                        .orEmpty()
                    val result = parseRequestLine(requestLine, redirectUri)
                    val body = OAuthCallbackHtml.page(success = result is DesktopLoopbackOAuthResult.Success)
                    val response = buildString {
                        append("HTTP/1.1 200 OK\r\n")
                        append("Content-Type: text/html; charset=utf-8\r\n")
                        append("Connection: close\r\n")
                        append("Content-Length: ")
                        append(body.toByteArray(Charsets.UTF_8).size)
                        append("\r\n\r\n")
                        append(body)
                    }
                    client.getOutputStream().use { it.write(response.toByteArray(Charsets.UTF_8)) }
                    client.close()
                    if (!resultDeferred.isCompleted) {
                        resultDeferred.complete(result)
                    }
                } catch (_: Exception) {
                    if (!resultDeferred.isCompleted) {
                        resultDeferred.complete(DesktopLoopbackOAuthResult.Cancelled)
                    }
                } finally {
                    stop()
                }
            },
            "desktop-oauth-loopback-accept",
        ).also { it.isDaemon = true; it.start() }
        return redirectUri
    }

    fun waitForResult(timeoutMs: Long = DEFAULT_TIMEOUT_MS): DesktopLoopbackOAuthResult =
        runBlocking {
            withTimeoutOrNull(timeoutMs) {
                withContext(Dispatchers.IO) { resultDeferred.await() }
            } ?: run {
                stop()
                DesktopLoopbackOAuthResult.TimedOut
            }
        }

    suspend fun awaitResult(timeoutMs: Long = DEFAULT_TIMEOUT_MS): DesktopLoopbackOAuthResult =
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(timeoutMs) { resultDeferred.await() } ?: run {
                stop()
                DesktopLoopbackOAuthResult.TimedOut
            }
        }

    fun cancel() {
        if (!resultDeferred.isCompleted) {
            resultDeferred.complete(DesktopLoopbackOAuthResult.Cancelled)
        }
        stop()
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread = null
    }

    private fun parseRequestLine(requestLine: String, redirectUri: String): DesktopLoopbackOAuthResult {
        // GET /Callback?code=... HTTP/1.1
        val pathAndQuery = requestLine.split(" ").getOrNull(1).orEmpty()
        val query = pathAndQuery.substringAfter('?', "")
        val params = parseQueryString(query)
        val error = params["error"]
        if (!error.isNullOrBlank()) {
            return DesktopLoopbackOAuthResult.OAuthError(
                error = error,
                description = params["error_description"],
            )
        }
        val code = params["code"]
        if (code.isNullOrBlank()) {
            return DesktopLoopbackOAuthResult.OAuthError(
                error = "missing_code",
                description = "OAuth callback had no authorization code",
            )
        }
        return DesktopLoopbackOAuthResult.Success(code = code, redirectUri = redirectUri)
    }

    companion object {
        private const val ACCEPT_TIMEOUT_MS = 180_000
        private const val DEFAULT_TIMEOUT_MS = 180_000L

        private fun parseQueryString(query: String): Map<String, String> {
            if (query.isBlank()) return emptyMap()
            return query.split("&").mapNotNull { part ->
                val idx = part.indexOf('=')
                if (idx <= 0) return@mapNotNull null
                val key = URLDecoder.decode(part.substring(0, idx), Charsets.UTF_8.name())
                val value = URLDecoder.decode(part.substring(idx + 1), Charsets.UTF_8.name())
                key to value
            }.toMap()
        }
    }
}
