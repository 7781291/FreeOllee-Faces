package com.blizzardcaron.freeolleefaces.notify

/**
 * Platform-agnostic notification severity, mapped to Android 17's framework
 * Notification.SEMANTIC_STYLE_* constants in the androidMain layer. Kept here (no framework
 * deps) so the failure-to-severity choice is unit-testable.
 */
enum class SemanticStyle { INFO, CAUTION, DANGER }

/** A failure the user relies on being fixed now (watch/alarm dead) is DANGER; the rest are CAUTION. */
fun FailureKind.semanticStyle(): SemanticStyle = when (this) {
    FailureKind.WATCH_UNREACHABLE,
    FailureKind.ALARM_UNREACHABLE -> SemanticStyle.DANGER
    FailureKind.WEATHER_FETCH_FAILED,
    FailureKind.SETUP_INCOMPLETE,
    FailureKind.SUN_UNREACHABLE,
    FailureKind.HEALTH_UNAVAILABLE -> SemanticStyle.CAUTION
}
