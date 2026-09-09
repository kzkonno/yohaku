package jp.yohaku.app

/** A recurring daily commitment, expressed as local wall-clock minutes. */
data class Event(val id: String, val title: String, val category: String, val start: Int, val end: Int)

/** A half-open interval within a single 24-hour day. */
data class Window(val start: Int, val end: Int) { val minutes: Int get() = end - start }

/** Daily accounting; overlapping commitments are counted once. */
data class Analysis(val busy: Int, val rawFree: Int, val usable: List<Window>) {
    val usableMinutes: Int get() = usable.sumOf { it.minutes }
}

/** Computes gaps on a recurring 24-hour clock, including buffers across midnight. */
fun analyze(events: List<Event>, buffer: Int, minimum: Int): Analysis {
    require(buffer in 0..60 && minimum in 1..1440)
    val busy = BooleanArray(1440)
    events.forEach { event ->
        require(event.start in 0..1439 && event.end in 0..1440 && event.start != event.end)
        val duration = eventMinutes(event)
        repeat(duration) { busy[(event.start + it) % 1440] = true }
    }
    val blocked = busy.copyOf()
    busy.indices.filter { busy[it] }.forEach { minute ->
        for (offset in -buffer..buffer) blocked[(minute + offset + 1440) % 1440] = true
    }
    val gaps = mutableListOf<Window>()
    var start = -1
    for (minute in 0..1440) {
        if (minute < 1440 && !blocked[minute]) {
            if (start < 0) start = minute
        } else if (start >= 0) {
            gaps += Window(start, minute)
            start = -1
        }
    }
    // The view deliberately splits free time at midnight into each calendar day.
    return Analysis(busy.count { it }, busy.count { !it }, gaps.filter { it.minutes >= minimum })
}

/** Formats a minute offset; 1440 is the end-of-day boundary. */
fun clockTime(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

/** Formats durations in Japanese without rounding. */
fun durationText(minutes: Int): String = when {
    minutes < 60 -> "${minutes}分"
    minutes % 60 == 0 -> "${minutes / 60}時間"
    else -> "${minutes / 60}時間${minutes % 60}分"
}

/** Provides editable starting examples for workdays and days off. */
fun sampleEvents(weekend: Boolean): List<Event> {
    val rows = if (weekend) listOf(
        Triple("睡眠", 1380, 480), Triple("身支度", 480, 510), Triple("朝食", 510, 540),
        Triple("昼食", 720, 780), Triple("家事", 960, 1020), Triple("夕食", 1140, 1200), Triple("入浴", 1260, 1290)
    ) else listOf(
        Triple("睡眠", 1380, 420), Triple("身支度", 420, 450), Triple("朝食", 450, 480),
        Triple("通勤", 480, 540), Triple("仕事", 540, 720), Triple("昼食", 720, 780),
        Triple("仕事", 780, 1080), Triple("通勤", 1080, 1140), Triple("夕食", 1140, 1185), Triple("入浴", 1230, 1260)
    )
    return rows.mapIndexed { index, (title, start, end) -> Event("sample-$index", title, title, start, end) }
}

/** Returns the occupied minutes, including a full day ending at 24:00. */
fun eventMinutes(event: Event): Int = if (event.end > event.start) event.end - event.start else event.end - event.start + 1440
