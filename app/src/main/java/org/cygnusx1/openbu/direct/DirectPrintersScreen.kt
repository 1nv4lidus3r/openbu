package org.cygnusx1.openbu.direct

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cygnusx1.openbu.notify.NotifyPrefs
import org.cygnusx1.openbu.ui.SettingsGroup

/**
 * The printers BamMate connects to directly: add, edit and remove them, or find them on the
 * network. [onChanged] is called after every change so the connections can be updated.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DirectPrintersScreen(onBack: () -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { NotifyPrefs.open(context) }
    val printers = remember { mutableStateListOf<DirectPrinter>().apply { prefs?.let { addAll(DirectStore.load(it)) } } }
    var editing by remember { mutableStateOf<DirectPrinter?>(null) }
    var deleting by remember { mutableStateOf<DirectPrinter?>(null) }
    val found = remember { mutableStateListOf<FoundPrinter>() }
    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    // Bambuddy's virtual printer announces itself like a real one, from the server's address.
    val bambuddyHost = remember {
        prefs?.getString("bb_base_url", null)?.let { runCatching { android.net.Uri.parse(it).host }.getOrNull() }
    }

    fun reload() {
        printers.clear()
        prefs?.let { printers.addAll(DirectStore.load(it)) }
        onChanged()
    }

    fun search() {
        if (searching) return
        searching = true
        searched = false
        searchError = null
        found.clear()
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    PrinterDiscovery.listen(context) { p ->
                        scope.launch { if (found.none { it.serial == p.serial }) found += p }
                    }
                }
            } catch (e: Exception) {
                searchError = e.message ?: "Search failed"
            } finally {
                searching = false
                searched = true
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Direct printers") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { editing = DirectPrinter(0, "", "", "", "") }) {
                        Icon(Icons.Filled.Add, contentDescription = "Add printer")
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
            Text(
                "BamMate connects to these printers over your local network when the direct connection is on. " +
                    "Each printer needs LAN access turned on; the access code is on the printer's screen " +
                    "under Settings > Network (WLAN).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )

            SettingsGroup(title = "Saved printers") {
                if (printers.isEmpty()) {
                    Text(
                        "No printers saved yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                printers.forEachIndexed { i, p ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(start = 68.dp))
                    PrinterRow(
                        title = p.displayName,
                        subtitle = listOf(p.model, p.ip, if (p.accessCode.isBlank()) "no access code" else null)
                            .filter { !it.isNullOrBlank() }.joinToString("  -  "),
                        onClick = { editing = p },
                    ) {
                        IconButton(onClick = { editing = p }) {
                            Icon(Icons.Filled.Edit, contentDescription = "Edit", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { deleting = p }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Remove", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            SettingsGroup(title = "Find printers on this network") {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { search() }, enabled = !searching, modifier = Modifier.fillMaxWidth()) {
                        if (searching) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("Listening for printers...")
                        } else {
                            Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Find printers")
                        }
                    }
                    searchError?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    if (searched && found.isEmpty() && searchError == null) {
                        Text(
                            "No printers answered. Make sure the phone is on the same Wi-Fi as the printers, " +
                                "or add them by IP address with the + button.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                found.forEach { f ->
                    val saved = printers.firstOrNull { it.serial == f.serial }
                    if (bambuddyHost != null && f.ip == bambuddyHost) {
                        PrinterRow(
                            title = f.name.ifBlank { f.serial },
                            subtitle = "Bambuddy's virtual printer - " + f.ip,
                            onClick = {},
                        ) {}
                    } else {
                        PrinterRow(
                            title = f.name.ifBlank { f.model.ifBlank { f.serial } },
                            subtitle = listOf(f.model, f.ip, if (saved != null) "saved" else null)
                                .filter { !it.isNullOrBlank() }.joinToString("  -  "),
                            onClick = {
                                editing = saved?.copy(ip = f.ip)
                                    ?: DirectPrinter(0, f.name, f.ip, f.serial, "")
                            },
                        ) {
                            TextButton(onClick = {
                                editing = saved?.copy(ip = f.ip) ?: DirectPrinter(0, f.name, f.ip, f.serial, "")
                            }) { Text(if (saved != null) "Update" else "Add") }
                        }
                    }
                }
            }
        }
    }

    editing?.let { p ->
        PrinterDialog(
            initial = p,
            onDismiss = { editing = null },
            onSave = { updated ->
                prefs?.let { DirectStore.upsert(it, updated) }
                editing = null
                reload()
            },
        )
    }

    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Remove ${p.displayName}?") },
            text = { Text("BamMate will stop connecting to this printer. You can add it again later.") },
            confirmButton = {
                TextButton(onClick = {
                    prefs?.let { DirectStore.delete(it, p.id) }
                    deleting = null
                    reload()
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PrinterRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    actions: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Print, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        actions()
    }
}

@Composable
private fun PrinterDialog(initial: DirectPrinter, onDismiss: () -> Unit, onSave: (DirectPrinter) -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var ip by remember { mutableStateOf(initial.ip) }
    var serial by remember { mutableStateOf(initial.serial) }
    var code by remember { mutableStateOf(initial.accessCode) }
    var showCode by remember { mutableStateOf(false) }
    val ipOk = ip.trim().isNotBlank()
    val serialOk = serial.trim().length >= 8
    val codeOk = code.trim().isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == 0) "Add printer" else "Edit printer") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = ip,
                    onValueChange = { ip = it.trim() },
                    label = { Text("IP address") },
                    placeholder = { Text("10.0.0.47") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = serial,
                    onValueChange = { serial = it.trim().uppercase() },
                    label = { Text("Serial number") },
                    supportingText = {
                        val model = modelFromSerial(serial.trim())
                        if (model.isNotBlank()) Text("Model: $model")
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.trim() },
                    label = { Text("Access code") },
                    singleLine = true,
                    visualTransformation = if (showCode) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showCode = !showCode }) {
                            Icon(
                                if (showCode) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (showCode) "Hide" else "Show",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                enabled = ipOk && serialOk && codeOk,
                onClick = {
                    onSave(initial.copy(name = name.trim(), ip = ip.trim(), serial = serial.trim(), accessCode = code.trim()))
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
