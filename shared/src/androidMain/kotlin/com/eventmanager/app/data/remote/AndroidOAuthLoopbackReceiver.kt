package com.eventmanager.app.data.remote

import android.net.Uri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean

internal sealed class LoopbackOAuthResult {
    data class Success(val code: String, val redirectUri: String) : LoopbackOAuthResult()
    data class OAuthError(val error: String, val description: String?) : LoopbackOAuthResult()
    data object TimedOut : LoopbackOAuthResult()
    data object Cancelled : LoopbackOAuthResult()
}

/**
 * Localhost OAuth callback server (same ports/URIs as Desktop [LocalServerReceiver]).
 * Chrome Custom Tabs redirects to http://localhost:PORT/Callback after Google Sign-In.
 */
internal class AndroidOAuthLoopbackReceiver {
    private var serverSocket: ServerSocket? = null
    private var redirectUri: String = ""
    private val resultDeferred = CompletableDeferred<LoopbackOAuthResult>()
    private val stopped = AtomicBoolean(false)
    private var acceptThread: Thread? = null

    val activeRedirectUri: String
        get() = redirectUri

    fun start(): String {
        check(serverSocket == null) { "Loopback receiver already started" }
        for (port in InstitutionGoogleWebOAuth.LOOPBACK_PORTS) {
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
                    InstitutionGoogleWebOAuth.LOOPBACK_PORTS.joinToString() +
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
                    val body = OAuthCallbackHtml.page(success = result is LoopbackOAuthResult.Success)
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
                        resultDeferred.complete(LoopbackOAuthResult.Cancelled)
                    }
                } finally {
                    stop()
                }
            },
            "oauth-loopback-accept",
        ).also { it.isDaemon = true; it.start() }
        return redirectUri
    }

    suspend fun awaitResult(timeoutMs: Long = DEFAULT_TIMEOUT_MS): LoopbackOAuthResult =
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(timeoutMs) { resultDeferred.await() } ?: run {
                stop()
                LoopbackOAuthResult.TimedOut
            }
        }

    fun cancel() {
        if (!resultDeferred.isCompleted) {
            resultDeferred.complete(LoopbackOAuthResult.Cancelled)
        }
        stop()
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread = null
    }

    private fun parseRequestLine(requestLine: String, redirectUri: String): LoopbackOAuthResult {
        // GET /Callback?code=... HTTP/1.1
        val pathAndQuery = requestLine.split(" ").getOrNull(1).orEmpty()
        val uri = Uri.parse("http://localhost$pathAndQuery")
        val error = uri.getQueryParameter("error")
        if (!error.isNullOrBlank()) {
            return LoopbackOAuthResult.OAuthError(
                error = error,
                description = uri.getQueryParameter("error_description"),
            )
        }
        val code = uri.getQueryParameter("code")
        if (code.isNullOrBlank()) {
            return LoopbackOAuthResult.OAuthError(
                error = "missing_code",
                description = "OAuth callback had no authorization code",
            )
        }
        return LoopbackOAuthResult.Success(code = code, redirectUri = redirectUri)
    }

    companion object {
        private const val ACCEPT_TIMEOUT_MS = 180_000
        private const val DEFAULT_TIMEOUT_MS = 180_000L
    }
}
