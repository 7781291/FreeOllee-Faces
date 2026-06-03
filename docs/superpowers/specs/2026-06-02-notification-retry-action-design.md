# Notification Retry action — design spec

**Date:** 2026-06-02
**Status:** Approved for planning
**Feature:** A "Retry" action button on transient error notifications. Ships in v0.7.0
(bundled into PR #3).

## Summary

Background update failures post a single error notification (owned by `ErrorNotifier`, driven
by `AutoUpdateWorker` → `NotifyDecision`). Add a **Retry** action to the notifications whose
failure is transient, so the user can re-attempt the update straight from the shade without
opening the app.

## Which failures get Retry

Only failures an immediate re-run can plausibly fix:

| `FailureKind` | Retry? | Rationale |
|---------------|--------|-----------|
| `WATCH_UNREACHABLE` | ✅ | The watch may now be in range. |
| `WEATHER_FETCH_FAILED` | ✅ | The network may have recovered. |
| `SUN_UNREACHABLE` | ✅ | The watch may now be in range. |
| `SETUP_INCOMPLETE` | ❌ | Needs the app to set watch/location. |
| `HEALTH_UNAVAILABLE` | ❌ | Needs the app to grant Health access. |

The non-retryable kinds keep only the existing tap-to-open-app content intent.

## Behavior

Tapping **Retry**:
1. Clears the current notification (`ErrorNotifier.clear`).
2. Resets `prefs.lastNotifiedKind = null`. The notification state machine
   (`NotifyDecision`) is transition-only: it stays silent while the same failure persists.
   After a manual Retry we *want* a repeat of the same failure to surface again (otherwise the
   user taps Retry, it fails again, and they get no feedback), so we forget the dismissed kind
   and let the retried run treat it as fresh.
3. Calls `AutoUpdateScheduler.enqueueNext(ctx, 0L, sunAttempt = 0)` — fires the existing
   self-rescheduling `AutoUpdateWorker` once for whatever face is active. The worker re-sends
   and re-evaluates the notification (clears on success via `applyHealth`, or re-posts on a
   fresh failure), then re-arms the normal chain.

It runs entirely in the background — no app launch. The existing tap-to-open content intent
is unchanged and still present on every notification.

## Components

- `notify/NotifyDecision.kt` — add a `val retryable: Boolean` to `FailureKind`
  (`WATCH_UNREACHABLE`, `WEATHER_FETCH_FAILED`, `SUN_UNREACHABLE` = true; others = false).
  **Unit-tested.**
- `notify/RetryReceiver.kt` *(new)* — a `BroadcastReceiver` whose `onReceive` clears the
  notification and enqueues an immediate worker run. No new send logic.
- `notify/ErrorNotifier.kt` — when `kind.retryable`, attach a
  `NotificationCompat.Action("Retry", …)` backed by `PendingIntent.getBroadcast` (explicit
  intent → `RetryReceiver`, `FLAG_IMMUTABLE`). Use a single fixed request code.
- `AndroidManifest.xml` — declare `<receiver android:name=".notify.RetryReceiver"
  android:exported="false" />`.

## Testing

- **Unit:** `FailureKind.retryable` returns the correct value for every kind (guards the
  table above and any future kinds).
- **On-device:** induce a watch-unreachable failure (watch off); confirm the notification
  shows the **Retry** action and that tapping it triggers a worker run.

## Scope

Bundled into PR #3; **VERSION stays 0.7.0**. No new permissions or dependencies. Out of
scope: per-kind retry limits or backoff (the worker's existing logic handles its own
scheduling); a Retry button on non-transient failures.
