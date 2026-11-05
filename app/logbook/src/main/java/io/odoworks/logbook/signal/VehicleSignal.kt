package io.odoworks.logbook.signal

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

enum class Gear { P, R, N, D, UNKNOWN }
sealed class VehicleSignal {
    data class Gear(val gear: io.odoworks.logbook.signal.Gear, val at: Long) : VehicleSignal()
    data class DriverSeat(val occupied: Boolean, val at: Long) : VehicleSignal()
    data class Destination(val name: String?, val address: String?, val poiId: String?, val at: Long) : VehicleSignal()
    data class Odometer(val km: Float, val at: Long) : VehicleSignal()
}
interface SignalSource {
    val events: Flow<VehicleSignal>
    fun start()
    fun close()
}
class FakeSignalSource : SignalSource {
    private val channel = Channel<VehicleSignal>(Channel.UNLIMITED)
    override val events = channel.receiveAsFlow()
    fun send(event: VehicleSignal) { channel.trySend(event).getOrThrow() }
    override fun start() = Unit
    override fun close() { channel.close() }
}
/** SDK의 반복 콜백은 이벤트를 만들기 전에 거른다. */
class OdometerFilter {
    private var last: Float? = null
    @Synchronized fun accept(km: Float): Boolean {
        if (!km.isFinite() || last == km) return false
        last = km
        return true
    }
}
