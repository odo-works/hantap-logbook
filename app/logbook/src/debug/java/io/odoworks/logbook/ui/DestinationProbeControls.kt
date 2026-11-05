package io.odoworks.logbook.ui

import ai.pleos.playground.navi.data.RouteInfo
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.odoworks.logbook.service.TripRecorderService

@Composable
fun DestinationProbeControls(parked: Boolean) {
    val status by TripRecorderService.navigationStatus.collectAsStateWithLifecycle()
    var message by remember { mutableStateOf("") }
    Button(onClick = {
        message = if (!parked) "기어 P에서 테스트 목적지를 설정하세요" else try {
            TripRecorderService.requestTestRoute(RouteInfo(latitude = 37.5663, longitude = 126.9779, poiName = "서울특별시청", poiId = "", poiSubId = "", address = "서울특별시 중구 세종대로 110"))
            "테스트 목적지 요청 완료"
        } catch (e: Exception) { "목적지 설정 실패: ${e.message}" }
    }) { Text("테스트 목적지 설정") }
    Text(if (message.isEmpty()) status else "$message · $status")
}
