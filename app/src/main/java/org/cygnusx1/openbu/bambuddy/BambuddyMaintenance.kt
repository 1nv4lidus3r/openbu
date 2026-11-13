package org.cygnusx1.openbu.bambuddy

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------------------------
// Models (PrinterMaintenanceOverview / MaintenanceStatus in the Bambuddy spec)
// ---------------------------------------------------------------------------------------------

data class BbMaintItem(
    val id: Int,
    val printerId: Int,
    val printerName: String,
    val name: String,
    val wikiUrl: String?,
    val enabled: Boolean,
    val intervalType: String,
    val intervalHours: Double,
    val hoursSince: Double,
    val hoursUntilDue: Double,
    val daysSince: Double?,
    val daysUntilDue: Double?,
    val isDue: Boolean,
    val isWarning: Boolean,
) {
    private val byDays get() = intervalType.equals("days", ignoreCase = true) && daysUntilDue != null

    /** 0..1 of the interval used up. */
    val used: Float
        get() = if (byDays) {
            val total = (daysSince ?: 0.0) + (daysUntilDue ?: 0.0)
            if (total > 0) ((daysSince ?: 0.0) / total).toFloat().coerceIn(0f, 1f) else 0f
        } else {
            if (intervalHours > 0) (hoursSince / intervalHours).toFloat().coerceIn(0f, 1f) else 0f
        }

    val dueText: String
        get() = if (byDays) {
            val d = (daysUntilDue ?: 0.0).roundToInt()
            when {
                isDue -> if (d < 0) "Overdue by ${-d} day(s)" else "Due now"
                else -> "Due in $d day(s)"
            }
        } else {
            val h = hoursUntilDue.roundToInt()
            when {
                isDue -> if (h < 0) "Overdue by ${-h} print hour(s)" else "Due now"
                else -> "Due in $h print hour(s)"
            }
        }
}

data class BbMaintPrinter(
    val printerId: Int,
    val printerName: String,
    val totalHours: Double,
    val items: List<BbMaintItem>,
) {
    val dueCount get() = items.count { it.enabled && it.isDue }
    val warningCount get() = items.count { it.enabled && it.isWarning && !it.isDue }
}

private fun JSONObject.d(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null

fun parseMaintOverview(body: String): List<BbMaintPrinter> {
    val t = body.trim()
    val arr = if (t.startsWith("[")) JSONArray(t) else JSONArray().put(JSONObject(t))
    return (0 until arr.length()).mapNotNull { i ->
        val p = arr.optJSONObject(i) ?: return@mapNotNull null
        val items = p.optJSONArray("maintenance_items") ?: JSONArray()
        BbMaintPrinter(
            printerId = p.optInt("printer_id"),
            printerName = p.optString("printer_name", "Printer"),
            totalHours = p.d("total_print_hours") ?: 0.0,
            items = (0 until items.length()).mapNotNull { j ->
                val o = items.optJSONObject(j) ?: return@mapNotNull null
                BbMaintItem(
                    id = o.optInt("id"),
                    printerId = o.optInt("printer_id"),
                    printerName = o.optString("printer_name", ""),
                    name = o.optString("maintenance_type_name", "Maintenance"),
                    wikiUrl = if (o.isNull("maintenance_type_wiki_url")) null else o.optString("maintenance_type_wiki_url").takeIf { it.startsWith("http") },
                    enabled = o.optBoolean("enabled", true),
                    intervalType = o.optString("interval_type", "hours"),
                    intervalHours = o.d("interval_hours") ?: 0.0,
                    hoursSince = o.d("hours_since_maintenance") ?: 0.0,
                    hoursUntilDue = o.d("hours_until_due") ?: 0.0,
                    daysSince = o.d("days_since_maintenance"),
                    daysUntilDue = o.d("days_until_due"),
                    isDue = o.optBoolean("is_due", false),
                    isWarning = o.optBoolean("is_warning", false),
                )
            }.sortedWith(compareByDescending<BbMaintItem> { it.isDue }.thenByDescending { it.isWarning }.thenByDescending { it.used }),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------------------------

private val Amber = Color(0xFFF59E0B)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BambuddyMaintenanceScreen(vm: BambuddyViewModel, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var data by remember { mutableStateOf<List<BbMaintPrinter>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf<BbMaintItem?>(null) }
    // Result of the last "Done", kept separate so the reload after it doesn't wipe it.
    var actionMessage by remember { mutableStateOf<String?>(null) }

    BackHandler(onBack = onBack)

    LaunchedEffect(reload) {
        try {
            data = vm.query { maintenanceOverview() }
            error = null
            vm.setMaintenance(data!!)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = vm.describe(e)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Maintenance") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = { reload++ }) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
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
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            actionMessage?.let {
                Text(
                    it,
                    color = if (it.startsWith("Couldn't")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            val list = data
            if (list == null && error == null) Text("Loading...", color = MaterialTheme.colorScheme.onSurfaceVariant)
            list?.forEach { p ->
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(p.printerName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(
                                    "${p.totalHours.roundToInt()} print hours" + when {
                                        p.dueCount > 0 -> "  -  ${p.dueCount} due"
                                        p.warningCount > 0 -> "  -  ${p.warningCount} coming up"
                                        else -> "  -  all good"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        p.items.filter { it.enabled }.forEach { item ->
                            MaintRow(
                                item = item,
                                onDone = { confirm = item },
                                onWiki = item.wikiUrl?.let { url ->
                                    {
                                        runCatching {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                        }
                                        Unit
                                    }
                                },
                            )
                        }
                        if (p.items.none { it.enabled }) {
                            Text(
                                "No maintenance items set up for this printer in Bambuddy.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    confirm?.let { item ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Mark as done?") },
            text = { Text("Record \"${item.name}\" as done on ${item.printerName.ifBlank { "this printer" }} now. This resets its counter.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    scope.launch {
                        actionMessage = try {
                            vm.query { performMaintenance(item.id) }
                            reload++
                            "${item.name}: marked as done."
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            "Couldn't mark it done: " + vm.describe(e)
                        }
                    }
                }) { Text("Done") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MaintRow(item: BbMaintItem, onDone: () -> Unit, onWiki: (() -> Unit)?) {
    val color = when {
        item.isDue -> MaterialTheme.colorScheme.error
        item.isWarning -> Amber
        else -> MaterialTheme.colorScheme.primary
    }
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Column(modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        item.dueText,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (item.isDue || item.isWarning) color else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (onWiki != null) TextButton(onClick = onWiki) { Text("How to") }
                TextButton(onClick = onDone) { Text("Done") }
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { item.used },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 8.dp)
                    .height(4.dp),
                color = color,
                trackColor = MaterialTheme.colorScheme.surfaceContainer,
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
    }
}
