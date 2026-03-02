package com.orbixtv.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.orbixtv.app.MainActivity
import com.orbixtv.app.R
import com.orbixtv.app.data.ChannelRepository

class OrbixTvWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (widgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, widgetId)
        }
    }

    companion object {
        fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            widgetId: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_orbixtv)

            try {
                val repo = ChannelRepository.getInstance(context)
                val recent = repo.getRecentChannels()
                val lastChannel = recent.firstOrNull()

                if (lastChannel != null) {
                    views.setTextViewText(R.id.widget_channel_name, lastChannel.name)
                    views.setTextViewText(R.id.widget_subtitle, lastChannel.group)
                } else {
                    views.setTextViewText(R.id.widget_channel_name, "OrbixTV")
                    views.setTextViewText(
                        R.id.widget_subtitle,
                        context.getString(R.string.recent_empty_title)
                    )
                }
            } catch (e: Exception) {
                views.setTextViewText(R.id.widget_channel_name, "OrbixTV")
                views.setTextViewText(R.id.widget_subtitle, "")
            }

            val intent = Intent(context, MainActivity::class.java)
            val pi = PendingIntent.getActivity(
                context, widgetId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_play_btn, pi)
            views.setOnClickPendingIntent(R.id.widget_channel_name, pi)

            appWidgetManager.updateAppWidget(widgetId, views)
        }
    }
}
