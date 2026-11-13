package org.cygnusx1.openbu.bambuddy

import androidx.compose.runtime.key
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------------------------
// Model (ArchiveStats in the Bambuddy spec)
// ---------------------------------------------------------------------------------------------

data class BbStats(
    val total: Int,
    val successful: Int,
    val failed: Int,
    val cancelled: Int,
    val hours: Double,
    val filamentGrams: Double,
    val cost: Double,
    val energyKwh: Double,
    val energyCost: Double,
    /** printer name -> number of prints */
    val byPrinter: List<Pair<String, Int>>,
    /** filament type -> number of prints */
    val byFilament: List<Pair<String, Int>>,
)

private fun JSONObject.num(key: String): Double =
    if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } ?: 0.0 else 0.0

private fun JSONObject.counts(key: String, names: JSONObject? = null): List<Pair<String, Int>> {
    val o = optJSONObject(key) ?: return emptyList()
    return o.keys().asSequence().map { k ->
        val label = names?.optString(k)?.takeIf { it.isNotBlank() } ?: k
        val v = o.opt(k)
        label to when (v) {
            is Number -> v.toInt()
            is JSONObject -> v.optInt("count", v.optInt("prints", 0))
            else -> 0
        }
    }.filter { it.second > 0 }.sortedByDescending { it.second }.toList()
}

fun parseStats(body: String): BbStats {
    val o = JSONObject(body)
    return BbStats(
        total = o.optInt("total_prints"),
        successful = o.optInt("successful_prints"),
        failed = o.optInt("failed_prints"),
        cancelled = o.optInt("cancelled_prints"),
        hours = o.num("total_print_time_hours"),
        filamentGrams = o.num("total_filament_grams"),
        cost = o.num("total_cost"),
        energyKwh = o.num("total_energy_kwh"),
        energyCost = o.num("total_energy_cost"),
        byPrinter = o.counts("prints_by_printer", o.optJSONObject("printer_names")),
        byFilament = o.counts("prints_by_filament_type"),
    )
}

private enum class StatsRange(val label: String, val days: Long?) {
    WEEK("7 days", 7),
    MONTH("30 days", 30),
    YEAR("12 months", 365),
    ALL("All time", null),
}

// ---------------------------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BambuddyStatsScreen(vm: BambuddyViewModel, onBack: () -> Unit) {
    var range by rememberSaveable { mutableStateOf(StatsRange.MONTH) }
    var stats by remember { mutableStateOf<BbStats?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    BackHandler(onBack = onBack)

    LaunchedEffect(range) {
        // Keep showing the previous numbers while loading, so everything animates from old to new.
        loading = true
        error = null
        try {
            val from = range.days?.let { LocalDate.now().minusDays(it).toString() }
            stats = vm.query { printStats(from, ZoneId.systemDefault().id) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = vm.describe(e)
        } finally {
            loading = false
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Stats") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
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
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatsRange.entries.forEach { r ->
                    FilterChip(selected = r == range, onClick = { range = r }, label = { Text(r.label) })
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            val s = stats
            if (s == null && error == null) Text("Loading...", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
            }
            if (s != null) {
                // Numbers count up/down to their new values when the period changes.
                val spec = tween<Float>(durationMillis = ANIM_MS)
                val total by animateIntAsState(s.total, tween(ANIM_MS), label = "total")
                val hours by animateFloatAsState(s.hours.toFloat(), spec, label = "hours")
                val grams by animateFloatAsState(s.filamentGrams.toFloat(), spec, label = "grams")
                val kwh by animateFloatAsState(s.energyKwh.toFloat(), spec, label = "kwh")
                val successRate = if (s.total > 0) (s.successful * 100.0 / s.total).roundToInt() else null
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BigTile("Prints", "$total", successRate?.let { "$it% successful" }, Modifier.weight(1f))
                    BigTile("Print time", formatHours(hours.toDouble()), null, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BigTile(
                        "Filament",
                        if (grams >= 1000) String.format(Locale.US, "%.2f kg", grams / 1000) else "${grams.roundToInt()} g",
                        s.cost.takeIf { it > 0 }?.let { String.format(Locale.US, "%.2f cost", it) },
                        Modifier.weight(1f),
                    )
                    BigTile(
                        "Energy",
                        String.format(Locale.US, "%.1f kWh", kwh),
                        s.energyCost.takeIf { it > 0 }?.let { String.format(Locale.US, "%.2f cost", it) },
                        Modifier.weight(1f),
                    )
                }
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(modifier = Modifier.padding(14.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Outcomes", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(
                            "${s.successful} finished  -  ${s.failed} failed  -  ${s.cancelled} cancelled",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (s.byPrinter.isNotEmpty()) BarList("Prints by printer", s.byPrinter)
                if (s.byFilament.isNotEmpty()) BarList("Prints by filament", s.byFilament.take(6))
            }
        }
    }
}

private fun formatHours(h: Double): String =
    if (h >= 100) "${h.roundToInt()} h" else String.format(Locale.US, "%.1f h", h)

@Composable
private fun BigTile(label: String, value: String, sub: String?, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BarList(title: String, rows: List<Pair<String, Int>>) {
    val max = rows.maxOf { it.second }.coerceAtLeast(1)
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(modifier = Modifier.padding(14.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            rows.forEach { (name, count) ->
                // key(name) keeps each bar tied to its printer/filament, so it grows or shrinks
                // smoothly instead of jumping when the order changes.
                key(name) {
                val fraction by animateFloatAsState(count.toFloat() / max, tween(ANIM_MS), label = "bar")
                val shown by animateIntAsState(count, tween(ANIM_MS), label = "count")
                Column {
                    Row {
                        Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text("$shown", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    }
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .height(6.dp),
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        strokeCap = StrokeCap.Round,
                        gapSize = 0.dp,
                        drawStopIndicator = {},
                    )
                }
                } // key
            }
        }
    }
}

private const val ANIM_MS = 600
