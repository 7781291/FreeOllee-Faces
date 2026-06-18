package com.blizzardcaron.freeolleefaces.ui

import com.blizzardcaron.freeolleefaces.auto.ActiveComplication
import com.blizzardcaron.freeolleefaces.format.TempUnit

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
