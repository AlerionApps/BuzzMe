package com.buzzme.app.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.buzzme.app.BuzzMeApp

class WidgetConfigureActivity : ComponentActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)

        val intent = intent
        val extras = intent.extras
        if (extras != null) {
            appWidgetId = extras.getInt(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID
            )
        }

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        // Validate AppWidget provider and handle transient null return
        val appWidgetManager = AppWidgetManager.getInstance(this)
        var widgetInfo = appWidgetManager.getAppWidgetInfo(appWidgetId)
        var retries = 0
        while (widgetInfo == null && retries < 3) {
            try {
                Thread.sleep(150)
            } catch (e: InterruptedException) {
                // Ignore
            }
            widgetInfo = appWidgetManager.getAppWidgetInfo(appWidgetId)
            retries++
        }

        if (widgetInfo != null) {
            val provider = widgetInfo.provider
            if (provider.packageName != packageName || provider.className != BuzzWidgetReceiver::class.java.name) {
                android.util.Log.e("BuzzMe", "WidgetConfigureActivity: Provider mismatch (${provider.packageName}/${provider.className})")
                finish()
                return
            }
        }

        val app = application as BuzzMeApp
        val repo = app.deviceRepository

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val peers by repo.peers.collectAsState()
                    val subs by repo.subscriptions.collectAsState()

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "Add Family Buzz Widget",
                            style = MaterialTheme.typography.headlineMedium
                        )
                        Text(
                            text = "Select a family member for this widget:",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )

                        if (peers.isEmpty() && subs.isEmpty()) {
                            Text(
                                text = "No family devices paired yet. Please pair a device in the BuzzMe app first.",
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 16.dp)
                            )
                        } else {
                            LazyColumn(modifier = Modifier.weight(1f)) {
                                items(peers) { peer ->
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp)
                                            .clickable {
                                                selectTarget(peer.keyId, peer.label)
                                            }
                                    ) {
                                        Column(modifier = Modifier.padding(16.dp)) {
                                            Text(peer.label, style = MaterialTheme.typography.titleLarge)
                                        }
                                    }
                                }

                                items(subs) { sub ->
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp)
                                            .clickable {
                                                selectTarget(sub.id, sub.label)
                                            }
                                    ) {
                                        Column(modifier = Modifier.padding(16.dp)) {
                                            Text(sub.label, style = MaterialTheme.typography.titleLarge)
                                        }
                                    }
                                }
                            }
                        }

                        Button(
                            onClick = { finish() },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        ) {
                            Text("Cancel")
                        }
                    }
                }
            }
        }
    }

    private fun selectTarget(targetId: String, targetLabel: String) {
        val prefs = getSharedPreferences("buzz_widget_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("widget_${appWidgetId}_target_id", targetId)
            .putString("widget_${appWidgetId}_label", targetLabel)
            .apply()

        val appWidgetManager = AppWidgetManager.getInstance(this)
        BuzzWidget.updateWidget(this, appWidgetManager, appWidgetId)

        val resultValue = Intent().apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        setResult(RESULT_OK, resultValue)
        finish()
    }
}
