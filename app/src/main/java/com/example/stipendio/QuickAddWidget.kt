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
                val salary = store.actual()[month] ?: effectiveSalary(store.salaryHist(), month)
                val extraIncome = store.incomes(month).sumOf { it.amount }
                val base = salary + extraIncome
                val remainingPct = if (base > 0.0) {
                    ((amount / base) * 100.0).toInt().coerceIn(0, 100)
                } else 0

                val formatted = NumberFormat
                    .getCurrencyInstance(Locale.ITALY)
                    .format(amount)

                views.setTextViewText(R.id.amount, formatted)
                views.setTextViewText(R.id.widget_subtitle, "Stipendio rimanente")

                // Static ring: no animation. Color progresses blue -> green -> red.
                val ringStep = ((remainingPct + 5) / 10).coerceIn(0, 10) * 10
                val ringRes = when (ringStep) {
                    0 -> R.drawable.widget_ring_0
                    10 -> R.drawable.widget_ring_10
                    20 -> R.drawable.widget_ring_20
                    30 -> R.drawable.widget_ring_30
                    40 -> R.drawable.widget_ring_40
                    50 -> R.drawable.widget_ring_50
                    60 -> R.drawable.widget_ring_60
                    70 -> R.drawable.widget_ring_70
                    80 -> R.drawable.widget_ring_80
                    90 -> R.drawable.widget_ring_90
                    else -> R.drawable.widget_ring_100
                }
                val percentColor = when (ringStep) {
                    0 -> "#1565C0"
                    10 -> "#1685C7"
                    20 -> "#169FC3"
                    30 -> "#16B38E"
                    40 -> "#16A34A"
                    50 -> "#2EAD43"
                    60 -> "#76B82A"
                    70 -> "#C0A52A"
                    80 -> "#E58A20"
                    90 -> "#E35A28"
                    else -> "#D92D2D"
                }
                views.setImageViewResource(R.id.widget_progress, ringRes)
                views.setTextViewText(R.id.widget_percent, "$remainingPct%")
                views.setTextColor(R.id.widget_percent, android.graphics.Color.parseColor(percentColor))
            } catch (_: Exception) {
                // Never let a malformed/old preference block the widget from loading.
                views.setTextViewText(R.id.amount, "Apri l'app")
                views.setTextViewText(R.id.widget_subtitle, "Tocca per aggiornare")
            }

            manager.updateAppWidget(ids, views)
        }
    }
}
