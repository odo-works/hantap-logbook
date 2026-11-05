package io.odoworks.logbook.trip

import io.odoworks.logbook.signal.*
import org.junit.Assert.*
import org.junit.Test

class TripMachineTest {
    private fun TripMachine.gear(g: Gear, at: Long) = accept(VehicleSignal.Gear(g, at))
    private fun TripMachine.km(km: Float, at: Long = 0) = accept(VehicleSignal.Odometer(km, at))
    private fun driving(): TripMachine = TripMachine().also { it.km(100f); it.gear(Gear.D, 1000) }
    @Test fun endsAtParkingWithCapturedDistance() {
        val m = driving()
        m.km(112f); m.gear(Gear.P, 2000)
        m.km(115f)
        assertTrue(m.accept(Tick(601999)).isEmpty())
        val end = m.accept(Tick(602000)).single() as TripEvent.Ended
        assertEquals(12f, end.distanceKm)
        assertEquals(2000L, end.endedAt)
        assertEquals(112f, end.endOdometerKm)
        assertEquals(TripState.IDLE, m.state)
        assertTrue(m.accept(Tick(999999)).isEmpty())
    }
    @Test fun resumesSameTripAfterTwoMinutes() {
        val m = driving(); val start = m.active!!.startedAt
        m.gear(Gear.P, 2000); m.gear(Gear.D, 122000)
        assertEquals(start, m.active!!.startedAt)
        assertEquals(TripState.DRIVING, m.state)
        assertTrue(m.accept(Tick(999999)).isEmpty())
    }
    @Test fun seatLeavingDoesNotEndUnclassifiedTrip() {
        val m = driving(); m.gear(Gear.P, 2000)
        assertEquals(listOf(TripEvent.LeftUnclassified), m.accept(VehicleSignal.DriverSeat(false, 3000)))
        assertTrue(m.active!!.leftWithoutClassifying)
        assertEquals(TripState.PAUSED, m.state)
    }
    @Test fun classifiedTripDoesNotFlagSeatLeaving() {
        val m = driving(); m.gear(Gear.P, 2000); m.classify()
        assertTrue(m.accept(VehicleSignal.DriverSeat(false, 3000)).isEmpty())
        assertFalse(m.active!!.leftWithoutClassifying)
    }
    @Test fun neutralDoesNotChangeState() {
        val m = driving(); m.gear(Gear.N, 2000)
        assertEquals(TripState.DRIVING, m.state)
        m.gear(Gear.P, 3000); m.gear(Gear.N, 4000)
        assertEquals(TripState.PAUSED, m.state)
    }
    @Test fun repeatedOdometerIsIgnored() {
        val filter = OdometerFilter()
        assertTrue(filter.accept(100f)); assertFalse(filter.accept(100f)); assertTrue(filter.accept(112f))
        val m = driving(); assertTrue(m.km(100f).isEmpty()); assertEquals(100f, m.lastOdometerKm)
    }
    @Test fun negativeDistanceIsZeroAndTripIsKept() {
        val m = driving(); m.km(90f); m.gear(Gear.P, 2000)
        assertEquals(0f, (m.accept(Tick(602000)).single() as TripEvent.Ended).distanceKm)
    }
    @Test fun restoresOnFirstParkOrDrive() {
        for (g in listOf(Gear.P, Gear.D, Gear.R)) {
            val m = TripMachine(restored = TripSnapshot(1000, 100f, classified = true))
            m.km(112f); m.gear(g, 5000)
            assertEquals(1000L, m.active!!.startedAt)
            assertEquals(if (g == Gear.P) TripState.PAUSED else TripState.DRIVING, m.state)
            assertEquals(if (g == Gear.P) 5000L else null, m.active!!.pausedAt)
            assertTrue(m.active!!.classified)
        }
    }
}
