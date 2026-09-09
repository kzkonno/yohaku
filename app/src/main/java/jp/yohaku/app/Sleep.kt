package jp.yohaku.app

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.ceil

/** A dated sleep interval with a durable local override and original import values. */
data class SleepEntry(
    val id: String,
    val title: String,
    val start: Long,
    val end: Long,
    val source: String,
    val imported: Boolean = false,
    val edited: Boolean = false,
    val hidden: Boolean = false,
    val originalStart: Long = start,
    val originalEnd: Long = end,
) {
    init { require(end > start) }
}

/** Reconciles a complete import without overwriting corrections or resurrecting hidden rows. */
fun mergeSleep(local: List<SleepEntry>, incoming: List<SleepEntry>, from: Long, to: Long): List<SleepEntry> {
    val remote = incoming.associateBy { it.id }
    val retained = local.mapNotNull { existing ->
        val fresh = remote[existing.id]
        when {
            !existing.imported -> existing
            fresh != null && (existing.edited || existing.hidden) -> existing.copy(
                source = fresh.source, originalStart = fresh.start, originalEnd = fresh.end
            )
            fresh != null -> fresh
            existing.edited || existing.hidden -> existing
            existing.originalStart < from || existing.originalStart >= to -> existing
            else -> null
        }
    }
    val ids = local.map { it.id }.toSet()
    return (retained + incoming.filter { it.id !in ids }).sortedBy { it.start }
}

/** Returns visible records touching the selected local calendar date. */
fun sleepForDay(entries: List<SleepEntry>, date: LocalDate, zone: ZoneId): List<SleepEntry> {
    val from = date.atStartOfDay(zone).toInstant().toEpochMilli()
    val to = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    return entries.filter { !it.hidden && it.start < to && it.end > from }.sortedBy { it.start }
}

/** Projects dated intervals to the planner's local clock, rounding outward to whole minutes. */
fun sleepEvents(entries: List<SleepEntry>, date: LocalDate, zone: ZoneId): List<Event> {
    val from = date.atStartOfDay(zone).toInstant().toEpochMilli()
    val to = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    return sleepForDay(entries, date, zone).mapNotNull { entry ->
        val start = if (entry.start <= from) 0 else Instant.ofEpochMilli(entry.start).atZone(zone).let { it.hour * 60 + it.minute }
        val end = if (entry.end >= to) 1440 else Instant.ofEpochMilli(entry.end).atZone(zone).let { ceil((it.hour * 3600 + it.minute * 60 + it.second + it.nano / 1e9) / 60).toInt() }
        if (end <= start) null else Event("sleep:${entry.id}", entry.title, "睡眠", start, end)
    }
}

/** Uses actual sleep where it overlaps a planned sleep segment; unmatched plans remain estimates. */
fun withSleep(template: List<Event>, actual: List<Event>): List<Event> {
    val planned = template.flatMap { event ->
        if (event.category != "睡眠") listOf(event) else {
            val segments = if (event.end < event.start) listOf(Window(0, event.end), Window(event.start, 1440)) else listOf(Window(event.start, event.end))
            segments.filter { span -> span.minutes > 0 && actual.none { it.start < span.end && it.end > span.start } }
                .mapIndexed { index, span -> event.copy(id = "plan:${event.id}:$index", title = "${event.title}（予定）", start = span.start, end = span.end) }
        }
    }
    return planned + actual
}

/** Counts the union of sleep intervals, avoiding duplicate time from multiple devices. */
fun sleepMinutes(entries: List<SleepEntry>): Long {
    var end = Long.MIN_VALUE
    var total = 0L
    entries.filterNot { it.hidden }.sortedBy { it.start }.forEach { entry ->
        total += (entry.end - maxOf(entry.start, end)).coerceAtLeast(0)
        end = maxOf(end, entry.end)
    }
    return total / 60000
}
