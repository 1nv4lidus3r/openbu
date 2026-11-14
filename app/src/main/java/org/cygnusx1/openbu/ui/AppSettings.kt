package org.cygnusx1.openbu.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.core.content.ContextCompat
import org.cygnusx1.openbu.R
import org.cygnusx1.openbu.notify.NotifyPrefs
import org.cygnusx1.openbu.notify.PrinterMonitorService

/** Shared top bar with a back arrow for the settings pages. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsPage(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
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
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) { content() }
    }
}

/** BamMate's settings: where the printers are, alerts, and about. */
@Composable
fun AppSettingsScreen(
    direct: Boolean,
    onSetDirect: (Boolean) -> Unit,
    onOpenServer: () -> Unit,
    onOpenDirectPrinters: () -> Unit,
    onOpenNotifications: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val versionName = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }
    var showAbout by remember { mutableStateOf(false) }

    SettingsPage(title = stringResource(R.string.settings), onBack = onBack) {
        SettingsGroup(title = "Printers") {
            SettingsNavRow(
                icon = Icons.Filled.Cloud,
                title = stringResource(R.string.bambuddy_title),
                subtitle = "Server address and API key",
                onClick = onOpenServer,
            )
            SettingsDivider()
            SettingsNavRow(
                icon = Icons.Filled.Wifi,
                title = "Direct printers",
                subtitle = "Printers reached over your network without Bambuddy",
                onClick = onOpenDirectPrinters,
            )
            SettingsDivider()
            SwitchRow(
                title = "Use direct connection",
                description = "Talk to the printers yourself instead of through the Bambuddy server. " +
                    "Camera, files, power, stats and maintenance need Bambuddy.",
                checked = direct,
                onCheckedChange = onSetDirect,
            )
        }
        SettingsGroup(title = "App") {
            SettingsNavRow(
                icon = Icons.Filled.Notifications,
                title = stringResource(R.string.notify_setting_title),
                subtitle = "Finished, failed, paused, errors and reminders",
                onClick = onOpenNotifications,
            )
            SettingsDivider()
            SettingsNavRow(
                icon = Icons.Filled.Info,
                title = stringResource(R.string.about_app),
                subtitle = stringResource(R.string.version, versionName),
                onClick = { showAbout = true },
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text(stringResource(R.string.about_app)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.version, versionName))
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.about_credits), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.about_license),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showAbout = false }) { Text(stringResource(R.string.ok)) } },
        )
    }
}

/** Which alerts to show. Stored in the settings file the alert service reads. */
@Composable
fun NotificationSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { NotifyPrefs.open(context) }
    var enabled by remember { mutableStateOf(prefs?.getBoolean(NotifyPrefs.KEY_ENABLED, false) == true) }
    var permissionDenied by remember { mutableStateOf(false) }

    fun setEnabled(on: Boolean) {
        prefs?.edit()?.putBoolean(NotifyPrefs.KEY_ENABLED, on)?.apply()
        enabled = on
        permissionDenied = false
        PrinterMonitorService.sync(context)
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) setEnabled(true) else permissionDenied = true
    }

    SettingsPage(title = stringResource(R.string.notify_setting_title), onBack = onBack) {
        SettingsGroup(title = null) {
            SwitchRow(
                title = stringResource(R.string.notify_setting_title),
                description = stringResource(R.string.notify_setting_desc),
                checked = enabled,
                onCheckedChange = { on ->
                    val needsPermission = on && Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED
                    if (needsPermission) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else setEnabled(on)
                },
            )
            if (permissionDenied) {
                Text(
                    stringResource(R.string.notify_permission_denied),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        if (enabled) {
            SettingsGroup(title = "Alert me about") {
                val items = listOf(
                    Triple(NotifyPrefs.KEY_FINISHED, R.string.notify_setting_finished, null),
                    Triple(NotifyPrefs.KEY_FAILED, R.string.notify_setting_failed, null),
                    Triple(NotifyPrefs.KEY_PAUSED, R.string.notify_setting_paused, null),
                    Triple(NotifyPrefs.KEY_HMS, R.string.notify_setting_hms, null),
                    Triple(NotifyPrefs.KEY_ALMOST, R.string.notify_setting_almost, R.string.notify_setting_almost_desc),
                    Triple(NotifyPrefs.KEY_PLATE, R.string.notify_setting_plate, R.string.notify_setting_plate_desc),
                    Triple(NotifyPrefs.KEY_IDLE_POWER, R.string.notify_setting_idle, R.string.notify_setting_idle_desc),
                    Triple(NotifyPrefs.KEY_MAINT, R.string.notify_setting_maint, R.string.notify_setting_maint_desc),
                )
                items.forEachIndexed { i, (key, title, desc) ->
                    if (i > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                    var checked by remember(key) { mutableStateOf(prefs?.getBoolean(key, true) ?: true) }
                    SwitchRow(
                        title = stringResource(title),
                        description = desc?.let { stringResource(it) },
                        checked = checked,
                        onCheckedChange = {
                            checked = it
                            prefs?.edit()?.putBoolean(key, it)?.apply()
                        },
                    )
                }
            }
            Text(
                stringResource(R.string.notify_battery_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            OutlinedButton(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    },
                )
            }) { Text(stringResource(R.string.notify_open_app_settings)) }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    description: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
