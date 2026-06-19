package com.blizzardcaron.freeolleefaces.ui

import com.blizzardcaron.freeolleefaces.auto.ActiveComplication
import com.blizzardcaron.freeolleefaces.ble.ConnectionStatus
import com.blizzardcaron.freeolleefaces.format.TempUnit

data class HomeState(
    val activeComplication: ActiveComplication = ActiveComplication.TEMPERATURE,
    val watchLabel: String = "Watch: none selected",
    val watchSelected: Boolean = false,
    val connectionStatus: ConnectionStatus = ConnectionStatus.NoWatch,
    val sending: Boolean = false,

    val locationLabel: String = "Location: not set",
    val locationFreshness: String? = null,
    val locating: Boolean = false,

    val tempUnit: TempUnit = TempUnit.FAHRENHEIT,
    val tempPreview: PreviewState = PreviewState.WaitingForCoords,
    val tempUpdated: String? = null,
    val tempNext: String? = null,
    val updateIntervalMinutes: Int = 15,
    val sleepEnabled: Boolean = true,
    val sleepStartMin: Int = DEFAULT_SLEEP_START_HOUR * MINUTES_PER_HOUR,
    val sleepEndMin: Int = DEFAULT_SLEEP_END_HOUR * MINUTES_PER_HOUR,
    val autoSleepScheduleEnabled: Boolean = false,
    val autoSleepWindowStartMin: Int = DEFAULT_SLEEP_START_HOUR * MINUTES_PER_HOUR,
    val autoSleepWindowEndMin: Int = DEFAULT_AUTO_SLEEP_END_HOUR * MINUTES_PER_HOUR,
    val autoSleepInWindowOn: Boolean = true,
    val autoSleepInWindowPeriodSec: Int = DEFAULT_AUTO_SLEEP_PERIOD_SEC,
    val autoSleepOutWindowOn: Boolean = false,
    val autoSleepOutWindowPeriodSec: Int = DEFAULT_AUTO_SLEEP_PERIOD_SEC,

    val sunPreview: PreviewState = PreviewState.WaitingForCoords,
    val sunUpdated: String? = null,
    val sunNext: String? = null,

    val stepsPreview: PreviewState = PreviewState.Loading,
    val stepsUpdated: String? = null,
    val stepsHealthGranted: Boolean = false,

    val custom: String = "",
    val customSent: String? = null,

    val notificationCount: Int = 0,
    val notificationAccessGranted: Boolean = false,
    val notificationsEnabled: Boolean = false,

    val lat: String = "",
    val lng: String = "",

    val versionLabel: String = "",
)

private const val MINUTES_PER_HOUR = 60
private const val DEFAULT_SLEEP_START_HOUR = 22
private const val DEFAULT_SLEEP_END_HOUR = 6
private const val DEFAULT_AUTO_SLEEP_END_HOUR = 7
private const val DEFAULT_AUTO_SLEEP_PERIOD_SEC = 120
