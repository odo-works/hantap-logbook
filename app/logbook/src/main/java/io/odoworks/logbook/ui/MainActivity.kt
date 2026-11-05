package io.odoworks.logbook.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.odoworks.logbook.BuildConfig
import io.odoworks.logbook.data.*
import io.odoworks.logbook.service.*
import io.odoworks.logbook.signal.Gear
import io.odoworks.logbook.trip.TripState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.sample
import java.time.YearMonth

class MainActivity : ComponentActivity() {
    @OptIn(FlowPreview::class, ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startForegroundService(Intent(this, TripRecorderService::class.java))
        val dao = LogbookDatabase.get(this).dao()
        // 운행 중 신호가 몰려도 화면용 상태 갱신은 1초에 한 번으로 제한한다.
        val displayState = TripRecorderService.state.sample(1000)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Tone.Brand, onPrimary = Color.White, background = Tone.Bg, surface = Tone.Surface, onSurface = Tone.Ink)) {
                val state by displayState.collectAsStateWithLifecycle(initialValue = TripRecorderService.state.value)
                val rows by remember { dao.observeTrips() }.collectAsStateWithLifecycle(emptyList())
                val monthRows = monthly(rows)
                val sum = totals(monthRows)
                val suggestedRows = monthRows.filter { it.source == ClassificationSource.SUGGESTED }
                // 진행 중인 운행은 아직 분류할 차례가 아니므로 미분류 건수에서 뺀다 (2026-11-05 사용자 결정)
                val unclassified = monthRows.count { it.category == null && it.trip.endedAt != null }
                val scope = rememberCoroutineScope()
                var showDebugControls by remember { mutableStateOf(false) }
                var table by remember { mutableStateOf(false) }
                var editing by remember { mutableStateOf<Long?>(null) }
                var message by remember { mutableStateOf("") }
                var saving by remember { mutableStateOf(false) }
                val current = rows.find { it.trip.id == state.tripId }
                val autoCard = state.state == TripState.PAUSED && state.gear == Gear.P && current?.category == null && current != null
                val target = if (state.gear == Gear.P) editing ?: if (autoCard) state.tripId else null else null
                val recommendation = state.recommendation.takeIf { target == state.tripId }
                val previous = rows.firstOrNull { it.trip.id != target && it.category != null }?.category
                val editingRow = rows.find { it.trip.id == target }

                Surface(Modifier.fillMaxSize(), color = Tone.Bg) {
                    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        TopBar(status = state.status, onTitleLongClick = { showDebugControls = !showDebugControls })
                        if (BuildConfig.DEBUG && showDebugControls) DestinationProbeControls(state.gear == Gear.P)

                        Box(Modifier.weight(1f)) {
                            when {
                                target != null -> ClassifyCard(
                                    destination = editingRow?.trip?.destinationName?.takeIf { it.isNotBlank() },
                                    // 정차 중인 운행은 아직 종료 거리가 저장되지 않았으므로 서비스의 현재 거리를 쓴다
                                    distanceKm = if (target == state.tripId) state.distanceKm else editingRow?.distanceKm,
                                    recommendation = recommendation,
                                    previous = previous,
                                    saving = saving,
                                    onPick = { category ->
                                        if (!saving) {
                                            saving = true
                                            scope.launch {
                                                try { TripRecorderService.classify(target, category); editing = null }
                                                catch (e: Exception) { message = e.message ?: "분류를 저장하지 못했습니다" }
                                                finally { saving = false }
                                            }
                                        }
                                    },
                                )
                                table -> MonthlyTable(
                                    rows = monthRows, sum = sum, suggestedCount = suggestedRows.size,
                                    onConfirmSuggested = {
                                        scope.launch {
                                            try { TripRecorderService.confirmSuggested(suggestedRows.map { it.trip.id }) }
                                            catch (e: Exception) { message = e.message ?: "추천을 확인하지 못했습니다" }
                                        }
                                    },
                                    onRowClick = { row ->
                                        if (state.gear == Gear.P) editing = row.trip.id
                                        else message = "기어 P에서 분류를 고칠 수 있습니다"
                                    },
                                )
                                else -> Home(state = state, sum = sum, unclassified = unclassified, suggested = suggestedRows.size)
                            }
                        }

                        if (message.isNotEmpty()) Notice(message)
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Pill(if (table) "홈으로" else "월간 표", primary = false) { table = !table; editing = null }
                            Pill("서식 파일 만들기", primary = true) {
                                scope.launch {
                                    message = try {
                                        val dir = getExternalFilesDir("exports") ?: error("저장 공간을 열 수 없습니다")
                                        val file = withContext(Dispatchers.IO) { CsvExporter.write(dir, rows) }
                                        "서식 파일을 만들었습니다 · ${file.name}\n${file.absolutePath}"
                                    } catch (e: Exception) { "파일 생성 실패: ${e.message}" }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------- 상단 ----------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TopBar(status: String, onTitleLongClick: () -> Unit) {
    val month = YearMonth.now()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("한탭 운행기록부", fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = Tone.Brand,
            modifier = if (BuildConfig.DEBUG) Modifier.combinedClickable(onClick = {}, onLongClick = onTitleLongClick) else Modifier)
        Spacer(Modifier.width(24.dp))
        Text("${month.year}년 ${month.monthValue}월", fontSize = 24.sp, color = Tone.Ink3)
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(12.dp).background(Tone.Brand, CircleShape))
        Spacer(Modifier.width(10.dp))
        Text(status, fontSize = 22.sp, color = Tone.Ink3)
    }
}

// ---------- 홈 ----------

@Composable
private fun Home(state: RecorderState, sum: Totals, unclassified: Int, suggested: Int) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state.state) {
                TripState.IDLE -> {
                    Text("대기 중", fontSize = 40.sp, color = Tone.Ink2)
                    Text("기어를 D에 넣으면 기록이 시작됩니다", fontSize = 72.sp, fontWeight = FontWeight.Bold, color = Tone.Ink, lineHeight = 84.sp)
                }
                TripState.DRIVING -> {
                    Text("주행 중", fontSize = 40.sp, color = Tone.Ink2)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(decimal(state.distanceKm), fontSize = 120.sp, fontWeight = FontWeight.Bold, color = Tone.Ink, lineHeight = 120.sp)
                        Text(" km", fontSize = 48.sp, color = Tone.Ink2, modifier = Modifier.padding(bottom = 16.dp))
                    }
                    Text("계기판 누적거리로 기록 중입니다", fontSize = 26.sp, color = Tone.Ink3)
                }
                TripState.PAUSED -> {
                    Text("정차 중", fontSize = 40.sp, color = Tone.Ink2)
                    Text("분류가 끝났습니다", fontSize = 72.sp, fontWeight = FontWeight.Bold, color = Tone.Ink, lineHeight = 84.sp)
                    Text("다시 출발하면 같은 운행으로 이어집니다", fontSize = 26.sp, color = Tone.Ink3)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            StatTile("이번 달 업무사용비율", decimal(sum.percent), "%", Modifier.weight(1.3f))
            StatTile("미분류", unclassified.toString(), "건", Modifier.weight(1f), tone = if (unclassified > 0) Tone.Warn else Tone.Ink)
            StatTile("추천 미확인", suggested.toString(), "건", Modifier.weight(1f), tone = if (suggested > 0) Tone.Brand else Tone.Ink)
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, unit: String, modifier: Modifier, tone: Color = Tone.Ink) {
    Column(modifier.background(Tone.Surface, RoundedCornerShape(Tone.RadiusCard)).padding(horizontal = 36.dp, vertical = 32.dp)) {
        Text(label, fontSize = 24.sp, color = Tone.Ink2)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontSize = 64.sp, fontWeight = FontWeight.Bold, color = tone, lineHeight = 64.sp)
            Text(unit, fontSize = 28.sp, color = Tone.Ink2, modifier = Modifier.padding(start = 6.dp, bottom = 8.dp))
        }
    }
}

// ---------- 분류 카드 ----------

@Composable
private fun ClassifyCard(destination: String?, distanceKm: Float?, recommendation: Category?, previous: Category?, saving: Boolean, onPick: (Category) -> Unit) {
    Column(Modifier.fillMaxSize().background(Tone.Surface, RoundedCornerShape(Tone.RadiusCard)).padding(44.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val where = listOfNotNull(destination, distanceKm?.let { "${decimal(it)}km" }).joinToString(" · ")
            if (where.isNotEmpty()) Text(where, fontSize = 28.sp, color = Tone.Ink3)
            if (recommendation != null) Text("지난번 ${destination ?: "이 목적지"}은 '${recommendation.label}'였어요", fontSize = 34.sp, fontWeight = FontWeight.SemiBold, color = Tone.Brand)
            Text("이번 운행은 어떤 용도였나요?", fontSize = 56.sp, fontWeight = FontWeight.Bold, color = Tone.Ink, lineHeight = 66.sp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Category.entries.forEach { category ->
                CategoryTile(category.label, filled = category == recommendation, outlined = category == previous, enabled = !saving, Modifier.weight(1f)) { onPick(category) }
            }
        }
        Text(
            when {
                recommendation != null -> "채워진 칸은 추천입니다. 안 눌러도 추천으로 기록됩니다"
                previous != null -> "테두리는 직전 운행의 분류입니다"
                else -> "내리기 전에 한 번만 누르면 됩니다"
            }, fontSize = 24.sp, color = Tone.Ink3,
        )
    }
}

@Composable
private fun CategoryTile(label: String, filled: Boolean, outlined: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Tone.RadiusTile)
    Box(
        modifier
            .height(200.dp)
            .clip(shape)
            .background(if (filled) Tone.Brand else Tone.SurfaceSub)
            .then(if (outlined && !filled) Modifier.border(4.dp, Tone.Brand, shape) else Modifier)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 48.sp, fontWeight = FontWeight.Bold, color = if (filled) Color.White else Tone.Ink)
    }
}

// ---------- 월간 표 ----------

@Composable
private fun MonthlyTable(rows: List<TripWithCategory>, sum: Totals, suggestedCount: Int, onConfirmSuggested: () -> Unit, onRowClick: (TripWithCategory) -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("이번 달 운행 ${rows.size}건", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Tone.Ink)
                Text("정차 중에 행을 누르면 분류를 고칠 수 있습니다", fontSize = 22.sp, color = Tone.Ink3)
            }
            Spacer(Modifier.weight(1f))
            if (suggestedCount > 0) Pill("추천 ${suggestedCount}건 한 번에 확인", primary = true, onClick = onConfirmSuggested)
        }
        Column(Modifier.weight(1f).background(Tone.Surface, RoundedCornerShape(Tone.RadiusCard)).padding(horizontal = 32.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
                Cell("날짜 · 시간", 1.5f, Tone.Ink3, 22.sp)
                Cell("계기판 전 → 후", 1.5f, Tone.Ink3, 22.sp)
                Cell("주행거리", 0.9f, Tone.Ink3, 22.sp, TextAlign.End)
                Cell("분류", 1.1f, Tone.Ink3, 22.sp, TextAlign.Center)
                Cell("목적지", 1.4f, Tone.Ink3, 22.sp)
            }
            HorizontalDivider(color = Tone.Line)
            LazyColumn(Modifier.weight(1f)) {
                items(rows, key = { it.trip.id }) { row ->
                    Row(Modifier.fillMaxWidth().clickable { onRowClick(row) }.padding(vertical = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                        Cell("${dateText(row.trip.startedAt)}  ${timeText(row.trip.startedAt)}–${timeText(row.trip.endedAt)}", 1.5f, Tone.Ink, 26.sp)
                        Cell("${decimal(row.trip.startOdometerKm)} → ${row.trip.endOdometerKm?.let(::decimal) ?: "진행 중"}", 1.5f, Tone.Ink2, 26.sp)
                        Cell("${decimal(row.distanceKm)} km", 0.9f, Tone.Ink, 30.sp, TextAlign.End, FontWeight.SemiBold)
                        Box(Modifier.weight(1.1f), contentAlignment = Alignment.Center) { CategoryChip(row) }
                        Cell(row.trip.destinationName?.takeIf { it.isNotBlank() } ?: "—", 1.4f, Tone.Ink2, 24.sp)
                    }
                    HorizontalDivider(color = Tone.Line)
                }
            }
        }
        Row(Modifier.fillMaxWidth().background(Tone.Surface, RoundedCornerShape(Tone.RadiusCard)).padding(horizontal = 36.dp, vertical = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Summary("총주행거리", "${decimal(sum.total)} km")
            Spacer(Modifier.width(56.dp))
            Summary("업무용(출퇴근+업무)", "${decimal(sum.business)} km")
            Spacer(Modifier.weight(1f))
            Summary("업무사용비율", "${decimal(sum.percent)} %", strong = true)
        }
    }
}

@Composable
private fun RowScope.Cell(text: String, weight: Float, color: Color, size: androidx.compose.ui.unit.TextUnit, align: TextAlign = TextAlign.Start, weightFont: FontWeight = FontWeight.Normal) {
    Text(text, fontSize = size, color = color, textAlign = align, fontWeight = weightFont, maxLines = 1, modifier = Modifier.weight(weight).padding(horizontal = 8.dp))
}

@Composable
private fun CategoryChip(row: TripWithCategory) {
    val suggested = row.source == ClassificationSource.SUGGESTED
    val (bg, fg) = when {
        row.category == null -> Tone.WarnSoft to Tone.Warn
        row.category == Category.PERSONAL -> Tone.SurfaceSub to Tone.Ink2
        suggested -> Color.Transparent to Tone.Brand
        else -> Tone.BrandSoft to Tone.Brand
    }
    val shape = RoundedCornerShape(Tone.RadiusPill)
    Text(
        row.label, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1,
        modifier = Modifier.background(bg, shape).then(if (suggested) Modifier.border(2.dp, Tone.Brand, shape) else Modifier).padding(horizontal = 20.dp, vertical = 10.dp),
    )
}

@Composable
private fun Summary(label: String, value: String, strong: Boolean = false) {
    Column {
        Text(label, fontSize = 22.sp, color = Tone.Ink3)
        Text(value, fontSize = if (strong) 44.sp else 32.sp, fontWeight = FontWeight.Bold, color = if (strong) Tone.Brand else Tone.Ink)
    }
}

// ---------- 공통 ----------

@Composable
private fun Pill(label: String, primary: Boolean, onClick: () -> Unit) {
    Text(
        label, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = if (primary) Color.White else Tone.Ink,
        modifier = Modifier
            .clip(RoundedCornerShape(Tone.RadiusPill))
            .background(if (primary) Tone.Brand else Tone.SurfaceSub)
            .clickable(onClick = onClick)
            .heightIn(min = 88.dp)
            .padding(horizontal = 40.dp, vertical = 26.dp),
    )
}

@Composable
private fun Notice(message: String) {
    val lines = message.split("\n", limit = 2)
    Column(Modifier.fillMaxWidth().background(Tone.Surface, RoundedCornerShape(20.dp)).padding(horizontal = 32.dp, vertical = 20.dp)) {
        Text(lines[0], fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = Tone.Ink)
        if (lines.size > 1) Text(lines[1], fontSize = 20.sp, color = Tone.Ink3)
    }
}
