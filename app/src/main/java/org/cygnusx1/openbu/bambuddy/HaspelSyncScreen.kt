package org.cygnusx1.openbu.bambuddy

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.cygnusx1.openbu.ui.SettingsGroup

/**
 * HaspelSync connection: its address and an API key (created on HaspelSync's API page). With it,
 * the AMS shows the Spoolman spool assigned to each slot: name, brand and grams left.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HaspelSyncSettingsScreen(vm: BambuddyViewModel, onBack: () -> Unit) {
    val saved = remember { vm.haspelSyncConfig() }
    var url by remember { mutableStateOf(saved.first) }
    var key by remember { mutableStateOf(saved.second) }
    var showKey by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var resultOk by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("HaspelSync") },
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Optional. When HaspelSync links your AMS slots to Spoolman spools, BamMate shows each " +
                    "spool's name, brand and grams left. BamMate only reads; assign spools in HaspelSync. " +
                    "Create an API key on HaspelSync's API page.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            SettingsGroup(title = null) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it.trim(); result = null },
                        label = { Text("Address") },
                        placeholder = { Text("10.0.0.20:4000") },
                        supportingText = { Text("Port 4000 is used when none is given") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it.trim(); result = null },
                        label = { Text("API key") },
                        singleLine = true,
                        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showKey = !showKey }) {
                                Icon(
                                    if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (showKey) "Hide" else "Show",
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            enabled = url.isNotBlank() && key.isNotBlank() && !testing,
                            onClick = {
                                testing = true
                                result = null
                                scope.launch {
                                    try {
                                        val printers = vm.testHaspelSync(url, key)
                                        resultOk = true
                                        result = if (printers.isEmpty()) {
                                            "Connected, but HaspelSync has no printers yet."
                                        } else {
                                            "Connected. Printers: " + printers.joinToString(", ") { it.name.ifBlank { it.id } }
                                        }
                                    } catch (e: Exception) {
                                        resultOk = false
                                        result = "Couldn't connect: " + (e.message ?: e.javaClass.simpleName)
                                    } finally {
                                        testing = false
                                    }
                                }
                            },
                        ) {
                            if (testing) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text("Test")
                        }
                        Button(onClick = {
                            vm.saveHaspelSync(url, key)
                            onBack()
                        }) { Text("Save") }
                    }
                    result?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (resultOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            if (saved.first.isNotBlank()) {
                OutlinedButton(onClick = {
                    vm.saveHaspelSync("", "")
                    onBack()
                }) { Text("Remove HaspelSync") }
            }
        }
    }
}
