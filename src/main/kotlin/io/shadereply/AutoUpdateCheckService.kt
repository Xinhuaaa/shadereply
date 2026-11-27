package io.shadereply

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import java.io.IOException
import java.util.concurrent.TimeUnit

class AutoUpdateCheckService : JobService() {
    private var current: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        if (!isEnabled(this) || !notificationsEnabled(this)) return false
        current = CoroutineScope(Dispatchers.Main).launch {
            val running = coroutineContext[Job]
            try {
                val installed = packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
                    .versionName.orEmpty()
                val release = withContext(Dispatchers.IO) {
                    ShadeReplyReleases.newerThan(installed, params.network)
                }
                if (isEnabled(this@AutoUpdateCheckService)) postUpdate(release)
            } catch (error: IOException) {
                Log.d(TAG, "Scheduled update check could not reach GitHub", error)
            } catch (error: JSONException) {
                Log.w(TAG, "Scheduled update check could not read GitHub releases", error)
            } finally {
                if (current === running) {
                    current = null
                    jobFinished(params, false)
                }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val stopped = current
        current = null
        stopped?.cancel()
        return false
    }

    override fun onDestroy() {
        val stopped = current
        current = null
        stopped?.cancel()
        super.onDestroy()
    }

    private fun postUpdate(release: ShadeReplyRelease?) {
        val manager = getSystemService(NotificationManager::class.java)
        if (release == null) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        val prefs = preferences(this)
        val version = release.tag.removePrefix("v")
        if (prefs.getString(LAST_ALERT, null) == version || !notificationsEnabled(this)) return
        val openAbout = PendingIntent.getActivity(this, NOTIFICATION_ID, Intent(this, AboutActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.module_icon_foreground)
            .setContentTitle("ShadeReply 有新版本")
            .setContentText("${release.tag} 可用，点击查看更新")
            .setContentIntent(openAbout)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
        prefs.edit().putString(LAST_ALERT, version).apply()
    }

    companion object {
        private const val TAG = "ShadeReplyUpdates"
        private const val JOB_ID = 2048
        private const val NOTIFICATION_ID = 2
        private const val CHANNEL = "available_updates"
        private const val ENABLED = "enabled"
        private const val LAST_ALERT = "last_alert"

        private fun preferences(context: Context) = context.getSharedPreferences("automatic_updates", MODE_PRIVATE)

        fun isEnabled(context: Context): Boolean = preferences(context).getBoolean(ENABLED, true)

        fun notificationsEnabled(context: Context): Boolean {
            val manager = context.getSystemService(NotificationManager::class.java)
            return manager.areNotificationsEnabled()
                && manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
        }

        fun setEnabled(context: Context, enabled: Boolean) {
            preferences(context).edit().putBoolean(ENABLED, enabled).apply()
            if (enabled) {
                ensureScheduled(context)
            } else {
                context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
                context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
            }
        }

        @JvmStatic
        fun ensureScheduled(context: Context) {
            if (!isEnabled(context)) return
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "版本更新", NotificationManager.IMPORTANCE_DEFAULT))
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, AutoUpdateCheckService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(TimeUnit.DAYS.toMillis(1))
                .setPersisted(true)
                .build()
            if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) {
                Log.w(TAG, "Could not schedule automatic update checks")
            }
        }

        fun notificationSettings(context: Context): Intent {
            val manager = context.getSystemService(NotificationManager::class.java)
            val action = if (manager.areNotificationsEnabled()) Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS
                else Settings.ACTION_APP_NOTIFICATION_SETTINGS
            return Intent(action)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL)
        }

        fun needsNotificationPermission(context: Context) =
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    }
}
