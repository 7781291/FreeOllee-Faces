# Alarm Re-arm Backstop Retry + Failure Notification (0.13.1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A failed alarm re-arm BLE push retries with escalating backoff and ultimately posts an actionable notification, instead of silently skipping the next alarm.

**Architecture:** The re-arm engine (`AlarmRearm`) currently treats a failed `0x25` push as "eventually consistent", but its next scheduled pass runs only at *next fire + 60s* — after the alarm should already have rung — so one failed push silently skips an alarm (observed on-device 2026-06-12: 7:15 AM M-F alarm fired, the 7:16 AM pass failed to push "armed 7:00 PM", nothing retried, 7 PM alarm lost). The fix mirrors the auto-update chain's Layer-2 backstop (`AutoUpdateWorker.handleSendFailure`): on push failure, schedule an exact AlarmManager backstop re-arm pass at 2 → 5 → 15 minutes (reusing `AutoUpdateSchedule.hasBackstopBudget`/`backstopDelayMs`); when the budget is spent, post an `ErrorNotifier` notification with a Retry action; on any later successful push, cancel the pending backstop and clear the notification. The decision policy is a new pure `AlarmRearmRecovery` object in commonMain (testable), with dumb glue in androidMain.

**Tech Stack:** Kotlin Multiplatform (commonMain policy + commonTest), Android `AlarmManager` exact triggers, `NotificationCompat` via existing `ErrorNotifier`. Unit tests: `./gradlew :app:testDebugUnitTest`. Android compile check: `./gradlew :app:compileDebugKotlinAndroid`.

**Root cause (for reference):**
- `AlarmRearm.rearm()` (app/src/androidMain/.../auto/AlarmRearm.kt) pushes the next-fire frame with only the Layer-1 GATT retry inside `AndroidBleClient.deliver` (3 attempts, ~30 s total). The Ollee watch sleeps its BLE radio, so a 30 s window routinely fails.
- On failure it only logs and emits to `pushResults` — a snackbar flow with no subscriber when triggered from `AlarmRearmReceiver`/`BootReceiver` with the app closed.
- The "next trigger retries" claim in its doc comment is structurally too late: the next trigger is set for the *next* alarm's fire time + 60 s.

**Constraints:**
- Today (Fri 2026-06-12) is inside the no-push window 9 AM–5 PM MT: commit locally on branch `fix/alarm-rearm-backstop`, do NOT push or merge to main (push-to-main cuts a signed release).
- No device is attached; verification is unit tests + compile. On-device verification happens later via the dev bench.

---

### Task 1: Add `FailureKind.ALARM_UNREACHABLE`

**Files:**
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/notify/NotifyDecision.kt` (the `FailureKind` enum at the top, lines 9-15)
- Test: `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/notify/FailureKindTest.kt`

- [ ] **Step 1: Extend the failing test**

In `FailureKindTest.kt`, add `ALARM_UNREACHABLE` to the retryable test and bump the explicit count from 3 to 4:

```kotlin
    @Test fun transientFailuresAreRetryable() {
        assertTrue(FailureKind.WATCH_UNREACHABLE.retryable)
        assertTrue(FailureKind.WEATHER_FETCH_FAILED.retryable)
        assertTrue(FailureKind.SUN_UNREACHABLE.retryable)
        assertTrue(FailureKind.ALARM_UNREACHABLE.retryable)
    }
```

and

```kotlin
    @Test fun everyKindHasAnExplicitRetryableValue() {
        // Guard against a future kind silently defaulting; exactly four are retryable today.
        assertEquals(4, FailureKind.entries.count { it.retryable })
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*FailureKindTest"`
Expected: FAIL — compile error `unresolved reference: ALARM_UNREACHABLE`.

- [ ] **Step 3: Add the enum value**

In `NotifyDecision.kt`, extend the enum (keep the doc comment; add the new value last):

```kotlin
enum class FailureKind(val retryable: Boolean) {
    WATCH_UNREACHABLE(retryable = true),
    WEATHER_FETCH_FAILED(retryable = true),
    SETUP_INCOMPLETE(retryable = false),
    SUN_UNREACHABLE(retryable = true),
    HEALTH_UNAVAILABLE(retryable = false),
    ALARM_UNREACHABLE(retryable = true),
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*FailureKindTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/notify/NotifyDecision.kt app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/notify/FailureKindTest.kt
git commit -m "feat: add ALARM_UNREACHABLE failure kind"
```

---

### Task 2: Pure recovery policy `AlarmRearmRecovery`

**Files:**
- Create: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/alarm/AlarmRearmRecovery.kt`
- Test: `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/alarm/AlarmRearmRecoveryTest.kt`

- [ ] **Step 1: Write the failing test**

Create `AlarmRearmRecoveryTest.kt`:

```kotlin
package com.blizzardcaron.freeolleefaces.alarm

import com.blizzardcaron.freeolleefaces.alarm.AlarmRearmRecovery.Action
import kotlin.test.Test
import kotlin.test.assertEquals

class AlarmRearmRecoveryTest {

    @Test fun successClearsFailureStateAtAnyAttempt() {
        assertEquals(Action.ClearFailure, AlarmRearmRecovery.afterPush(pushSucceeded = true, attempt = 0))
        assertEquals(Action.ClearFailure, AlarmRearmRecovery.afterPush(pushSucceeded = true, attempt = 3))
    }

    @Test fun failuresRetryWithEscalatingBackoff() {
        // Same 2 → 5 → 15 minute ladder as the auto-update backstop.
        assertEquals(
            Action.ScheduleRetry(delayMs = 2 * 60_000L, nextAttempt = 1),
            AlarmRearmRecovery.afterPush(pushSucceeded = false, attempt = 0),
        )
        assertEquals(
            Action.ScheduleRetry(delayMs = 5 * 60_000L, nextAttempt = 2),
            AlarmRearmRecovery.afterPush(pushSucceeded = false, attempt = 1),
        )
        assertEquals(
            Action.ScheduleRetry(delayMs = 15 * 60_000L, nextAttempt = 3),
            AlarmRearmRecovery.afterPush(pushSucceeded = false, attempt = 2),
        )
    }

    @Test fun fourthConsecutiveFailureNotifies() {
        assertEquals(Action.NotifyFailure, AlarmRearmRecovery.afterPush(pushSucceeded = false, attempt = 3))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*AlarmRearmRecoveryTest"`
Expected: FAIL — compile error, `AlarmRearmRecovery` does not exist.

- [ ] **Step 3: Implement the policy**

Create `AlarmRearmRecovery.kt`:

```kotlin
package com.blizzardcaron.freeolleefaces.alarm

import com.blizzardcaron.freeolleefaces.auto.AutoUpdateSchedule

/**
 * Pure policy for what the re-arm engine does after each watch push attempt. A failed push is
 * NOT eventually consistent: the engine's next scheduled pass runs only after the next alarm
 * should already have fired, so without a backstop a single failed push silently skips that
 * alarm. Mirrors the auto-update chain's backstop (same budget and 2/5/15-minute backoff via
 * [AutoUpdateSchedule]) with a terminal notification once the budget is spent.
 */
object AlarmRearmRecovery {

    sealed interface Action {
        /** Push landed: cancel any pending backstop retry and clear the failure notification. */
        data object ClearFailure : Action

        /** Push failed with budget left: run another re-arm pass after [delayMs]. */
        data class ScheduleRetry(val delayMs: Long, val nextAttempt: Int) : Action

        /** Push failed with the budget spent: surface the failure notification. */
        data object NotifyFailure : Action
    }

    /** [attempt] is the 0-based index of the push that just completed. */
    fun afterPush(pushSucceeded: Boolean, attempt: Int): Action = when {
        pushSucceeded -> Action.ClearFailure
        AutoUpdateSchedule.hasBackstopBudget(attempt) ->
            Action.ScheduleRetry(AutoUpdateSchedule.backstopDelayMs(attempt), attempt + 1)
        else -> Action.NotifyFailure
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*AlarmRearmRecoveryTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/alarm/AlarmRearmRecovery.kt app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/alarm/AlarmRearmRecoveryTest.kt
git commit -m "feat: pure backstop recovery policy for alarm re-arm pushes"
```

---

### Task 3: Alarm failure notification (`ErrorNotifier` + `AlarmRetryReceiver` + manifest)

Android-only glue — no unit tests possible; verify by compiling. The alarm failure gets its
own notification ID so it never masks (or gets masked by) a face-update problem, and its own
Retry receiver that re-runs the re-arm pass instead of the auto-update chain. No
`NotifyDecision`/`lastNotifiedKind` state for the alarm path: exhaustion always (re)posts and
success always clears — transition-only suppression would go silent after the user dismisses
the notification (the bug `RetryReceiver` has to work around by nulling `lastNotifiedKind`).

**Files:**
- Create: `app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/notify/AlarmRetryReceiver.kt`
- Modify: `app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/notify/ErrorNotifier.kt`
- Modify: `app/src/androidMain/AndroidManifest.xml` (add receiver next to `.notify.RetryReceiver`)

- [ ] **Step 1: Create `AlarmRetryReceiver.kt`**

```kotlin
package com.blizzardcaron.freeolleefaces.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.blizzardcaron.freeolleefaces.auto.AlarmRearm

/**
 * Backs the alarm-failure notification's "Retry" action: clear the notification and run a
 * fresh re-arm pass with a full backstop budget. [goAsync] keeps the process alive for the
 * BLE push (same pattern as AlarmRearmReceiver).
 */
class AlarmRetryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        ErrorNotifier.clearAlarm(ctx)
        val pending = goAsync()
        AlarmRearm.rearm(ctx) { pending.finish() }
    }

    companion object {
        const val REQUEST_CODE = 2002
    }
}
```

- [ ] **Step 2: Update `ErrorNotifier.kt`**

Replace the object's doc comment and the constants block:

```kotlin
/**
 * Owns the error notifications: the single face-update problem (post/clear driven by
 * [NotifyDecision]) and the separate alarm-push failure (driven by the re-arm engine's
 * [com.blizzardcaron.freeolleefaces.alarm.AlarmRearmRecovery] outcomes).
 */
object ErrorNotifier {

    private const val CHANNEL_ID = "background_problems"
    private const val NOTIFICATION_ID = 1001
    private const val ALARM_NOTIFICATION_ID = 1002
```

In `notify()`, replace the retry-action block and the final post so both route per kind:

```kotlin
        // Transient failures get a Retry action that re-runs the failed push in the background.
        if (kind.retryable) {
            val retryIntent = Intent(ctx, retryReceiverFor(kind))
            val retryPending = PendingIntent.getBroadcast(
                ctx, retryRequestCodeFor(kind), retryIntent, PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, "Retry", retryPending)
        }

        NotificationManagerCompat.from(ctx).notify(idFor(kind), builder.build())
        return true
```

After `clear()`, add the alarm clear and the per-kind routing helpers:

```kotlin
    /** Clears only the alarm-push failure notification (the face-update one is independent). */
    fun clearAlarm(context: Context) {
        NotificationManagerCompat.from(context.applicationContext).cancel(ALARM_NOTIFICATION_ID)
    }

    /** Alarm failures get their own slot so they never mask (or get masked by) face problems. */
    private fun idFor(kind: FailureKind): Int =
        if (kind == FailureKind.ALARM_UNREACHABLE) ALARM_NOTIFICATION_ID else NOTIFICATION_ID

    /** Alarm retries re-run the re-arm pass; everything else re-runs the auto-update chain. */
    private fun retryReceiverFor(kind: FailureKind): Class<*> =
        if (kind == FailureKind.ALARM_UNREACHABLE) AlarmRetryReceiver::class.java else RetryReceiver::class.java

    private fun retryRequestCodeFor(kind: FailureKind): Int =
        if (kind == FailureKind.ALARM_UNREACHABLE) AlarmRetryReceiver.REQUEST_CODE else RetryReceiver.REQUEST_CODE
```

Add the new kind to both copy maps:

```kotlin
        FailureKind.ALARM_UNREACHABLE -> "Watch alarm not set"
```

```kotlin
        FailureKind.ALARM_UNREACHABLE -> "Couldn't push your next alarm to the watch after several tries — it won't ring until this succeeds. Long-press the ALARM button to wake the watch, then tap Retry."
```

- [ ] **Step 3: Register the receiver in `app/src/androidMain/AndroidManifest.xml`**

Directly after the `.notify.RetryReceiver` entry:

```xml
        <!-- Backs the alarm-failure notification's "Retry" action. -->
        <receiver
            android:name=".notify.AlarmRetryReceiver"
            android:exported="false" />
```

- [ ] **Step 4: Compile**

Run: `./gradlew :app:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/notify/AlarmRetryReceiver.kt app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/notify/ErrorNotifier.kt app/src/androidMain/AndroidManifest.xml
git commit -m "feat: alarm-failure notification with dedicated Retry receiver"
```

---

### Task 4: Wire the backstop into `AlarmRearm` / `AlarmRearmReceiver`

**Files:**
- Modify: `app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/auto/AlarmRearm.kt`
- Modify: `app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/auto/AlarmRearmReceiver.kt`

- [ ] **Step 1: Update `AlarmRearm.kt`**

Add imports:

```kotlin
import com.blizzardcaron.freeolleefaces.alarm.AlarmRearmRecovery
import com.blizzardcaron.freeolleefaces.notify.ErrorNotifier
import com.blizzardcaron.freeolleefaces.notify.FailureKind
```

Replace the third paragraph of the object's doc comment ("The watch cannot tell the phone…
eventually consistent.") with:

```
 * The watch cannot tell the phone an alarm fired, so the trigger time is our own computed
 * `nextFire + 60 s`. A failed BLE push is NOT left for the next trigger — that next trigger
 * only runs after the *next* alarm should already have fired, which would silently skip it
 * (observed on-device 2026-06-12). Instead [AlarmRearmRecovery] drives a backstop chain:
 * exact re-arm passes at 2/5/15 min, then the alarm-failure notification once the budget is
 * spent. A later successful push cancels the chain and clears the notification.
```

Update the constants block:

```kotlin
    const val TAG = "ALARM_REARM"
    const val EXTRA_ATTEMPT = "rearm_attempt"
    private const val REQUEST_CODE = 4025           // fire+60s trigger: one slot, each schedule replaces the last
    private const val BACKSTOP_REQUEST_CODE = 4026  // failed-push backstop: separate slot, same receiver
    private const val PUSH_DEBOUNCE_MS = 750L
```

Change the signature (existing callers pass a trailing `onComplete` lambda and keep working;
`attempt` arrives only via the backstop trigger's intent extra):

```kotlin
    fun rearm(context: Context, attempt: Int = 0, onComplete: () -> Unit = {}) {
```

Inside `pushMutex.withLock`, replace everything after the `sendPacket` call (the `Log.i` and
`pushResults.tryEmit`) with:

```kotlin
                    val result = AndroidBleClient(ctx).sendPacket(address, AlarmSchedule.packetFor(latest))
                    Log.i(
                        TAG,
                        if (result.isSuccess) "push OK (${if (latest != null) "armed ${latest.dateTime}" else "disarm"}) [attempt $attempt]"
                        else "push FAIL ${result.exceptionOrNull()?.message} [attempt $attempt]",
                    )
                    val action = AlarmRearmRecovery.afterPush(result.isSuccess, attempt)
                    applyRecovery(ctx, am, action)
                    pushResults.tryEmit(
                        when {
                            !result.isSuccess && action is AlarmRearmRecovery.Action.ScheduleRetry ->
                                "Alarm send failed — long-press ALARM to wake the watch (retrying automatically)"
                            !result.isSuccess ->
                                "Alarm send failed after several tries — long-press ALARM to wake the watch, then tap Retry in the notification"
                            latest != null -> "Sent to watch — ${AlarmSchedule.formatNext(latest)}"
                            else -> "Sent to watch — alarm off"
                        },
                    )
```

Add below `rearm()` (still inside the object):

```kotlin
    /** Applies one [AlarmRearmRecovery] outcome: backstop scheduling, cleanup, or notification. */
    private fun applyRecovery(ctx: Context, am: AlarmManager, action: AlarmRearmRecovery.Action) {
        when (action) {
            AlarmRearmRecovery.Action.ClearFailure -> {
                am.cancel(backstopTrigger(ctx, nextAttempt = 0))
                ErrorNotifier.clearAlarm(ctx)
            }
            is AlarmRearmRecovery.Action.ScheduleRetry -> {
                val atMs = System.currentTimeMillis() + action.delayMs
                val pi = backstopTrigger(ctx, action.nextAttempt)
                if (am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
                }
                Log.i(TAG, "backstop retry ${action.nextAttempt} in ${action.delayMs / 1000}s")
            }
            AlarmRearmRecovery.Action.NotifyFailure -> {
                Log.w(TAG, "backstop budget spent — posting alarm-failure notification")
                ErrorNotifier.notify(ctx, FailureKind.ALARM_UNREACHABLE)
            }
        }
    }

    /** Extras don't participate in PendingIntent matching, so the same shape serves cancel(). */
    private fun backstopTrigger(ctx: Context, nextAttempt: Int): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, BACKSTOP_REQUEST_CODE,
            Intent(ctx, AlarmRearmReceiver::class.java).putExtra(EXTRA_ATTEMPT, nextAttempt),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
```

- [ ] **Step 2: Update `AlarmRearmReceiver.kt`**

```kotlin
package com.blizzardcaron.freeolleefaces.auto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Fires for both of [AlarmRearm]'s exact triggers — ~1 minute after each computed watch-alarm
 * fire (no extra: fresh attempt-0 budget) and each failed-push backstop retry (attempt carried
 * in [AlarmRearm.EXTRA_ATTEMPT]) — and re-runs the re-arm pass. [goAsync] keeps the process
 * alive for the BLE push (same pattern as DevToolsReceiver).
 */
class AlarmRearmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val attempt = intent.getIntExtra(AlarmRearm.EXTRA_ATTEMPT, 0)
        AlarmRearm.rearm(context, attempt) { pending.finish() }
    }
}
```

- [ ] **Step 3: Compile and run the full unit suite**

Run: `./gradlew :app:compileDebugKotlinAndroid :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 4: Commit**

```bash
git add app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/auto/AlarmRearm.kt app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/auto/AlarmRearmReceiver.kt
git commit -m "fix: backstop retries + notification for failed alarm re-arm pushes"
```

---

### Task 5: README + version bump to 0.13.1

**Files:**
- Modify: `README.md` (Alarms paragraph, lines 27-32)
- Modify: `VERSION`

- [ ] **Step 1: Extend the README Alarms paragraph**

In the `**Alarms**` paragraph, after "…which live in the same BLE record." and before "See",
add:

```
If a re-arm push can't reach the watch it retries at 2/5/15 minutes, then posts a
notification with a Retry action — a missed push otherwise means a silently skipped alarm.
```

- [ ] **Step 2: Bump VERSION**

`VERSION` becomes:

```
0.13.1
```

- [ ] **Step 3: Commit**

```bash
git add README.md VERSION
git commit -m "chore: bump version to 0.13.1

Failed alarm re-arm pushes now backstop-retry and ultimately notify,
instead of silently skipping the next alarm."
```

---

### Task 6: Final verification (NO push)

- [ ] **Step 1: Full unit suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, zero failures.

- [ ] **Step 2: Debug APK assembles**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Confirm branch state, do NOT push**

Run: `git -C . log --oneline main..HEAD && git status --short`
Expected: the 5 commits above, clean tree. Pushing/merging to main is deferred past 5 PM MT
(push-to-main cuts a signed public release; no-push window is 9 AM–5 PM MT weekdays).
