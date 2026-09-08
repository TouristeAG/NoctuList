package com.eventmanager.app.data.update

import com.eventmanager.app.platform.AppBuildInfo
import com.eventmanager.app.platform.DesktopFileActions
import com.eventmanager.app.platform.PlatformContext
import com.eventmanager.app.platform.PlatformFileManager
import com.eventmanager.app.data.sync.settingsManagerFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

actual class UpdateChecker actual constructor(private val platformContext: PlatformContext) {
    actual suspend fun checkForUpdates(): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            val settingsManager = settingsManagerFor(platformContext)
            val manifestUrl = settingsManager.getUpdateManifestUrl()
            val connection = (URL(manifestUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                requestMethod = "GET"
            }

            connection.inputStream.use { inputStream ->
                val reader = BufferedReader(InputStreamReader(inputStream))
                val response = buildString {
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        append(line)
                    }
                }

                val manifest = UpdateManifestEvaluator.parseManifest(response)
                UpdateManifestEvaluator.evaluate(
                    manifest = manifest,
                    currentVersionCode = AppBuildInfo.VERSION_CODE,
                    preferDesktopArtifact = true,
                )
            }
        } catch (e: Exception) {
            UpdateCheckResult.Error(e.message ?: "Unknown error while checking for updates")
        }
    }
}

actual class UpdateDownloader actual constructor(private val platformContext: PlatformContext) {
    private val updatesDir = PlatformFileManager(platformContext).getUpdatesDirectory()

    actual suspend fun downloadUpdate(downloadUrl: String): Flow<DownloadState> = flow {
        emit(DownloadState.Idle)
        try {
            val url = URL(downloadUrl)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 30_000
                requestMethod = "GET"
                doInput = true
            }
            connection.connect()
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                emit(DownloadState.Error("Server returned HTTP ${connection.responseCode}"))
                return@flow
            }

            val fileLength = connection.contentLength
            val extension = url.path.substringAfterLast('.', "").ifBlank { "bin" }
            val outputFile = File(updatesDir, "noctulist-update.$extension")
            if (outputFile.exists()) {
                outputFile.delete()
            }

            connection.inputStream.use { input ->
                FileOutputStream(outputFile).use { output ->
                    val buffer = ByteArray(8192)
                    var totalBytesRead = 0L
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                        if (fileLength > 0) {
                            emit(DownloadState.Downloading(((totalBytesRead * 100) / fileLength).toInt()))
                        }
                    }
                    output.flush()
                }
            }
            emit(DownloadState.Downloaded(outputFile.absolutePath))
        } catch (e: Exception) {
            emit(DownloadState.Error(e.message ?: "Unknown download error"))
        }
    }.flowOn(Dispatchers.IO)

    actual fun installUpdate(filePath: String) {
        runCatching {
            val file = File(filePath)
            if (!file.exists()) return

            if (file.name.endsWith(".AppImage", ignoreCase = true)) {
                file.setExecutable(true)
            }

            DesktopFileActions.open(file)
        }
    }
}
