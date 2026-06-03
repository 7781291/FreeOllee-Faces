package com.blizzardcaron.freeolleefaces.notify

/**
 * Maps a `StepsRepository.todaySteps()` failure to the notification kind it should drive — or
 * `null` when the failure is a transient read glitch that the user can't act on and the chain
 * will simply retry next cycle.
 *
 * Why this exists: a background Health Connect read can fail either because access is genuinely
 * missing (Health Connect unavailable, or steps/background-read permission not granted) or
 * because of a momentary IPC/service hiccup with access fully intact. Collapsing both into
 * `HEALTH_UNAVAILABLE` told the user to "grant Health access" even when they already had — a
 * false alarm with no Retry. We only surface that message for failures whose remedy really is
 * granting access; everything else is left to the silent automatic retry.
 *
 *  - [SecurityException] — our own pre-check (`READ_STEPS` ungranted) *and* the exception Health
 *    Connect raises when a background read is denied (`READ_HEALTH_DATA_IN_BACKGROUND` missing).
 *    In both cases "grant Health access" is the correct remedy.
 *  - [IllegalStateException] — our own signal that Health Connect is unavailable / needs setup.
 *  - anything else (e.g. a transport/`RemoteException`) — transient; skip silently.
 */
object StepsFailureClassifier {
    fun kindFor(error: Throwable): FailureKind? = when (error) {
        is SecurityException -> FailureKind.HEALTH_UNAVAILABLE
        is IllegalStateException -> FailureKind.HEALTH_UNAVAILABLE
        else -> null
    }
}
