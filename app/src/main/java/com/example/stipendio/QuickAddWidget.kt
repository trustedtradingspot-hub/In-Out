package com.example.stipendio

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.text.NumberFormat
import java.util.Locale

class QuickAddWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = refresh(ctx)

    companion object {
        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, QuickAddWidget::class.java))
            if (ids.isEmpty()) return
            val store = Store(ctx)
            val left = store.remaining(currentCycle(store.startDay()).toString())
            val intent = Intent(ctx, MainActivity::class.java)
                .putExtra("quick_add", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pi = PendingIntent.getActivity(ctx, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val v = RemoteViews(ctx.packageName, R.layout.widget_quick_add)
            v.setTextViewText(R.id.amount, NumberFormat.getCurrencyInstance(Locale.ITALY).format(left))
            v.setOnClickPendingIntent(R.id.root, pi)
            mgr.updateAppWidget(ids, v)
        }
    }
}
