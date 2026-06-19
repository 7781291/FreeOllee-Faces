package com.blizzardcaron.freeolleefaces

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blizzardcaron.freeolleefaces.alarm.AlarmsRepository
import com.blizzardcaron.freeolleefaces.auto.ActiveComplication
import com.blizzardcaron.freeolleefaces.auto.AlarmScheduler
import com.blizzardcaron.freeolleefaces.auto.AutoSleepReconciler
import com.blizzardcaron.freeolleefaces.auto.AutoUpdateSchedule
import com.blizzardcaron.freeolleefaces.auto.Scheduler
import com.blizzardcaron.freeolleefaces.auto.SleepWindow
import com.blizzardcaron.freeolleefaces.auto.isTempCacheFresh
import com.blizzardcaron.freeolleefaces.ble.AutoSleepApply
import com.blizzardcaron.freeolleefaces.ble.BleClient
import com.blizzardcaron.freeolleefaces.ble.ConnectionStatus
import com.blizzardcaron.freeolleefaces.ble.NoopWatchConnection
import com.blizzardcaron.freeolleefaces.ble.OlleeProtocol
import com.blizzardcaron.freeolleefaces.ble.TimerReadback
import com.blizzardcaron.freeolleefaces.ble.WatchConnection
import com.blizzardcaron.freeolleefaces.format.DisplayFormatter
import com.blizzardcaron.freeolleefaces.format.formatDecimal
import com.blizzardcaron.freeolleefaces.format.groupThousands
import com.blizzardcaron.freeolleefaces.format.WeatherErrorCopy
import com.blizzardcaron.freeolleefaces.health.StepsProvider
import com.blizzardcaron.freeolleefaces.location.LocationProvider
import com.blizzardcaron.freeolleefaces.location.freshnessLabel
import com.blizzardcaron.freeolleefaces.notifications.NotificationAccessChecker
import com.blizzardcaron.freeolleefaces.notifications.NotificationCount
import com.blizzardcaron.freeolleefaces.prefs.Prefs
import com.blizzardcaron.freeolleefaces.sun.SunCalc
import com.blizzardcaron.freeolleefaces.timer.QuickAlarm
import com.blizzardcaron.freeolleefaces.timer.Reorder
import com.blizzardcaron.freeolleefaces.timer.TimerSet
import com.blizzardcaron.freeolleefaces.timer.TimerSetsRepository
import com.blizzardcaron.freeolleefaces.ui.HomeState
import com.blizzardcaron.freeolleefaces.ui.PreviewState
import com.blizzardcaron.freeolleefaces.ui.Screen
import com.blizzardcaron.freeolleefaces.vm.AlarmController
import com.blizzardcaron.freeolleefaces.weather.OpenMeteoClient
import com.blizzardcaron.freeolleefaces.weather.RetryPolicy
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.DateTimeFormat
import kotlinx.datetime.format.Padding
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
private fun randomId(): String = Uuid.random().toString()

private val CLOCK: DateTimeFormat<LocalTime> = LocalTime.Format {
    amPmHour(Padding.NONE); char(':'); minute(); char(' '); amPmMarker("AM", "PM")
}

private fun clockTime(ms: Long): String =
    CLOCK.format(
        Instant.fromEpochMilliseconds(ms)
            .toLocalDateTime(TimeZone.currentSystemDefault())
            .time
    )

private fun locLabel(lat: Double?, lng: Double?): String =
    if (lat != null && lng != null) "Location: ${formatDecimal(lat, 4)}, ${formatDecimal(lng, 4)}"
    else "Location: not set"

private fun stepsHuman(count: Long): String = "Today: ${groupThousands(count)} steps"

private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

class AppViewModel(
    private val prefs: Prefs,
    private val ble: BleClient,
    private val steps: StepsProvider,
    private val location: LocationProvider,
    private val notificationAccess: NotificationAccessChecker,
    private val timerRepo: TimerSetsRepository,
    private val scheduler: Scheduler,
    private val alarmRepo: AlarmsRepository,
    private val alarmScheduler: AlarmScheduler,
    private val versionLabel: String = "",
    private val watchConnection: WatchConnection = NoopWatchConnection,
    private val clock: Clock = Clock.System,
) : ViewModel() {

    var state by mutableStateOf(initialState())
        private set
    var screen by mutableStateOf<Screen>(Screen.Home)
        private set

    var timerSets by mutableStateOf(timerRepo.getAll())
        private set
    var timerActiveId by mutableStateOf(timerRepo.activeId())
        private set
    var quickTimerSeconds by mutableStateOf(prefs.quickTimerSeconds)
        private set
    var quickTimerStartFromApp by mutableStateOf(prefs.quickTimerStartFromApp)
        private set
    var quickTimerIntervalMode by mutableStateOf(prefs.quickTimerIntervalMode)
        private set
    var quickTimerAlarmMode by mutableStateOf(prefs.quickTimerAlarmMode)
        private set
    var quickTimerAlarmHour by mutableStateOf(prefs.quickTimerAlarmHour)
        private set
    var quickTimerAlarmMinute by mutableStateOf(prefs.quickTimerAlarmMinute)
        private set

    val alarms = AlarmController(
        alarmRepo = alarmRepo,
        alarmScheduler = alarmScheduler,
        ble = ble,
        prefs = prefs,
        scope = viewModelScope,
        showSnackbar = ::emitEvent,
        clock = clock,
    )

    private val _events = Channel<String>(Channel.BUFFERED)   // snackbar messages
    val events = _events.receiveAsFlow()

    private var refreshJob: Job? = null
    private var debounceJob: Job? = null
    private var statusJob: Job? = null

    private fun update(transform: (HomeState) -> HomeState) { state = transform(state) }
    private fun showSnackbar(message: String) = emitEvent(message)

    /** Launches a snackbar send on [viewModelScope]; shared with controllers via injection. */
    internal fun emitEvent(message: String) { viewModelScope.launch { _events.send(message) } }

    private fun initialState(): HomeState = HomeState(
        activeComplication = prefs.activeComplication,
        lat = prefs.lastLat?.toString() ?: "",
        lng = prefs.lastLng?.toString() ?: "",
        watchLabel = prefs.watchAddress?.let { "Watch: $it" } ?: "Watch: none selected",
        watchSelected = prefs.watchAddress != null,
        connectionStatus = if (prefs.watchAddress != null) ConnectionStatus.Connecting
                           else ConnectionStatus.NoWatch,
        tempUnit = prefs.tempUnit,
        updateIntervalMinutes = prefs.updateIntervalMinutes,
        sleepEnabled = prefs.sleepEnabled,
        sleepStartMin = prefs.sleepStartMin,
        sleepEndMin = prefs.sleepEndMin,
        autoSleepScheduleEnabled = prefs.autoSleepScheduleEnabled,
        autoSleepWindowStartMin = prefs.autoSleepWindowStartMin,
        autoSleepWindowEndMin = prefs.autoSleepWindowEndMin,
        autoSleepInWindowOn = prefs.autoSleepInWindowOn,
        autoSleepInWindowPeriodSec = prefs.autoSleepInWindowPeriodSec,
        autoSleepOutWindowOn = prefs.autoSleepOutWindowOn,
        autoSleepOutWindowPeriodSec = prefs.autoSleepOutWindowPeriodSec,
        custom = prefs.customText,
        customSent = prefs.customSentMs?.let { "Sent '${prefs.customText}' at ${clockTime(it)}" },
        stepsPreview = prefs.lastStepCount?.let {
            PreviewState.Ready(DisplayFormatter.steps(it), stepsHuman(it))
        } ?: PreviewState.Loading,
        stepsUpdated = prefs.stepsFetchedMs?.let { "Updated ${clockTime(it)}" },
        locationLabel = locLabel(prefs.lastLat, prefs.lastLng),
        locationFreshness = freshnessLabel(prefs.lastLocationFetchedMs, nowMs()),
        notificationCount = prefs.notificationCount,
        notificationAccessGranted = notificationAccess.isGranted(),
        notificationsEnabled = prefs.notificationsEnabled,
        versionLabel = versionLabel,
    )

    fun navigateTo(s: Screen) { screen = s }

    fun refreshTimers() {
        timerSets = timerRepo.getAll()
        timerActiveId = timerRepo.activeId()
    }

    fun newTimerSet() {
        val set = TimerSet.blank(randomId(), "Set ${timerSets.size + 1}")
        timerRepo.save(set)
        refreshTimers()
        editingSet = set
        screen = Screen.TimerSetEdit
    }

    var editingSet by mutableStateOf<TimerSet?>(null)
        private set

    fun editTimerSet(set: TimerSet?) { editingSet = set }

    fun saveTimerSet(set: TimerSet) {
        timerRepo.save(set)
        refreshTimers()
    }

    fun duplicateTimerSet(src: TimerSet) {
        if (timerSets.size < TimerSetsRepository.MAX_SETS) {
            timerRepo.save(src.copy(id = randomId(), name = src.name + " copy"))
            refreshTimers()
        }
    }

    fun deleteTimerSet(set: TimerSet) {
        timerRepo.delete(set.id)
        refreshTimers()
    }

    fun moveTimerSetUp(set: TimerSet) {
        val index = timerSets.indexOfFirst { it.id == set.id }
        if (index < 0) return
        timerRepo.reorder(Reorder.moveUp(timerSets.map { it.id }, index))
        refreshTimers()
    }

    fun moveTimerSetDown(set: TimerSet) {
        val index = timerSets.indexOfFirst { it.id == set.id }
        if (index < 0) return
        timerRepo.reorder(Reorder.moveDown(timerSets.map { it.id }, index))
        refreshTimers()
    }

    /** Shared path for the three 0x26 sends: addr check, in-flight guard, push, snackbar. */
    private fun pushTimerFrame(packet: ByteArray, successMsg: String, onSuccess: () -> Unit = {}) {
        val addr = prefs.watchAddress
        if (addr == null) { showSnackbar("No watch selected — open Settings (⚙)"); return }
        if (state.sending) return
        viewModelScope.launch {
            update { it.copy(sending = true) }
            val result = ble.sendPacket(addr, packet)
            update { it.copy(sending = false) }
            result
                .onSuccess {
                    onSuccess()
                    // Partial read-back confirmation: the watch's 0x2c reply exposes only the active
                    // countdown value + run flag, not the full slot table (see TimerConfirm).
                    val confirmed = TimerReadback.confirm(ble, addr, packet)
                    showSnackbar(if (confirmed) successMsg else "$successMsg — but the watch didn't confirm it")
                }
                .onFailure { showSnackbar("Send failed — long-press ALARM to wake the watch, then retry") }
        }
    }

    fun saveQuickTimer(seconds: Int) {
        prefs.quickTimerSeconds = seconds
        quickTimerSeconds = prefs.quickTimerSeconds   // read back to apply the >=0 coercion
    }

    /** "Start timer from app" toggle — whether a Send to watch also starts the countdown. */
    fun toggleQuickTimerStartFromApp(enabled: Boolean) {
        prefs.quickTimerStartFromApp = enabled
        quickTimerStartFromApp = enabled
    }

    /** "Interval timer mode" toggle — interval (10-slot sequence) vs. single quick-timer countdown. */
    fun toggleQuickTimerIntervalMode(enabled: Boolean) {
        prefs.quickTimerIntervalMode = enabled
        quickTimerIntervalMode = enabled
    }

    fun toggleQuickTimerAlarmMode(enabled: Boolean) {
        prefs.quickTimerAlarmMode = enabled
        quickTimerAlarmMode = enabled
    }

    fun saveQuickTimerAlarmTime(hour: Int, minute: Int) {
        prefs.quickTimerAlarmHour = hour
        prefs.quickTimerAlarmMinute = minute
        quickTimerAlarmHour = prefs.quickTimerAlarmHour     // read back to apply coercion
        quickTimerAlarmMinute = prefs.quickTimerAlarmMinute
    }

    /**
     * Alarm-mode quick timer: compute the countdown to the next occurrence of the saved
     * wall-clock target and push it as a single countdown the watch starts immediately.
     * Capped at 23:59:59 so the header hours byte stays <= 23 (the watch UI's own max).
     */
    fun sendQuickAlarm() {
        val now = clock.now().toLocalDateTime(TimeZone.currentSystemDefault()).time
        val seconds = QuickAlarm.countdownSeconds(now, quickTimerAlarmHour, quickTimerAlarmMinute)
            .coerceAtMost(86_399)
        val slots = timerActiveId?.let { timerRepo.get(it) }?.durations() ?: List(10) { 0 }
        val packet = OlleeProtocol.buildTimerPacket(
            slots, headerSeconds = seconds, startMode = OlleeProtocol.TimerStartMode.START_SINGLE)
        pushTimerFrame(packet, "Started alarm timer on watch")
    }

    fun sendTimerSet(set: TimerSet) {
        val packet = OlleeProtocol.buildTimerPacket(
            set.durations(), headerSeconds = quickTimerSeconds, startMode = OlleeProtocol.TimerStartMode.SAVE)
        pushTimerFrame(packet, "Sent '${set.name}' to watch") {
            timerRepo.setActive(set.id); timerActiveId = set.id
        }
    }

    fun startTimerSet(set: TimerSet) {
        val packet = OlleeProtocol.buildTimerPacket(
            set.durations(), headerSeconds = quickTimerSeconds, startMode = OlleeProtocol.TimerStartMode.START_INTERVAL)
        pushTimerFrame(packet, "Started '${set.name}' on watch") {
            timerRepo.setActive(set.id); timerActiveId = set.id
        }
    }

    /**
     * "Send to watch" for the Quick timer. The two independent toggles ([quickTimerStartFromApp],
     * [quickTimerIntervalMode]) — not which button was pressed — select the start/mode, decoupled
     * exactly as the official app does. Slots are the active set's (preserved on the watch), or
     * zeros if no set is active.
     */
    fun sendQuickTimer() {
        val mode = OlleeProtocol.TimerStartMode.of(quickTimerStartFromApp, quickTimerIntervalMode)
        val slots = timerActiveId?.let { timerRepo.get(it) }?.durations() ?: List(10) { 0 }
        val packet = OlleeProtocol.buildTimerPacket(
            slots, headerSeconds = quickTimerSeconds, startMode = mode)
        val msg = when (mode) {
            OlleeProtocol.TimerStartMode.SAVE -> "Sent quick timer to watch"
            OlleeProtocol.TimerStartMode.START_INTERVAL -> "Started intervals on watch"
            OlleeProtocol.TimerStartMode.START_SINGLE -> "Started quick timer on watch"
        }
        pushTimerFrame(packet, msg)
    }

    private fun validCoords(): Pair<Double, Double>? {
        val lat = state.lat.toDoubleOrNull(); val lng = state.lng.toDoubleOrNull()
        return if (lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0) {
            lat to lng
        } else null
    }

    private fun pushIfWatch(payload: String) {
        val addr = prefs.watchAddress ?: return
        viewModelScope.launch { sendAndReport(addr, payload) }
    }

    fun pushCountIfWatch() {
        val addr = prefs.watchAddress ?: return
        val packet = NotificationCount.packetFor(prefs.notificationCount)
        viewModelScope.launch {
            ble.sendPacket(addr, packet)
                .onSuccess { showSnackbar("Sent notifications: ${prefs.notificationCount}") }
                .onFailure { showSnackbar("Send failed — long-press ALARM to wake the watch, then retry") }
        }
    }

    fun sendCustom(text: String) {
        val addr = prefs.watchAddress ?: return
        prefs.customText = text
        viewModelScope.launch {
            val result = sendAndReport(addr, DisplayFormatter.custom(text))
            if (result.isSuccess) {
                prefs.customSentMs = nowMs()
                update { it.copy(customSent = "Sent '$text' at ${clockTime(prefs.customSentMs!!)}") }
            }
        }
    }

    private fun interval(): Int = state.updateIntervalMinutes

    // Reads from prefs (the just-persisted source of truth) so a setting change reflects
    // immediately — computing from `state` inside the same copy would see the pre-change value.
    fun tempNextText(): String {
        val sleep = if (prefs.sleepEnabled) SleepWindow(prefs.sleepStartMin, prefs.sleepEndMin) else null
        val fire = AutoUpdateSchedule.nextTemperatureFire(
            Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()),
            prefs.updateIntervalMinutes, sleep,
        )
        return "Next update ${CLOCK.format(fire.time)}"
    }

    fun refreshTemp(force: Boolean, push: Boolean) {
        val c = validCoords()
        if (c == null) {
            update { it.copy(tempPreview = PreviewState.Error("Location not set — open Settings (⚙)")) }
            return
        }
        val (lat, lng) = c
        if (!force && isTempCacheFresh(
                prefs.tempFetchedMs, prefs.tempCacheUnit, state.tempUnit, interval(), nowMs(),
            )
        ) {
            val cached = prefs.tempValue!!
            val payload = DisplayFormatter.temperature(cached, state.tempUnit)
            val human = "Currently: ${formatDecimal(cached, 1)}°${state.tempUnit.symbol}"
            update { it.copy(
                tempPreview = PreviewState.Ready(payload, human),
                tempUpdated = "Updated ${clockTime(prefs.tempFetchedMs!!)}",
                tempNext = tempNextText(),
            ) }
            if (push) pushIfWatch(payload)
            return
        }
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            update { it.copy(tempPreview = PreviewState.Loading) }
            OpenMeteoClient.currentTemp(lat, lng, state.tempUnit, RetryPolicy.Preview)
                .onSuccess { temp ->
                    prefs.recordTempFetch(temp, state.tempUnit)
                    val payload = DisplayFormatter.temperature(temp, state.tempUnit)
                    val human = "Currently: ${formatDecimal(temp, 1)}°${state.tempUnit.symbol}"
                    update { it.copy(
                        tempPreview = PreviewState.Ready(payload, human),
                        tempUpdated = "Updated ${clockTime(prefs.tempFetchedMs!!)}",
                        tempNext = tempNextText(),
                    ) }
                    if (push) pushIfWatch(payload)
                }
                .onFailure { err ->
                    // Fetch failed: fall back to the last cached temp (only if it's in the unit we'd
                    // display) and mark it stale with a leading 'E'. No usable cache -> show the error.
                    val cached = prefs.tempValue
                    if (cached != null && prefs.tempCacheUnit == state.tempUnit) {
                        val payload = DisplayFormatter.temperature(cached, state.tempUnit, stale = true)
                        val human = "Currently: ${formatDecimal(cached, 1)}°${state.tempUnit.symbol} (stale)"
                        update { it.copy(
                            tempPreview = PreviewState.Ready(payload, human),
                            tempUpdated = prefs.tempFetchedMs?.let { ms -> "Updated ${clockTime(ms)}" },
                        ) }
                        if (push) pushIfWatch(payload)
                    } else {
                        update { it.copy(tempPreview = PreviewState.Error(WeatherErrorCopy.describe(err))) }
                    }
                }
        }
    }

    fun refreshSun(push: Boolean) {
        val c = validCoords()
        if (c == null) {
            update { it.copy(sunPreview = PreviewState.Error("Location not set — open Settings (⚙)")) }
            return
        }
        val (lat, lng) = c
        val event = SunCalc.nextEvent(Clock.System.now(), lat, lng, TimeZone.currentSystemDefault())
        if (event == null) {
            update { it.copy(sunPreview = PreviewState.NoEvent, sunNext = null) }
            return
        }
        val payload = DisplayFormatter.sunTime(event.kind, event.time.time)
        val pretty = CLOCK.format(event.time.time)
        val kindLabel = event.kind.name.lowercase().replaceFirstChar { it.uppercase() }
        update { it.copy(
            sunPreview = PreviewState.Ready(payload, "$kindLabel at $pretty"),
            sunUpdated = "Updated ${clockTime(nowMs())}",
            sunNext = "Next: $kindLabel at $pretty",
        ) }
        if (push) pushIfWatch(payload)
    }

    fun refreshSteps(push: Boolean) {
        viewModelScope.launch {
            if (!steps.hasReadPermission()) {
                update { it.copy(
                    stepsHealthGranted = false,
                    stepsPreview = PreviewState.Error("Grant Health access to read steps"),
                ) }
                return@launch
            }
            update { it.copy(stepsHealthGranted = true, stepsPreview = PreviewState.Loading) }
            steps.todaySteps()
                .onSuccess { count ->
                    prefs.recordStepsFetch(count)
                    val payload = DisplayFormatter.steps(count)
                    update { it.copy(
                        stepsPreview = PreviewState.Ready(payload, stepsHuman(count)),
                        stepsUpdated = "Updated ${clockTime(prefs.stepsFetchedMs!!)}",
                    ) }
                    if (push) pushIfWatch(payload)
                }
                .onFailure {
                    // Read failed: fall back to the last cached step count, marked stale with 'E'.
                    val cached = prefs.lastStepCount
                    if (cached != null) {
                        val payload = DisplayFormatter.steps(cached, stale = true)
                        update { it.copy(
                            stepsPreview = PreviewState.Ready(payload, stepsHuman(cached) + " (stale)"),
                            stepsUpdated = prefs.stepsFetchedMs?.let { ms -> "Updated ${clockTime(ms)}" },
                        ) }
                        if (push) pushIfWatch(payload)
                    } else {
                        update { it.copy(
                            stepsPreview = PreviewState.Error("Couldn't read steps from Health Connect"),
                        ) }
                    }
                }
        }
    }

    fun refreshActive(force: Boolean, push: Boolean) {
        when (state.activeComplication) {
            ActiveComplication.TEMPERATURE -> refreshTemp(force, push)
            ActiveComplication.SUN -> refreshSun(push)
            ActiveComplication.STEPS -> refreshSteps(push)
            ActiveComplication.CUSTOM -> {}
        }
    }

    /**
     * Refresh every face's preview for the dashboard. Never pushes to the watch. Cheap: only
     * temperature can hit the network, and only when its cache has expired (refreshTemp honors
     * isTempCacheFresh); sun is local, steps is a local read, notifications is read from prefs.
     */
    fun refreshAllPreviews() {
        refreshTemp(force = false, push = false)
        refreshSun(push = false)
        refreshSteps(push = false)
        update { it.copy(
            notificationCount = prefs.notificationCount,
            notificationAccessGranted = notificationAccess.isGranted(),
            notificationsEnabled = prefs.notificationsEnabled,
        ) }
    }

    fun activate(complication: ActiveComplication) {
        prefs.activeComplication = complication
        update { it.copy(activeComplication = complication) }
        // No navigation: selection updates the radio in place on the Home screen and the
        // card highlight reflects it. The old silent bounce back to Home read as "nothing
        // happened" — it is gone.
        scheduler.reschedule()
        when (complication) {
            ActiveComplication.TEMPERATURE -> refreshTemp(force = false, push = true)
            ActiveComplication.SUN -> refreshSun(push = true)
            ActiveComplication.STEPS -> refreshSteps(push = true)
            ActiveComplication.CUSTOM -> {
                val text = prefs.customText
                if (text.isNotEmpty()) sendCustom(text)
            }
        }
    }

    /**
     * Toggle the notification-count overlay (independent of the name-tag face). Enabling pushes the
     * current count to the weekday slot; disabling restores the real weekday table (count 0).
     */
    fun setNotificationsEnabled(enabled: Boolean) {
        prefs.notificationsEnabled = enabled
        update { it.copy(notificationsEnabled = enabled) }
        val addr = prefs.watchAddress ?: return
        val packet = if (enabled) {
            NotificationCount.packetFor(prefs.notificationCount)
        } else {
            NotificationCount.packetFor(0) // restores the real weekday table
        }
        viewModelScope.launch {
            ble.sendPacket(addr, packet)
                .onFailure { showSnackbar("Send failed — long-press ALARM to wake the watch, then retry") }
        }
    }

    fun onCoordEdit(lat: String, lng: String) {
        update { it.copy(lat = lat, lng = lng) }
        val latD = lat.toDoubleOrNull(); val lngD = lng.toDoubleOrNull()
        if (latD != null && lngD != null && latD in -90.0..90.0 && lngD in -180.0..180.0) {
            prefs.lastLat = latD; prefs.lastLng = lngD
            prefs.lastLocationFetchedMs = nowMs()
            update { it.copy(
                locationLabel = locLabel(latD, lngD),
                locationFreshness = "just now",
            ) }
        }
        debounceJob?.cancel()
        debounceJob = viewModelScope.launch {
            delay(500)
            refreshActive(force = false, push = false)
        }
    }

    fun setTempUnit(unit: com.blizzardcaron.freeolleefaces.format.TempUnit) {
        prefs.tempUnit = unit
        update { it.copy(tempUnit = unit) }
        refreshTemp(force = false, push = state.activeComplication == ActiveComplication.TEMPERATURE)
    }

    fun setCustomText(text: String) {
        val capped = text.take(6)
        update { it.copy(custom = capped) }
        prefs.customText = capped
    }

    fun setInterval(mins: Int) {
        prefs.updateIntervalMinutes = mins
        update { it.copy(updateIntervalMinutes = prefs.updateIntervalMinutes, tempNext = tempNextText()) }
        scheduler.reschedule()
    }

    fun setSleepEnabled(enabled: Boolean) {
        prefs.sleepEnabled = enabled
        update { it.copy(sleepEnabled = enabled, tempNext = tempNextText()) }
        scheduler.reschedule()
    }

    fun setSleepStart(min: Int) {
        prefs.sleepStartMin = min
        update { it.copy(sleepStartMin = min, tempNext = tempNextText()) }
        scheduler.reschedule()
    }

    fun setSleepEnd(min: Int) {
        prefs.sleepEndMin = min
        update { it.copy(sleepEndMin = min, tempNext = tempNextText()) }
        scheduler.reschedule()
    }

    fun setAutoSleepScheduleEnabled(enabled: Boolean) {
        prefs.autoSleepScheduleEnabled = enabled
        update { it.copy(autoSleepScheduleEnabled = enabled) }
    }

    fun setAutoSleepWindowStart(min: Int) {
        prefs.autoSleepWindowStartMin = min
        update { it.copy(autoSleepWindowStartMin = min) }
    }

    fun setAutoSleepWindowEnd(min: Int) {
        prefs.autoSleepWindowEndMin = min
        update { it.copy(autoSleepWindowEndMin = min) }
    }

    fun setAutoSleepInWindowOn(on: Boolean) {
        prefs.autoSleepInWindowOn = on
        update { it.copy(autoSleepInWindowOn = on) }
    }

    fun setAutoSleepInWindowPeriod(sec: Int) {
        prefs.autoSleepInWindowPeriodSec = sec
        update { it.copy(autoSleepInWindowPeriodSec = prefs.autoSleepInWindowPeriodSec) }
    }

    fun setAutoSleepOutWindowOn(on: Boolean) {
        prefs.autoSleepOutWindowOn = on
        update { it.copy(autoSleepOutWindowOn = on) }
    }

    fun setAutoSleepOutWindowPeriod(sec: Int) {
        prefs.autoSleepOutWindowPeriodSec = sec
        update { it.copy(autoSleepOutWindowPeriodSec = prefs.autoSleepOutWindowPeriodSec) }
    }

    fun onWatchPicked(address: String, label: String) {
        prefs.watchAddress = address
        // Show Connecting immediately: WatchLink may already sit at Connecting, so the connect below
        // can be a no-op StateFlow transition the mirror collector never re-emits — set it here.
        update { it.copy(watchLabel = label, watchSelected = true, connectionStatus = ConnectionStatus.Connecting) }
        viewModelScope.launch { watchConnection.connect(address) }
        when (state.activeComplication) {
            ActiveComplication.CUSTOM -> prefs.customText.takeIf { it.isNotEmpty() }?.let { sendCustom(it) }
            else -> refreshActive(force = false, push = true)
        }
    }

    fun onLocationFetched(lat: Double, lng: Double) {
        prefs.lastLat = lat; prefs.lastLng = lng
        prefs.lastLocationFetchedMs = nowMs()
        update { it.copy(
            lat = lat.toString(), lng = lng.toString(), locating = false,
            locationLabel = locLabel(lat, lng), locationFreshness = "just now",
        ) }
        refreshActive(force = true, push = false)
    }

    /**
     * Silent-refresh variant used by the location bootstrap: same persistence + state update as
     * [onLocationFetched], but does not trigger a face refresh (the bootstrap refreshes the active
     * face once at the end).
     */
    fun onLocationFetchedSilent(lat: Double, lng: Double) {
        prefs.lastLat = lat; prefs.lastLng = lng
        prefs.lastLocationFetchedMs = nowMs()
        update { it.copy(
            lat = lat.toString(), lng = lng.toString(), locating = false,
            locationLabel = locLabel(lat, lng), locationFreshness = "just now",
        ) }
    }

    /** Bootstrap-refresh failure where saved coords exist: mark "refresh failed" and warn. */
    fun onLocationRefreshFailed() {
        update { it.copy(
            locating = false,
            locationFreshness = (freshnessLabel(prefs.lastLocationFetchedMs, nowMs()) ?: "stale") +
                " · refresh failed",
        ) }
        showSnackbar("Couldn't refresh location — using saved coordinates")
    }

    fun onLocationDenied() { showSnackbar("Location permission denied — enter coordinates manually.") }

    fun onBluetoothDenied() { showSnackbar("Bluetooth permission denied — can't list paired watches.") }

    /** Whether the platform health store is usable; the Activity branches on this for the prompt. */
    fun healthAvailability(): StepsProvider.Availability = steps.availability()

    fun onHealthUpdateRequired() {
        showSnackbar("Update Health Connect (in system settings) to read steps.")
    }

    fun onHealthUnavailable() {
        showSnackbar("Health Connect isn't available on this device.")
    }

    fun onPartialHealthGrant() {
        showSnackbar("Allow background read too, so steps keep syncing when the app is closed.")
    }

    fun onLocationFailed(message: String?) {
        update { it.copy(locating = false) }
        showSnackbar("Location failed: $message")
    }

    fun setLocating(v: Boolean) { update { it.copy(locating = v) } }

    suspend fun fetchLocation() = location.fetch()  // Activity calls when it holds permission

    fun onResumeNotifications() {
        update { it.copy(
            notificationCount = prefs.notificationCount,
            notificationAccessGranted = notificationAccess.isGranted(),
            notificationsEnabled = prefs.notificationsEnabled,
        ) }
    }

    /** Convenience: whether the active face is the steps face (Activity uses this when arming reads). */
    fun activeIsSteps(): Boolean = state.activeComplication == ActiveComplication.STEPS

    /** Whether a background chain is active at startup — drives the POST_NOTIFICATIONS prompt. */
    fun backgroundActive(): Boolean = prefs.activeComplication != ActiveComplication.CUSTOM && prefs.watchAddress != null

    /** Saved coords present (used by the location bootstrap to decide whether to fetch). */
    fun hasSavedCoords(): Boolean = prefs.lastLat != null && prefs.lastLng != null

    /** Whether the saved location fix is stale (used by the location bootstrap). */
    fun locationIsStale(): Boolean =
        com.blizzardcaron.freeolleefaces.location.isLocationStale(prefs.lastLocationFetchedMs, nowMs())

    /** Initial re-arm of the auto-update chain; called once from a LaunchedEffect on start. */
    fun onStart() { scheduler.reschedule(); alarmScheduler.rearm() }

    /**
     * UI entered the foreground: start mirroring link status into state and connect if a watch is
     * selected. The collector is scoped to the foreground window (cancelled in [onBackground]); while
     * no watch is selected the chip reads NoWatch regardless of the link's own status.
     */
    fun onForeground() {
        if (statusJob != null) return   // already foregrounded — don't start a second collector or connect
        statusJob = viewModelScope.launch {
            watchConnection.status.collect { s ->
                val effective = if (prefs.watchAddress == null) ConnectionStatus.NoWatch else s
                update { it.copy(connectionStatus = effective) }
                if (s == ConnectionStatus.Connected) reconcileAutoSleepOnConnect()
            }
        }
        prefs.watchAddress?.let { addr -> viewModelScope.launch { watchConnection.connect(addr) } }
    }

    /** On a fresh link, converge the watch's auto-sleep register to the scheduled desired state. */
    private fun reconcileAutoSleepOnConnect() {
        val addr = prefs.watchAddress ?: return
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        val desired = AutoSleepReconciler.desiredProfile(
            prefs.autoSleepWindowConfig(), now.hour * 60 + now.minute,
        ) ?: return
        viewModelScope.launch { runCatching { AutoSleepApply.reconcile(ble, addr, desired) } }
    }

    /** UI left the foreground: stop mirroring status and release the held link. */
    fun onBackground() {
        statusJob?.cancel()
        statusJob = null
        watchConnection.disconnect()
    }

    /** Reconnect button: re-establish the held link to the selected watch (no-op without one). */
    fun onReconnect() {
        val addr = prefs.watchAddress ?: return
        viewModelScope.launch { watchConnection.connect(addr) }
    }

    private suspend fun sendAndReport(
        address: String,
        value: String,
        target: Int = OlleeProtocol.TARGET_NAMEPLATE,
    ): Result<Unit> {
        update { it.copy(sending = true) }
        return ble.send(address, value, target)
            .onSuccess { update { it.copy(sending = false) }; showSnackbar("Sent '$value'") }
            .onFailure { err -> update { it.copy(sending = false) }; showSnackbar("Send failed: ${err.message}") }
    }
}
