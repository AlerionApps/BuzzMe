package com.buzzme.app.ui

import android.Manifest
import com.buzzme.app.R
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.buzzme.app.ui.screens.DashboardScreen
import com.buzzme.app.ui.screens.DeviceManageScreen
import com.buzzme.app.ui.screens.DeviceNameSetupDialog
import com.buzzme.app.ui.screens.PairingDialog
import com.buzzme.app.ui.theme.BuzzMeTheme

enum class AppScreen {
    DASHBOARD,
    DEVICE_MANAGE
}

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Handle permissions granted/denied
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            val appIcon = packageManager.getApplicationIcon(packageName)
            android.util.Log.i("BuzzMeIcon", "ApplicationIcon class: ${appIcon.javaClass.name}")
            val bmp = android.graphics.Bitmap.createBitmap(192, 192, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            appIcon.setBounds(0, 0, 192, 192)
            appIcon.draw(canvas)
            val outDir = getExternalFilesDir(null) ?: filesDir
            java.io.FileOutputStream(java.io.File(outDir, "test_app_icon.png")).use {
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            android.util.Log.i("BuzzMeIcon", "Saved test_app_icon.png to ${outDir.absolutePath}")

            val small = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_notification_small)
            if (small != null) {
                val bmp2 = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
                val c2 = android.graphics.Canvas(bmp2)
                small.setBounds(0, 0, 96, 96)
                small.draw(c2)
                java.io.FileOutputStream(java.io.File(outDir, "test_small_icon.png")).use {
                    bmp2.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                android.util.Log.i("BuzzMeIcon", "Saved test_small_icon.png to ${outDir.absolutePath}")
            }
        } catch (e: Exception) {
            android.util.Log.e("BuzzMeIcon", "Error loading icon", e)
        }

        checkAndRequestPermissions()
        handleIntent(intent)

        setContent {
            BuzzMeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var currentScreen by remember { mutableStateOf(AppScreen.DASHBOARD) }
                    var showPairingDialog by remember { mutableStateOf(false) }
                    val deviceName by viewModel.deviceName.collectAsState()
                    val pendingDeepLink by viewModel.pendingDeepLinkPairing.collectAsState()
                    val lastReceivedBuzz by viewModel.lastReceivedBuzz.collectAsState()
                    var showReceiverAlertModal by remember { mutableStateOf(false) }

                    LaunchedEffect(lastReceivedBuzz) {
                        if (lastReceivedBuzz != null) {
                            showReceiverAlertModal = true
                        }
                    }

                    when (currentScreen) {
                        AppScreen.DASHBOARD -> {
                            DashboardScreen(
                                viewModel = viewModel,
                                onOpenPairing = { showPairingDialog = true },
                                onOpenDeviceManage = { currentScreen = AppScreen.DEVICE_MANAGE }
                            )
                        }
                        AppScreen.DEVICE_MANAGE -> {
                            DeviceManageScreen(
                                viewModel = viewModel,
                                onBack = { currentScreen = AppScreen.DASHBOARD }
                            )
                        }
                    }

                    if (showPairingDialog) {
                        PairingDialog(
                            viewModel = viewModel,
                            onDismiss = { showPairingDialog = false }
                        )
                    }

                    pendingDeepLink?.let { deepLink ->
                        AlertDialog(
                            onDismissRequest = { viewModel.dismissPendingDeepLink() },
                            title = { Text("Pair with Device") },
                            text = {
                                Text("Do you want to pair this device with \"${deepLink.hostPayload.hostDeviceName ?: "Host"}\"?")
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        val myName = viewModel.deviceName.value?.ifBlank { null } ?: "Android Peer"
                                        viewModel.executePeerHandshakeFromQr(deepLink.rawUrl, myName)
                                        viewModel.dismissPendingDeepLink()
                                    }
                                ) {
                                    Text("Connect & Pair")
                                }
                            },
                            dismissButton = {
                                OutlinedButton(
                                    onClick = { viewModel.dismissPendingDeepLink() }
                                ) {
                                    Text("Cancel")
                                }
                            }
                        )
                    }

                    if (showReceiverAlertModal && lastReceivedBuzz != null) {
                        val buzz = lastReceivedBuzz!!
                        androidx.compose.ui.window.Dialog(
                            onDismissRequest = {
                                com.buzzme.app.service.AlertEngine.stopVibration(this@MainActivity)
                                showReceiverAlertModal = false
                            },
                            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
                        ) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth(0.92f)
                                    .wrapContentHeight(),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                                color = MaterialTheme.colorScheme.surface,
                                tonalElevation = 8.dp
                            ) {
                                androidx.compose.foundation.layout.Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(24.dp),
                                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                                ) {
                                    androidx.compose.material3.Text(
                                        text = "🚨 Incoming Buzz",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                        color = androidx.compose.ui.graphics.Color(0xFFEF4444)
                                    )
                                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(12.dp))
                                    androidx.compose.material3.Text(
                                        text = "From: ${buzz.senderLabel}",
                                        style = MaterialTheme.typography.headlineSmall,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                    )
                                    if (buzz.message.isNotBlank() && buzz.message != "Buzz!") {
                                        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(8.dp))
                                        androidx.compose.material3.Text(
                                            text = "\"${buzz.message}\"",
                                            style = MaterialTheme.typography.bodyLarge,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(24.dp))
                                    Button(
                                        onClick = {
                                            com.buzzme.app.service.AlertEngine.stopVibration(this@MainActivity)
                                            showReceiverAlertModal = false
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(60.dp),
                                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                            containerColor = androidx.compose.ui.graphics.Color(0xFF10B981)
                                        ),
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
                                    ) {
                                        androidx.compose.material3.Text(
                                            text = "Got it / I'm coming",
                                            fontSize = 18.sp,
                                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                            color = androidx.compose.ui.graphics.Color.White
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (deviceName.isNullOrBlank()) {
                        DeviceNameSetupDialog(
                            suggestedName = viewModel.getSuggestedDeviceName(),
                            onDismiss = null, // Undismissable on first launch
                            onSave = { newName ->
                                viewModel.setDeviceName(newName)
                            }
                        )
                    }
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.CAMERA)
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private var isTestingAlert = false

    override fun onResume() {
        super.onResume()
        if (!isTestingAlert) {
            com.buzzme.app.service.AlertEngine.stopVibration(this)
        }
        isTestingAlert = false
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: android.content.Intent?) {
        android.util.Log.i("BuzzMe", "MainActivity handleIntent called with intent=$intent")
        val senderKey = intent?.getStringExtra("sender_key")
        if (senderKey != null) {
            com.buzzme.app.service.AlertEngine.resetEscalation(senderKey)
            com.buzzme.app.service.AlertEngine.stopVibration(this)
        }
        val buzzMessage = intent?.getStringExtra("buzz_message")
        val senderLabel = intent?.getStringExtra("sender_label")
        if (!buzzMessage.isNullOrBlank() && !senderLabel.isNullOrBlank()) {
            viewModel.setLastReceivedBuzz(senderLabel, buzzMessage)
        }
        val qrJson = intent?.getStringExtra("pairQrBase64")?.let {
            try {
                String(android.util.Base64.decode(it, android.util.Base64.DEFAULT), java.nio.charset.StandardCharsets.UTF_8)
            } catch (e: Exception) {
                android.util.Log.e("BuzzMe", "Failed to decode pairQrBase64", e)
                null
            }
        } ?: intent?.getStringExtra("pairQr")

        android.util.Log.i("BuzzMe", "MainActivity resolved qrJson: $qrJson")
        if (intent?.getBooleanExtra("testAlert", false) == true) {
            intent.removeExtra("testAlert")
            isTestingAlert = true
            viewModel.triggerTestAlert()
        }
        val dataUri = intent?.data
        if (dataUri != null) {
            val urlString = dataUri.toString()
            if ((dataUri.scheme == "https" && dataUri.host == "buzzme.app" && dataUri.path?.startsWith("/pair") == true) ||
                (dataUri.scheme == "buzzme" && dataUri.host == "pair")) {
                android.util.Log.i("BuzzMe", "MainActivity received deep link: $urlString")
                viewModel.handleIncomingDeepLink(urlString)
            }
        }

        if (qrJson != null) {
            val label = intent?.getStringExtra("peerLabel") ?: "Pixel 7 Peer"
            viewModel.executePeerHandshakeFromQr(qrJson, label)
        }
    }
}
