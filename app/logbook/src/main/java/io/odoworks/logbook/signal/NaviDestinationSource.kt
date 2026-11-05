package io.odoworks.logbook.signal

import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.navi.data.*
import ai.pleos.playground.navi.constants.NaviErrorCode
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow

class NaviDestinationSource(context: Context, private val emit: (VehicleSignal) -> Unit) {
    private val helper = NaviHelper(context.applicationContext)
    val status = MutableStateFlow("목적지 연결 중")
    @Volatile private var ready = false
    @Volatile private var parked = false
    private fun record(message: String) { status.value = message; Log.i("LogbookNavi", message) }
    private val listener = object : NaviHelperEventListener {
        override fun onDestinationInfo(info: DestinationInfo) {
            record("onDestinationInfo name=${info.name} address=${info.address} poiId=${info.poiId}")
            // 경로가 없는 초기 응답(모든 필드가 빈 값)은 목적지로 저장하지 않는다.
            if (!info.name.isNullOrBlank() || !info.address.isNullOrBlank() || !info.poiId.isNullOrBlank())
                emit(VehicleSignal.Destination(info.name, info.address, info.poiId, System.currentTimeMillis()))
        }
        override fun onDestinationArrived(info: DestinationArrivedInfo) {
            record("onDestinationArrived name=${info.name} address=${info.address}")
            emit(VehicleSignal.Destination(info.name, info.address, null, System.currentTimeMillis()))
        }
        override fun onError(code: NaviErrorCode) { record("onError ${code.name}") }
        override fun onRouteStarted(info: RouteStartInfo) { record("onRouteStarted") }
    }
    fun start() {
        helper.initialize({
            helper.addListener(listener)
            ready = true
            record("목적지 연결됨")
            if (parked) query()
        }, { record("목적지 연결 실패 ${it.javaClass.simpleName}: ${it.message}") })
    }
    fun onGear(gear: Gear) {
        parked = gear == Gear.P
        if (parked && ready) query()
    }
    private fun query() {
        record("P getDestinationInfo 요청")
        runCatching { helper.getDestinationInfo() }.onFailure { record("목적지 조회 실패 ${it.javaClass.simpleName}: ${it.message}") }
    }
    fun requestRoute(route: RouteInfo) {
        check(ready) { "목적지 서비스 연결 중입니다" }
        record("requestRoute ${route.poiName}")
        helper.requestRoute(route)
    }
    fun close() {
        if (ready) helper.removeListener(listener)
        helper.release()
        ready = false
    }
}
