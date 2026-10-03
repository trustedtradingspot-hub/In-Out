package com.example.stipendio

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

class QuickAddWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        update(context, manager, appWidgetIds)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(
            android.content.ComponentName(context, QuickAddWidget::class.java)
        )
        update(context, manager, ids)
    }

    companion object {
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                android.content.ComponentName(context, QuickAddWidget::class.java)
            )
            if (ids.isNotEmpty()) update(context, manager, ids)
        }

        private fun update(
            context: Context,
            manager: AppWidgetManager,
            ids: IntArray
        ) {
            if (ids.isEmpty()) return

            val views = RemoteViews(context.packageName, R.layout.widget_quick_add)

            val openIntent = Intent(context, MainActivity::class.java).apply {
                putExtra("quick_add", true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pending = PendingIntent.getActivity(
                context,
                1001,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.root, pending)

            try {
                val store = Store(context.applicationContext)
                val day = store.startDay().coerceIn(1, 28)
                val today = LocalDate.now()
                val month = if (today.dayOfMonth >= day) {
                    YearMonth.from(today)
                } else {
                    YearMonth.from(today).minusMonths(1)
                }.toString()

                val amount = store.remaining(month)
                val formatted = NumberFormat
                    .getCurrencyInstance(Locale.ITALY)
                    .format(amount)

                views.setTextViewText(R.id.amount, formatted)
                views.setTextViewText(R.id.widget_subtitle, "Disponibilità")
            } catch (_: Exception) {
                // Never let a malformed/old preference block the widget from loading.
                views.setTextViewText(R.id.amount, "Apri l'app")
                views.setTextViewText(R.id.widget_subtitle, "Tocca per aggiornare")
            }

            manager.updateAppWidget(ids, views)
        }
    }
}
