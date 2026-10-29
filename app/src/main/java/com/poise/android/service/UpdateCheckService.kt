package com.poise.android.service

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
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.poise.android.AppLanguage
import com.poise.android.R
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Once a day, asks GitHub for Poise's latest release and, when it's newer than this build, posts a
 * notification that opens the release page. Sends nothing about the user; reads only the tag.
 */
class UpdateCheckService : JobService() {

    companion object {
        private const val JOB_ID = 1
        private const val CHANNEL_ID = "poise_updates"
        private const val NOTIFICATION_ID = 2 // the session's is 1
        private const val LATEST = "https://api.github.com/repos/chabandou/poise-android/releases/latest"
        private const val RELEASE_PAGE = "https://github.com/chabandou/poise-android/releases/latest"

        /** Starts the daily check, or stops it when [on] is false. Kept across reboots. */
        fun schedule(context: Context, on: Boolean) {
            // Never worth a crash: at worst no update notifications
            runCatching { scheduleOrThrow(context, on) }.onFailure { Log.w("UpdateCheck", "Not scheduled", it) }
        }

        private fun scheduleOrThrow(context: Context, on: Boolean) {
            val jobs = context.getSystemService(JobScheduler::class.java)
            if (!on) return jobs.cancel(JOB_ID)
            if (jobs.getPendingJob(JOB_ID) != null) return
            jobs.schedule(
                    JobInfo.Builder(JOB_ID, ComponentName(context, UpdateCheckService::class.java))
                            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                            .setPeriodic(TimeUnit.DAYS.toMillis(1))
                            .setPersisted(true)
                            .build()
            )
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onStartJob(params: JobParameters): Boolean {
        thread(name = "update-check") {
            // Offline or GitHub down: Android tries again later
            jobFinished(params, runCatching { check() }.isFailure)
        }
        return true
    }

    override fun onStopJob(params: JobParameters) = true

    private fun check() {
        val connection = URL(LATEST).openConnection() as HttpURLConnection
        val body = try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            check(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
        val tag = JSONObject(body).getString("tag_name")
        val current = packageManager.getPackageInfo(packageName, 0).versionName ?: return
        val prefs = getSharedPreferences("updates", MODE_PRIVATE)
        // Once per release, so a dismissed notification stays dismissed
        if (!isNewer(tag, current) || prefs.getString("notified", null) == tag) return
        notify(tag.removePrefix("v"))
        prefs.edit().putString("notified", tag).apply()
    }

    private fun notify(version: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.channel_updates), NotificationManager.IMPORTANCE_LOW).apply {
                    description = getString(R.string.channel_updates_note)
                }
        )
        val open = PendingIntent.getActivity(
                this, 0, Intent(Intent.ACTION_VIEW, Uri.parse(RELEASE_PAGE)), PendingIntent.FLAG_IMMUTABLE
        )
        manager.notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(this, CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_wave)
                        .setColor(ContextCompat.getColor(this, R.color.poise_cyan))
                        .setContentTitle(getString(R.string.update_title, version))
                        .setContentText(getString(R.string.update_text))
                        .setContentIntent(open)
                        .setAutoCancel(true)
                        .build()
        )
    }
}

/** True when [tag] (v1.2.0) is a later version than [current] (1.1.3). False if either isn't one. */
internal fun isNewer(tag: String, current: String): Boolean {
    fun parts(v: String): List<Int>? = v.removePrefix("v").split('.').map { it.toIntOrNull() ?: return null }
    val a = parts(tag) ?: return false
    val b = parts(current) ?: return false
    for (i in 0 until maxOf(a.size, b.size)) {
        val d = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
        if (d != 0) return d > 0
    }
    return false
}
