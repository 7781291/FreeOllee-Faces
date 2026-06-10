package com.blizzardcaron.freeolleefaces.ui

import com.blizzardcaron.freeolleefaces.format.TempUnit

data class HomeCallbacks(
    val onOpenFaces: () -> Unit,
    val onOpenTimerSets: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onUpdateNow: () -> Unit,
    val onTempUnitChange: (TempUnit) -> Unit,
    val onCustomChange: (String) -> Unit,
    val onSendCustom: () -> Unit,
    val onGrantHealth: () -> Unit,
    val onGrantNotificationAccess: () -> Unit,
    val onToggleNotifications: (Boolean) -> Unit,
)

data class SettingsCallbacks(
    val onBack: () -> Unit,
    val onSelectWatch: () -> Unit,
    val onIntervalChange: (Int) -> Unit,
    val onSleepEnabledChange: (Boolean) -> Unit,
    val onSleepStartChange: (Int) -> Unit,
    val onSleepEndChange: (Int) -> Unit,
    val onLatChange: (String) -> Unit,
    val onLngChange: (String) -> Unit,
    val onUseMyLocation: () -> Unit,
)
