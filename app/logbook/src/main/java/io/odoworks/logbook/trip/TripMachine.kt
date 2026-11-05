package io.odoworks.logbook.trip

import io.odoworks.logbook.signal.Gear
import io.odoworks.logbook.signal.VehicleSignal

enum class TripState { IDLE, DRIVING, PAUSED }
data class TripSnapshot(
    val startedAt: Long,
    val startOdometerKm: Float,
    val pausedAt: Long? = null,
    val pausedOdometerKm: Float? = null,
    val classified: Boolean = false,
    val leftWithoutClassifying: Boolean = false,
)
data class Tick(val at: Long)
sealed class TripEvent {
    data class Started(val trip: TripSnapshot) : TripEvent()
    data class Paused(val trip: TripSnapshot) : TripEvent()
    data object Resumed : TripEvent()
    data class Ended(val trip: TripSnapshot, val endedAt: Long, val endOdometerKm: Float) : TripEvent() {
        val distanceKm get() = (endOdometerKm - trip.startOdometerKm).coerceAtLeast(0f)
    }
    data object LeftUnclassified : TripEvent()
}
class TripMachine(private val pauseTimeout: Long = 10 * 60_000L, restored: TripSnapshot? = null) {
    var state = TripState.IDLE; private set
    var active: TripSnapshot? = restored; private set
    var lastOdometerKm: Float? = null; private set
    private var awaitingFirstGear = restored != null
    fun classify() { active = active?.copy(classified = true) }
    fun accept(signal: VehicleSignal): List<TripEvent> {
        when (signal) {
            is VehicleSignal.Destination -> Unit
            is VehicleSignal.Odometer -> {
                if (signal.km.isFinite() && signal.km != lastOdometerKm) lastOdometerKm = signal.km
            }
            is VehicleSignal.DriverSeat -> {
                val trip = active
                if (state == TripState.PAUSED && !signal.occupied && trip != null && !trip.classified && !trip.leftWithoutClassifying) {
                    active = trip.copy(leftWithoutClassifying = true)
                    return listOf(TripEvent.LeftUnclassified)
                }
            }
            is VehicleSignal.Gear -> {
                if (awaitingFirstGear) {
                    if (signal.gear !in listOf(Gear.P, Gear.D, Gear.R)) return emptyList()
                    awaitingFirstGear = false
                    if (signal.gear == Gear.P) {
                        state = TripState.PAUSED
                        active = active!!.copy(pausedAt = signal.at, pausedOdometerKm = lastOdometerKm ?: active!!.startOdometerKm)
                        return listOf(TripEvent.Paused(active!!))
                    }
                    state = TripState.DRIVING
                    return listOf(TripEvent.Resumed)
                }
                when {
                    signal.gear in listOf(Gear.D, Gear.R) && state == TripState.IDLE -> {
                        // 시작 거리가 없으면 원본을 꾸며 저장하지 않고 다음 기어 신호를 기다린다.
                        val km = lastOdometerKm ?: return emptyList()
                        active = TripSnapshot(signal.at, km)
                        state = TripState.DRIVING
                        return listOf(TripEvent.Started(active!!))
                    }
                    signal.gear == Gear.P && state == TripState.DRIVING -> {
                        active = active!!.copy(pausedAt = signal.at, pausedOdometerKm = lastOdometerKm ?: active!!.startOdometerKm)
                        state = TripState.PAUSED
                        return listOf(TripEvent.Paused(active!!))
                    }
                    signal.gear in listOf(Gear.D, Gear.R) && state == TripState.PAUSED -> {
                        active = active!!.copy(pausedAt = null, pausedOdometerKm = null)
                        state = TripState.DRIVING
                        return listOf(TripEvent.Resumed)
                    }
                }
            }
        }
        return emptyList()
    }
    fun accept(tick: Tick): List<TripEvent> {
        val trip = active ?: return emptyList()
        val pausedAt = trip.pausedAt ?: return emptyList()
        if (state != TripState.PAUSED || tick.at - pausedAt < pauseTimeout) return emptyList()
        state = TripState.IDLE
        active = null
        return listOf(TripEvent.Ended(trip, pausedAt, trip.pausedOdometerKm ?: trip.startOdometerKm))
    }
}
