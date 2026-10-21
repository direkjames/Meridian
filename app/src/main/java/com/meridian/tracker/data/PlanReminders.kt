package com.meridian.tracker.data

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.meridian.tracker.MainActivity
import com.meridian.tracker.MeridianApp
import com.meridian.tracker.R
import com.meridian.tracker.ui.Format
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * Daily training-plan reminders, scheduled with Android's WorkManager so they survive restarts
 * and work offline. Each reminder schedules the next one.
 */
object PlanReminders {

    internal const val MORNING = "plan-morning"
    internal const val EVENING = "plan-evening"
    internal const val KIND = "kind"
    private const val EVENING_HOUR = 19
    private const val NOTIFICATION_MORNING = 101
    private const val NOTIFICATION_EVENING = 102

    /** (Re)schedules reminders for the current plan, or cancels them when there's none. */
    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)
        val plan = ExtrasStore.plan.value
        if (plan == null || LocalDate.now().isAfter(plan.config.eventDate)) {
            wm.cancelUniqueWork(MORNING)
            wm.cancelUniqueWork(EVENING)
            return
        }
        enqueue(wm, MORNING, LocalTime.of(plan.config.reminderHour, plan.config.reminderMinute))
        if (plan.config.eveningNudge) enqueue(wm, EVENING, LocalTime.of(EVENING_HOUR, 0)) else wm.cancelUniqueWork(EVENING)
    }

    private fun enqueue(wm: WorkManager, name: String, time: LocalTime) {
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(time)
        if (!next.isAfter(now.plusSeconds(30))) next = next.plusDays(1)
        val delay = Duration.between(now, next).toMillis()
        val request = OneTimeWorkRequestBuilder<PlanReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(KIND to name))
            .build()
        wm.enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, request)
    }

    internal suspend fun fire(context: Context, kind: String) {
        ActivityStore.ready() // make sure activities are loaded before checking today's session
        val plan = ExtrasStore.plan.value ?: return
        val today = LocalDate.now()
        val day = plan.day(today)
        val target = { d: PlanDay ->
            if (plan.config.targetType == TargetType.TIME) "${d.minutes} min" else "${Format.km(d.distanceM)} ${Format.distUnit}"
        }

        val (title, text) = when {
            day == null -> return
            kind == MORNING && day.kind == SessionKind.RACE ->
                "It's event day: ${plan.config.name}" to "You've trained for this. Start easy and enjoy it."
            kind == MORNING && day.isRest ->
                "Rest day" to day.detail
            kind == MORNING ->
                "Today: ${day.title} · ${target(day)}" to day.detail
            // Evening: only nudge when today's session isn't done yet.
            kind == EVENING && !day.isRest && day.kind != SessionKind.RACE -> {
                val status = PlanGenerator.status(plan, ActivityStore.activities.value, today)[today]
                if (status == DayStatus.DONE) return
                "Still time for today's ${day.title.lowercase()}" to "${target(day)} planned. Even a short one counts."
            }
            else -> return
        }
        notify(context, if (kind == MORNING) NOTIFICATION_MORNING else NOTIFICATION_EVENING, title, text)
    }

    private fun notify(context: Context, id: Int, title: String, text: String) {
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!allowed) return
        val open = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, MeridianApp.CHANNEL_PLAN)
            .setSmallIcon(R.drawable.ic_stat_meridian)
            .setColor(ContextCompat.getColor(context, R.color.accent))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(id, n)
    }
}

class PlanReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        PlanReminders.fire(applicationContext, inputData.getString(PlanReminders.KIND) ?: PlanReminders.MORNING)
        PlanReminders.schedule(applicationContext)
        return Result.success()
    }
}
