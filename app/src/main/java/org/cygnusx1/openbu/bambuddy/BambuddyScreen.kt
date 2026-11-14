package org.cygnusx1.openbu.bambuddy

import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material.icons.filled.LocalFireDepartment
import org.cygnusx1.openbu.ui.SettingsGroup
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Badge
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Build
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Surface
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Home screen: one collapsible row per printer (name, state, time left, progress bar, arrow).
 * Only one row is open at a time; with a single printer its card is simply shown open.
 * Until a server is saved it shows the first-launch form instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BambuddyHome(
    onOpenSettings: () -> Unit,
    onUseDirectConnection: () -> Unit,
    onOpenDirectPrinters: () -> Unit,
    vm: BambuddyViewModel = viewModel(),
) {
    val baseUrl by vm.baseUrl.collectAsState()
    val apiKey by vm.apiKey.collectAsState()
    val statuses by vm.statuses.collectAsState()
    val power by vm.power.collectAsState()
    val maintenance by vm.maintenance.collectAsState()
    val maintDue = maintenance.sumOf { it.dueCount }
    val error by vm.error.collectAsState()
    val loaded by vm.loaded.collectAsState()
    // Direct mode needs no server: the printers are reached over the local network.
    val direct by vm.fallbackMode.collectAsState()
    val configured = direct || baseUrl.isNotBlank()
    var expandedId by rememberSaveable { mutableStateOf<Int?>(null) }
    var filesFor by rememberSaveable { mutableStateOf<Int?>(null) }
    var page by rememberSaveable { mutableStateOf<String?>(null) } // "maintenance" or "stats"

    // Poll only while the app is on screen.
    LifecycleResumeEffect(configured, direct) {
        if (configured) vm.startPolling()
        onPauseOrDispose { vm.stopPolling() }
    }

    // File manager for one printer replaces the list while open.
    val filesPrinter = filesFor?.let { id -> statuses.firstOrNull { it.id == id } }
    when (page) {
        "maintenance" -> {
            BambuddyMaintenanceScreen(vm = vm, onBack = { page = null })
            return
        }
        "stats" -> {
            BambuddyStatsScreen(vm = vm, onBack = { page = null })
            return
        }
    }
    if (filesFor != null) {
        BambuddyFilesScreen(
            printerId = filesFor!!,
            printerName = filesPrinter?.name ?: "Printer",
            vm = vm,
            onBack = { filesFor = null },
        )
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // Pinned to the bottom of the screen, above the system navigation bar.
        bottomBar = { if (configured) DonateBanner() },
        topBar = {
            TopAppBar(
                title = { Text("BamMate") },
                actions = {
                    if (configured) {
                        if (!direct) {
                            IconButton(onClick = { page = "stats" }) {
                                Icon(Icons.Filled.BarChart, contentDescription = "Stats")
                            }
                            IconButton(onClick = { page = "maintenance" }) {
                                BadgedBox(badge = { if (maintDue > 0) Badge { Text("$maintDue") } }) {
                                    Icon(Icons.Filled.Build, contentDescription = "Maintenance")
                                }
                            }
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                        }
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!configured) {
                SettingsGroup(title = null) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ServerForm(
                            title = "Welcome to BamMate",
                            initialUrl = baseUrl,
                            initialKey = apiKey,
                            error = error,
                            onSave = { url, key -> vm.saveConfig(url, key) },
                        )
                    }
                }
                TextButton(onClick = onUseDirectConnection, modifier = Modifier.fillMaxWidth()) {
                    Text("No Bambuddy server? Connect to a printer directly")
                }
            } else {
                error?.let {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = it,
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                if (direct) DirectModeBar(onOpenDirectPrinters)
                if (statuses.isEmpty() && error == null) {
                    if (direct && loaded) {
                        SettingsGroup(title = null) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text("No printers yet", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Add a printer with its IP address, serial number and access code, " +
                                        "or let BamMate find the printers on your network.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Button(onClick = onOpenDirectPrinters) { Text("Add a printer") }
                            }
                        }
                    } else {
                        Text(
                            text = if (loaded) "No printers found on this Bambuddy server." else "Connecting...",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (statuses.isNotEmpty()) HomeSummary(statuses, if (direct) emptyList() else maintenance) { page = "maintenance" }
                val single = statuses.size == 1
                statuses.forEach { s ->
                    PrinterCard(
                        s = s,
                        vm = vm,
                        collapsible = !single,
                        expanded = single || expandedId == s.id,
                        onToggle = { expandedId = if (expandedId == s.id) null else s.id },
                        onOpenFiles = { filesFor = s.id },
                        power = if (direct) null else power[s.id],
                        direct = direct,
                    )
                }
            }
        }
    }
}

/** Bambuddy server address and API key (opened from Settings). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BambuddySettingsScreen(
    onBack: () -> Unit,
    vm: BambuddyViewModel = viewModel(),
) {
    val baseUrl by vm.baseUrl.collectAsState()
    val apiKey by vm.apiKey.collectAsState()
    val error by vm.error.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Bambuddy server") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
        ) {
            SettingsGroup(title = "Server") {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ServerForm(
                        title = "Bambuddy server",
                        initialUrl = baseUrl,
                        initialKey = apiKey,
                        error = error,
                        onSave = { url, key ->
                            vm.saveConfig(url, key)
                            onBack()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ServerForm(
    title: String,
    initialUrl: String,
    initialKey: String,
    error: String?,
    onSave: (String, String) -> Unit,
) {
    var url by remember { mutableStateOf(initialUrl) }
    var key by remember { mutableStateOf(initialKey) }
    Text(title, style = MaterialTheme.typography.titleMedium)
    Text(
        "Enter your Bambuddy server address and an API key (Bambuddy: Settings, API Keys). " +
            "Use a key that only has the permissions this app needs. " +
            "Away from home? Turn on your VPN first.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = url,
        onValueChange = { url = it },
        label = { Text("Server address") },
        placeholder = { Text("http://192.168.1.50:8000") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = key,
        onValueChange = { key = it },
        label = { Text("API key") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    if (error != null) {
        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    Button(
        onClick = { onSave(url, key) },
        enabled = url.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Save and connect") }
}

private sealed class CardDialog {
    data class Temp(val label: String, val max: Int, val initial: Int, val onSet: (Int) -> Unit) : CardDialog()
    data class Fan(val label: String, val fan: String, val initial: Int) : CardDialog()
    object Speed : CardDialog()
    object Stop : CardDialog()
    object Skip : CardDialog()
    object Raw : CardDialog()
}

private val SPEED_NAMES = listOf("Silent", "Standard", "Sport", "Ludicrous")
private val SPEED_NOTES = listOf(
    "About 50% speed, quieter",
    "Normal speed (100%)",
    "About 124% speed",
    "About 166% speed, loudest",
)

@Composable
private fun PrinterCard(
    s: BbStatus,
    vm: BambuddyViewModel,
    collapsible: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenFiles: () -> Unit,
    power: BbPower? = null,
    /** Direct connection: no files, camera, skip objects or plate flag (Bambuddy features). */
    direct: Boolean = false,
) {
    var dialog by remember { mutableStateOf<CardDialog?>(null) }
    // The camera is off until the camera icon is tapped, and goes off again when the card closes.
    var cameraOn by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) { if (!expanded) cameraOn = false }
    val active = s.state == "RUNNING" || s.state == "PAUSE"

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

            // Header: always visible. Tapping it opens/closes the card when there are several printers.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // Clip so the tap/hover highlight follows a rounded shape, not a hard rectangle.
                    .clip(RoundedCornerShape(10.dp))
                    .then(if (collapsible) Modifier.clickable(onClick = onToggle) else Modifier),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(
                            stateColor(s),
                            CircleShape,
                        ),
                )
                Spacer(Modifier.size(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(s.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        text = listOfNotNull(
                            when {
                                !s.connected && power?.isOn == false -> "Powered off"
                                !s.connected -> "Offline"
                                active && s.remainingMin != null ->
                                    "${activeLabel(s)}  -  ${formatMinutes(s.remainingMin)} left"
                                active -> activeLabel(s)
                                else -> restingLabel(s)
                            },
                            power?.headerText(),
                        ).joinToString("  -  "),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (s.connected) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    )
                }
                if (active && !expanded) {
                    Text(
                        "${(s.progress ?: 0.0).roundToInt()}%",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = stateColor(s),
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
                if (expanded && s.connected && !direct) {
                    IconButton(onClick = onOpenFiles) {
                        Icon(
                            imageVector = Icons.Filled.Folder,
                            contentDescription = "Files on printer",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { cameraOn = !cameraOn }) {
                        Icon(
                            imageVector = if (cameraOn) Icons.Filled.VideocamOff else Icons.Filled.Videocam,
                            contentDescription = if (cameraOn) "Hide camera" else "Show camera",
                            tint = if (cameraOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (collapsible) {
                    Icon(
                        imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                    )
                }
            }
            if (active) {
                val p = ((s.progress ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { p },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                    color = stateColor(s),
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    strokeCap = StrokeCap.Round,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            }

            if (expanded) {
            val powerSection: @Composable () -> Unit = {
                if (power != null && power.plug != null) {
                    SectionLabel("Power")
                    PowerSection(
                        printerName = s.name,
                        printing = active,
                        power = power,
                        onSetPower = { on -> vm.powerCommand(s.id) { plug -> controlPlug(plug.id, if (on) "on" else "off") } },
                        onSetAutoOff = { on -> vm.powerCommand(s.id) { plug -> setPlugAutoOff(plug.id, on) } },
                    )
                }
            }
            // An offline printer may just be switched off, so put the power control first.
            if (!s.connected) {
                // Everything else would be stale data from before the printer went offline.
                powerSection()
                Text(
                    text = when {
                        power?.isOn == false -> "The printer is switched off. Details appear once it's back online."
                        direct -> (s.note ?: "Can't reach this printer.") + " Details appear once it's connected."
                        else -> "Bambuddy can't reach this printer. Details appear once it's back online."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
            if (cameraOn) {
                BambuddyCameraView(printerId = s.id, vm = vm, onClose = { cameraOn = false })
            }
            // Job details: plate picture, stage, name, progress and finish time
            if (s.job.isNotBlank() || active) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (s.hasCover && active) {
                        PrintCover(printerId = s.id, jobKey = s.job, vm = vm)
                        Spacer(Modifier.size(12.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        if (active && !s.stage.isNullOrBlank()) {
                            Text(
                                s.stage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Text(
                            s.job.substringAfterLast('/').ifBlank { "Print in progress" },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (active) {
                            val p = ((s.progress ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f)
                            Text(
                                buildString {
                                    append("${(p * 100).roundToInt()}%")
                                    if (s.layer != null && s.totalLayers != null && s.totalLayers > 0) {
                                        append("  -  layer ${s.layer}/${s.totalLayers}")
                                    }
                                    s.remainingMin?.takeIf { it > 0 }?.let { append("  -  done ${finishTime(it)}") }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (s.awaitingPlateClear && (s.state == "FINISH" || s.state == "FAILED")) {
                // Bambuddy holds its queue until someone confirms the plate is empty.
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = StatusAmber.copy(alpha = 0.12f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Waiting for the plate to be cleared",
                            style = MaterialTheme.typography.bodySmall,
                            color = StatusAmber,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { vm.control { clearPlate(s.id) } }) { Text("Plate cleared") }
                    }
                }
            }

            // Temperatures (tap a tile to set the target)
            SectionLabel("Temperatures")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                val t = s.temps
                val limits = heatLimits(s.model)
                if (s.dualNozzle) {
                    // Readings: nozzle = left, nozzle2 = right. Commands and the active nozzle use the
                    // printer's numbering: 0 = right, 1 = left (see BbTemps).
                    HeatTile(
                        "Left", PrinterIcons.Nozzle, t.nozzle, t.nozzleTarget, limits.nozzle, 5.0,
                        Modifier.weight(1f), active = s.activeNozzle == 1,
                    ) {
                        dialog = CardDialog.Temp("Left nozzle target", limits.nozzle, (t.nozzleTarget ?: 0.0).roundToInt()) {
                            vm.control { setNozzleTemp(s.id, it, 1) }
                        }
                    }
                    HeatTile(
                        "Right", PrinterIcons.Nozzle, t.nozzle2, t.nozzle2Target, limits.nozzle, 5.0,
                        Modifier.weight(1f), active = s.activeNozzle == 0,
                    ) {
                        dialog = CardDialog.Temp("Right nozzle target", limits.nozzle, (t.nozzle2Target ?: 0.0).roundToInt()) {
                            vm.control { setNozzleTemp(s.id, it, 0) }
                        }
                    }
                } else {
                    HeatTile("Nozzle", PrinterIcons.Nozzle, t.nozzle, t.nozzleTarget, limits.nozzle, 5.0, Modifier.weight(1f)) {
                        dialog = CardDialog.Temp("Nozzle target", limits.nozzle, (t.nozzleTarget ?: 0.0).roundToInt()) {
                            vm.control { setNozzleTemp(s.id, it, 0) }
                        }
                    }
                }
                HeatTile("Bed", PrinterIcons.Bed, t.bed, t.bedTarget, limits.bed, 2.0, Modifier.weight(1f)) {
                    dialog = CardDialog.Temp("Bed target", limits.bed, (t.bedTarget ?: 0.0).roundToInt()) {
                        vm.control { setBedTemp(s.id, it) }
                    }
                }
                HeatTile(
                    "Chamber", PrinterIcons.Chamber, t.chamber, t.chamberTarget, limits.chamber, 2.0,
                    Modifier.weight(1f), enabled = s.supportsChamberHeater,
                ) {
                    dialog = CardDialog.Temp("Chamber target", limits.chamber, (t.chamberTarget ?: 0.0).roundToInt()) {
                        vm.control { setChamberTemp(s.id, it) }
                    }
                }
            }

            // Fans (tap a tile to set percent)
            SectionLabel("Fans")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                FanTile("Part", s.partFan, Modifier.weight(1f)) {
                    dialog = CardDialog.Fan("Part cooling fan", "part", s.partFan ?: 0)
                }
                FanTile("Aux", s.auxFan, Modifier.weight(1f)) {
                    dialog = CardDialog.Fan("Auxiliary fan", "aux", s.auxFan ?: 0)
                }
                FanTile("Chamber", s.chamberFan, Modifier.weight(1f)) {
                    dialog = CardDialog.Fan("Chamber fan", "chamber", s.chamberFan ?: 0)
                }
            }

            // Controls
            SectionLabel("Controls")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                ControlTile(
                    icon = Icons.Filled.Lightbulb,
                    label = "Light",
                    value = if (s.light) "On" else "Off",
                    highlighted = s.light,
                    modifier = Modifier.weight(1f),
                ) { vm.control { setChamberLight(s.id, !s.light) } }
                ControlTile(
                    icon = Icons.Filled.Speed,
                    label = "Speed",
                    value = SPEED_NAMES.getOrElse(s.speedLevel - 1) { "Level ${s.speedLevel}" },
                    highlighted = false,
                    modifier = Modifier.weight(1f),
                ) { dialog = CardDialog.Speed }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (s.state == "PAUSE") {
                    Button(onClick = { vm.control { resume(s.id) } }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Resume")
                    }
                } else {
                    Button(
                        onClick = { vm.control { pause(s.id) } },
                        enabled = s.state == "RUNNING",
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Pause")
                    }
                }
                OutlinedButton(
                    onClick = { dialog = CardDialog.Stop },
                    enabled = active,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Stop")
                }
            }
            if (!direct) {
                OutlinedButton(
                    onClick = { dialog = CardDialog.Skip },
                    enabled = active,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Skip objects") }
            }

            // Errors
            if (s.hms.isNotEmpty()) {
                SectionLabel("Printer errors")
                s.hms.take(4).forEach {
                    Text(
                        text = it.code + (it.description?.let { d -> "  -  $d" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (s.hms.size > 4) Text("+${s.hms.size - 4} more", style = MaterialTheme.typography.bodySmall)
            }

            // Filaments
            if (s.ams.isNotEmpty() || s.external.isNotEmpty()) {
                SectionLabel("Filaments")
                s.ams.forEach { AmsView(it, s, vm) }
                if (s.external.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Text("External", style = MaterialTheme.typography.labelLarge)
                            Row(modifier = Modifier.fillMaxWidth()) {
                                s.external.forEach { TrayView(it, Modifier.weight(1f), showRemain = false) }
                            }
                        }
                    }
                }
            }

            powerSection()
            } // end connected

            TextButton(onClick = { dialog = CardDialog.Raw }, modifier = Modifier.align(Alignment.End)) {
                Text("Raw data")
            }
            } // end expanded
        }
    }

    // Dialogs
    when (val d = dialog) {
        is CardDialog.Temp -> NumberDialog(
            title = d.label,
            initial = d.initial,
            max = d.max,
            unit = "°C",
            helper = "0 turns heating off",
            presets = tempPresets(d.label, d.max),
            step = tempStep(d.label),
            onDismiss = { dialog = null },
            onConfirm = { d.onSet(it); dialog = null },
        )
        is CardDialog.Fan -> NumberDialog(
            title = d.label,
            initial = d.initial,
            max = 100,
            unit = "%",
            helper = null,
            presets = listOf(0, 25, 50, 75, 100),
            onDismiss = { dialog = null },
            onConfirm = { v -> vm.control { setFanSpeed(s.id, d.fan, v) }; dialog = null },
        )
        CardDialog.Speed -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Print speed") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SPEED_NAMES.forEachIndexed { i, name ->
                        val level = i + 1
                        val current = level == s.speedLevel
                        Surface(
                            onClick = { vm.control { setPrintSpeed(s.id, level) }; dialog = null },
                            shape = RoundedCornerShape(14.dp),
                            color = if (current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        SPEED_NOTES.getOrElse(i) { "" },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (current) Icon(Icons.Filled.CheckCircle, contentDescription = "Current", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
        CardDialog.Stop -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Stop print?") },
            text = { Text("This cancels the current print on ${s.name}. It can't be resumed.") },
            confirmButton = {
                TextButton(onClick = { vm.control { stop(s.id) }; dialog = null }) { Text("Stop print") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Keep printing") } },
        )
        CardDialog.Skip -> SkipObjectsDialog(s = s, vm = vm, onDismiss = { dialog = null })
        CardDialog.Raw -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Raw status: ${s.name}") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = prettyJson(s.raw),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("Close") } },
        )
        null -> {}
    }
}

@Composable
private fun SkipObjectsDialog(s: BbStatus, vm: BambuddyViewModel, onDismiss: () -> Unit) {
    var result by remember { mutableStateOf<BbObjects?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var confirming by remember { mutableStateOf(false) }

    LaunchedEffect(s.id) {
        try {
            result = vm.query { printObjects(s.id) }
        } catch (e: Exception) {
            loadError = vm.describe(e)
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Skip ${selected.size} object(s)?") },
            text = { Text("This can't be undone. Skipped objects won't be printed for the rest of this print.") },
            confirmButton = {
                TextButton(onClick = {
                    val ids = selected.toList()
                    vm.control { skipObjects(s.id, ids) }
                    onDismiss()
                }) { Text("Skip") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Back") } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Skip objects: ${s.name}") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (s.job.isNotBlank()) {
                    Text(
                        text = "Current print: ${s.job.substringAfterLast('/')}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Prints started from the printer's own screen report a raw path like
                // /data/Metadata/plate_5.gcode. Bambuddy never received that file, so its
                // object list can be left over from another print.
                if (s.job.startsWith("/") || s.job.endsWith(".gcode", ignoreCase = true)) {
                    Text(
                        text = "This print looks like it was started from the printer's screen, so Bambuddy " +
                            "may not have its file and this list could belong to another print. Check the names before skipping.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                val r = result
                when {
                    loadError != null -> Text(loadError!!, color = MaterialTheme.colorScheme.error)
                    r == null -> Text("Loading objects...")
                    r.objects.isEmpty() -> {
                        Text("No objects found for this print.")
                        Text(
                            text = prettyJsonAny(r.raw).take(1200),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> r.objects.forEachIndexed { index, o ->
                        // Number repeated names (four "Cube"s become Cube #1..#4) in list order.
                        val sameName = r.objects.filter { it.name == o.name }
                        val label = if (sameName.size > 1) {
                            "${o.name} #${r.objects.take(index + 1).count { it.name == o.name }}"
                        } else {
                            o.name
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = o.skipped || o.id in selected,
                                enabled = !o.skipped,
                                onCheckedChange = { on ->
                                    selected = if (on) selected + o.id else selected - o.id
                                },
                            )
                            Text(
                                text = label + if (o.skipped) "  (already skipped)" else "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (o.skipped) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { confirming = true }, enabled = selected.isNotEmpty()) {
                Text("Skip selected (${selected.size})")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Tappable tile with an icon, e.g. Light On / Speed Standard. */
@Composable
private fun ControlTile(
    icon: ImageVector,
    label: String,
    value: String,
    highlighted: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = modifier,
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private val StatusGreen = Color(0xFF22C55E)
private val StatusAmber = Color(0xFFF59E0B)

/** One colour per state, used for the dot, progress bar and percent. */
@Composable
private fun stateColor(s: BbStatus): Color = when {
    !s.connected -> MaterialTheme.colorScheme.error
    s.state == "PAUSE" || s.hms.isNotEmpty() -> StatusAmber
    s.state == "FAILED" && s.awaitingPlateClear -> MaterialTheme.colorScheme.error
    s.state == "FINISH" && s.awaitingPlateClear -> StatusGreen
    s.state in setOf("RUNNING", "PREPARE", "SLICING") -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.outline
}

/**
 * One-glance summary above the printer list: what needs you, what's printing and when the
 * next print finishes, or "All clear".
 */
@Composable
private fun HomeSummary(
    statuses: List<BbStatus>,
    maintenance: List<BbMaintPrinter> = emptyList(),
    onOpenMaintenance: () -> Unit = {},
) {
    val attention = statuses.filter {
        it.connected && (it.state == "PAUSE" || it.hms.isNotEmpty() ||
            (it.awaitingPlateClear && (it.state == "FINISH" || it.state == "FAILED")))
    }
    val printing = statuses.filter { it.connected && it.state in setOf("RUNNING", "PREPARE", "SLICING") }
    val offline = statuses.count { !it.connected }

    val (icon, color, title) = when {
        attention.isNotEmpty() -> Triple(Icons.Filled.Warning, StatusAmber, "Needs attention")
        printing.isNotEmpty() -> Triple(Icons.Filled.PlayArrow, MaterialTheme.colorScheme.primary, "${printing.size} printing")
        else -> Triple(Icons.Filled.CheckCircle, StatusGreen, "All clear")
    }
    val lines = buildList {
        attention.forEach {
            add(
                it.name + ": " + when {
                    it.state == "PAUSE" -> "paused"
                    it.hms.isNotEmpty() -> "printer error"
                    else -> "plate needs clearing"
                },
            )
        }
        printing.filter { it.remainingMin != null }.minByOrNull { it.remainingMin!! }?.let {
            add("Next done: ${it.name} at ${finishTime(it.remainingMin!!)}")
        }
        if (attention.isEmpty() && printing.isEmpty()) add("Nothing printing right now")
        if (offline > 0) add("$offline offline")
        maintenance.flatMap { p -> p.items.filter { it.enabled && it.isDue }.map { p.printerName to it.name } }
            .take(2)
            .forEach { (printer, item) -> add("Maintenance due: $printer - $item") }
    }
    val hasMaintDue = maintenance.any { it.dueCount > 0 }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .then(if (hasMaintDue) Modifier.clickable(onClick = onOpenMaintenance) else Modifier),
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(color.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = color)
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                lines.forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Shown at the top of the home screen while the printers are reached directly. */
@Composable
private fun DirectModeBar(onManage: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Wifi,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "Direct connection",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onManage) { Text("Printers") }
        }
    }
}

/** Small "Buy me a coffee" link under the printers (direct/sideload build only, see flags.xml). */
@Composable
private fun DonateBanner() {
    val context = androidx.compose.ui.platform.LocalContext.current
    if (!context.resources.getBoolean(org.cygnusx1.openbu.R.bool.show_donate)) return
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            onClick = {
                runCatching {
                    context.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(context.getString(org.cygnusx1.openbu.R.string.donate_url)),
                        ),
                    )
                }
            },
            shape = RoundedCornerShape(50),
            color = Color(0xFFFFDD00).copy(alpha = 0.12f),
        ) {
            Text(
                "\u2615  Enjoying BamMate? Buy me a coffee",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFFFFDD00),
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun AmsView(u: BbAms, s: BbStatus, vm: BambuddyViewModel) {
    var showDry by remember { mutableStateOf(false) }
    val scheduled by vm.scheduledDryings.collectAsState()
    val mine = scheduled.filter { it.printerId == s.id && it.amsId == u.id }
    if (showDry) DryingDialog(s, u, vm) { showDry = false }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(u.displayName(), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                val info = listOfNotNull(
                    u.humidity?.let { "$it% RH" },
                    u.temp?.let { "${it.roundToInt()}°C" },
                ).joinToString("  ")
                if (info.isNotBlank()) {
                    Text(info, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (u.canDry(s) && !u.drying) {
                    IconButton(onClick = { showDry = true }) {
                        Icon(
                            Icons.Filled.LocalFireDepartment,
                            contentDescription = "Dry filament",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (u.drying) DryingStrip(s, u, vm)
            if (mine.isNotEmpty()) ScheduledDryRows(mine, vm)
            Row(modifier = Modifier.fillMaxWidth()) {
                u.trays.forEach { TrayView(it, Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun TrayView(t: BbTray, modifier: Modifier = Modifier, showRemain: Boolean = true) {
    val empty = !t.exists || t.type.isBlank()
    Column(modifier = modifier.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(if (empty) Color.Transparent else parseTrayColor(t.colorHex), CircleShape)
                .border(
                    width = if (empty) 1.dp else 2.dp,
                    color = if (empty) MaterialTheme.colorScheme.outline else Color.White.copy(alpha = 0.12f),
                    shape = CircleShape,
                ),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (empty) "Empty" else t.type,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
        if (showRemain && !empty && t.remain in 0..100) {
            Text(
                "${t.remain}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Nozzle moves 1°C at a time; bed and chamber 5°C, like the printer's own screen. */
private fun tempStep(label: String): Int = if ("nozzle" in label.lowercase()) 1 else 5

/** Common targets offered as one-tap chips in the temperature dialog. */
private fun tempPresets(label: String, max: Int): List<Int> {
    val l = label.lowercase()
    return when {
        "nozzle" in l -> listOf(0, 190, 220, 250, 280)
        "bed" in l -> listOf(0, 35, 55, 65, 90, 100)
        "chamber" in l -> listOf(0, 35, 45, 55, 65)
        else -> listOf(0)
    }.filter { it <= max }
}

/**
 * Big value, slider, +/- and one-tap presets. Used for temperatures and fans.
 * Moves in `step`s (nozzle 1, bed/chamber 5, fans 10), like the printer's own screen.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun NumberDialog(
    title: String,
    initial: Int,
    max: Int,
    unit: String,
    helper: String?,
    presets: List<Int> = emptyList(),
    step: Int = 10,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    fun snap(v: Int) = ((v.toFloat() / step).roundToInt() * step).coerceIn(0, max)
    var value by remember { mutableStateOf(snap(initial)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    OutlinedButton(onClick = { value = snap(value - step) }) { Text("-$step") }
                    Text(
                        if (value == 0 && unit == "°C") "Off" else "$value$unit",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = { value = snap(value + step) }) { Text("+$step") }
                }
                Slider(
                    value = value.toFloat(),
                    onValueChange = { value = snap(it.roundToInt()) },
                    valueRange = 0f..max.toFloat(),
                    // Notches every `step`; single-degree steps are too fine for notches, so slide freely.
                    steps = if (step <= 1) 0 else (max / step - 1).coerceAtLeast(0),
                )
                if (presets.isNotEmpty()) {
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        presets.forEach { p ->
                            FilterChip(
                                selected = value == p,
                                onClick = { value = p },
                                label = { Text(if (p == 0 && unit == "°C") "Off" else "$p$unit") },
                            )
                        }
                    }
                }
                if (helper != null) {
                    Text(helper, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text("Set") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** While printing, prefer the printer's own stage ("Heatbed preheating") over a generic "Printing". */
private fun activeLabel(s: BbStatus): String =
    s.stage?.takeIf { it.isNotBlank() && s.state != "PAUSE" } ?: stateLabel(s.state)

/** Clock time the print should finish, e.g. "11:41 PM" (or "Fri 1:05 AM" if it's not today). */
private fun finishTime(remainingMin: Int): String {
    val end = java.time.LocalDateTime.now().plusMinutes(remainingMin.toLong())
    val time = end.format(java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT))
    return if (end.toLocalDate() == java.time.LocalDate.now()) time
    else end.format(java.time.format.DateTimeFormatter.ofPattern("EEE")) + " " + time
}

/** Small plate picture for the running print, fetched once per job. */
@Composable
private fun PrintCover(printerId: Int, jobKey: String, vm: BambuddyViewModel) {
    var img by remember(printerId, jobKey) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(printerId, jobKey) {
        img = try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val c = vm.currentClient() ?: return@withContext null
                val conn = c.openPrintCover(printerId, c.cameraToken())
                try {
                    if (conn.responseCode !in 200..299) null
                    else conn.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }?.asImageBitmap()
                } finally {
                    conn.disconnect()
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
    val i = img ?: return
    androidx.compose.foundation.Image(
        bitmap = i,
        contentDescription = "Plate preview",
        modifier = Modifier
            .size(72.dp)
            .background(Color.Black, RoundedCornerShape(8.dp)),
    )
}

/**
 * The printer keeps reporting FINISH/FAILED until its next job starts, even after the plate
 * has been cleared in Bambuddy. Once cleared, it's really just ready for the next print.
 */
private fun restingLabel(s: BbStatus): String = when {
    s.state == "FINISH" && !s.awaitingPlateClear -> "Ready  -  last print finished"
    s.state == "FAILED" && !s.awaitingPlateClear -> "Ready  -  last print stopped"
    else -> stateLabel(s.state)
}

private fun stateLabel(state: String): String = when (state) {
    "IDLE" -> "Idle"
    "RUNNING" -> "Printing"
    "PAUSE" -> "Paused"
    "FINISH" -> "Finished"
    "FAILED" -> "Failed / stopped" // the printer reports a cancelled print as FAILED too
    "PREPARE" -> "Preparing"
    "SLICING" -> "Slicing"
    "" -> "Unknown"
    else -> state.lowercase().replaceFirstChar { it.uppercase() }
}

private fun formatMinutes(min: Int): String =
    if (min >= 60) "${min / 60}h ${min % 60}m" else "${min}m"

private fun parseTrayColor(hex: String?): Color {
    val h = hex?.trim()?.removePrefix("#") ?: return Color.Gray
    if (h.length < 6) return Color.Gray
    return try {
        Color(android.graphics.Color.parseColor("#" + h.take(6)))
    } catch (_: IllegalArgumentException) {
        Color.Gray
    }
}

private fun prettyJsonAny(raw: String): String = try {
    val t = raw.trim()
    if (t.startsWith("[")) org.json.JSONArray(t).toString(2) else JSONObject(t).toString(2)
} catch (_: Exception) {
    raw
}

private fun prettyJson(raw: String): String = try {
    JSONObject(raw).toString(2)
} catch (_: Exception) {
    raw
}
