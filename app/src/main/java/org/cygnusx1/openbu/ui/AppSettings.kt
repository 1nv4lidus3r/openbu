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
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Inventory2
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
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
import androidx.compose.foundation.clickable
import org.cygnusx1.openbu.R
import org.cygnusx1.openbu.bambuddy.MotionPref
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
    onOpenHaspelSync: () -> Unit,
    motion: String,
    onSetMotion: (String) -> Unit,
    jogEnabled: Boolean,
    onSetJog: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val versionName = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }
    var showAbout by remember { mutableStateOf(false) }
    var showMotion by remember { mutableStateOf(false) }
    var showJogNotice by remember { mutableStateOf(false) }

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
        SettingsGroup(title = "Filament") {
            SettingsNavRow(
                icon = Icons.Filled.Inventory2,
                title = "HaspelSync",
                subtitle = "Spool names and grams from Spoolman",
                onClick = onOpenHaspelSync,
            )
        }
        SettingsGroup(title = "Printer control") {
            SwitchRow(
                title = "Manual moves",
                description = "Jog pad to move the print head, bed and extruder. Off by default; " +
                    "the printer does not check travel limits for these moves.",
                checked = jogEnabled,
                onCheckedChange = { on -> if (on) showJogNotice = true else onSetJog(false) },
            )
        }
        SettingsGroup(title = "Appearance") {
            SettingsNavRow(
                icon = Icons.Filled.Animation,
                title = "Motion",
                subtitle = motionOptions.firstOrNull { it.first == motion }?.second ?: motionOptions.first().second,
                onClick = { showMotion = true },
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

    if (showJogNotice) {
        var accepted by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showJogNotice = false },
            title = { Text("Turn on manual moves?") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Before you turn this on, please read:", style = MaterialTheme.typography.bodyMedium)
                    listOf(
                        "Bambu printers do not enforce their travel limits for moves sent from an app. A move can drive the print head or bed into the frame.",
                        "A collision can damage the printer, the build plate or the print head.",
                        "Moves are sent exactly as you tap them. Always home first, check that nothing is in the way, and use small steps.",
                        "BamMate is provided as is, without warranty of any kind. You are responsible for every move you make with it.",
                    ).forEach {
                        Row {
                            Text("•  ", style = MaterialTheme.typography.bodySmall)
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { accepted = !accepted }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = accepted, onCheckedChange = { accepted = it })
                        Spacer(Modifier.width(8.dp))
                        Text("I understand and accept the risk", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = accepted,
                    onClick = {
                        onSetJog(true)
                        showJogNotice = false
                    },
                ) { Text("Turn on") }
            },
            dismissButton = { TextButton(onClick = { showJogNotice = false }) { Text("Cancel") } },
        )
    }

    if (showMotion) {
        AlertDialog(
            onDismissRequest = { showMotion = false },
            title = { Text("Motion") },
            text = {
                Column {
                    motionOptions.forEach { (value, title, desc) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSetMotion(value)
                                    showMotion = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = value == motion, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(title, style = MaterialTheme.typography.bodyLarge)
                                Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showMotion = false }) { Text(stringResource(R.string.ok)) } },
        )
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
                    Text(stringResource(R.string.about_disclaimer), style = MaterialTheme.typography.bodySmall)
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

/** The Motion choices: saved value, title, what it does. */
private val motionOptions = listOf(
    Triple(MotionPref.SYSTEM, "Follow phone", "Full, or Off when the phone's animations are removed"),
    Triple(MotionPref.FULL, "Full", "Spinning fans, rolling numbers, glows and breathing alerts"),
    Triple(MotionPref.SUBTLE, "Subtle", "Fans and numbers move; no glows pulsing for attention"),
    Triple(MotionPref.OFF, "Off", "Nothing moves; colors and arrows still show everything"),
)

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
