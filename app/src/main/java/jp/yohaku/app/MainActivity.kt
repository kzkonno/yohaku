package jp.yohaku.app

import android.app.TimePickerDialog
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.time.LocalDate
import java.time.ZoneId
import androidx.lifecycle.viewmodel.compose.viewModel

private val Green = Color(0xFF286450)
private val Ink = Color(0xFF213D34)
private val Paper = Color(0xFFF6F5F0)
private val Muted = Color(0xFF69776D)
private val categories = listOf("睡眠", "身支度", "朝食", "通勤", "仕事", "昼食", "夕食", "入浴", "家事", "その他")

/** Hosts the offline daily time planner. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Green, background = Paper, surface = Paper, onSurface = Ink)) {
                Planner()
            }
        }
    }
}

/** Persists independent recurring templates on this device. */
private class ScheduleStore(context: Context) {
    private val prefs = context.getSharedPreferences("yohaku", Context.MODE_PRIVATE)
    fun read(weekend: Boolean): List<Event> {
        val saved = prefs.getString("events-$weekend", null) ?: return sampleEvents(weekend)
        return runCatching {
            val array = JSONArray(saved)
            List(array.length()) { i ->
                val row = array.getJSONObject(i)
                Event(row.getString("id"), row.getString("title"), row.getString("category"), row.getInt("start"), row.getInt("end"))
            }.also { analyze(it, 0, 1) }
        }.getOrElse { sampleEvents(weekend) }
    }
    fun write(weekend: Boolean, events: List<Event>) {
        val array = JSONArray()
        events.forEach { event -> array.put(JSONObject().put("id", event.id).put("title", event.title).put("category", event.category).put("start", event.start).put("end", event.end)) }
        prefs.edit().putString("events-$weekend", array.toString()).apply()
    }
    fun setting(key: String, fallback: Int): Int = prefs.getInt(key, fallback)
    fun saveSetting(key: String, value: Int) { prefs.edit().putInt(key, value).apply() }
}

/** Assigns a consistent visual identity to each commitment category. */
private fun categoryColor(category: String): Color = when (category) {
    "睡眠" -> Color(0xFF8C95B1)
    "仕事" -> Color(0xFFCBAC79)
    "通勤" -> Color(0xFFA0B7BC)
    "朝食", "昼食", "夕食" -> Color(0xFFD79980)
    else -> Color(0xFFB5BCA0)
}

/** Renders overview, usable gaps, settings and an editable daily schedule. */
@Composable
private fun Planner() {
    val context = LocalContext.current
    val store = remember { ScheduleStore(context) }
    var weekend by rememberSaveable { mutableStateOf(false) }
    var template by remember(weekend) { mutableStateOf(store.read(weekend)) }
    val sleep: SleepController = viewModel()
    var dated by rememberSaveable { mutableStateOf(true) }
    var dateText by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    val date = LocalDate.parse(dateText)
    var sleepEditorOpen by rememberSaveable { mutableStateOf(false) }
    var sleepEditingId by rememberSaveable { mutableStateOf<String?>(null) }
    val sleepEditing = sleep.entries.firstOrNull { it.id == sleepEditingId }
    val events = if (dated) withSleep(template, sleepEvents(sleep.entries, date, ZoneId.systemDefault())) else template
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(sleep.message) {
        sleep.message?.let { message -> snackbar.showSnackbar(message); if (sleep.message == message) sleep.message = null }
    }
    var minimum by rememberSaveable { mutableIntStateOf(store.setting("minimum", 30)) }
    var buffer by rememberSaveable { mutableIntStateOf(store.setting("buffer", 5)) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    val editing = template.firstOrNull { it.id == editingId }
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    val result = remember(events, minimum, buffer) { analyze(events, buffer, minimum) }
    val scroll = rememberScrollState()

    Scaffold(containerColor = Paper, snackbarHost = { SnackbarHost(snackbar) }, floatingActionButton = {
        ExtendedFloatingActionButton(onClick = { if (dated) { sleepEditingId = null; sleepEditorOpen = true } else { editingId = null; editorOpen = true } }, containerColor = Green, contentColor = Color.White) {
            Text(if (dated) "＋ 睡眠を追加" else "＋ 予定を追加", fontWeight = FontWeight.Bold)
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(scroll).padding(horizontal = 24.dp).padding(top = 20.dp, bottom = 104.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column { Text("Yohaku", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Ink); Text("暮らしの中に、自分の時間を。", color = Muted, fontSize = 12.sp) }
                Text("MY TIME", fontSize = 11.sp, color = Green)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = dated, onClick = { dated = true }, label = { Text("日付別の記録") })
                FilterChip(selected = !dated, onClick = { dated = false }, label = { Text("基本の予定") })
            }
            if (dated) {
                SleepDateSelector(date) { dateText = it.toString() }
                TextButton(onClick = { dateText = LocalDate.now().toString() }) { Text("今日に戻る") }
                SleepConnection(sleep)
            }
            Text(if (dated) "この日に使う生活予定" else "繰り返し予定を編集", fontSize = 13.sp, color = Muted)
            Row(Modifier.fillMaxWidth().background(Color(0xFFE9EAE2), RoundedCornerShape(18.dp)).padding(4.dp)) {
                listOf(false to "平日", true to "休日").forEach { (value, label) ->
                    Box(Modifier.weight(1f).background(if (weekend == value) Color.White else Color.Transparent, RoundedCornerShape(14.dp)).clickable { weekend = value }.padding(12.dp), contentAlignment = Alignment.Center) {
                        Text(label, color = if (weekend == value) Green else Muted, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFE5EDE2)), shape = RoundedCornerShape(28.dp)) {
                Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (dated) "記録と予定から見積もる自由時間" else "本当に使える自由時間", color = Green, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Box(Modifier.size(230.dp).padding(12.dp), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.fillMaxSize().semantics { contentDescription = "24時間のうち自由時間は${durationText(result.usableMinutes)}" }) {
                            val stroke = 13.dp.toPx()
                            drawArc(Color(0xFFCFD9CC), -90f, 360f, false, style = Stroke(stroke))
                            if (result.usableMinutes > 0) drawArc(Green, -90f, result.usableMinutes / 1440f * 360f, false, style = Stroke(stroke, cap = StrokeCap.Round))
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(durationText(result.usableMinutes), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Ink)
                            Text("24時間の ${result.usableMinutes * 100 / 1440}%", color = Muted, fontSize = 12.sp)
                        }
                    }
                    Text("${minimum}分以上のまとまりが ${result.usable.size} 回", color = Green)
                    HorizontalDivider(Modifier.padding(vertical = 18.dp), color = Color(0xFFCAD7C7))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Metric("生活・仕事", durationText(result.busy))
                        Metric("余裕・短い空き", durationText(result.rawFree - result.usableMinutes))
                    }
                }
            }
            if (dated) {
                SleepRecords(sleep, date) { id -> sleepEditingId = id; sleepEditorOpen = true }
                Text("睡眠記録と重なる睡眠予定を置き換え、それ以外は基本の予定で補っています。表示は1日24時間の時計上の見積もりです。", fontSize = 12.sp, color = Muted)
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle("自分に使える時間", "FREE TIME")
                if (result.usable.isEmpty()) {
                    Text("今の条件では、まとまった空き時間がありません。予定や下の条件を調整してみましょう。", color = Muted)
                }
                result.usable.forEach { gap ->
                    Row(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(16.dp)).padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column { Text("${clockTime(gap.start)} — ${clockTime(gap.end)}", color = Ink, fontWeight = FontWeight.Bold); Text(if (gap.minutes >= 60) "趣味や学習に、じっくり使える" else "散歩や読書、ひと息つく時間に", color = Muted, fontSize = 12.sp) }
                        Text(durationText(gap.minutes), color = Green, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle("自由時間の条件", "YOUR PACE")
                Text("何分あれば、自由に使える？", fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 30, 60, 90).forEach { value -> FilterChip(selected = minimum == value, onClick = { minimum = value; store.saveSetting("minimum", value) }, label = { Text("${value}分") }) }
                }
                Text("予定の前後に確保する余裕時間", fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 5, 10, 15).forEach { value -> FilterChip(selected = buffer == value, onClick = { buffer = value; store.saveSetting("buffer", value) }, label = { Text("${value}分") }) }
                }
                Text("空き時間から予定の前後の余裕を引き、指定した長さ以上の枠だけを集計します。", color = Muted, fontSize = 12.sp)
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionTitle("1日のタイムライン", "24 HOURS")
                DayStrip(events)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { listOf("0:00", "6:00", "12:00", "18:00", "24:00").forEach { Text(it, color = Muted, fontSize = 10.sp) } }
                Text("薄緑：空き時間（余裕・短い空きを含む）\n予定をタップして編集", color = Muted, fontSize = 12.sp)
                events.sortedBy { it.start }.forEach { event ->
                    Row(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(16.dp)).clickable {
                        if (event.id.startsWith("sleep:")) {
                            sleepEditingId = event.id.removePrefix("sleep:"); sleepEditorOpen = true
                        } else {
                            editingId = if (event.id.startsWith("plan:")) event.id.removePrefix("plan:").substringBeforeLast(":") else event.id
                            editorOpen = true
                        }
                    }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(4.dp).height(38.dp).background(categoryColor(event.category), RoundedCornerShape(2.dp)))
                        Column(Modifier.weight(1f).padding(start = 14.dp)) {
                            Text(event.title, color = Ink, fontWeight = FontWeight.Medium)
                            Text("${clockTime(event.start)} — ${if (event.end < event.start) "翌 " else ""}${clockTime(event.end)}", fontSize = 12.sp, color = Muted)
                        }
                        Text(durationText(eventMinutes(event)), fontSize = 12.sp, color = Muted)
                        Text("  ›", color = Muted, fontSize = 20.sp)
                    }
                }
                Text("重なる予定は一度だけ集計。毎日繰り返す24時間のモデルです。自由時間は0時で区切ります。データはこの端末に自動保存されます。", fontSize = 12.sp, color = Muted)
            }
        }
    }
    if (sleepEditorOpen) SleepEditor(sleepEditing, date, onDismiss = { sleepEditorOpen = false }, onSave = sleep::save, onHide = sleep::hide, onRestore = sleep::restore)
    if (editorOpen) EventEditor(editing, onDismiss = { editorOpen = false }, onSave = { event ->
        template = template.filterNot { it.id == event.id } + event
        store.write(weekend, template)
        editorOpen = false
    }, onDelete = { event ->
        template = template.filterNot { it.id == event.id }
        store.write(weekend, template)
        editorOpen = false
    })
}

/** Shows a paired metric that wraps naturally at larger font scales. */
@Composable
private fun Metric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Muted, fontSize = 12.sp)
        Text(value, color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Draws a quiet section heading and its secondary label. */
@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column { Text(subtitle, color = Green, fontSize = 10.sp, letterSpacing = 2.sp); Text(title, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Ink) }
}

/** Shows occupied time proportionally, including commitments spanning midnight. */
@Composable
private fun DayStrip(events: List<Event>) {
    Canvas(Modifier.fillMaxWidth().height(28.dp).semantics { contentDescription = "24時間の予定。薄緑は空き時間。詳細は下の予定一覧。" }) {
        drawRect(Color(0xFFDCE9D8))
        events.forEach { event ->
            val spans = if (event.end < event.start) listOf(Window(0, event.end), Window(event.start, 1440)) else listOf(Window(event.start, event.end))
            spans.forEach { span -> drawRect(categoryColor(event.category), topLeft = androidx.compose.ui.geometry.Offset(size.width * span.start / 1440f, 0f), size = androidx.compose.ui.geometry.Size(size.width * span.minutes / 1440f, size.height)) }
        }
    }
}

/** Edits local times with native pickers and requires a nonempty, nonzero commitment. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EventEditor(event: Event?, onDismiss: () -> Unit, onSave: (Event) -> Unit, onDelete: (Event) -> Unit) {
    var title by rememberSaveable { mutableStateOf(event?.title ?: "") }
    var category by rememberSaveable { mutableStateOf(event?.category ?: "その他") }
    var start by rememberSaveable { mutableIntStateOf(event?.start ?: 1200) }
    var end by rememberSaveable { mutableIntStateOf(event?.end ?: 1230) }
    var deleting by remember { mutableStateOf(false) }
    val context = LocalContext.current
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (event == null) "基本の予定を追加" else "基本の予定を編集") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = title, onValueChange = { title = it.take(40) }, label = { Text("予定の名前") }, singleLine = true)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                categories.forEach { value -> FilterChip(selected = category == value, onClick = { category = value; if (title.isBlank() || title in categories) title = value }, label = { Text(value) }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { TimePickerDialog(context, { _, h, m -> start = h * 60 + m }, start / 60, start % 60, true).show() }) { Text("開始 ${clockTime(start)}") }
                OutlinedButton(onClick = { TimePickerDialog(context, { _, h, m -> end = h * 60 + m }, end / 60, end % 60, true).show() }) { Text("終了 ${clockTime(end)}") }
            }
            Text(if (start == end) "開始と終了は異なる時刻を指定してください。" else if (end < start) "翌日の ${clockTime(end)} まで · ${durationText((end - start + 1440) % 1440)}" else durationText(end - start), color = Muted, fontSize = 12.sp)
            if (event != null) TextButton(onClick = { deleting = true }) { Text("この予定を削除", color = Color(0xFF985248)) }
        }
    }, confirmButton = { TextButton(enabled = title.isNotBlank() && start != end, onClick = { onSave(Event(event?.id ?: UUID.randomUUID().toString(), title.trim(), category, start, end)) }) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } })
    if (deleting && event != null) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("予定を削除しますか？") }, text = { Text(event.title) }, confirmButton = { TextButton(onClick = { onDelete(event) }) { Text("削除") } }, dismissButton = { TextButton(onClick = { deleting = false }) { Text("戻る") } })
}
