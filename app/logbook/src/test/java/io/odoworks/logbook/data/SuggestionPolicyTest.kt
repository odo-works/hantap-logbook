package io.odoworks.logbook.data

import org.junit.Assert.*
import org.junit.Test

class SuggestionPolicyTest {
    private val key = "addr:중구 정동"
    private fun choice(id: Long, category: Category, source: ClassificationSource = ClassificationSource.USER, destination: String? = key, trip: Long = id, at: Long = id) = DestinationChoice(id, trip, destination, category, source, at)
    @Test fun suggestsLatestUserChoiceForSameDestinationOnly() {
        val choices = listOf(choice(1, Category.BUSINESS), choice(2, Category.COMMUTE), choice(3, Category.PERSONAL, destination = "poi:other"), choice(4, Category.PERSONAL, trip = 10))
        assertEquals(Category.COMMUTE, recommend(choices, key, 10))
        assertNull(recommend(choices, null, 10))
    }
    @Test fun finishingWithoutTapWritesSuggestedAndDoesNotOverwriteUser() {
        val recommendation = recommend(listOf(choice(1, Category.BUSINESS)), key, 2)
        val write = automaticClassification(null, recommendation)!!
        assertEquals(Category.BUSINESS, write.category)
        assertEquals(ClassificationSource.SUGGESTED, write.source)
        assertNull(automaticClassification(Category.PERSONAL, recommendation))
        assertNull(automaticClassification(null, null))
    }
    @Test fun suggestedChoicesDoNotPropagate() {
        assertNull(recommend(listOf(choice(1, Category.BUSINESS, ClassificationSource.SUGGESTED)), key, 2))
        assertEquals(Category.COMMUTE, recommend(listOf(choice(1, Category.COMMUTE), choice(2, Category.PERSONAL, ClassificationSource.SUGGESTED)), key, 3))
    }
    @Test fun usesTrimmedAddressWhenPoiIsAbsent() {
        assertEquals("poi:123", destinationKey("123", "중구 정동"))
        assertEquals(key, destinationKey("", "  중구 정동  "))
        assertEquals(key, destinationKey(null, "중구 정동"))
        assertNull(destinationKey(" ", " "))
    }
    @Test fun csvRemarkContainsDestinationAndUnconfirmedMarker() {
        val trip = TripRow(startedAt = 0, endedAt = 1, startOdometerKm = 100f, endOdometerKm = 107f, destinationName = "서울특별시청", destinationAddress = "중구 정동", destinationKey = key)
        val suggested = TripWithCategory(trip, Category.BUSINESS, ClassificationSource.SUGGESTED)
        assertEquals("서울특별시청 (중구 정동) · 추천(미확인)", csvRemark(suggested))
        assertTrue(csvTripLine(suggested).endsWith("\"서울특별시청 (중구 정동) · 추천(미확인)\""))
        assertEquals("서울특별시청 (중구 정동)", csvRemark(suggested.copy(source = ClassificationSource.USER)))
    }
}
