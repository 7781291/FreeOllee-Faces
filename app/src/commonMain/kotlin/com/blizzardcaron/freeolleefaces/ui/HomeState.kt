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
    val powerSavingEnabled: Boolean = true,
    val screenSleepTimeoutSec: Int = DEFAULT_SCREEN_SLEEP_SEC,
    val quietHoursEnabled: Boolean = true,
    val quietHoursStartMin: Int = DEFAULT_QH_START_HOUR * MINUTES_PER_HOUR,
    val quietHoursEndMin: Int = DEFAULT_QH_END_HOUR * MINUTES_PER_HOUR,

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
private const val DEFAULT_QH_START_HOUR = 22
private const val DEFAULT_QH_END_HOUR = 7
private const val DEFAULT_SCREEN_SLEEP_SEC = 120
