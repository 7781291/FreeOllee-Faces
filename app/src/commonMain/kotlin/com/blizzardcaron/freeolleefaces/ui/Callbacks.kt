package com.blizzardcaron.freeolleefaces.ui

import com.blizzardcaron.freeolleefaces.auto.ActiveComplication
import com.blizzardcaron.freeolleefaces.format.TempUnit
import com.blizzardcaron.freeolleefaces.timer.TimerSet

data class HomeCallbacks(
    val onActivate: (ActiveComplication) -> Unit,
    val onUpdateNow: () -> Unit,
    val onTempUnitChange: (TempUnit) -> Unit,
    val onCustomChange: (String) -> Unit,
    val onSendCustom: () -> Unit,
    val onGrantHealth: () -> Unit,
    val onGrantNotificationAccess: () -> Unit,
    val onToggleNotifications: (Boolean) -> Unit,
    val onNotificationsUpdateNow: () -> Unit,
    val onReconnect: () -> Unit = {},
)

data class SettingsCallbacks(
    val onBack: () -> Unit,
    val onSelectWatch: () -> Unit,
    val onIntervalChange: (Int) -> Unit,
    val onSleepEnabledChange: (Boolean) -> Unit,
    val onSleepStartChange: (Int) -> Unit,
    val onSleepEndChange: (Int) -> Unit,
    val onAutoSleepScheduleEnabledChange: (Boolean) -> Unit,
    val onAutoSleepWindowStartChange: (Int) -> Unit,
    val onAutoSleepWindowEndChange: (Int) -> Unit,
    val onAutoSleepInWindowOnChange: (Boolean) -> Unit,
    val onAutoSleepInWindowPeriodChange: (Int) -> Unit,
    val onAutoSleepOutWindowOnChange: (Boolean) -> Unit,
    val onAutoSleepOutWindowPeriodChange: (Int) -> Unit,
    val onLatChange: (String) -> Unit,
    val onLngChange: (String) -> Unit,
    val onUseMyLocation: () -> Unit,
)

/** UI state for the Timer screen's quick-timer card. */
data class QuickTimerState(
    val seconds: Int,
    val startFromApp: Boolean,
    val intervalMode: Boolean,
    val alarmMode: Boolean,
    val alarmHour: Int,
    val alarmMinute: Int,
)

/** All Timer-screen callbacks, bundled to keep the composable signature small. */
data class TimerSetsCallbacks(
    val onSaveQuick: (Int) -> Unit,
    val onToggleStartFromApp: (Boolean) -> Unit,
    val onToggleIntervalMode: (Boolean) -> Unit,
    val onToggleAlarmMode: (Boolean) -> Unit,
    val onSaveAlarmTime: (Int, Int) -> Unit,
    val onSendAlarm: () -> Unit,
    val onSendQuick: () -> Unit,
    val onOpen: (TimerSet) -> Unit,
    val onNew: () -> Unit,
    val onDuplicate: (TimerSet) -> Unit,
    val onDelete: (TimerSet) -> Unit,
    val onSend: (TimerSet) -> Unit,
    val onStart: (TimerSet) -> Unit,
    val onMoveUp: (TimerSet) -> Unit,
    val onMoveDown: (TimerSet) -> Unit,
    val onBack: () -> Unit,
    val onReconnect: () -> Unit,
)

/** Per-row callbacks for a timer set in the list. */
data class TimerSetRowCallbacks(
    val onOpen: () -> Unit,
    val onDuplicate: () -> Unit,
    val onDelete: () -> Unit,
    val onSend: () -> Unit,
    val onStart: () -> Unit,
    val onMoveUp: () -> Unit,
    val onMoveDown: () -> Unit,
)
