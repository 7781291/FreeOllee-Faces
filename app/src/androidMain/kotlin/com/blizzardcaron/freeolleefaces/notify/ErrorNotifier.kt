package com.blizzardcaron.freeolleefaces.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.blizzardcaron.freeolleefaces.MainActivity

/**
 * Owns the error notifications: the single face-update problem (post/clear driven by
 * [NotifyDecision]) and the separate alarm-push failure (driven by the re-arm engine's
 * [com.blizzardcaron.freeolleefaces.alarm.AlarmRearmRecovery] outcomes).
 */
object ErrorNotifier {

    private const val CHANNEL_ID = "background_problems"
    private const val NOTIFICATION_ID = 1001
    private const val ALARM_NOTIFICATION_ID = 1002
    private const val API_37 = 37

    /**
     * Posts (or replaces) the single error notification. Returns `true` if it was actually shown,
     * `false` if suppressed because POST_NOTIFICATIONS is not granted — callers must not record the
     * notification as shown unless this returned `true`, or a later grant would never surface an
     * ongoing failure.
     */
    fun notify(context: Context, kind: FailureKind): Boolean {
        val ctx = context.applicationContext
        ensureChannel(ctx)
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }

        val pending = PendingIntent.getActivity(
            ctx,
            0,
            Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE,
        )
        // Transient failures get a Retry action that re-runs the failed push in the background.
        val retryPending = if (kind.retryable) {
            PendingIntent.getBroadcast(
                ctx,
                retryRequestCodeFor(kind),
                Intent(ctx, retryReceiverFor(kind)),
                PendingIntent.FLAG_IMMUTABLE,
            )
        } else {
            null
        }

        val notification = if (Build.VERSION.SDK_INT >= API_37) {
            buildSemanticNotification(ctx, kind, pending, retryPending)
        } else {
            buildCompatNotification(ctx, kind, pending, retryPending)
        }
        NotificationManagerCompat.from(ctx).notify(idFor(kind), notification)
        return true
    }

    /** The pre-37 path, unchanged from the original builder. */
    private fun buildCompatNotification(
        ctx: Context,
        kind: FailureKind,
        contentPending: PendingIntent,
        retryPending: PendingIntent?,
    ): Notification {
        val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(titleFor(kind))
            .setContentText(textFor(kind))
            .setContentIntent(contentPending)
            .setAutoCancel(true)
        if (retryPending != null) {
            builder.addAction(0, "Retry", retryPending)
        }
        return builder.build()
    }

    /** Android 17+: same notification, body text tinted by the failure's semantic severity. */
    @RequiresApi(API_37)
    private fun buildSemanticNotification(
        ctx: Context,
        kind: FailureKind,
        contentPending: PendingIntent,
        retryPending: PendingIntent?,
    ): Notification {
        val styled = SpannableStringBuilder(textFor(kind)).apply {
            setSpan(
                Notification.createSemanticStyleAnnotation(kind.semanticStyle().toAndroidSemanticStyle()),
                0,
                length,
                Spanned.SPAN_INCLUSIVE_EXCLUSIVE,
            )
        }
        val builder = Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(titleFor(kind))
            .setContentText(styled)
            .setContentIntent(contentPending)
            .setAutoCancel(true)
        if (retryPending != null) {
            builder.addAction(
                Notification.Action.Builder(null as Icon?, "Retry", retryPending).build(),
            )
        }
        return builder.build()
    }

    fun clear(context: Context) {
        NotificationManagerCompat.from(context.applicationContext).cancel(NOTIFICATION_ID)
    }

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

    private fun ensureChannel(ctx: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Background update problems",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = "Alerts when a background watch update fails." }
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun titleFor(kind: FailureKind): String = when (kind) {
        FailureKind.WATCH_UNREACHABLE -> "Watch unreachable"
        FailureKind.WEATHER_FETCH_FAILED -> "Weather update failed"
        FailureKind.SETUP_INCOMPLETE -> "Setup incomplete"
        FailureKind.SUN_UNREACHABLE -> "Sun update missed"
        FailureKind.HEALTH_UNAVAILABLE -> "Health access needed"
        FailureKind.ALARM_UNREACHABLE -> "Watch alarm not set"
    }

    private fun textFor(kind: FailureKind): String = when (kind) {
        FailureKind.WATCH_UNREACHABLE ->
            "Couldn't reach your watch after several tries. Long-press the ALARM button to wake its " +
                "Bluetooth, then tap Retry."
        FailureKind.WEATHER_FETCH_FAILED -> "Couldn't fetch the temperature. The watch value may be stale."
        FailureKind.SETUP_INCOMPLETE -> "Open the app to set your location and watch."
        FailureKind.SUN_UNREACHABLE ->
            "Couldn't deliver the next sunrise/sunset after several tries. Long-press the ALARM button " +
                "to wake your watch, then tap Retry."
        FailureKind.HEALTH_UNAVAILABLE -> "Open the app and grant Health access so step counts can sync."
        FailureKind.ALARM_UNREACHABLE ->
            "Couldn't push your next alarm to the watch after several tries — it won't ring until this " +
                "succeeds. Long-press the ALARM button to wake the watch, then tap Retry."
    }
}
