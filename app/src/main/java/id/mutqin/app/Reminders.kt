package id.mutqin.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Pengingat murajaah harian (pengganti pemeriksa jadwal 30-detik di versi web). */
object ReminderScheduler {
    const val CHANNEL_ID = "mutqin_reminder"
    private const val REQUEST_CODE = 4101
    private const val NOTIF_ID = 4102

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(CHANNEL_ID, "Pengingat Murajaah", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Pengingat harian untuk murajaah hafalan"
            }
            nm.createNotificationChannel(channel)
        }
    }

    fun hasNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }

    /** Waktu pengingat berikutnya (hari terpilih + jam aktif) setelah [now], atau null. */
    fun nextTrigger(n: NotificationSettings, now: ZonedDateTime): ZonedDateTime? {
        if (!n.enabled) return null
        val times = n.times.filter { it.enabled }.mapNotNull { runCatching { LocalTime.parse(it.time) }.getOrNull() }
        if (times.isEmpty() || n.days.isEmpty()) return null

        for (offset in 0..7) {
            val date = now.toLocalDate().plusDays(offset.toLong())
            val dayId = date.dayOfWeek.value % 7 // Senin=1 ... Sabtu=6, Ahad=0
            if (dayId !in n.days) continue
            val candidate = times.sorted()
                .map { date.atTime(it).atZone(now.zone) }
                .firstOrNull { it.isAfter(now) }
            if (candidate != null) return candidate
        }
        return null
    }

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, REQUEST_CODE,
            Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    @SuppressLint("ScheduleExactAlarm")
    fun schedule(context: Context, settings: UserSettings) {
        val appCtx = context.applicationContext
        val am = appCtx.getSystemService(AlarmManager::class.java)
        val pi = pendingIntent(appCtx)
        am.cancel(pi)

        val n = settings.notifications ?: return
        val next = nextTrigger(n, ZonedDateTime.now(ZoneId.systemDefault())) ?: return
        val millis = next.toInstant().toEpochMilli()

        try {
            if (canScheduleExact(appCtx)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        }
    }

    fun cancel(context: Context) {
        val appCtx = context.applicationContext
        appCtx.getSystemService(AlarmManager::class.java).cancel(pendingIntent(appCtx))
    }

    fun buildBody(streakDays: Int, next: QuranPageMeta): String =
        "🔥 Rekor Streak: $streakDays Hari Berturut-turut\n" +
            "📖 Halaman berikutnya: Hal ${next.pageNumber}, Lembar ke-${next.lembar} (${next.side.title}) Juz ${next.juz}"

    @SuppressLint("MissingPermission")
    fun show(context: Context, title: String, body: String) {
        val appCtx = context.applicationContext
        if (!hasNotificationPermission(appCtx)) return
        ensureChannel(appCtx)

        val open = PendingIntent.getActivity(
            appCtx, 0,
            Intent(appCtx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(appCtx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mutqin)
            .setContentTitle(title)
            .setContentText(body.lineSequence().firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        NotificationManagerCompat.from(appCtx).notify(NOTIF_ID, notification)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val appCtx = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                QuranData.init(appCtx)
                val store = MutqinStore(appCtx)
                val settings = store.loadSettings()
                val progress = store.loadProgress()

                val n = settings.notifications
                if (n != null && n.enabled) {
                    val stats = StatsEngine.dailyStats(progress.values)
                    val pick = QueueEngine.nextFlashcard(progress, settings.activeScopePages, null, settings.scopeQueueOrder)
                    val meta = QuranData.pageMeta(pick?.pageNumber ?: store.lastPage ?: 1)
                    ReminderScheduler.show(
                        appCtx,
                        n.title.ifBlank { NotificationSettings.DEFAULT_TITLE },
                        ReminderScheduler.buildBody(stats.streakDays, meta)
                    )
                }
                // jadwalkan pengingat berikutnya
                ReminderScheduler.schedule(appCtx, settings)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "id.mutqin.app.ACTION_REMINDER"
    }
}

/** Menjadwalkan ulang alarm setelah perangkat reboot / aplikasi diperbarui. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val appCtx = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ReminderScheduler.schedule(appCtx, MutqinStore(appCtx).loadSettings())
            } finally {
                pending.finish()
            }
        }
    }
}
