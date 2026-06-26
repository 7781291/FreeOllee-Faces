package com.blizzardcaron.freeolleefaces.activity

import com.russhwolf.settings.Settings

/**
 * Persists the [ActivityMetricsConfig] (JSON via [ActivityMetricsJson]) in a dedicated [Settings]
 * store. Thin glue over the codec + the config's pure ops; mirrors [TimerSetsRepository].
 */
class ActivityMetricsRepository(private val settings: Settings) {

    fun get(): ActivityMetricsConfig = ActivityMetricsJson.decode(settings.getStringOrNull(KEY_CONFIG))

    fun moveUp(mode: ActivityMode, index: Int) = save(get().moveUp(mode, index))

    fun moveDown(mode: ActivityMode, index: Int) = save(get().moveDown(mode, index))

    fun setEnabled(mode: ActivityMode, metric: ActivityMetric, enabled: Boolean) =
        save(get().setEnabled(mode, metric, enabled))

    private fun save(config: ActivityMetricsConfig) {
        settings.putString(KEY_CONFIG, ActivityMetricsJson.encode(config))
    }

    private companion object {
        const val KEY_CONFIG = "activity_metric_config"
    }
}
