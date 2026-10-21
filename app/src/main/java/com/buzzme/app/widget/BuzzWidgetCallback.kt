package com.buzzme.app.widget

import android.content.Context
import android.content.Intent

object BuzzWidgetCallback {
    fun createBuzzIntent(context: Context, appWidgetId: Int, targetId: String, targetLabel: String): Intent {
        return Intent(context, BuzzWidgetReceiver::class.java).apply {
            action = BuzzWidgetReceiver.ACTION_WIDGET_BUZZ
            putExtra("appWidgetId", appWidgetId)
            putExtra(BuzzWidgetReceiver.EXTRA_TARGET_ID, targetId)
            putExtra(BuzzWidgetReceiver.EXTRA_TARGET_LABEL, targetLabel)
        }
    }
}
