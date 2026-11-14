package org.cygnusx1.openbu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import org.cygnusx1.openbu.bambuddy.BambuddyHome
import org.cygnusx1.openbu.bambuddy.BambuddySettingsScreen
import org.cygnusx1.openbu.bambuddy.BambuddyViewModel
import org.cygnusx1.openbu.direct.DirectPrintersScreen
import org.cygnusx1.openbu.notify.PrinterMonitorService
import org.cygnusx1.openbu.ui.AppSettingsScreen
import org.cygnusx1.openbu.ui.NotificationSettingsScreen
import org.cygnusx1.openbu.ui.theme.BamMateTheme

/**
 * BamMate's only activity. The home screen lists the printers (through the Bambuddy server or
 * directly over the network); settings pages open on top of it.
 */
class MainActivity : ComponentActivity() {

    private enum class Page { HOME, SETTINGS, SERVER, DIRECT_PRINTERS, NOTIFICATIONS }

    override fun onStart() {
        super.onStart()
        // Keep the background print-alert service in step with the settings.
        PrinterMonitorService.sync(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The BamMate theme is always dark, so keep the system bar icons light.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )

        setContent {
            val vm: BambuddyViewModel = viewModel()
            val direct by vm.fallbackMode.collectAsState()
            var page by rememberSaveable { mutableStateOf(Page.HOME) }
            // Where Back goes from a page opened inside Settings.
            var cameFromSettings by rememberSaveable { mutableStateOf(false) }

            fun open(p: Page, fromSettings: Boolean) {
                cameFromSettings = fromSettings
                page = p
            }

            fun back() {
                page = when {
                    page == Page.SETTINGS -> Page.HOME
                    cameFromSettings -> Page.SETTINGS
                    else -> Page.HOME
                }
                if (page != Page.SETTINGS) cameFromSettings = false
            }

            BamMateTheme {
                if (page != Page.HOME) BackHandler { back() }
                when (page) {
                    Page.HOME -> BambuddyHome(
                        onOpenSettings = { open(Page.SETTINGS, false) },
                        onUseDirectConnection = { vm.setFallback(true) },
                        onOpenDirectPrinters = { open(Page.DIRECT_PRINTERS, false) },
                        vm = vm,
                    )
                    Page.SETTINGS -> AppSettingsScreen(
                        direct = direct,
                        onSetDirect = { vm.setFallback(it) },
                        onOpenServer = { open(Page.SERVER, true) },
                        onOpenDirectPrinters = { open(Page.DIRECT_PRINTERS, true) },
                        onOpenNotifications = { open(Page.NOTIFICATIONS, true) },
                        onBack = { back() },
                    )
                    Page.SERVER -> BambuddySettingsScreen(onBack = { back() }, vm = vm)
                    Page.DIRECT_PRINTERS -> DirectPrintersScreen(
                        onBack = { back() },
                        onChanged = { vm.directPrintersChanged() },
                    )
                    Page.NOTIFICATIONS -> NotificationSettingsScreen(onBack = { back() })
                }
            }
        }
    }
}
