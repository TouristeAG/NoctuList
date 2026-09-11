package com.eventmanager.app.ui.desktop

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import com.eventmanager.app.data.sync.SettingsManager
import com.eventmanager.app.ui.navigation.AdminTab
import com.eventmanager.app.ui.navigation.BilleterieSection

/**
 * Navigation state hoisted above [com.eventmanager.app.ui.platform.AppLocaleEnvironment]
 * so locale changes (which remount the UI subtree via `key`) do not reset admin / billeterie flow.
 */
@Stable
class DesktopNavigationHolder(
    initialShowSetupWizard: Boolean,
) {
    var showWelcome by mutableStateOf(true)
    var showSetupWizard by mutableStateOf(initialShowSetupWizard)
    var showAdminAuth by mutableStateOf(false)
    var showTicketCheck by mutableStateOf(false)
    var showPos by mutableStateOf(false)
    var selectedTab by mutableStateOf(AdminTab.Dashboard.index)
    var previousTab by mutableStateOf(AdminTab.Dashboard.index)
    var showJobTypeManagement by mutableStateOf(false)
    var showVenueManagement by mutableStateOf(false)
    var showSalesSheetItemManagement by mutableStateOf(false)
    var showGuestFormManagement by mutableStateOf(false)
    var showPosAccountingReport by mutableStateOf(false)
    var showQRScanner by mutableStateOf(false)
    var showAdminSetup by mutableStateOf(false)
    var adminCheckDone by mutableStateOf(false)
    var billeterieSection by mutableStateOf(BilleterieSection.Home.name)
    var showBilleterieSettings by mutableStateOf(false)

    companion object {
        val Saver: Saver<DesktopNavigationHolder, *> = listSaver(
            save = {
                listOf(
                    it.showWelcome,
                    it.showSetupWizard,
                    it.showAdminAuth,
                    it.showTicketCheck,
                    it.showPos,
                    it.selectedTab,
                    it.previousTab,
                    it.showJobTypeManagement,
                    it.showVenueManagement,
                    it.showSalesSheetItemManagement,
                    it.showPosAccountingReport,
                    it.showQRScanner,
                    it.showAdminSetup,
                    it.adminCheckDone,
                    it.billeterieSection,
                    it.showBilleterieSettings,
                    it.showGuestFormManagement,
                )
            },
            restore = {
                DesktopNavigationHolder(initialShowSetupWizard = false).apply {
                    showWelcome = it[0] as Boolean
                    showSetupWizard = it[1] as Boolean
                    showAdminAuth = it[2] as Boolean
                    showTicketCheck = it[3] as Boolean
                    showPos = it[4] as Boolean
                    selectedTab = it[5] as Int
                    previousTab = it[6] as Int
                    showJobTypeManagement = it[7] as Boolean
                    showVenueManagement = it[8] as Boolean
                    showSalesSheetItemManagement = it[9] as Boolean
                    showPosAccountingReport = it[10] as Boolean
                    showQRScanner = it[11] as Boolean
                    showAdminSetup = it[12] as Boolean
                    adminCheckDone = it[13] as Boolean
                    billeterieSection = it[14] as String
                    showBilleterieSettings = it[15] as Boolean
                    showGuestFormManagement = (it.getOrNull(16) as? Boolean) ?: false
                }
            }
        )
    }
}

val LocalDesktopNavigation = staticCompositionLocalOf<DesktopNavigationHolder?> { null }

@Composable
fun rememberDesktopNavigationHolder(settingsManager: SettingsManager): DesktopNavigationHolder {
    return rememberSaveable(saver = DesktopNavigationHolder.Saver) {
        DesktopNavigationHolder(initialShowSetupWizard = settingsManager.shouldShowSetupWizard())
    }
}
