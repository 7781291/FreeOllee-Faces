package com.blizzardcaron.freeolleefaces.auto

import android.content.Context

class AndroidAlarmScheduler(private val context: Context) : AlarmScheduler {
    override fun rearm() = AlarmRearm.rearm(context)
}
