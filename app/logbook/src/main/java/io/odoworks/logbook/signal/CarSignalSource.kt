package io.odoworks.logbook.signal

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

// 05 문서 11절: 에뮬레이터에서 콘솔과 반대로 옴. 실차 확인 전까지 true
const val SEAT_VALUE_INVERTED = true
class CarSignalSource(private val context: Context) : SignalSource {
    private val channel = Channel<VehicleSignal>(Channel.UNLIMITED)
    override val events = channel.receiveAsFlow()
    private val navigation = NaviDestinationSource(context, ::emit)
    val navigationStatus get() = navigation.status
    fun requestRoute(route: ai.pleos.playground.navi.data.RouteInfo) = navigation.requestRoute(route)
    val status = MutableStateFlow("신호 연결 중")
    private var vehicle: Vehicle? = null
    private var car: Car? = null
    private var propertyManager: CarPropertyManager? = null
    private val driverSeat = SeatArea(SeatValue.ROW_1_LEFT)
    private val filter = OdometerFilter()
    private fun emit(event: VehicleSignal) { channel.trySend(event) }
    private fun seat(raw: Boolean?) {
        if (raw != null) emit(VehicleSignal.DriverSeat(if (SEAT_VALUE_INVERTED) !raw else raw, System.currentTimeMillis()))
    }
    private fun distance(km: Float?) {
        if (km != null && filter.accept(km)) emit(VehicleSignal.Odometer(km, System.currentTimeMillis()))
    }
    private fun failed(e: Exception) { status.value = "신호 오류: ${e.javaClass.simpleName}"; Log.e("Logbook", "신호 수신 실패", e) }
    private val driverListener = object : SeatOccupancyListener {
        override fun onSeatOccupancyUpdated(occupied: Boolean?) = seat(occupied)
        override fun onFailed(e: Exception) = failed(e)
    }
    private val distanceListener = object : DistanceDrivenListener {
        override fun onDistanceDrivenUpdated(km: Float?) = distance(km)
        override fun onFailed(e: Exception) = failed(e)
    }
    private val propertyCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            if (value.propertyId != VehiclePropertyIds.GEAR_SELECTION) return
            val gear = when (value.value as? Int) { 1 -> Gear.N; 2 -> Gear.R; 4 -> Gear.P; 8 -> Gear.D; else -> Gear.UNKNOWN }
            emit(VehicleSignal.Gear(gear, System.currentTimeMillis()))
            navigation.onGear(gear)
        }
        override fun onErrorEvent(propertyId: Int, areaId: Int) { status.value = "기어 신호 오류" }
    }
    override fun start() {
        navigation.start()
        startPleos()
        startCarApi()
    }
    private fun startPleos() {
        try {
            val v = Vehicle(context.applicationContext).also { it.initialize() }
            vehicle = v
            val seat = v.getSeat()
            seat.getCurrentSeatOccupancy(driverSeat, { seat(it) }, { failed(it) })
            seat.registerSeatOccupancy(driverSeat, driverListener)
            val odometer = v.getOdometer()
            odometer.getCurrentDistanceDriven({ distance(it) }, { failed(it) })
            odometer.registerDistanceDriven(distanceListener)
        } catch (e: Exception) { failed(e) }
    }
    private fun startCarApi() {
        if (Build.VERSION.SDK_INT < 30) { status.value = "지원 안 됨 · 기어 구독은 Android 11 이상에서 가능"; return }
        try {
            car = Car.createCar(context, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { c, ready ->
                if (!ready) { status.value = "차량 연결 끊김"; return@createCar }
                val pm = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
                propertyManager = pm
                status.value = if (pm.registerCallback(propertyCallback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)) "차량 신호 연결됨" else "기어 구독 실패"
            }
        } catch (e: Exception) { failed(e) }
    }
    override fun close() {
        navigation.close()
        propertyManager?.unregisterCallback(propertyCallback)
        car?.disconnect()
        vehicle?.let { v ->
            runCatching {
                v.getSeat().unregisterSeatOccupancy(driverSeat, driverListener)
                v.getOdometer().unregisterDistanceDriven(distanceListener)
            }
            v.release()
        }
        channel.close()
    }
}
