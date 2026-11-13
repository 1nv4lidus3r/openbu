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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneralSettingsScreen(
    showMainStream: Boolean,
    onShowMainStreamChanged: (Boolean) -> Unit,
    forceDarkMode: Boolean,
    onForceDarkModeChanged: (Boolean) -> Unit,
    useVlcForRtsp: Boolean,
    onUseVlcForRtspChanged: (Boolean) -> Unit,
    onBack: () -> Unit,
    /** Camera/stream options only apply to the direct-connection fallback screens. */
    showDirectOptions: Boolean = true,
) {
    // Print-notification settings live in the same encrypted prefs the service reads, so no
    // ViewModel plumbing is needed: the service picks changes up via PrinterMonitorService.sync().
    val context = LocalContext.current
    val notifyPrefs = remember { NotifyPrefs.open(context) }
    var notifyEnabled by remember {
        mutableStateOf(notifyPrefs?.getBoolean(NotifyPrefs.KEY_ENABLED, false) == true)
    }
    var notifyFinished by remember {
        mutableStateOf(notifyPrefs?.getBoolean(NotifyPrefs.KEY_FINISHED, true) ?: true)
    }
    var notifyFailed by remember {
        mutableStateOf(notifyPrefs?.getBoolean(NotifyPrefs.KEY_FAILED, true) ?: true)
    }
    var notifyPaused by remember {
        mutableStateOf(notifyPrefs?.getBoolean(NotifyPrefs.KEY_PAUSED, true) ?: true)
    }
    var notifyHms by remember {
        mutableStateOf(notifyPrefs?.getBoolean(NotifyPrefs.KEY_HMS, true) ?: true)
    }
    var notifyAlmost by remember {
        mutableStateOf(notifyPrefs?.getBoolean(NotifyPrefs.KEY_ALMOST, true) ?: true)
    }
    var notifyPlate by remember {
        mutableStateOf(notifyPrefs?.getBoolean(NotifyPrefs.KEY_PLATE, true) ?: true)
    }
    var notifyIdle by remember {
        mutableStateOf(notifyPrefs?.getBoolean(NotifyPrefs.KEY_IDLE_POWER, true) ?: true)
    }
    var notifyMaint by remember {
        mutableStateOf(notifyPrefs?.getBoolean(NotifyPrefs.KEY_MAINT, true) ?: true)
    }
    var permissionDenied by remember { mutableStateOf(false) }

    fun turnOn() {
        notifyPrefs?.edit()?.putBoolean(NotifyPrefs.KEY_ENABLED, true)?.apply()
        notifyEnabled = true
        permissionDenied = false
        PrinterMonitorService.sync(context)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) turnOn() else permissionDenied = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.general)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
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
        ) {
            if (showDirectOptions) {
            // Internal stream toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.internal_stream),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.internal_stream_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = showMainStream,
                    onCheckedChange = onShowMainStreamChanged,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // VLC decoder toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.vlc_decoder_rtsp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.vlc_decoder_rtsp_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = useVlcForRtsp,
                    onCheckedChange = onUseVlcForRtspChanged,
                )
            }
            } // end direct-connection options

            if (showDirectOptions) Spacer(modifier = Modifier.height(24.dp))

            // Print notifications (grouped like the rest of the app)
            SettingsGroup(title = "Print notifications") {
            NotifySwitchRow(
                title = stringResource(R.string.notify_setting_title),
                description = stringResource(R.string.notify_setting_desc),
                checked = notifyEnabled,
                onCheckedChange = { on ->
                    if (!on) {
                        notifyPrefs?.edit()?.putBoolean(NotifyPrefs.KEY_ENABLED, false)?.apply()
                        notifyEnabled = false
                        permissionDenied = false
                        PrinterMonitorService.sync(context)
                    } else if (
                        Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(
                            context, Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        turnOn()
                    }
                },
            )

            if (permissionDenied) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.notify_permission_denied),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            if (notifyEnabled) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                NotifySwitchRow(
                    title = stringResource(R.string.notify_setting_finished),
                    checked = notifyFinished,
                    onCheckedChange = {
                        notifyFinished = it
                        notifyPrefs?.edit()?.putBoolean(NotifyPrefs.KEY_FINISHED, it)?.apply()
                    },
                )
                NotifySwitchRow(
                    title = stringResource(R.string.notify_setting_failed),
                    checked = notifyFailed,
                    onCheckedChange = {
                        notifyFailed = it
                        notifyPrefs?.edit()?.putBoolean(NotifyPrefs.KEY_FAILED, it)?.apply()
                    },
                )
                NotifySwitchRow(
                    title = stringResource(R.string.notify_setting_paused),
                    checked = notifyPaused,
                    onCheckedChange = {
                        notifyPaused = it
                        notifyPrefs?.edit()?.putBoolean(NotifyPrefs.KEY_PAUSED, it)?.apply()
                    },
                )
                NotifySwitchRow(
                    title = stringResource(R.string.notify_setting_hms),
                    checked = notifyHms,
                    onCheckedChange = {
                        notifyHms = it
                        notifyPrefs?.edit()?.putBoolean(NotifyPrefs.KEY_HMS, it)?.apply()
                    },
                )
                NotifySwitchRow(
                    title = stringResource(R.string.notify_setting_almost),
                    description = stringResource(R.string.notify_setting_almost_desc),
                    checked = notifyAlmost,
                    onCheckedChange = {
                        notifyAlmost = it
                        notifyPrefs?.edit()?.putBoolean(NotifyPrefs.KEY_ALMOST, it)?.apply()
                    },
                )
                NotifySwitchRow(
                    title = stringResource(R.string.notify_setting_plate),
                    description = stringResource(R.string.notify_setting_plate_desc),
                    checked = notifyPlate,
                    onCheckedChange = {
                        notifyPlate = it
                        notifyPrefs?.edit()?.putBoolean(NotifyPrefs.KEY_PLATE, it)?.apply()
                    },
                )
                NotifySwitchRow(
                    title = stringResource(R.string.notify_setting_idle),
                    description = stringResource(R.string.notify_setting_idle_desc),
                    checked = notifyIdle,
                    onCheckedChange = {
                        notifyIdle = it
                        notifyPrefs?.edit()?.putBoolean(NotifyPrefs.KEY_IDLE_POWER, it)?.apply()
                    },
                )
                NotifySwitchRow(
                    title = stringResource(R.string.notify_setting_maint),
                    description = stringResource(R.string.notify_setting_maint_desc),
                    checked = notifyMaint,
                    onCheckedChange = {
                        notifyMaint = it
                        notifyPrefs?.edit()?.putBoolean(NotifyPrefs.KEY_MAINT, it)?.apply()
                    },
                )
            }
            } // end Print notifications group

            if (notifyEnabled) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.notify_battery_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                            }
                        )
                    },
                ) {
                    Text(stringResource(R.string.notify_open_app_settings))
                }
            }
        }
    }
}

@Composable
private fun NotifySwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    description: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (description != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}
