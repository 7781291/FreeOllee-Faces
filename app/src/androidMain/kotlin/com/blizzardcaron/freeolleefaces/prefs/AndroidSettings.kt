package com.blizzardcaron.freeolleefaces.prefs

import android.content.Context
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings

/**
 * [Settings] backed by the app's main SharedPreferences file. The file name must stay
 * `"freeollee_faces_prefs"` forever — existing users' on-disk data lives there.
 */
fun appSettings(context: Context): Settings {
    val sp = context.applicationContext.getSharedPreferences("freeollee_faces_prefs", Context.MODE_PRIVATE)
    return SharedPreferencesSettings(sp)
}
