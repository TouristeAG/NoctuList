package com.eventmanager.app.platform

import com.russhwolf.settings.PreferencesSettings
import java.util.prefs.Preferences

actual fun createAppStorage(context: PlatformContext): AppStorage {
    val prefs = Preferences.userRoot().node(desktopPreferencesNode())
    return SettingsAppStorage(PreferencesSettings(prefs))
}

private class SettingsAppStorage(private val settings: com.russhwolf.settings.Settings) : AppStorage {
    override fun getString(key: String, default: String) = settings.getStringOrNull(key) ?: default
    override fun putString(key: String, value: String) = settings.putString(key, value)
    override fun getBoolean(key: String, default: Boolean) = settings.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) = settings.putBoolean(key, value)
    override fun getInt(key: String, default: Int) = settings.getInt(key, default)
    override fun putInt(key: String, value: Int) = settings.putInt(key, value)
    override fun getLong(key: String, default: Long) = settings.getLong(key, default)
    override fun putLong(key: String, value: Long) = settings.putLong(key, value)
    override fun getFloat(key: String, default: Float) = settings.getFloat(key, default)
    override fun putFloat(key: String, value: Float) = settings.putFloat(key, value)
    override fun remove(key: String) = settings.remove(key)
    override fun contains(key: String) = settings.hasKey(key)
    override fun getStringSet(key: String, default: Set<String>): Set<String> {
        val raw = settings.getStringOrNull(key) ?: return default
        if (raw.isEmpty()) return emptySet()
        return raw.split('\u001F').filter { it.isNotEmpty() }.toSet()
    }
    override fun putStringSet(key: String, value: Set<String>) =
        settings.putString(key, value.joinToString("\u001F"))
    override fun clear() = settings.clear()
}
