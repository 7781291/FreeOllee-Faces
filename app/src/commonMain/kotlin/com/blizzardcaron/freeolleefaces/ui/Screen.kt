package com.blizzardcaron.freeolleefaces.ui

sealed interface Screen {
    data object Home : Screen
    data object Settings : Screen
    data object TimerSets : Screen
    data object TimerSetEdit : Screen
    data object Alarms : Screen
    data object Activity : Screen
    data object Instruments : Screen
}
