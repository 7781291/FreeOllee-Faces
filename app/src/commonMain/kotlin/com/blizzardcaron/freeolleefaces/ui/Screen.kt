package com.blizzardcaron.freeolleefaces.ui

sealed interface Screen {
    data object Home : Screen
    data object ComplicationsList : Screen
    data object Notifications : Screen
    data object Settings : Screen
    data object TimerSets : Screen
    data object TimerSetEdit : Screen
}
