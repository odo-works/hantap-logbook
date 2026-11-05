package io.odoworks.logbook.data

fun destinationKey(poiId: String?, address: String?): String? = when {
    !poiId.isNullOrBlank() -> "poi:$poiId"
    !address.isNullOrBlank() -> "addr:${address.trim()}"
    else -> null
}
enum class ClassificationSource { USER, SUGGESTED }
data class DestinationChoice(val id: Long, val tripId: Long, val destinationKey: String?, val category: Category, val source: ClassificationSource, val decidedAt: Long)
fun recommend(choices: List<DestinationChoice>, key: String?, currentTripId: Long): Category? {
    if (key == null) return null
    return choices.filter { it.tripId != currentTripId && it.destinationKey == key && it.source == ClassificationSource.USER }
        .maxWithOrNull(compareBy<DestinationChoice> { it.decidedAt }.thenBy { it.id })?.category
}
data class AutomaticClassification(val category: Category, val source: ClassificationSource = ClassificationSource.SUGGESTED)
fun automaticClassification(current: Category?, suggestion: Category?): AutomaticClassification? =
    if (current == null && suggestion != null) AutomaticClassification(suggestion) else null
fun csvRemark(row: TripWithCategory): String {
    val name = row.trip.destinationName?.takeIf { it.isNotBlank() }
    val address = row.trip.destinationAddress?.takeIf { it.isNotBlank() }
    val place = when {
        name != null && address != null -> "$name ($address)"
        name != null -> name
        address != null -> address
        else -> ""
    }
    val remark = if (row.category == null) listOf(place, row.label).filter { it.isNotEmpty() }.joinToString(" · ") else place
    return if (row.source == ClassificationSource.SUGGESTED) listOf(remark, "추천(미확인)").filter { it.isNotEmpty() }.joinToString(" · ") else remark
}
