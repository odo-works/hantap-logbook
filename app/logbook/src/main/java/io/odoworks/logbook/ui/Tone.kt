package io.odoworks.logbook.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 디자인 토큰. 근거와 쓰는 법은 docs/08_design.md.
 * 색은 "면적이 큰 곳은 무채색, 색은 행동 하나·상태 하나에만" 원칙으로 쓴다.
 */
object Tone {
    val Bg = Color(0xFFF2F4F6)         // 화면 바탕
    val Surface = Color(0xFFFFFFFF)    // 카드
    val SurfaceSub = Color(0xFFEDF0F3) // 보조 버튼·선택 안 된 타일
    val Line = Color(0xFFE5E8EB)       // 표 구분선

    val Ink = Color(0xFF191F28)        // 본문·숫자
    val Ink2 = Color(0xFF4E5968)       // 설명
    val Ink3 = Color(0xFF8B95A1)       // 보조 설명·표 머리

    val Brand = Color(0xFF0F6B4F)      // 주 행동 1개, 추천 1개, 업무 분류
    val BrandSoft = Color(0xFFE1F0E8)  // 업무·출퇴근 칩 바탕

    val Warn = Color(0xFFB45309)       // 사람 손이 남은 것(미분류)
    val WarnSoft = Color(0xFFFCEFD9)

    val RadiusCard = 28.dp
    val RadiusTile = 24.dp
    val RadiusPill = 999.dp
}
