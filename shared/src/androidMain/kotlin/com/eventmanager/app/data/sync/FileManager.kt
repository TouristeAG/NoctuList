package com.eventmanager.app.data.sync

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Manages file operations for the app, including JSON key file handling
 */
class FileManager(private val context: Context) {
    
    companion object {
        private const val TAG = "FileManager"
        private const val ASSETS_FOLDER = "assets"
        private const val SERVICE_ACCOUNT_KEY_FILE = "service_account_key.json"
    }
    
    /**
     * Copy a file from URI to the assets folder
     */
    suspend fun copyFileToAssets(uri: Uri, fileName: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            if (inputStream == null) {
                return@withContext Result.failure(Exception("Could not open file"))
            }
            
            // Create assets directory if it doesn't exist
            val assetsDir = File(context.filesDir, ASSETS_FOLDER)
            if (!assetsDir.exists()) {
                assetsDir.mkdirs()
            }
            
            val destinationFile = File(assetsDir, fileName)
            
            // Copy file
            inputStream.use { input ->
                FileOutputStream(destinationFile).use { output ->
                    input.copyTo(output)
                }
            }
            
            Log.d(TAG, "File copied successfully to: ${destinationFile.absolutePath}")
            Result.success(destinationFile.absolutePath)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error copying file", e)
            Result.failure(e)
        }
    }
    
    /**
     * Check if service account key file exists
     */
    fun hasServiceAccountKey(): Boolean {
        val assetsDir = File(context.filesDir, ASSETS_FOLDER)
        val keyFile = File(assetsDir, SERVICE_ACCOUNT_KEY_FILE)
        return keyFile.exists()
    }
    
    /**
     * Get the path to the service account key file
     */
    fun getServiceAccountKeyPath(): String? {
        val assetsDir = File(context.filesDir, ASSETS_FOLDER)
        val keyFile = File(assetsDir, SERVICE_ACCOUNT_KEY_FILE)
        return if (keyFile.exists()) keyFile.absolutePath else null
    }
    
    /**
     * The e-mail and project of the stored service account key, or null when there is no key or it
     * cannot be read. Used to show the key status after a restart.
     */
    suspend fun readStoredKeyInfo(): JsonKeyInfo? = withContext(Dispatchers.IO) {
        val path = getServiceAccountKeyPath() ?: return@withContext null
        runCatching {
            val json = File(path).readText()
            val clientEmail = "\"client_email\"\\s*:\\s*\"([^\"]+)\"".toRegex().find(json)?.groupValues?.get(1)
            val projectId = "\"project_id\"\\s*:\\s*\"([^\"]+)\"".toRegex().find(json)?.groupValues?.get(1)
            if (clientEmail.isNullOrBlank()) null else JsonKeyInfo(clientEmail, projectId.orEmpty())
        }.getOrNull()
    }

    /**
     * Validate JSON key file content
     */
    suspend fun validateJsonKeyFile(uri: Uri): Result<JsonKeyInfo> = withContext(Dispatchers.IO) {
        try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            if (inputStream == null) {
                return@withContext Result.failure(Exception("Could not open file"))
            }
            
            val jsonContent = inputStream.bufferedReader().use { it.readText() }
            
            // Basic JSON validation
            if (!jsonContent.trim().startsWith("{") || !jsonContent.trim().endsWith("}")) {
                return@withContext Result.failure(Exception("Invalid JSON format"))
            }
            
            // Check for required fields
            val requiredFields = listOf("type", "project_id", "private_key_id", "private_key", "client_email", "client_id")
            val missingFields = requiredFields.filter { !jsonContent.contains("\"$it\"") }
            
            if (missingFields.isNotEmpty()) {
                return@withContext Result.failure(Exception("Missing required fields: ${missingFields.joinToString(", ")}"))
            }
            
            // Extract client email for display
            val clientEmailRegex = "\"client_email\"\\s*:\\s*\"([^\"]+)\"".toRegex()
            val clientEmail = clientEmailRegex.find(jsonContent)?.groupValues?.get(1) ?: "Unknown"
            
            // Extract project ID
            val projectIdRegex = "\"project_id\"\\s*:\\s*\"([^\"]+)\"".toRegex()
            val projectId = projectIdRegex.find(jsonContent)?.groupValues?.get(1) ?: "Unknown"
            
            Result.success(JsonKeyInfo(clientEmail, projectId))
            
        } catch (e: Exception) {
            Log.e(TAG, "Error validating JSON key file", e)
            Result.failure(e)
        }
    }
    
}

data class JsonKeyInfo(
    val clientEmail: String,
    val projectId: String
)
