package jp.yohaku.app

import android.app.Application
import androidx.compose.runtime.*
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Coordinates opt-in foreground synchronization while preserving local edits during in-flight reads. */
class SleepController(application: Application) : AndroidViewModel(application) {
    private val store = SleepStore(application)
    private var task: Job? = null
    var entries by mutableStateOf<List<SleepEntry>>(emptyList())
        private set
    var automatic by mutableStateOf(store.automatic)
        private set
    var available by mutableIntStateOf(HealthConnectClient.SDK_UNAVAILABLE)
        private set
    var granted by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var ready by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
    var lastSync by mutableLongStateOf(store.lastSync)
        private set
    var status by mutableStateOf("睡眠の自動入力は未接続です")
        private set
    val permissions = setOf(HealthPermission.getReadPermission(SleepSessionRecord::class))

    init { load() }

    /** Reloads persisted data; a failed load prevents any destructive writes. */
    fun load() {
        try { entries = store.read(); ready = true }
        catch (_: Exception) { ready = false; message = "保存済みデータを開けませんでした。再読み込みをお試しください。" }
    }

    /** Rechecks availability and permissions every time the app becomes active. */
    fun refresh() {
        if (task?.isActive == true) return
        task = viewModelScope.launch {
            busy = true
            try {
                available = HealthConnectClient.getSdkStatus(getApplication())
                if (available != HealthConnectClient.SDK_AVAILABLE) {
                    granted = false
                    status = if (available == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED) "Health Connectのインストール・更新が必要です" else "この端末では手入力をご利用ください"
                    return@launch
                }
                val client = HealthConnectClient.getOrCreate(getApplication())
                granted = client.permissionController.getGrantedPermissions().containsAll(permissions)
                if (!granted || !automatic) {
                    status = if (!granted) "睡眠の読み取りを許可すると自動入力できます" else "自動入力を停止中です。保存済みの記録は編集できます"
                    return@launch
                }
                if (!ready) return@launch
                val to = Instant.now()
                val from = to.minus(29, ChronoUnit.DAYS)
                val records = mutableListOf<SleepEntry>()
                val seenTokens = mutableSetOf<String>()
                var token: String? = null
                do {
                    // Permission may be revoked while pages are being fetched.
                    if (!client.permissionController.getGrantedPermissions().containsAll(permissions)) throw SecurityException()
                    val response = client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, timeRangeFilter = TimeRangeFilter.between(from, to), pageToken = token, pageSize = 500))
                    records += response.records.map { record ->
                        val origin = record.metadata.dataOrigin.packageName
                        SleepEntry("hc:$origin:${record.metadata.id}", "睡眠", record.startTime.toEpochMilli(), record.endTime.toEpochMilli(), origin, imported = true)
                    }
                    token = response.pageToken?.takeIf { it.isNotBlank() }
                    if (token != null && !seenTokens.add(token!!)) error("Repeated page token")
                } while (token != null)
                // Use current state, including edits made while Health Connect was reading.
                val merged = mergeSleep(entries, records, from.toEpochMilli(), to.toEpochMilli())
                store.write(merged)
                entries = merged
                store.lastSync = to.toEpochMilli()
                lastSync = store.lastSync
                status = if (records.isEmpty()) "同期済み・直近29日間の睡眠記録はありません" else "同期済み・直近29日間の睡眠を自動入力しました"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SecurityException) {
                granted = false
                status = "睡眠の読み取り許可が必要です"
                message = "Health Connectの睡眠の読み取り許可をご確認ください。手入力は引き続き使えます。"
            } catch (_: Exception) {
                status = "前回の保存データを表示しています"
                message = "今回は同期できませんでした。接続を確認して再同期してください。手動修正は保持されています。"
            } finally { busy = false }
        }
    }

    /** Enables automatic imports after consent, without requesting write access. */
    fun connect() {
        try { store.automatic = true; automatic = true; task?.cancel(); task = null; refresh() }
        catch (_: Exception) { message = "設定を保存できませんでした。再度お試しください。" }
    }

    /** Stops future reads and leaves existing user data untouched. */
    fun disconnect() {
        try {
            store.automatic = false; automatic = false
            task?.cancel(); task = null; busy = false
            status = "自動入力を停止中です。保存済みの記録は編集できます"
        } catch (_: Exception) { message = "設定を保存できませんでした。再度お試しください。" }
    }

    /** Saves a correction only when durable storage succeeds. */
    fun save(entry: SleepEntry): Boolean = update(entries.filterNot { it.id == entry.id } + entry)

    /** Keeps tombstones for imported records so future sync cannot bring them back. */
    fun hide(entry: SleepEntry): Boolean = if (entry.imported) save(entry.copy(hidden = true)) else update(entries.filterNot { it.id == entry.id })

    /** Removes a local override using the most recently imported interval. */
    fun restore(entry: SleepEntry): Boolean = save(entry.copy(start = entry.originalStart, end = entry.originalEnd, title = "睡眠", edited = false, hidden = false))

    /** Atomically updates storage before publishing the new state to Compose. */
    private fun update(value: List<SleepEntry>): Boolean {
        if (!ready) return false
        return try { store.write(value); entries = value; true }
        catch (_: Exception) { message = "保存できませんでした。入力内容を確認して再度保存してください。"; false }
    }
}
