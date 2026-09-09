package jp.yohaku.app

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Regression tests for the user's manual-correction and multi-device sleep workflow. */
class SleepTest {
    private val zone = ZoneId.of("Asia/Tokyo")
    private val date = LocalDate.of(2026, 9, 9)
    private fun at(value: String) = Instant.parse(value).toEpochMilli()
    private fun raw(id: String = "hc:watch:1", start: Long = 1000, end: Long = 2000) = SleepEntry(id, "睡眠", start, end, "watch", imported = true)

    @Test fun repeatedSyncDoesNotDuplicateRecords() {
        val original = raw()
        assertEquals(listOf(original), mergeSleep(listOf(original), listOf(original), 0, 5000))
    }
    @Test fun resyncPreservesManualExtensionAndUpdatesOriginal() {
        val corrected = raw().copy(end = 3000, edited = true)
        val merged = mergeSleep(listOf(corrected), listOf(raw(end = 2500)), 0, 5000).single()
        assertEquals(3000, merged.end)
        assertEquals(2500, merged.originalEnd)
        assertTrue(merged.edited)
    }
    @Test fun hiddenImportDoesNotReappear() {
        assertTrue(mergeSleep(listOf(raw().copy(hidden = true)), listOf(raw()), 0, 5000).single().hidden)
    }
    @Test fun secondSleepAndAppleEntrySurviveSync() {
        val manual = SleepEntry("manual:1", "二度寝", 3000, 4000, "Apple Watch")
        assertEquals(listOf(raw(), manual), mergeSleep(listOf(manual), listOf(raw()), 0, 5000))
    }
    @Test fun deletedSourceRemovesUneditedButKeepsCorrection() {
        val corrected = raw("hc:watch:2").copy(edited = true)
        assertEquals(listOf(corrected), mergeSleep(listOf(raw(), corrected), emptyList(), 0, 5000))
    }
    @Test fun olderImportsOutsideSnapshotAreRetained() {
        assertEquals(listOf(raw()), mergeSleep(listOf(raw()), emptyList(), 2500, 5000))
    }
    @Test fun nightSleepClipsToEachLocalCalendarDate() {
        val entry = raw(start = at("2026-09-08T14:00:00Z"), end = at("2026-09-08T22:00:00Z"))
        assertEquals(Window(0, 420), sleepEvents(listOf(entry), date, zone).single().let { Window(it.start, it.end) })
        assertEquals(Window(1380, 1440), sleepEvents(listOf(entry), date.minusDays(1), zone).single().let { Window(it.start, it.end) })
        assertTrue(sleepEvents(listOf(entry), date.plusDays(1), zone).isEmpty())
    }
    @Test fun overlappingDevicesAreCountedOnce() {
        val first = raw(start = 0, end = 120 * 60000)
        val second = raw("apple", 60 * 60000, 180 * 60000)
        assertEquals(180, sleepMinutes(listOf(first, second)))
    }
    @Test fun actualMorningSleepReplacesMorningPlanButKeepsUpcomingNight() {
        val actual = Event("sleep:1", "睡眠", "睡眠", 0, 360)
        val result = withSleep(listOf(Event("night", "睡眠", "睡眠", 1380, 420)), listOf(actual))
        assertEquals(420, analyze(result, 0, 1).busy)
        assertTrue(result.any { it.start == 1380 && it.end == 1440 })
        assertFalse(result.any { it.end == 420 })
    }
    @Test fun missingSleepRetainsPlannedTime() {
        assertEquals(analyze(sampleEvents(false), 0, 1).busy, analyze(withSleep(sampleEvents(false), emptyList()), 0, 1).busy)
    }
    @Test fun secondSleepChangesOnlyItsRecordedDate() {
        val nap = SleepEntry("manual", "二度寝", at("2026-09-09T00:00:00Z"), at("2026-09-09T01:00:00Z"), "手入力")
        assertEquals(60, analyze(sleepEvents(listOf(nap), date, zone), 0, 1).busy)
        assertTrue(sleepEvents(listOf(nap), date.plusDays(1), zone).isEmpty())
    }
    @Test fun exactMidnightAndFullDayRemainValid() {
        val entry = raw(start = at("2026-09-08T15:00:00Z"), end = at("2026-09-09T15:00:00Z"))
        assertEquals(1440, analyze(sleepEvents(listOf(entry), date, zone), 0, 1).busy)
        assertTrue(sleepForDay(listOf(entry), date.plusDays(1), zone).isEmpty())
    }
}
