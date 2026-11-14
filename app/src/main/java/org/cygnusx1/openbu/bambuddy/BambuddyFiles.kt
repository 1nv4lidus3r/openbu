package org.cygnusx1.openbu.bambuddy

import androidx.compose.material3.FilledTonalButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.ViewInAr
import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Surface
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.DateFormat
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------------------------------------
// Models. The spec doesn't describe these responses, so parsing accepts several likely shapes
// and the screen keeps the raw text for the (i) button.
// ---------------------------------------------------------------------------------------------

data class BbFile(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long?,
    val modified: String?,
    /** Epoch millis for sorting by date; null if the server's date couldn't be read. */
    val time: Long? = null,
)

private fun JSONObject.firstString(vararg keys: String): String? {
    for (k in keys) if (has(k) && !isNull(k)) optString(k).takeIf { it.isNotBlank() }?.let { return it }
    return null
}

private fun JSONObject.firstLong(vararg keys: String): Long? {
    for (k in keys) {
        if (!has(k) || isNull(k)) continue
        val v = opt(k)
        if (v is Number) return v.toLong()
        (v as? String)?.toLongOrNull()?.let { return it }
    }
    return null
}

private fun joinPath(dir: String, name: String): String =
    if (dir.endsWith("/")) dir + name else "$dir/$name"

/** "2026-08-06T06:53:00" -> "Aug 6, 2026, 6:53 AM" (in the phone's language); unchanged if it can't be read. */
private fun prettyDate(s: String): String = try {
    val fmt = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    LocalDateTime.parse(s.take(19)).format(fmt)
} catch (_: Exception) {
    s
}

fun parseFiles(body: String, dir: String): List<BbFile> {
    val t = body.trim()
    val arr: JSONArray = when {
        t.startsWith("[") -> JSONArray(t)
        t.startsWith("{") -> JSONObject(t).let {
            it.optJSONArray("files") ?: it.optJSONArray("items") ?: it.optJSONArray("entries")
                ?: it.optJSONArray("contents") ?: JSONArray()
        }
        else -> JSONArray()
    }
    return (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val name = o.firstString("name", "filename", "file_name")
            ?: o.firstString("path")?.trimEnd('/')?.substringAfterLast('/')
            ?: return@mapNotNull null
        val type = (o.firstString("type", "kind") ?: "").lowercase()
        val isDir = o.optBoolean("is_dir", false) || o.optBoolean("is_directory", false) ||
            o.optBoolean("isDir", false) || o.optBoolean("directory", false) ||
            type == "dir" || type == "directory" || type == "folder"
        val modified: String? = run {
            val n = o.firstLong("mtime", "modified", "modified_at", "timestamp", "date")
            if (n != null && n > 100_000_000L) {
                val ms = if (n > 100_000_000_000L) n else n * 1000
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms))
            } else {
                o.firstString("modified", "modified_at", "mtime", "date")?.let(::prettyDate)
            }
        }
        val time: Long? = run {
            val n = o.firstLong("mtime", "modified", "modified_at", "timestamp", "date")
            if (n != null && n > 100_000_000L) {
                if (n > 100_000_000_000L) n else n * 1000
            } else {
                o.firstString("modified", "modified_at", "mtime", "date")?.let { s ->
                    try {
                        LocalDateTime.parse(s.take(19)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    } catch (_: Exception) {
                        null
                    }
                }
            }
        }
        BbFile(
            name = name,
            path = o.firstString("path")?.takeIf { it.startsWith("/") } ?: joinPath(dir, name),
            isDir = isDir,
            size = o.firstLong("size", "size_bytes", "bytes"),
            modified = modified,
            time = time,
        )
    }.sortedWith(compareByDescending<BbFile> { it.isDir }.thenBy { it.name.lowercase() })
}

/** "12.3 GB free of 29.1 GB", or null if the reply doesn't contain recognisable numbers. */
fun parseStorage(body: String): String? = try {
    var o = JSONObject(body)
    // Some servers nest it, e.g. {"sdcard": {...}} or {"storage": {...}}
    for (k in listOf("storage", "sdcard", "sd_card", "sd")) o.optJSONObject(k)?.let { o = it }
    val total = o.firstLong("total", "total_bytes", "size", "capacity")
    val free = o.firstLong("free", "free_bytes", "available", "avail")
        ?: o.firstLong("used", "used_bytes")?.let { used -> total?.minus(used) }
    val used = o.firstLong("used_bytes", "used")
    when {
        total != null && total > 0 && free != null -> "${formatBytes(free)} free of ${formatBytes(total)}"
        used != null -> "${formatBytes(used)} used" // Bambuddy 1.3 only reports used_bytes
        else -> null
    }
} catch (_: Exception) {
    null
}

/** Plate numbers in a .3mf, defaulting to plate 1 if the reply can't be read. */
fun parsePlateIndexes(body: String): List<Int> = try {
    val t = body.trim()
    val arr = if (t.startsWith("[")) JSONArray(t) else JSONObject(t).optJSONArray("plates") ?: JSONArray()
    (0 until arr.length()).mapNotNull { i ->
        when (val v = arr.opt(i)) {
            is Number -> v.toInt()
            is JSONObject -> v.firstLong("index", "plate_index", "plate", "id")?.toInt()
            else -> null
        }
    }.ifEmpty { listOf(1) }
} catch (_: Exception) {
    listOf(1)
}

fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", b / (1L shl 20).toDouble())
    b >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", b / (1L shl 10).toDouble())
    else -> "$b B"
}

// ---------------------------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------------------------

/** Browse, download and delete files on one printer's storage, through the Bambuddy server. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BambuddyFilesScreen(
    printerId: Int,
    printerName: String,
    vm: BambuddyViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val source = remember(printerId) { vm.files(printerId) }
    var path by rememberSaveable { mutableStateOf("/") }
    var files by remember { mutableStateOf<List<BbFile>>(emptyList()) }
    var raw by remember { mutableStateOf("") }
    var storage by remember { mutableStateOf<String?>(null) }
    var storageRaw by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<BbFile?>(null) }
    var confirmDelete by remember { mutableStateOf<BbFile?>(null) }
    var showRaw by remember { mutableStateOf(false) }
    var view3d by remember { mutableStateOf<BbFile?>(null) }
    var filter by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(FileSort.NAME_AZ) }
    var sortMenu by remember { mutableStateOf(false) }
    // Multi-select (files only), cleared whenever the folder changes.
    var checked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmBulkDelete by remember { mutableStateOf(false) }
    LaunchedEffect(path) { checked = emptySet() }
    val shown = remember(files, filter, sort) {
        val matching = if (filter.isBlank()) files else files.filter { it.name.contains(filter.trim(), ignoreCase = true) }
        val byKind = compareByDescending<BbFile> { it.isDir }
        matching.sortedWith(
            when (sort) {
                FileSort.NAME_AZ -> byKind.thenBy { it.name.lowercase() }
                FileSort.NAME_ZA -> byKind.thenByDescending { it.name.lowercase() }
                FileSort.NEWEST -> byKind.thenByDescending { it.time ?: 0L }
                FileSort.OLDEST -> byKind.thenBy { it.time ?: Long.MAX_VALUE }
                FileSort.LARGEST -> byKind.thenByDescending { it.size ?: 0L }
                FileSort.SMALLEST -> byKind.thenBy { it.size ?: Long.MAX_VALUE }
            },
        )
    }

    fun goUp() {
        path = path.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" }
    }

    BackHandler { if (path != "/") goUp() else onBack() }

    LaunchedEffect(printerId) {
        storage = try {
            val src = source ?: throw IllegalStateException("No connection to this printer")
            withContext(Dispatchers.IO) { src.storage() }.also { storageRaw = src.lastRaw }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            storageRaw = "Storage request failed: " + vm.describe(e)
            null
        }
    }

    LaunchedEffect(printerId, path, reload) {
        loading = true
        error = null
        try {
            val src = source ?: throw IllegalStateException("No connection to this printer")
            files = withContext(Dispatchers.IO) { src.list(path) }
            raw = src.lastRaw
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            files = emptyList()
            error = vm.describe(e)
        } finally {
            loading = false
        }
    }

    view3d?.let { f ->
        Model3DScreen(printerId = printerId, file = f, vm = vm, onBack = { view3d = null })
        return
    }

    val checkedFiles = files.filter { !it.isDir && it.path in checked }
    val visibleFiles = shown.filter { !it.isDir }

    fun downloadAll(list: List<BbFile>) {
        downloading = true
        scope.launch {
            var ok = 0
            val failed = mutableListOf<String>()
            list.forEachIndexed { i, f ->
                status = "Downloading ${i + 1} of ${list.size}: ${f.name}"
                try {
                    withContext(Dispatchers.IO) { downloadToPhone(context, vm, printerId, f) }
                    ok++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed += f.name
                }
            }
            downloading = false
            status = if (failed.isEmpty()) {
                "Saved $ok file(s) to Downloads/BamMate"
            } else {
                "Saved $ok, failed ${failed.size}: " + failed.joinToString(", ")
            }
            checked = emptySet()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (checked.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding() // stay above the phone's navigation buttons
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${checkedFiles.size} selected",
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 8.dp),
                        )
                        TextButton(onClick = { checked = emptySet() }) { Text("Clear") }
                        TextButton(
                            onClick = { downloadAll(checkedFiles) },
                            enabled = !downloading && checkedFiles.isNotEmpty(),
                        ) { Text("Download") }
                        TextButton(
                            onClick = { confirmBulkDelete = true },
                            enabled = checkedFiles.isNotEmpty(),
                        ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("$printerName files", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            path,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { if (path != "/") goUp() else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { reload++ }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = { showRaw = true }) {
                        Icon(Icons.Filled.Info, contentDescription = "Raw server reply")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding),
        ) {
            if (loading || downloading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            // Quick folders, like the Bambuddy web file manager
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("Root" to "/", "Cache" to "/cache", "Timelapse" to "/timelapse").forEach { (label, dir) ->
                    FilterChip(
                        selected = path == dir,
                        onClick = { path = dir },
                        label = { Text(label) },
                    )
                }
            }
            Row(
                modifier = Modifier.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    placeholder = { Text("Filter files") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.weight(1f),
                )
                Box {
                    IconButton(onClick = { sortMenu = true }) {
                        Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort")
                    }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        FileSort.entries.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        option.label,
                                        color = if (option == sort) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    )
                                },
                                onClick = {
                                    sort = option
                                    sortMenu = false
                                },
                            )
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = listOfNotNull(
                        "${shown.size} item${if (shown.size == 1) "" else "s"}",
                        storage,
                        sort.label,
                    ).joinToString("  -  "),
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (visibleFiles.isNotEmpty()) {
                    val allChecked = visibleFiles.all { it.path in checked }
                    TextButton(onClick = {
                        checked = if (allChecked) checked - visibleFiles.map { it.path }.toSet()
                        else checked + visibleFiles.map { it.path }
                    }) { Text(if (allChecked) "Select none" else "Select all") }
                }
            }
            status?.let {
                Text(
                    it,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            error?.let {
                Text(
                    it,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (!loading && error == null && shown.isEmpty()) {
                Text(
                    if (files.isEmpty()) "This folder is empty." else "No files match the filter.",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(shown, key = { it.path }) { f ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { if (f.isDir) path = f.path else selected = f }
                            .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (f.isDir) {
                            Spacer(Modifier.size(48.dp)) // keeps names lined up with the checkboxes
                        } else {
                            Checkbox(
                                checked = f.path in checked,
                                onCheckedChange = { on -> checked = if (on) checked + f.path else checked - f.path },
                            )
                        }
                        val (icon, tint) = fileIcon(f)
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(tint.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                        }
                        Spacer(Modifier.size(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(f.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val details = listOfNotNull(
                                f.size?.takeIf { !f.isDir }?.let(::formatBytes),
                                f.modified,
                            ).joinToString("  -  ")
                            if (details.isNotEmpty()) {
                                Text(
                                    details,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }

    selected?.let { f ->
        FileActionsDialog(
            printerId = printerId,
            file = f,
            vm = vm,
            onDismiss = { selected = null },
            onDownload = {
                selected = null
                downloading = true
                status = "Downloading ${f.name}..."
                scope.launch {
                    status = try {
                        val where = withContext(Dispatchers.IO) { downloadToPhone(context, vm, printerId, f) }
                        "Saved to $where"
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        "Download failed: " + vm.describe(e)
                    } finally {
                        downloading = false
                    }
                }
            },
            onDelete = {
                selected = null
                confirmDelete = f
            },
            onView3d = {
                selected = null
                view3d = f
            },
        )
    }

    if (confirmBulkDelete) {
        val toDelete = checkedFiles
        AlertDialog(
            onDismissRequest = { confirmBulkDelete = false },
            title = { Text("Delete ${toDelete.size} file(s)?") },
            text = {
                Text(
                    "Delete these from $printerName? This can't be undone.\n\n" +
                        toDelete.take(10).joinToString("\n") { it.name } +
                        if (toDelete.size > 10) "\n...and ${toDelete.size - 10} more" else "",
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmBulkDelete = false
                    scope.launch {
                        var ok = 0
                        val failed = mutableListOf<String>()
                        toDelete.forEachIndexed { i, f ->
                            status = "Deleting ${i + 1} of ${toDelete.size}: ${f.name}"
                            try {
                                withContext(Dispatchers.IO) { source!!.delete(f) }
                                ok++
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                failed += f.name
                            }
                        }
                        status = if (failed.isEmpty()) "Deleted $ok file(s)"
                        else "Deleted $ok, failed ${failed.size}: " + failed.joinToString(", ")
                        checked = emptySet()
                        reload++
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmBulkDelete = false }) { Text("Cancel") } },
        )
    }

    confirmDelete?.let { f ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete file?") },
            text = { Text("Delete ${f.name} from $printerName? This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    scope.launch {
                        status = try {
                            withContext(Dispatchers.IO) { source!!.delete(f) }
                            reload++
                            "Deleted ${f.name}"
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            "Delete failed: " + vm.describe(e)
                        }
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }

    if (showRaw) {
        AlertDialog(
            onDismissRequest = { showRaw = false },
            title = { Text("Server reply for $path") },
            text = {
                Text(
                    text = "Storage:\n" + prettyAny(storageRaw).take(1500) +
                        "\n\nFiles in $path:\n" + prettyAny(raw).take(6000),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = { TextButton(onClick = { showRaw = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun FileActionsDialog(
    printerId: Int,
    file: BbFile,
    vm: BambuddyViewModel,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onView3d: () -> Unit,
) {
    val is3mf = file.name.endsWith(".3mf", ignoreCase = true)
    var thumb by remember { mutableStateOf<ImageBitmap?>(null) }
    var thumbNote by remember { mutableStateOf<String?>(null) }

    if (is3mf) {
        LaunchedEffect(file.path) {
            thumbNote = "Loading preview..."
            try {
                val bmp = withContext(Dispatchers.IO) {
                    val src = vm.files(printerId) ?: throw IllegalStateException("No connection to this printer")
                    src.thumbnail(file.path)
                }
                thumb = bmp?.asImageBitmap()
                thumbNote = if (bmp == null) "No preview in this file" else null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                thumbNote = "No preview: " + vm.describe(e)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(file.name, maxLines = 3, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (is3mf) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .background(Color.Black),
                        contentAlignment = Alignment.Center,
                    ) {
                        val t = thumb
                        if (t != null) {
                            Image(t, contentDescription = "Plate preview", contentScale = ContentScale.Fit)
                        } else {
                            Text(
                                thumbNote ?: "",
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (is3mf) {
                    FilledTonalButton(onClick = onView3d, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.ViewInAr, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("View in 3D")
                    }
                }
                Text(
                    listOfNotNull(file.size?.let(::formatBytes), file.modified).joinToString("  -  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(file.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDownload) { Text("Download") } },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}

/** Streams a printer file into the phone's Downloads/BamMate folder. Returns where it went. */
private fun downloadToPhone(context: Context, vm: BambuddyViewModel, printerId: Int, file: BbFile): String {
    val src = vm.files(printerId) ?: throw IllegalStateException("No connection to this printer")
    val (stream, _) = src.open(file.path)
    run {
        stream.use { input ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/BamMate")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("Couldn't create the file in Downloads")
                try {
                    val out: OutputStream = resolver.openOutputStream(uri)
                        ?: throw IllegalStateException("Couldn't write to Downloads")
                    out.use { input.copyTo(it, 64 * 1024) }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                } catch (e: Exception) {
                    resolver.delete(uri, null, null)
                    throw e
                }
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "BamMate")
                dir.mkdirs()
                FileOutputStream(File(dir, file.name)).use { input.copyTo(it, 64 * 1024) }
            }
        }
    }
    return "Downloads/BamMate/${file.name}"
}

private val FolderAmber = Color(0xFFF59E0B)
private val VideoPurple = Color(0xFFA78BFA)
private val ImageGreen = Color(0xFF22C55E)

/** Icon and colour by kind: folders, print files (.3mf / .gcode), videos, images, the rest. */
@Composable
private fun fileIcon(f: BbFile): Pair<ImageVector, Color> {
    if (f.isDir) return Icons.Filled.Folder to FolderAmber
    val ext = f.name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "3mf", "gcode", "stl", "step", "obj" -> Icons.Filled.ViewInAr to MaterialTheme.colorScheme.primary
        "mp4", "avi", "mov", "mkv" -> Icons.Filled.Movie to VideoPurple
        "jpg", "jpeg", "png", "webp" -> Icons.Filled.Photo to ImageGreen
        else -> Icons.Filled.Description to MaterialTheme.colorScheme.onSurfaceVariant
    }
}

private enum class FileSort(val label: String) {
    NAME_AZ("Name (A-Z)"),
    NAME_ZA("Name (Z-A)"),
    NEWEST("Newest first"),
    OLDEST("Oldest first"),
    LARGEST("Largest first"),
    SMALLEST("Smallest first"),
}

private fun prettyAny(raw: String): String = try {
    val t = raw.trim()
    if (t.startsWith("[")) JSONArray(t).toString(2) else JSONObject(t).toString(2)
} catch (_: Exception) {
    raw
}
