package io.odoworks.logbook.data

import java.io.File
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

fun decimal(value: Float) = String.format(Locale.US, "%.1f", value)
fun dateText(at: Long): String = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd(E)", Locale.KOREAN))
fun timeText(at: Long?): String = at?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm")) } ?: "진행 중"
fun monthly(rows: List<TripWithCategory>, month: YearMonth = YearMonth.now()): List<TripWithCategory> = rows.filter {
    YearMonth.from(Instant.ofEpochMilli(it.trip.startedAt).atZone(ZoneId.systemDefault())) == month
}
data class Totals(val total: Float, val business: Float) {
    val percent get() = if (total > 0f) business / total * 100 else 0f
}
fun totals(rows: List<TripWithCategory>) = Totals(rows.sumOf { it.distanceKm.toDouble() }.toFloat(), rows.filter { it.category == Category.BUSINESS || it.category == Category.COMMUTE }.sumOf { it.distanceKm.toDouble() }.toFloat())
private fun csvLine(values: List<String>): String = values.joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" }
fun csvTripLine(row: TripWithCategory): String {
    val date = Instant.ofEpochMilli(row.trip.startedAt).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd(E)", Locale.KOREAN))
    return csvLine(listOf(date, "", "", decimal(row.trip.startOdometerKm), decimal(requireNotNull(row.trip.endOdometerKm)), decimal(row.distanceKm), decimal(if (row.category == Category.COMMUTE) row.distanceKm else 0f), decimal(if (row.category == Category.BUSINESS) row.distanceKm else 0f), csvRemark(row)))
}
object CsvExporter {
    fun write(directory: File, rows: List<TripWithCategory>, month: YearMonth = YearMonth.now()): File {
        check(directory.isDirectory || directory.mkdirs()) { "저장 폴더를 만들 수 없습니다" }
        val selected = monthly(rows, month).filter { it.trip.endedAt != null }.sortedBy { it.trip.startedAt }
        val lines = mutableListOf<String>()
        lines += csvLine(listOf("사업연도", "", "법인명", "", "사업자등록번호", "", "차종", "", "자동차등록번호", ""))
        lines += csvLine(listOf("사용일자(요일)", "사용자(부서)", "사용자(성명)", "주행 전 계기판 거리(km)", "주행 후 계기판 거리(km)", "주행거리(km)", "출퇴근용(km)", "일반 업무용(km)", "비고"))
        selected.forEach { row -> lines += csvTripLine(row) }
        val total = totals(selected)
        lines += csvLine(listOf("${month}월 총주행거리(km)", decimal(total.total)))
        lines += csvLine(listOf("${month}월 업무용 사용거리(km)", decimal(total.business)))
        lines += csvLine(listOf("${month}월 업무사용비율(%)", decimal(total.percent)))
        val file = File(directory, "운행기록부_$month.csv")
        file.writeText("\uFEFF" + lines.joinToString("\r\n", postfix = "\r\n"), Charsets.UTF_8)
        return file
    }
}
