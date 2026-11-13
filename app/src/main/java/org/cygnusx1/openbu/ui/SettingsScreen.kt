package org.cygnusx1.openbu.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.cygnusx1.openbu.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenBambuddy: () -> Unit,
    onOpenGeneralSettings: () -> Unit,
    onOpenRelaySettings: () -> Unit,
    onOpenDebuggingSettings: () -> Unit,
    onBack: () -> Unit,
    /** The SOCKS relay only applies to direct printer connections (fallback mode). */
    showRelay: Boolean = true,
) {
    var showAboutDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val versionName = remember {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "Unknown"
    }

    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = { Text(stringResource(R.string.about_openbu)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.version, versionName))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.about_credits),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) {
                    Text(stringResource(R.string.ok))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showAboutDialog = true }) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = stringResource(R.string.cd_about),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SettingsGroup(title = "Connection") {
                SettingsNavRow(
                    icon = Icons.Filled.Cloud,
                    title = stringResource(R.string.bambuddy_title),
                    subtitle = stringResource(R.string.bambuddy_desc),
                    onClick = onOpenBambuddy,
                )
                if (showRelay) {
                    SettingsDivider()
                    SettingsNavRow(
                        icon = Icons.Filled.Wifi,
                        title = stringResource(R.string.remote_relay),
                        subtitle = stringResource(R.string.remote_relay_desc),
                        onClick = onOpenRelaySettings,
                    )
                }
            }
            SettingsGroup(title = "App") {
                SettingsNavRow(
                    icon = Icons.Filled.Settings,
                    title = stringResource(R.string.general),
                    subtitle = if (showRelay) stringResource(R.string.general_settings_desc) else "Print notifications and alerts",
                    onClick = onOpenGeneralSettings,
                )
                SettingsDivider()
                SettingsNavRow(
                    icon = Icons.Filled.BugReport,
                    title = stringResource(R.string.debug),
                    subtitle = stringResource(R.string.debug_settings_desc),
                    onClick = onOpenDebuggingSettings,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SettingsDivider()
                SettingsNavRow(
                    icon = Icons.Filled.Info,
                    title = stringResource(R.string.about_openbu),
                    subtitle = stringResource(R.string.version, versionName),
                    onClick = { showAboutDialog = true },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
