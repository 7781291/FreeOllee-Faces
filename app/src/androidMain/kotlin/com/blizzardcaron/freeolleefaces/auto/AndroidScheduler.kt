package com.blizzardcaron.freeolleefaces.auto

import android.content.Context

class AndroidScheduler(private val context: Context) : Scheduler {
    override fun reschedule() = AutoUpdateScheduler.reschedule(context)
}
