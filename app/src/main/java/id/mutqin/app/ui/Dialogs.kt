@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package id.mutqin.app.ui

import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import id.mutqin.app.MutqinViewModel
import id.mutqin.app.NotificationSettings
import id.mutqin.app.QuranData
import id.mutqin.app.QuranStructure
import id.mutqin.app.ReminderSchedule
import id.mutqin.app.ReminderScheduler
import id.mutqin.app.UserSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Locale

@Composable
private fun FullDialog(onClose: () -> Unit, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.padding(12.dp).fillMaxWidth().fillMaxSize(0.94f),
            shape = RoundedCornerShape(26.dp),
            color = p.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, p.emerald.copy(alpha = 0.5f))
        ) { content() }
    }
}

@Composable
private fun DialogHeader(title: String, subtitle: String, onClose: () -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = p.textPrimary, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
            Text(subtitle, color = p.textMuted, fontSize = 12.sp)
        }
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Tutup") }
    }
}

// ===========================================================================
// Target hafalan (cakupan murajaah): per Juz / Surat / rentang halaman
// ===========================================================================

@Composable
fun ScopeManagerDialog(settings: UserSettings, onSave: (mode: String, pages: Set<Int>) -> Unit, onClose: () -> Unit) {
    val p = LocalPalette.current
    var mode by remember { mutableStateOf(settings.scopeMode) }
    var selected by remember { mutableStateOf(settings.activeScopePages.toSet()) }
    var tab by remember { mutableIntStateOf(0) }
    var fromText by remember { mutableStateOf("1") }
    var toText by remember { mutableStateOf("20") }

    fun togglePages(pages: List<Int>) {
        mode = "custom"
        selected = if (pages.all { it in selected }) selected - pages.toSet() else selected + pages
    }

    FullDialog(onClose) {
        Column(Modifier.padding(16.dp)) {
            DialogHeader("Atur Target Hafalan", "Pilih Juz, Surat, atau rentang halaman yang ingin dimurajaah.", onClose)
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(p.background)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Halaman aktif dalam program", color = p.textMuted, fontSize = 11.sp)
                    Text("${selected.size} / 604", color = p.emerald, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                }
                TextButton(onClick = { selected = (1..QuranStructure.TOTAL_PAGES).toSet(); mode = "all" }) { Text("Pilih Semua") }
                TextButton(onClick = { selected = emptySet(); mode = "custom" }) { Text("Kosongkan") }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Per Juz", "Per Surat", "Rentang").forEachIndexed { i, label ->
                    val sel = tab == i
                    Text(
                        label, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        color = if (sel) Color(0xFF020617) else p.textMuted,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (sel) p.gold else p.surfaceAlt)
                            .clickable { tab = i }
                            .padding(horizontal = 14.dp, vertical = 7.dp)
                    )
                }
            }
            Spacer(Modifier.height(10.dp))

            Column(Modifier.weight(1f)) {
                when (tab) {
                    0 -> Column(Modifier.verticalScroll(rememberScrollState())) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            (1..30).forEach { j ->
                                val pages = QuranStructure.pagesInJuz(j)
                                val count = pages.count { it in selected }
                                val all = count == pages.size
                                val partial = count in 1 until pages.size
                                Text(
                                    "Juz $j" + if (partial) " ($count/${pages.size})" else "",
                                    fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                    color = if (all) Color.White else if (partial) p.warning else p.textMuted,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (all) p.emerald else p.surfaceAlt)
                                        .border(1.dp, if (partial) p.warning else p.border, RoundedCornerShape(12.dp))
                                        .clickable { togglePages(pages) }
                                        .padding(horizontal = 12.dp, vertical = 9.dp)
                                )
                            }
                        }
                    }
                    1 -> LazyColumn {
                        items(QuranData.surahs, key = { it.number }) { s ->
                            val pages = (s.startPage..s.endPage).toList()
                            val all = pages.all { it in selected }
                            Row(
                                Modifier.fillMaxWidth().clickable { togglePages(pages) },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(checked = all, onCheckedChange = { togglePages(pages) })
                                Column(Modifier.weight(1f)) {
                                    Text("${s.number}. ${s.nameLatin}", color = p.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    Text("Hal ${s.startPage}–${s.endPage} • ${s.totalVerses} ayat", color = p.textMuted, fontSize = 11.sp)
                                }
                                Text(s.nameArabic, fontFamily = MutqinFonts.quran, fontSize = 18.sp, color = p.textMuted)
                            }
                        }
                    }
                    else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = fromText, onValueChange = { fromText = it.filter(Char::isDigit).take(3) },
                                label = { Text("Dari halaman") }, singleLine = true, modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                            OutlinedTextField(
                                value = toText, onValueChange = { toText = it.filter(Char::isDigit).take(3) },
                                label = { Text("Sampai halaman") }, singleLine = true, modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                        }
                        Button(onClick = {
                            val from = (fromText.toIntOrNull() ?: 1).coerceIn(1, 604)
                            val to = (toText.toIntOrNull() ?: from).coerceIn(from, 604)
                            mode = "custom"
                            selected = selected + (from..to)
                        }) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("Tambahkan rentang")
                        }
                        Text(
                            "Halaman baru yang ditambahkan ke program akan antri di BELAKANG antrean halaman yang belum dimurajaah.",
                            color = p.textMuted, fontSize = 11.sp
                        )
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f)) { Text("Batal") }
                Button(
                    onClick = { onSave(mode, selected); onClose() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = p.emerald, contentColor = Color(0xFF022C22))
                ) { Text("Simpan Target", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

// ===========================================================================
// Pengingat murajaah (notifikasi terjadwal)
// ===========================================================================

private val DAY_CHIPS = listOf(1 to "Sen", 2 to "Sel", 3 to "Rab", 4 to "Kam", 5 to "Jum", 6 to "Sab", 0 to "Aha")

@Composable
fun NotificationSettingsDialog(vm: MutqinViewModel, onClose: () -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val initial = vm.settings.notifications ?: NotificationSettings()

    var enabled by remember { mutableStateOf(initial.enabled) }
    var title by remember { mutableStateOf(initial.title) }
    var days by remember { mutableStateOf(initial.days) }
    var times by remember { mutableStateOf(initial.times) }
    var status by remember { mutableStateOf<String?>(null) }
    var hasPermission by remember { mutableStateOf(ReminderScheduler.hasNotificationPermission(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        status = if (granted) "Izin notifikasi aktif." else "Izin notifikasi ditolak. Aktifkan lewat Pengaturan perangkat."
    }

    val nextMeta = QuranData.pageMeta(vm.targetNextPage)
    val body = ReminderScheduler.buildBody(vm.stats.streakDays, nextMeta)

    FullDialog(onClose) {
        Column(Modifier.padding(16.dp)) {
            DialogHeader("Pengaturan Pengingat", "Atur jam, hari, dan judul notifikasi murajaah.", onClose)
            Spacer(Modifier.height(8.dp))
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.background).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Aktifkan Pengingat Murajaah", color = p.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("Notifikasi otomatis di jam & hari yang Anda tentukan", color = p.textMuted, fontSize = 11.sp)
                    }
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }

                if (enabled) {
                    if (!hasPermission) {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.warning.copy(alpha = 0.14f)).padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Izin notifikasi belum aktif di perangkat ini.", color = p.warning, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            Button(onClick = {
                                if (Build.VERSION.SDK_INT >= 33) permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                            }) { Text("Izinkan", fontSize = 12.sp) }
                        }
                    }
                    if (Build.VERSION.SDK_INT >= 31 && !ReminderScheduler.canScheduleExact(context)) {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.surfaceAlt).padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Alarm tepat waktu belum diizinkan; pengingat bisa terlambat beberapa menit.",
                                color = p.textMuted, fontSize = 12.sp, modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
                                    )
                                }
                            }) { Text("Atur", fontSize = 12.sp) }
                        }
                    }

                    OutlinedTextField(
                        value = title, onValueChange = { title = it }, singleLine = true,
                        label = { Text("Judul notifikasi") }, modifier = Modifier.fillMaxWidth()
                    )

                    Text("Hari pengingat", color = p.textMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        DAY_CHIPS.forEach { (id, label) ->
                            val sel = id in days
                            Text(
                                label, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                color = if (sel) Color.White else p.textMuted,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (sel) p.emerald else p.surfaceAlt)
                                    .clickable {
                                        days = if (sel) { if (days.size > 1) days - id else days } else days + id
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }

                    Text("Jam pengingat", color = p.textMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    times.forEach { t ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.background).padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                t.time, color = p.textPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp,
                                modifier = Modifier.weight(1f).clickable {
                                    pickTime(context, t.time) { picked ->
                                        times = times.map { if (it.id == t.id) it.copy(time = picked) else it }.sortedBy { it.time }
                                    }
                                }
                            )
                            Switch(checked = t.enabled, onCheckedChange = { on -> times = times.map { if (it.id == t.id) it.copy(enabled = on) else it } })
                            IconButton(onClick = { if (times.size > 1) times = times.filter { it.id != t.id } }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Hapus jam", tint = p.textMuted)
                            }
                        }
                    }
                    OutlinedButton(onClick = {
                        pickTime(context, "20:00") { picked ->
                            if (times.none { it.time == picked }) {
                                times = (times + ReminderSchedule("time_${System.currentTimeMillis()}", picked, true)).sortedBy { it.time }
                            }
                        }
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Tambah jam")
                    }

                    Text("Pratinjau isi notifikasi", color = p.textMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.background).padding(12.dp)) {
                        Text(title.ifBlank { NotificationSettings.DEFAULT_TITLE }, color = p.goldSoft, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(body, color = p.textPrimary, fontSize = 12.sp)
                    }
                    OutlinedButton(onClick = {
                        if (!hasPermission && Build.VERSION.SDK_INT >= 33) {
                            permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            ReminderScheduler.show(context, title.ifBlank { NotificationSettings.DEFAULT_TITLE }, body)
                            status = "Notifikasi uji dikirim."
                        }
                    }) { Text("Kirim notifikasi uji") }
                }
                status?.let { Text(it, color = p.emerald, fontSize = 12.sp) }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f)) { Text("Batal") }
                Button(
                    onClick = {
                        vm.updateSettings(
                            vm.settings.copy(
                                notifications = NotificationSettings(
                                    enabled = enabled,
                                    title = title.trim().ifEmpty { NotificationSettings.DEFAULT_TITLE },
                                    days = days,
                                    times = times
                                )
                            )
                        )
                        onClose()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = p.emerald, contentColor = Color(0xFF022C22))
                ) { Text("Simpan", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

private fun pickTime(context: Context, initial: String, onPicked: (String) -> Unit) {
    val parts = initial.split(":")
    val h = parts.getOrNull(0)?.toIntOrNull() ?: 5
    val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
    TimePickerDialog(context, { _, hh, mm -> onPicked(String.format(Locale.US, "%02d:%02d", hh, mm)) }, h, m, true).show()
}

// ===========================================================================
// Cadangan & sinkronisasi antar-perangkat (JSON; kompatibel dengan versi web)
// ===========================================================================

@Composable
fun BackupDialog(vm: MutqinViewModel, onClose: () -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var status by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // pesan, sukses?
    var pasted by remember { mutableStateOf("") }
    var pendingJson by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val createLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val json = pendingJson
        pendingJson = null
        if (uri != null && json != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) } != null
                    }.getOrDefault(false)
                }
                status = if (ok) "File cadangan berhasil disimpan." to true else "Gagal menyimpan file cadangan." to false
            }
        }
    }

    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                val text = withContext(Dispatchers.IO) {
                    runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } }.getOrNull()
                }
                status = if (text == null) "Gagal membaca file." to false else importResult(vm.importJson(text))
                busy = false
            }
        }
    }

    FullDialog(onClose) {
        Column(Modifier.padding(16.dp)) {
            DialogHeader("Cadangan & Sinkronisasi", "Pindahkan progres antar HP/tablet, atau dari versi web Mutqin.", onClose)
            Spacer(Modifier.height(8.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("ID perangkat ini: ${vm.deviceId}", color = p.textMuted, fontSize = 12.sp)

                MCard(Modifier.fillMaxWidth(), accent = p.emerald) {
                    Text("Ekspor data", color = p.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("Berisi seluruh progres, riwayat murajaah, dan pengaturan.", color = p.textMuted, fontSize = 12.sp)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !busy, onClick = {
                            scope.launch {
                                pendingJson = vm.exportJson()
                                createLauncher.launch("mutqin-cadangan-${LocalDate.now()}.json")
                            }
                        }) { Text("Simpan ke File", fontSize = 12.sp) }
                        OutlinedButton(enabled = !busy, onClick = {
                            scope.launch {
                                val json = vm.exportJson()
                                status = try {
                                    clipboard.setText(AnnotatedString(json))
                                    "Data disalin ke clipboard." to true
                                } catch (e: Exception) {
                                    "Data terlalu besar untuk clipboard. Gunakan \"Simpan ke File\"." to false
                                }
                            }
                        }) { Text("Salin", fontSize = 12.sp) }
                    }
                }

                MCard(Modifier.fillMaxWidth(), accent = p.gold) {
                    Text("Impor data", color = p.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(
                        "Data digabung per halaman: yang murajaahnya paling baru dipertahankan.",
                        color = p.textMuted, fontSize = 12.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(enabled = !busy, onClick = { openLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }) {
                        Text("Pilih File Cadangan", fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = pasted, onValueChange = { pasted = it },
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        placeholder = { Text("…atau tempel JSON di sini", fontSize = 12.sp) }
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(enabled = !busy && pasted.isNotBlank(), onClick = {
                        scope.launch {
                            busy = true
                            status = importResult(vm.importJson(pasted))
                            if (status?.second == true) pasted = ""
                            busy = false
                        }
                    }) { Text("Impor dari Teks", fontSize = 12.sp) }
                }

                status?.let { (msg, ok) ->
                    Text(msg, color = if (ok) p.emerald else p.danger, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Tutup") }
        }
    }
}

private fun importResult(result: Result<Int>): Pair<String, Boolean> =
    result.fold(
        onSuccess = { "Alhamdulillah! $it data halaman berhasil disinkronkan ke perangkat ini." to true },
        onFailure = { (it.message ?: "Format data cadangan tidak sesuai.") to false }
    )
