package jp.yohaku.app

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

private val stamp = DateTimeFormatter.ofPattern("M/d HH:mm")

/** Formats a recorded instant in the current device time zone. */
fun sleepStamp(value: Long): String = Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()).format(stamp)

/** Requests read-only Health Connect consent and refreshes only while the app is in use. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SleepConnection(controller: SleepController) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val launcher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { permissions ->
        if (permissions.containsAll(controller.permissions)) controller.connect()
        else { controller.message = "許可されていないため自動入力を開始しませんでした。手入力は利用できます。"; controller.refresh() }
    }
    DisposableEffect(lifecycle, controller) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) controller.refresh() }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) controller.refresh()
        onDispose { lifecycle.removeObserver(observer) }
    }
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("睡眠の自動入力", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(controller.status, fontSize = 13.sp)
            if (controller.lastSync > 0) Text("最終同期 ${sleepStamp(controller.lastSync)}", fontSize = 12.sp)
            Text("接続後はアプリを開いたとき・戻ったときに同期。修正や追加は再同期後も残ります。", fontSize = 12.sp)
            if (controller.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (controller.available == HealthConnectClient.SDK_AVAILABLE) {
                    Button(enabled = !controller.busy && controller.ready, onClick = {
                        if (controller.granted) controller.connect() else launcher.launch(controller.permissions)
                    }) { Text(if (controller.automatic && controller.granted) "再同期" else "Health Connectに接続") }
                    if (controller.automatic) TextButton(onClick = controller::disconnect) { Text("停止") }
                } else if (controller.available == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED) {
                    Button(onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata"))) }
                            .onFailure { controller.message = "ストアでHealth Connectをインストール・更新してください。" }
                    }) { Text("インストール・更新") }
                }
            }
            if (!controller.ready) TextButton(onClick = controller::load) { Text("保存データを再読み込み") }
            TextButton(onClick = { context.startActivity(Intent(context, PermissionsRationaleActivity::class.java)) }) { Text("データの取り扱い・接続方法") }
        }
    }
}

/** Shows the selected day's real sleep, with a separate editor for dated corrections. */
@Composable
fun SleepRecords(controller: SleepController, date: LocalDate, onEdit: (String?) -> Unit) {
    val zone = ZoneId.systemDefault()
    val rows = sleepForDay(controller.entries, date, zone)
    val actual = sleepEvents(controller.entries, date, zone)
    var showHidden by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("睡眠記録 · ${date.monthValue}/${date.dayOfMonth}", fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Text(if (rows.isEmpty()) "未入力（生活予定で補完）" else "この日の睡眠枠 ${durationText(analyze(actual, 0, 1).busy)}", fontWeight = FontWeight.Medium)
        Text("睡眠枠は途中の覚醒を含む開始〜終了です。実際に起きて活動した場合は、記録を分けて調整できます。", fontSize = 12.sp)
        if (rows.isEmpty()) Text("この日付の記録はまだありません。未計測の睡眠や二度寝を追加できます。", fontSize = 13.sp)
        rows.forEach { row ->
            Card(Modifier.fillMaxWidth().clickable { onEdit(row.id) }) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(row.title, fontWeight = FontWeight.Bold)
                    Text("${sleepStamp(row.start)} → ${sleepStamp(row.end)}", fontSize = 14.sp)
                    Text("${row.source} · ${if (row.imported) if (row.edited) "手動修正済み" else "自動取得" else "手入力"}", fontSize = 12.sp)
                    Text("タップして時間を修正", fontSize = 12.sp)
                }
            }
        }
        OutlinedButton(onClick = { onEdit(null) }, enabled = controller.ready) { Text("＋ 二度寝・別端末の睡眠を追加") }
        if (controller.entries.any { it.hidden }) TextButton(onClick = { showHidden = true }) { Text("非表示にした記録を戻す") }
        Text("Apple Watchなどの記録は手入力できます。Appleヘルスケアからの直接同期には対応していません。", fontSize = 12.sp)
    }
    if (showHidden) AlertDialog(onDismissRequest = { showHidden = false }, title = { Text("非表示の記録") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            controller.entries.filter { it.hidden }.forEach { row ->
                TextButton(onClick = { controller.save(row.copy(hidden = false)) }) { Text("${sleepStamp(row.start)} ${row.title} を戻す") }
            }
        }
    }, confirmButton = { TextButton(onClick = { showHidden = false }) { Text("閉じる") } })
}

/** Lets the user choose an actual date rather than changing a recurring template. */
@Composable
fun SleepDateSelector(date: LocalDate, onChange: (LocalDate) -> Unit) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = { onChange(date.minusDays(1)) }) { Text("‹ 前日") }
        TextButton(onClick = {
            DatePickerDialog(context, { _, y, m, d -> onChange(LocalDate.of(y, m + 1, d)) }, date.year, date.monthValue - 1, date.dayOfMonth).apply {
                datePicker.maxDate = System.currentTimeMillis()
            }.show()
        }) { Text(date.format(DateTimeFormatter.ofPattern("yyyy/M/d"))) }
        TextButton(enabled = date < LocalDate.now(), onClick = { onChange(date.plusDays(1)) }) { Text("翌日 ›") }
    }
}

/** Edits start/end dates and times, preserving source identity for imported records. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SleepEditor(entry: SleepEntry?, date: LocalDate, onDismiss: () -> Unit, onSave: (SleepEntry) -> Boolean, onHide: (SleepEntry) -> Boolean, onRestore: (SleepEntry) -> Boolean) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val defaultEnd = minOf(date.atTime(8, 0), LocalDateTime.now().withSecond(0).withNano(0))
    var startText by rememberSaveable { mutableStateOf((entry?.let { Instant.ofEpochMilli(it.start).atZone(zone).toLocalDateTime() } ?: defaultEnd.minusHours(1)).toString()) }
    var endText by rememberSaveable { mutableStateOf((entry?.let { Instant.ofEpochMilli(it.end).atZone(zone).toLocalDateTime() } ?: defaultEnd).toString()) }
    var title by rememberSaveable { mutableStateOf(entry?.title ?: "二度寝") }
    var source by rememberSaveable { mutableStateOf(entry?.source ?: "手入力") }
    var confirmation by remember { mutableStateOf<String?>(null) }
    val start = LocalDateTime.parse(startText)
    val end = LocalDateTime.parse(endText)
    val startMillis = start.atZone(zone).toInstant().toEpochMilli()
    val endMillis = end.atZone(zone).toInstant().toEpochMilli()
    val length = Duration.between(start.atZone(zone), end.atZone(zone)).toMinutes()
    val valid = title.isNotBlank() && length in 1..2880 && endMillis <= System.currentTimeMillis() && zone.rules.getValidOffsets(start).size == 1 && zone.rules.getValidOffsets(end).size == 1
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (entry == null) "睡眠を追加" else "睡眠を修正") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(title, { title = it.take(40) }, label = { Text("記録の名前") }, singleLine = true)
            if (entry?.imported != true) {
                Text("計測元", fontSize = 13.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("手入力", "Apple Watch", "その他の端末").forEach { value -> FilterChip(selected = source == value, onClick = { source = value }, label = { Text(value) }) }
                }
            } else {
                Text("${entry.source}\n修正はYohakuの中だけに保存されます。", fontSize = 12.sp)
                Text("取得時 ${sleepStamp(entry.originalStart)} → ${sleepStamp(entry.originalEnd)}", fontSize = 12.sp)
            }
            listOf("開始" to start, "終了" to end).forEach { (label, value) ->
                Text(label, fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        DatePickerDialog(context, { _, y, m, d ->
                            val updated = LocalDate.of(y, m + 1, d).atTime(value.toLocalTime())
                            if (label == "開始") startText = updated.toString() else endText = updated.toString()
                        }, value.year, value.monthValue - 1, value.dayOfMonth).show()
                    }) { Text(value.toLocalDate().toString()) }
                    OutlinedButton(onClick = {
                        TimePickerDialog(context, { _, h, m ->
                            val updated = value.withHour(h).withMinute(m).withSecond(0).withNano(0)
                            if (label == "開始") startText = updated.toString() else endText = updated.toString()
                        }, value.hour, value.minute, true).show()
                    }) { Text(value.format(DateTimeFormatter.ofPattern("HH:mm"))) }
                }
            }
            Text(if (valid) "睡眠枠 ${durationText(length.toInt())}" else "開始より後の終了時刻を指定してください（過去の1分〜48時間）。夏時間の切り替わりで曖昧な時刻は避けてください。", fontSize = 12.sp)
            if (entry != null) {
                TextButton(onClick = { confirmation = "hide" }) { Text(if (entry.imported) "この記録を非表示" else "この記録を削除") }
                if (entry.imported && entry.edited) TextButton(onClick = { confirmation = "restore" }) { Text("取得した時刻に戻す") }
            }
        }
    }, confirmButton = { TextButton(enabled = valid, onClick = {
        val row = entry?.copy(title = title.trim(), start = startMillis, end = endMillis, edited = true)
            ?: SleepEntry("manual:${UUID.randomUUID()}", title.trim(), startMillis, endMillis, source)
        if (onSave(row)) onDismiss()
    }) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } })
    if (confirmation != null && entry != null) AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(if (confirmation == "restore") "手動修正を取り消しますか？" else if (entry.imported) "この記録を非表示にしますか？" else "この記録を削除しますか？") }, text = { Text(if (confirmation == "restore") "最後に取得した時刻に戻します。" else if (entry.imported) "再同期しても表示されません。後から戻せます。" else "この端末の手入力記録を削除します。") }, confirmButton = {
        TextButton(onClick = { if (if (confirmation == "restore") onRestore(entry) else onHide(entry)) onDismiss(); confirmation = null }) { Text("実行") }
    }, dismissButton = { TextButton(onClick = { confirmation = null }) { Text("戻る") } })
}
