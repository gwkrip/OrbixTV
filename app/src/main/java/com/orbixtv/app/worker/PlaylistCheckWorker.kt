package com.orbixtv.app.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.orbixtv.app.MainActivity
import com.orbixtv.app.R
import com.orbixtv.app.data.ChannelRepository

class PlaylistCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val WORK_NAME  = "playlist_check"
        const val CHANNEL_ID = "orbixtv_playlist"
        const val NOTIF_ID   = 1001
    }

    override suspend fun doWork(): Result {
        val repo = ChannelRepository.getInstance(applicationContext)
        if (repo.getPlaylistUrl().isEmpty()) return Result.success()

        val reachable = repo.isPlaylistUrlReachable()
        if (!reachable) sendNotification()

        return Result.success()
    }

    private fun sendNotification() {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.notif_channel_playlist_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = applicationContext.getString(R.string.notif_channel_playlist_desc)
            }
            nm.createNotificationChannel(ch)
        }

        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pi = PendingIntent.getActivity(
            applicationContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_signal_cellular_off)
            .setContentTitle(applicationContext.getString(R.string.notif_playlist_title))
            .setContentText(applicationContext.getString(R.string.notif_playlist_text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        nm.notify(NOTIF_ID, notif)
    }
}
