package com.blizzardcaron.freeolleefaces.vm

import com.blizzardcaron.freeolleefaces.auto.ActiveComplication
import com.blizzardcaron.freeolleefaces.auto.AutoUpdateSchedule
import com.blizzardcaron.freeolleefaces.auto.Scheduler
import com.blizzardcaron.freeolleefaces.auto.SleepWindow
import com.blizzardcaron.freeolleefaces.auto.isTempCacheFresh
import com.blizzardcaron.freeolleefaces.ble.BleClient
import com.blizzardcaron.freeolleefaces.ble.OlleeProtocol
import com.blizzardcaron.freeolleefaces.format.DisplayFormatter
import com.blizzardcaron.freeolleefaces.format.TempUnit
import com.blizzardcaron.freeolleefaces.format.WeatherErrorCopy
import com.blizzardcaron.freeolleefaces.format.formatDecimal
import com.blizzardcaron.freeolleefaces.health.StepsProvider
import com.blizzardcaron.freeolleefaces.location.LocationProvider
import com.blizzardcaron.freeolleefaces.location.freshnessLabel
import com.blizzardcaron.freeolleefaces.location.isLocationStale
import com.blizzardcaron.freeolleefaces.notifications.NotificationAccessChecker
import com.blizzardcaron.freeolleefaces.notifications.NotificationCount
import com.blizzardcaron.freeolleefaces.prefs.Prefs
import com.blizzardcaron.freeolleefaces.sun.SunCalc
import com.blizzardcaron.freeolleefaces.ui.HomeState
import com.blizzardcaron.freeolleefaces.ui.PreviewState
import com.blizzardcaron.freeolleefaces.weather.OpenMeteoClient
import com.blizzardcaron.freeolleefaces.weather.RetryPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Owns the complication cluster extracted from [com.blizzardcaron.freeolleefaces.AppViewModel]:
 * weather/temperature/sun previews, steps, notifications, custom text, and the active-complication
 * refresh/activate path. Moved verbatim; the only renames are `viewModelScope` -> `scope`,
 * `state.X` -> `state().X`, `state = transform(state)` -> `update { transform(it) }`, and
 * `Clock.System` -> the injected [clock] (matching the [vm.AlarmController]/[vm.TimerController]
 * precedent). The
 * `scheduler` dependency (used only by [activate]) is injected here since [activate] is the only
 * complication-cluster method that calls `scheduler.reschedule()` — the VM's other 5 call sites
 * (setInterval/setSleepEnabled/setSleepStart/setSleepEnd/onStart) are outside this cluster and keep
 * their own reference. The shared `HomeState.sending` flag is NOT duplicated here — [state]/[update]
 * read and write the VM's single shared flag (also used by the VM's own `sendAndReport` and
 * [vm.TimerController]'s `pushTimerFrame`), via the injected accessors.
 */
class ComplicationController(
    private val prefs: Prefs,
    private val ble: BleClient,
    private val steps: StepsProvider,
    private val location: LocationProvider,
    private val notificationAccess: NotificationAccessChecker,
    private val scheduler: Scheduler,
    private val scope: CoroutineScope,
    private val showSnackbar: (String) -> Unit,
    private val state: () -> HomeState,
    private val update: ((HomeState) -> HomeState) -> Unit,
    private val clock: Clock = Clock.System,
) {
    private var refreshJob: Job? = null

    private fun nowMs(): Long = clock.now().toEpochMilliseconds()

    private fun validCoords(): Pair<Double, Double>? {
        val lat = state().lat.toDoubleOrNull()
        val lng = state().lng.toDoubleOrNull()
        return if (lat != null && lng != null && lat in -LAT_ABS_MAX..LAT_ABS_MAX && lng in -LNG_ABS_MAX..LNG_ABS_MAX) {
            lat to lng
        } else {
            null
        }
    }

    private fun pushIfWatch(payload: String) {
        val addr = prefs.watchAddress ?: return
        scope.launch { sendAndReport(addr, payload) }
    }

    fun pushCountIfWatch() {
        val addr = prefs.watchAddress ?: return
        val packet = NotificationCount.packetFor(prefs.notificationCount)
        scope.launch {
            ble.sendPacket(addr, packet)
                .onSuccess { showSnackbar("Sent notifications: ${prefs.notificationCount}") }
                .onFailure { showSnackbar("Send failed — long-press ALARM to wake the watch, then retry") }
        }
    }

    fun sendCustom(text: String) {
        val addr = prefs.watchAddress ?: return
        prefs.customText = text
        scope.launch {
            val result = sendAndReport(addr, DisplayFormatter.custom(text))
            if (result.isSuccess) {
                prefs.customSentMs = nowMs()
                update { it.copy(customSent = "Sent '$text' at ${clockTime(prefs.customSentMs!!)}") }
            }
        }
    }

    private fun interval(): Int = state().updateIntervalMinutes

    // Reads from prefs (the just-persisted source of truth) so a setting change reflects
    // immediately — computing from `state` inside the same copy would see the pre-change value.
    fun tempNextText(): String {
        val sleep = if (prefs.sleepEnabled) SleepWindow(prefs.sleepStartMin, prefs.sleepEndMin) else null
        val fire = AutoUpdateSchedule.nextTemperatureFire(
            clock.now().toLocalDateTime(TimeZone.currentSystemDefault()),
            prefs.updateIntervalMinutes,
            sleep,
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
                prefs.tempFetchedMs, prefs.tempCacheUnit, state().tempUnit, interval(), nowMs(),
            )
        ) {
            val cached = prefs.tempValue!!
            val payload = DisplayFormatter.temperature(cached, state().tempUnit)
            val human = "Currently: ${formatDecimal(cached, 1)}°${state().tempUnit.symbol}"
            update {
                it.copy(
                    tempPreview = PreviewState.Ready(payload, human),
                    tempUpdated = "Updated ${clockTime(prefs.tempFetchedMs!!)}",
                    tempNext = tempNextText(),
                )
            }
            if (push) pushIfWatch(payload)
            return
        }
        refreshJob?.cancel()
        refreshJob = scope.launch {
            update { it.copy(tempPreview = PreviewState.Loading) }
            OpenMeteoClient.currentTemp(lat, lng, state().tempUnit, RetryPolicy.Preview)
                .onSuccess { temp ->
                    prefs.recordTempFetch(temp, state().tempUnit)
                    val payload = DisplayFormatter.temperature(temp, state().tempUnit)
                    val human = "Currently: ${formatDecimal(temp, 1)}°${state().tempUnit.symbol}"
                    update {
                        it.copy(
                            tempPreview = PreviewState.Ready(payload, human),
                            tempUpdated = "Updated ${clockTime(prefs.tempFetchedMs!!)}",
                            tempNext = tempNextText(),
                        )
                    }
                    if (push) pushIfWatch(payload)
                }
                .onFailure { err ->
                    // Fetch failed: fall back to the last cached temp (only if it's in the unit we'd
                    // display) and mark it stale with a leading 'E'. No usable cache -> show the error.
                    val cached = prefs.tempValue
                    if (cached != null && prefs.tempCacheUnit == state().tempUnit) {
                        val payload = DisplayFormatter.temperature(cached, state().tempUnit, stale = true)
                        val human = "Currently: ${formatDecimal(cached, 1)}°${state().tempUnit.symbol} (stale)"
                        update {
                            it.copy(
                                tempPreview = PreviewState.Ready(payload, human),
                                tempUpdated = prefs.tempFetchedMs?.let { ms -> "Updated ${clockTime(ms)}" },
                            )
                        }
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
        val event = SunCalc.nextEvent(clock.now(), lat, lng, TimeZone.currentSystemDefault())
        if (event == null) {
            update { it.copy(sunPreview = PreviewState.NoEvent, sunNext = null) }
            return
        }
        val payload = DisplayFormatter.sunTime(event.kind, event.time.time)
        val pretty = CLOCK.format(event.time.time)
        val kindLabel = event.kind.name.lowercase().replaceFirstChar { it.uppercase() }
        update {
            it.copy(
                sunPreview = PreviewState.Ready(payload, "$kindLabel at $pretty"),
                sunUpdated = "Updated ${clockTime(nowMs())}",
                sunNext = "Next: $kindLabel at $pretty",
            )
        }
        if (push) pushIfWatch(payload)
    }

    fun refreshSteps(push: Boolean) {
        scope.launch {
            if (!steps.hasReadPermission()) {
                update {
                    it.copy(
                        stepsHealthGranted = false,
                        stepsPreview = PreviewState.Error("Grant Health access to read steps"),
                    )
                }
                return@launch
            }
            update { it.copy(stepsHealthGranted = true, stepsPreview = PreviewState.Loading) }
            steps.todaySteps()
                .onSuccess { count ->
                    prefs.recordStepsFetch(count)
                    val payload = DisplayFormatter.steps(count)
                    update {
                        it.copy(
                            stepsPreview = PreviewState.Ready(payload, stepsHuman(count)),
                            stepsUpdated = "Updated ${clockTime(prefs.stepsFetchedMs!!)}",
                        )
                    }
                    if (push) pushIfWatch(payload)
                }
                .onFailure {
                    // Read failed: fall back to the last cached step count, marked stale with 'E'.
                    val cached = prefs.lastStepCount
                    if (cached != null) {
                        val payload = DisplayFormatter.steps(cached, stale = true)
                        update {
                            it.copy(
                                stepsPreview = PreviewState.Ready(payload, stepsHuman(cached) + " (stale)"),
                                stepsUpdated = prefs.stepsFetchedMs?.let { ms -> "Updated ${clockTime(ms)}" },
                            )
                        }
                        if (push) pushIfWatch(payload)
                    } else {
                        update {
                            it.copy(
                                stepsPreview = PreviewState.Error("Couldn't read steps from Health Connect"),
                            )
                        }
                    }
                }
        }
    }

    fun refreshActive(force: Boolean, push: Boolean) {
        when (state().activeComplication) {
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
        update {
            it.copy(
                notificationCount = prefs.notificationCount,
                notificationAccessGranted = notificationAccess.isGranted(),
                notificationsEnabled = prefs.notificationsEnabled,
            )
        }
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
        scope.launch {
            ble.sendPacket(addr, packet)
                .onFailure { showSnackbar("Send failed — long-press ALARM to wake the watch, then retry") }
        }
    }

    fun setTempUnit(unit: TempUnit) {
        prefs.tempUnit = unit
        update { it.copy(tempUnit = unit) }
        refreshTemp(force = false, push = state().activeComplication == ActiveComplication.TEMPERATURE)
    }

    fun setCustomText(text: String) {
        val capped = text.take(6)
        update { it.copy(custom = capped) }
        prefs.customText = capped
    }

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

    fun setLocating(v: Boolean) { update { it.copy(locating = v) } }

    suspend fun fetchLocation() = location.fetch() // Activity calls when it holds permission

    fun onLocationFetched(lat: Double, lng: Double) {
        prefs.lastLat = lat
        prefs.lastLng = lng
        prefs.lastLocationFetchedMs = nowMs()
        update {
            it.copy(
                lat = lat.toString(),
                lng = lng.toString(),
                locating = false,
                locationLabel = locLabel(lat, lng),
                locationFreshness = "just now",
            )
        }
        refreshActive(force = true, push = false)
    }

    /**
     * Silent-refresh variant used by the location bootstrap: same persistence + state update as
     * [onLocationFetched], but does not trigger a face refresh (the bootstrap refreshes the active
     * face once at the end).
     */
    fun onLocationFetchedSilent(lat: Double, lng: Double) {
        prefs.lastLat = lat
        prefs.lastLng = lng
        prefs.lastLocationFetchedMs = nowMs()
        update {
            it.copy(
                lat = lat.toString(),
                lng = lng.toString(),
                locating = false,
                locationLabel = locLabel(lat, lng),
                locationFreshness = "just now",
            )
        }
    }

    /** Bootstrap-refresh failure where saved coords exist: mark "refresh failed" and warn. */
    fun onLocationRefreshFailed() {
        update {
            it.copy(
                locating = false,
                locationFreshness = (freshnessLabel(prefs.lastLocationFetchedMs, nowMs()) ?: "stale") +
                    " · refresh failed",
            )
        }
        showSnackbar("Couldn't refresh location — using saved coordinates")
    }

    fun onLocationDenied() { showSnackbar("Location permission denied — enter coordinates manually.") }

    fun onLocationFailed(message: String?) {
        update { it.copy(locating = false) }
        showSnackbar("Location failed: $message")
    }

    fun onResumeNotifications() {
        update {
            it.copy(
                notificationCount = prefs.notificationCount,
                notificationAccessGranted = notificationAccess.isGranted(),
                notificationsEnabled = prefs.notificationsEnabled,
            )
        }
    }

    /** Convenience: whether the active face is the steps face (Activity uses this when arming reads). */
    fun activeIsSteps(): Boolean = state().activeComplication == ActiveComplication.STEPS

    /** Saved coords present (used by the location bootstrap to decide whether to fetch). */
    fun hasSavedCoords(): Boolean = prefs.lastLat != null && prefs.lastLng != null

    /** Whether the saved location fix is stale (used by the location bootstrap). */
    fun locationIsStale(): Boolean =
        isLocationStale(prefs.lastLocationFetchedMs, nowMs())

    private suspend fun sendAndReport(
        address: String,
        value: String,
        target: Int = OlleeProtocol.TARGET_NAMEPLATE,
    ): Result<Unit> {
        update { it.copy(sending = true) }
        return ble.send(address, value, target)
            .onSuccess {
                update { it.copy(sending = false) }
                showSnackbar("Sent '$value'")
            }
            .onFailure { err ->
                update { it.copy(sending = false) }
                showSnackbar("Send failed: ${err.message}")
            }
    }
}
