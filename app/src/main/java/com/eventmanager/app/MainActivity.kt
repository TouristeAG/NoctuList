package com.eventmanager.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.eventmanager.app.data.sync.FileAppLogger
import com.eventmanager.app.data.sync.SettingsManager
import com.eventmanager.app.data.utils.AppIconManager
import com.eventmanager.app.platform.AndroidFragmentActivityProvider
import com.eventmanager.app.platform.createAppStorage
import com.eventmanager.app.platform.createPlatformContext
import com.eventmanager.app.ui.AppRoot
import com.eventmanager.app.ui.AdminSessionHost
import com.eventmanager.app.ui.AdminSessionWatchdog
import com.eventmanager.app.ui.theme.EventManagerTheme
import com.eventmanager.app.ui.theme.ThemeMode
import com.eventmanager.app.ui.platform.AppAppearanceState
import com.eventmanager.app.ui.platform.bootstrapAppLocale
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class MainActivity : FragmentActivity(), AdminSessionHost {

    override val adminSessionWatchdog = AdminSessionWatchdog()
    override var adminSessionAutoLogout: (() -> Unit)? = null

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                adminSessionWatchdog.onDisplayTurnedOff()
            }
        }
    }

    private var screenOffReceiverRegistered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyLanguageSettings()
        applyResolutionScaling()
        
        val platformContext = createPlatformContext(this)
        val settingsManager = SettingsManager(createAppStorage(platformContext))
        FileAppLogger.init(this, settingsManager)

        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { applyAppIconSettings() }
        }

        if (!screenOffReceiverRegistered) {
            val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenOffReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(screenOffReceiver, filter)
            }
            screenOffReceiverRegistered = true
        }

        setContent {
            var themeModeString by remember { mutableStateOf(settingsManager.getThemeMode()) }
            val themeRefreshNonce by AppAppearanceState::themeRefreshNonce

            LaunchedEffect(themeRefreshNonce) {
                themeModeString = settingsManager.getThemeMode()
            }

            EventManagerTheme(
                themeMode = ThemeMode.fromString(themeModeString),
                platformContext = platformContext,
                settingsManager = settingsManager,
                themeRefreshNonce = themeRefreshNonce,
            ) {
                AppRoot(
                    platformContext = platformContext,
                    onThemeModeChanged = { themeModeString = it }
                )
            }
        }
    }

    override fun onDestroy() {
        if (screenOffReceiverRegistered) {
            unregisterReceiver(screenOffReceiver)
            screenOffReceiverRegistered = false
        }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        AndroidFragmentActivityProvider.current = this
        if (adminSessionWatchdog.consumeLogoutAfterSleepIfPending()) {
            adminSessionAutoLogout?.invoke()
        }
    }

    override fun onPause() {
        AndroidFragmentActivityProvider.current = null
        super.onPause()
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        val result = super.dispatchTouchEvent(ev)
        if (ev != null && adminSessionWatchdog.monitoring) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_POINTER_DOWN -> adminSessionWatchdog.onUserInput()
            }
        }
        return result
    }

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(applyLanguageToContext(applyResolutionScalingToContext(newBase)))
    }
    
    @Suppress("DEPRECATION")
    private fun applyLanguageSettings() {
        val settingsManager = SettingsManager(createAppStorage(createPlatformContext(this)))
        val language = settingsManager.getLanguage()
        bootstrapAppLocale(language)
        val locale = createLocaleFromLanguageCode(language)
        Locale.setDefault(locale)
        val config = Configuration(resources.configuration)
        config.setLocale(locale)
        resources.updateConfiguration(config, resources.displayMetrics)
    }
    
    private fun createLocaleFromLanguageCode(languageCode: String): Locale = when {
            languageCode.equals("en", ignoreCase = true) -> Locale("en", "GB")
        languageCode.contains("-") -> languageCode.split("-").let { Locale(it[0], it[1]) }
        languageCode.contains("_") -> languageCode.split("_").let { Locale(it[0], it[1]) }
            else -> Locale(languageCode)
    }
    
    @Suppress("DEPRECATION")
    private fun applyResolutionScaling() {
        val settingsManager = SettingsManager(createAppStorage(createPlatformContext(this)))
        val resolutionScale = settingsManager.getResolutionScale()
        val metrics = resources.displayMetrics
        metrics.density = metrics.density / resolutionScale
        metrics.scaledDensity = metrics.scaledDensity / resolutionScale
    }

    private fun applyResolutionScalingToContext(base: Context?): Context? {
        if (base == null) return null
        val settingsManager = SettingsManager(createAppStorage(createPlatformContext(base)))
        val scale = settingsManager.getResolutionScale()
        val config = Configuration(base.resources.configuration)
        val metrics = base.resources.displayMetrics
        metrics.density = metrics.density / scale
        metrics.scaledDensity = metrics.scaledDensity / scale
        return base.createConfigurationContext(config)
    }

    private fun applyLanguageToContext(base: Context?): Context? {
        if (base == null) return null
        val settingsManager = SettingsManager(createAppStorage(createPlatformContext(base)))
        val language = settingsManager.getLanguage()
        bootstrapAppLocale(language)
        val locale = createLocaleFromLanguageCode(language)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
    
    private fun applyAppIconSettings() {
        val appIconManager = AppIconManager(this)
        val settingsManager = SettingsManager(createAppStorage(createPlatformContext(this)))
        val targetIcon = settingsManager.getAppIconStyle()
        if (appIconManager.getCurrentEnabledIcon() != targetIcon) {
            appIconManager.setAppIcon(targetIcon)
        }
    }
}
