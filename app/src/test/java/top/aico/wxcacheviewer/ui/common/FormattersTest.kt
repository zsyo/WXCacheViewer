package top.aico.wxcacheviewer.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class FormattersTest {

    private fun utcMillis(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    private fun withUtcTimeZone(block: () -> Unit) {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            block()
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun sameYearKeepsMonthDayAndTime() = withUtcTimeZone {
        val fileTime = utcMillis(2026, 11, 20, 14, 32) / 1000
        val now = utcMillis(2026, 11, 21, 15, 0)

        assertEquals("11-20 14:32", formatModifiedAt(fileTime, now))
    }

    @Test
    fun otherYearAddsTheYear() = withUtcTimeZone {
        val fileTime = utcMillis(2025, 3, 4, 9, 5) / 1000
        val now = utcMillis(2026, 11, 21, 15, 0)

        assertEquals("2025-03-04", formatModifiedAt(fileTime, now))
    }

    @Test
    fun unavailableTimeRendersEmpty() {
        assertEquals("", formatModifiedAt(0L))
        assertEquals("", formatModifiedAt(-1L))
    }

    @Test
    fun shortHashTakesFirstEightCharacters() {
        assertEquals("ffdef473", shortHash("ffdef473a3a56cbf6d38456a41f52f46"))
        assertEquals("abc", shortHash("abc"))
    }

    @Test
    fun formatsDurationBelowOneHour() {
        assertEquals("00:00", formatDuration(0))
        assertEquals("00:09", formatDuration(9_400))
        assertEquals("01:05", formatDuration(65_000))
        assertEquals("59:59", formatDuration(3_599_000))
    }

    @Test
    fun formatsDurationAboveOneHour() {
        assertEquals("1:00:00", formatDuration(3_600_000))
        assertEquals("1:02:05", formatDuration(3_725_000))
    }

    @Test
    fun negativeDurationClampsToZero() {
        assertEquals("00:00", formatDuration(-5_000))
    }
}
