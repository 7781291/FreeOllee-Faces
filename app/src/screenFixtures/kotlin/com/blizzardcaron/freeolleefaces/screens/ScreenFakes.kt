package com.blizzardcaron.freeolleefaces.screens

import com.blizzardcaron.freeolleefaces.activity.ActivityMetricsConfig
import com.blizzardcaron.freeolleefaces.activity.ActivityState
import com.blizzardcaron.freeolleefaces.activity.ActivitySummary
import com.blizzardcaron.freeolleefaces.activity.ActivityTrack
import com.blizzardcaron.freeolleefaces.activity.ActivityUnit
import com.blizzardcaron.freeolleefaces.activity.TrackPoint
import com.blizzardcaron.freeolleefaces.alarm.Alarm
import com.blizzardcaron.freeolleefaces.ble.ConnectionStatus
import com.blizzardcaron.freeolleefaces.timer.TimerSet
import com.blizzardcaron.freeolleefaces.timer.TimerSlot
import com.blizzardcaron.freeolleefaces.ui.ActivityCallbacks
import com.blizzardcaron.freeolleefaces.ui.ActivityHistoryCallbacks
import com.blizzardcaron.freeolleefaces.ui.ActivityMetricsConfigCallbacks
import com.blizzardcaron.freeolleefaces.ui.AlarmsCallbacks
import com.blizzardcaron.freeolleefaces.ui.HomeCallbacks
import com.blizzardcaron.freeolleefaces.ui.HomeState
import com.blizzardcaron.freeolleefaces.ui.QuickTimerState
import com.blizzardcaron.freeolleefaces.ui.SettingsCallbacks
import com.blizzardcaron.freeolleefaces.ui.TimerSetsCallbacks

/** Representative fake state + no-op callbacks for rendering every screen in isolation. */
object ScreenFakes {
    val unit = ActivityUnit.METRIC

    val homeState = HomeState(
        watchLabel = "Watch: Ollee",
        watchSelected = true,
        connectionStatus = ConnectionStatus.Connected,
        versionLabel = "v0.0.0",
    )
    val homeCallbacks = HomeCallbacks(
        onActivate = {},
        onUpdateNow = {},
        onTempUnitChange = {},
        onSetBatteryReadout = {},
        onCustomChange = {},
        onSendCustom = {},
        onGrantHealth = {},
        onGrantNotificationAccess = {},
        onToggleNotifications = {},
        onNotificationsUpdateNow = {},
    )
    val settingsCallbacks = SettingsCallbacks(
        onBack = {},
        onSelectWatch = {},
        onDisconnect = {},
        onUnsetWatch = {},
        onIntervalChange = {},
        onPowerSavingEnabledChange = {},
        onScreenSleepTimeoutChange = {},
        onQuietHoursEnabledChange = {},
        onQuietHoursStartChange = {},
        onQuietHoursEndChange = {},
        onLatChange = {},
        onLngChange = {},
        onUseMyLocation = {},
    )

    val timerSets = listOf(
        TimerSet("a", "Tea", List(TimerSet.SLOT_COUNT) { TimerSlot() }),
        TimerSet("b", "Workout", List(TimerSet.SLOT_COUNT) { TimerSlot() }),
    )
    val timerSet = timerSets.first()
    val quickTimer = QuickTimerState(
        seconds = 60,
        startFromApp = true,
        intervalMode = false,
        alarmMode = false,
        alarmHour = 7,
        alarmMinute = 30,
    )
    val timerSetsCallbacks = TimerSetsCallbacks(
        onSaveQuick = {},
        onToggleStartFromApp = {},
        onToggleIntervalMode = {},
        onToggleAlarmMode = {},
        onSaveAlarmTime = { _, _ -> },
        onSendAlarm = {},
        onSendQuick = {},
        onOpen = {},
        onNew = {},
        onDuplicate = {},
        onDelete = {},
        onSend = {},
        onStart = {},
        onMoveUp = {},
        onMoveDown = {},
        onBack = {},
        onReconnect = {},
    )

    val alarms = listOf(
        Alarm(id = "1", hour = 7, minute = 0),
        Alarm(id = "2", hour = 22, minute = 30, enabled = false),
    )
    val alarmsCallbacks = AlarmsCallbacks(
        onAdd = {},
        onSave = {},
        onToggle = { _, _ -> },
        onDelete = {},
        onBack = {},
        onReconnect = {},
    )

    val activityState = ActivityState(
        running = true,
        recording = true,
        distanceMeters = 1234.0,
        recentPaceSecPerKm = 300.0,
        elapsedMs = 600_000L,
        headingDeg = 90f,
        altitudeM = 100.0,
        pressureHpa = 1013.0,
        hasFix = true,
    )
    val activitySummary = ActivitySummary(
        distanceM = 5000.0,
        movingTimeMs = 1_500_000L,
        elapsedTimeMs = 1_600_000L,
        avgPaceSecPerKm = 300.0,
    )
    val activityCallbacks = ActivityCallbacks(
        onStart = {},
        onShowLive = {},
        onStop = {},
        onMode = {},
        onToggleUnit = {},
        onOpenHistory = {},
        onConfigureMetrics = {},
    )
    val activityTrack = ActivityTrack(
        id = "t1",
        startedAtMs = 1_000L,
        endedAtMs = 1_600_000L,
        unit = ActivityUnit.METRIC,
        points = listOf(
            TrackPoint(tMs = 0L, lat = 40.000, lng = -105.000),
            TrackPoint(tMs = 1_000L, lat = 40.001, lng = -105.001),
        ),
        summary = activitySummary,
    )
    val activityHistoryCallbacks = ActivityHistoryCallbacks(
        onOpen = {},
        onDelete = {},
        onBack = {},
    )
    val metricsConfig = ActivityMetricsConfig.DEFAULT
    val activityMetricsConfigCallbacks = ActivityMetricsConfigCallbacks(
        onMoveUp = { _, _ -> },
        onMoveDown = { _, _ -> },
        onToggle = { _, _, _ -> },
        onBack = {},
    )
}
