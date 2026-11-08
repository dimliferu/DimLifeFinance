package ru.dimlife.finance

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ru.dimlife.finance.data.FinanceDatabase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val REMINDER_WORK_NAME = "dimlife_subscription_payment_reminders"
private const val REMINDER_CHANNEL_ID = "subscription_payment_reminders"

fun scheduleSubscriptionReminders(context: Context) {
    val request = PeriodicWorkRequestBuilder<SubscriptionReminderWorker>(24, TimeUnit.HOURS)
        .setInitialDelay(SubscriptionReminderWorker.delayUntilNextMorning(), TimeUnit.MILLISECONDS)
        .build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        REMINDER_WORK_NAME,
        ExistingPeriodicWorkPolicy.UPDATE,
        request
    )
}

fun cancelSubscriptionReminders(context: Context) {
    WorkManager.getInstance(context).cancelUniqueWork(REMINDER_WORK_NAME)
}

class SubscriptionReminderWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return Result.success()

        createNotificationChannel()
        val now = System.currentTimeMillis()
        val horizon = now + TimeUnit.HOURS.toMillis(24)
        val subscriptions = try {
            FinanceDatabase.get(applicationContext).financeDao().getAllSubscriptions()
        } catch (_: Exception) {
            return Result.retry()
        }

        val preferences = applicationContext.getSharedPreferences("subscription_reminder_history", Context.MODE_PRIVATE)
        subscriptions.filter { it.isActive && it.nextPaymentMillis in now..horizon }.forEach { subscription ->
            val reminderKey = "sent_${subscription.id}_${subscription.nextPaymentMillis}"
            if (preferences.getBoolean(reminderKey, false)) return@forEach

            val launchIntent = Intent(applicationContext, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                applicationContext,
                subscription.id.toInt(),
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val dateText = SimpleDateFormat("d MMMM", Locale("ru", "RU")).format(Date(subscription.nextPaymentMillis))
            val notification = NotificationCompat.Builder(applicationContext, REMINDER_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Скоро платёж: ${subscription.title}")
                .setContentText("${moneyForReminder(subscription.amountMinor)} · ${dateText}")
                .setStyle(NotificationCompat.BigTextStyle().bigText("Предстоящий платёж за ${subscription.title}: ${moneyForReminder(subscription.amountMinor)}. Дата списания: ${dateText}."))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()

            try {
                NotificationManagerCompat.from(applicationContext).notify(
                    "${subscription.id}_${subscription.nextPaymentMillis}".hashCode(),
                    notification
                )
                preferences.edit().putBoolean(reminderKey, true).apply()
            } catch (_: SecurityException) {
                // Notification permission may have been revoked while the worker was running.
            }
        }
        return Result.success()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                REMINDER_CHANNEL_ID,
                "Напоминания о платежах",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Напоминания о предстоящих платежах по подпискам"
            }
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        fun delayUntilNextMorning(): Long {
            val now = java.util.Calendar.getInstance()
            val next = (now.clone() as java.util.Calendar).apply {
                set(java.util.Calendar.HOUR_OF_DAY, 9)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
                if (timeInMillis <= now.timeInMillis) add(java.util.Calendar.DAY_OF_YEAR, 1)
            }
            return (next.timeInMillis - now.timeInMillis).coerceAtLeast(0L)
        }
    }
}

private fun moneyForReminder(amountMinor: Long): String =
    String.format(Locale("ru", "RU"), "%,.2f ₽", amountMinor / 100.0)
        .replace(',', ' ')
        .replace(".00 ₽", " ₽")
