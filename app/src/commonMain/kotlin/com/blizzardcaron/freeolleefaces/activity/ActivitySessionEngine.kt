package com.blizzardcaron.freeolleefaces.activity

import com.blizzardcaron.freeolleefaces.ble.BleClient
import com.blizzardcaron.freeolleefaces.ble.OlleeProtocol
import com.blizzardcaron.freeolleefaces.location.Coords
import com.blizzardcaron.freeolleefaces.prefs.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Clock

/**
 * Orchestrates a live activity: feeds GPS fixes into [ActivitySession], renders the selected
 * metric, decides + performs name-tag pushes, records the track, and brackets the session with
 * auto-sleep disable/restore. Owns no coroutines — the Android service drives [ingest]/[tick].
 */
class ActivitySessionEngine(
    private val ble: BleClient,
    private val store: ActivityTrackStore,
    private val prefs: Prefs,
    private val autoSleep: SessionAutoSleep,
    private val watchAddress: () -> String?,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val newId: () -> String = { Clock.System.now().toEpochMilliseconds().toString() },
) {
    private val _state = MutableStateFlow(ActivityState())
    val state: StateFlow<ActivityState> = _state.asStateFlow()

    private var session: ActivitySession? = null
    private var trackId: String = ""
    private var startedAtMs: Long = 0L
    private var unit: ActivityUnit = ActivityUnit.IMPERIAL
    private val points = mutableListOf<TrackPoint>()
    private var selectedMetric: ActivityMetric = ActivityMetric.PACE
    private var lastPushedText: String? = null
    private var lastPushMs: Long = 0L
    private var forceNextPush: Boolean = false

    suspend fun start() {
        if (session != null) return
        startedAtMs = now()
        unit = prefs.activityUnit
        trackId = newId()
        points.clear()
        selectedMetric = ActivityMetric.PACE
        lastPushedText = null
        lastPushMs = 0L
        forceNextPush = false
        session = ActivitySession(startedAtMs)
        _state.value = ActivityState(running = true, selectedMetric = selectedMetric)
        watchAddress()?.let { autoSleep.disableForActivity(it) }
    }

    suspend fun ingest(coords: Coords, nowMs: Long) {
        val s = session ?: return
        s.onSample(coords, nowMs)
        points += TrackPoint(nowMs, coords.lat, coords.lng, coords.accuracyM, coords.altM)
        _state.value = s.state(selectedMetric, nowMs)
            .copy(watchReachable = _state.value.watchReachable, lastPushText = lastPushedText)
    }

    suspend fun tick(nowMs: Long) {
        val s = session ?: return
        val st = s.state(selectedMetric, nowMs)
        val text = selectedMetric.render(st, unit)
        var reachable = _state.value.watchReachable
        val addr = watchAddress()
        if (addr != null &&
            ActivityPushDecider.shouldPush(lastPushedText, text, nowMs - lastPushMs, forceNextPush)
        ) {
            ble.send(addr, text, OlleeProtocol.TARGET_NAMEPLATE)
                .onSuccess {
                    lastPushedText = text
                    lastPushMs = nowMs
                    reachable = true
                }
                .onFailure { reachable = false }
        }
        forceNextPush = false
        _state.value = st.copy(watchReachable = reachable, lastPushText = lastPushedText)
    }

    fun cycleMetric() {
        selectedMetric = selectedMetric.next()
        forceNextPush = true
        _state.value = _state.value.copy(selectedMetric = selectedMetric)
    }

    fun setUnit(newUnit: ActivityUnit) {
        unit = newUnit
        forceNextPush = true
    }

    suspend fun flush() {
        if (session != null) store.save(snapshot(endedAtMs = null, abnormal = false))
    }

    suspend fun stop(abnormal: Boolean = false) {
        if (session == null) return
        val endMs = now()
        store.save(snapshot(endedAtMs = endMs, abnormal = abnormal))
        watchAddress()?.let { autoSleep.restoreAfterActivity(it) }
        session = null
        _state.value = ActivityState()
    }

    private fun snapshot(endedAtMs: Long?, abnormal: Boolean): ActivityTrack {
        val elapsed = ((endedAtMs ?: now()) - startedAtMs).coerceAtLeast(0L)
        val distance = session?.distanceMeters ?: 0.0
        val avgPaceSecPerKm =
            if (distance > 0.0) (elapsed / MILLIS_PER_SECOND) / (distance / METERS_PER_KM) else 0.0
        return ActivityTrack(
            id = trackId,
            startedAtMs = startedAtMs,
            endedAtMs = endedAtMs,
            endedAbnormally = abnormal,
            unit = unit,
            points = points.toList(),
            summary = ActivitySummary(
                distanceM = distance,
                movingTimeMs = elapsed, // MVP: moving-gap subtraction is a later refinement
                elapsedTimeMs = elapsed,
                avgPaceSecPerKm = avgPaceSecPerKm,
            ),
        )
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000.0
        const val METERS_PER_KM = 1000.0
    }
}
