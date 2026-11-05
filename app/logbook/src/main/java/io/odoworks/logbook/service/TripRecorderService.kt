package io.odoworks.logbook.service

import android.app.*
import android.content.Intent
import android.os.IBinder
import android.util.Log
import io.odoworks.logbook.BuildConfig
import io.odoworks.logbook.data.*
import io.odoworks.logbook.signal.*
import io.odoworks.logbook.trip.*
import io.odoworks.logbook.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class RecorderState(val state: TripState = TripState.IDLE, val tripId: Long? = null, val distanceKm: Float = 0f, val gear: Gear = Gear.UNKNOWN, val status: String = "신호 연결 중", val recommendation: Category? = null)
class TripRecorderService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Mutex()
    private lateinit var source: CarSignalSource
    private lateinit var dao: LogbookDao
    private lateinit var machine: TripMachine
    private var activeId: Long? = null
    private var recommendation: Category? = null
    private var gear = Gear.UNKNOWN
    private var pendingGear: VehicleSignal.Gear? = null
    companion object {
        private val mutableState = MutableStateFlow(RecorderState())
        val state: StateFlow<RecorderState> = mutableState.asStateFlow()
        private val mutableNavigationStatus = MutableStateFlow("목적지 연결 중")
        val navigationStatus = mutableNavigationStatus.asStateFlow()
        fun requestTestRoute(route: ai.pleos.playground.navi.data.RouteInfo) {
            check(BuildConfig.DEBUG)
            val service = running ?: error("기록 서비스 연결 중입니다")
            check(service.gear == Gear.P) { "기어 P에서 목적지를 설정하세요" }
            service.source.requestRoute(route)
        }
        suspend fun confirmSuggested(ids: List<Long>) {
            val service = running ?: error("기록 서비스 연결 중입니다")
            service.lock.withLock {
                check(service.gear == Gear.P) { "기어 P에서 추천을 확인하세요" }
                service.dao.confirmSuggested(ids, System.currentTimeMillis())
            }
        }
        private var running: TripRecorderService? = null
        suspend fun classify(tripId: Long, category: Category) {
            val service = running ?: error("기록 서비스를 연결하는 중입니다")
            service.lock.withLock {
                check(service.gear == Gear.P) { "기어 P에서 분류를 고칠 수 있습니다" }
                service.dao.classify(ClassificationRow(tripId = tripId, category = category, decidedAt = System.currentTimeMillis()))
                if (service.activeId == tripId) service.machine.classify()
                service.publish()
            }
        }
    }
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("recording", "운행 기록", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(1, Notification.Builder(this, "recording").setContentTitle("오토 운행기록부").setContentText("차량 신호로 운행을 기록합니다").setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentIntent(open).build())
        dao = LogbookDatabase.get(this).dao()
        source = CarSignalSource(this)
        scope.launch {
            val restored = dao.unfinished()
            activeId = restored?.trip?.id
            machine = TripMachine(BuildConfig.PAUSE_TIMEOUT, restored?.let { TripSnapshot(it.trip.startedAt, it.trip.startOdometerKm, classified = it.category != null, leftWithoutClassifying = it.trip.leftWithoutClassifying) })
            running = this@TripRecorderService
            launch {
                source.events.collect { signal -> lock.withLock {
                    if (signal is VehicleSignal.Gear) {
                        gear = signal.gear
                        if (machine.lastOdometerKm == null) { pendingGear = signal; publish(); return@withLock }
                    }
                    if (signal is VehicleSignal.Destination) activeId?.let {
                        dao.setDestination(it, signal.name, signal.address, destinationKey(signal.poiId, signal.address))
                        recommendation = dao.suggestion(it)
                    }
                    handle(machine.accept(signal))
                    if (signal is VehicleSignal.Odometer) pendingGear?.let {
                        pendingGear = null
                        handle(machine.accept(it))
                    }
                    publish()
                } }
            }
            launch { source.navigationStatus.collect { mutableNavigationStatus.value = it } }
            launch { source.status.collect { mutableState.value = mutableState.value.copy(status = it) } }
            launch {
                while (isActive) {
                    delay(60_000)
                    lock.withLock { handle(machine.accept(Tick(System.currentTimeMillis()))); publish() }
                }
            }
            source.start()
        }
    }
    private suspend fun handle(events: List<TripEvent>) {
        for (event in events) {
            when (event) {
                is TripEvent.Started -> {
                    recommendation = null
                    activeId = dao.insertTrip(TripRow(startedAt = event.trip.startedAt, startOdometerKm = event.trip.startOdometerKm))
                }
                is TripEvent.Ended -> {
                    activeId?.let { dao.finishWithSuggestion(it, event.endedAt, event.endOdometerKm, System.currentTimeMillis()) }
                    activeId = null
                    recommendation = null
                }
                is TripEvent.Paused -> { recommendation = activeId?.let { dao.suggestion(it) } }
                TripEvent.LeftUnclassified -> activeId?.let { dao.markLeft(it) }
                else -> Unit
            }
            Log.i("Logbook", "${event.javaClass.simpleName} trip=$activeId")
        }
    }
    private fun publish() {
        val active = machine.active
        val distance = if (active != null) ((if (machine.state == TripState.PAUSED) active.pausedOdometerKm else machine.lastOdometerKm) ?: active.startOdometerKm) - active.startOdometerKm else 0f
        mutableState.value = RecorderState(machine.state, activeId, distance.coerceAtLeast(0f), gear, source.status.value, recommendation)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        running = null
        source.close()
        scope.cancel()
        super.onDestroy()
    }
}
