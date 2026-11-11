package com.eventmanager.app.setup

import java.io.File

/** What the step runner needs from the Firebase CLI ([FirebaseCli] in production). */
interface SetupCli {
    /** Starts the paste-the-code sign-in (the CLI cannot open a browser from the app). */
    suspend fun loginStart(): LoginChallenge
    suspend fun loginFinish(code: String)
    suspend fun loginList(): List<String>
    suspend fun projectsList(): List<ProjectInfo>
    suspend fun projectsCreate(id: String, displayName: String, onLine: (String) -> Unit)
    suspend fun databases(projectId: String): List<DatabaseInfo>
    suspend fun firestoreLocations(projectId: String): List<String>
    suspend fun createDatabase(projectId: String, location: String, allowed: List<String>, onLine: (String) -> Unit)
    suspend fun webApps(projectId: String): List<String>
    suspend fun createWebApp(projectId: String, onLine: (String) -> Unit): String
    suspend fun sdkConfig(projectId: String, appId: String): WebConfig
    suspend fun deploy(projectId: String, dir: File, only: String, onLine: (String) -> Unit)
    suspend fun setSecret(projectId: String, dir: File, name: String, value: CharArray, onLine: (String) -> Unit)
    suspend fun npmInstall(dir: File, onLine: (String) -> Unit)
    suspend fun functionsList(projectId: String): List<FunctionInfo>
    suspend fun setArtifactPolicy(projectId: String, region: String, onLine: (String) -> Unit)
}

/** Finds or installs Node.js and the Firebase CLI ([ToolsInstaller] in production). */
interface SetupTools {
    suspend fun detect(): ToolPaths?
    suspend fun install(onProgress: (Float, String) -> Unit): ToolPaths
}

/** The few app settings the assistant reads and fills. */
interface SetupSettings {
    var projectId: String
    fun setWebConfig(apiKey: String, appId: String)
    fun setWebClient(id: String, secret: String)
    fun hasWebClient(): Boolean
    fun loadProgress(): String
    fun saveProgress(json: String)
    fun enableProfilePhotos()
}
