package io.odoworks.logbook.data

import java.time.Instant
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class CsvTripLineTest {
    @Test fun includesYearUsingDeviceTimezone() {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))
            // UTC로는 전날이지만 기기 시간대에서는 2026-11-04다.
            val at = Instant.parse("2026-11-03T15:30:00Z").toEpochMilli()
            val row = TripWithCategory(TripRow(startedAt = at, endedAt = at + 60_000, startOdometerKm = 132f, endOdometerKm = 137f), Category.COMMUTE)
            assertEquals("\"2026-11-04(수)\",\"\",\"\",\"132.0\",\"137.0\",\"5.0\",\"5.0\",\"0.0\",\"\"", csvTripLine(row))
        } finally {
            TimeZone.setDefault(previous)
        }
    }
}
