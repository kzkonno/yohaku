package jp.yohaku.app

import org.junit.Assert.*
import org.junit.Test

/** Exercises boundary cases that materially change the user's available time. */
class ScheduleTest {
    private fun event(start: Int, end: Int) = Event("test", "予定", "その他", start, end)

    @Test fun emptyDayIsFullyAvailable() {
        val result = analyze(emptyList(), 15, 30)
        assertEquals(1440, result.usableMinutes)
        assertEquals(listOf(Window(0, 1440)), result.usable)
    }
    @Test fun overnightSleepCountsBothSidesOfMidnight() {
        val result = analyze(listOf(event(1380, 420)), 5, 30)
        assertEquals(480, result.busy)
        assertEquals(listOf(Window(425, 1375)), result.usable)
    }
    @Test fun overlapIsNotDoubleCounted() {
        val result = analyze(listOf(event(540, 720), event(660, 780)), 0, 1)
        assertEquals(240, result.busy)
        assertEquals(1200, result.rawFree)
    }
    @Test fun bufferWrapsAroundMidnight() {
        val result = analyze(listOf(event(0, 60)), 10, 1)
        assertEquals(listOf(Window(70, 1430)), result.usable)
    }
    @Test fun minimumIsAppliedAfterBuffersAndIncludesExactThreshold() {
        val events = listOf(event(0, 600), event(640, 1439))
        assertEquals(listOf(Window(605, 635)), analyze(events, 5, 30).usable)
        assertTrue(analyze(events, 5, 31).usable.isEmpty())
    }
    @Test fun fullyOccupiedDayHasNoFreeTime() {
        val result = analyze(listOf(event(0, 720), event(720, 0)), 5, 30)
        assertEquals(1440, result.busy)
        assertEquals(0, result.usableMinutes)
    }
    @Test fun sampleDayAccountsForEveryMinute() {
        val result = analyze(sampleEvents(false), 5, 30)
        assertEquals(1275, result.busy)
        assertEquals(145, result.usableMinutes)
        assertEquals(listOf(Window(1190, 1225), Window(1265, 1375)), result.usable.filter { it.start > 1000 })
    }
    @Test(expected = IllegalArgumentException::class) fun equalTimesAreRejected() {
        analyze(listOf(event(600, 600)), 0, 30)
    }
}
