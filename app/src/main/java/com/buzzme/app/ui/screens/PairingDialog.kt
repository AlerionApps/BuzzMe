package com.buzzme.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.buzzme.app.data.model.PairingState
import com.buzzme.app.data.model.PairingType
import com.buzzme.app.ui.MainViewModel
import com.buzzme.app.ui.components.CameraQrScanner
import com.buzzme.app.ui.components.QrCodeView
import com.buzzme.app.ui.components.SystemQrScanner
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class PairingFlowMode {
    MENU,
    IOS_SHORTCUT_SETUP,
    IOS_PWA_CAMERA_SCAN,
    ANDROID_SHOW_QR,
    ANDROID_SCAN_QR,
    PAIRING_RESULT
}

@Composable
fun PairingDialog(
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(PairingFlowMode.MENU) }
    var deviceLabel by remember { mutableStateOf("") }
    var pairingResultSuccess by remember { mutableStateOf<Boolean?>(null) }
    var pairingResultMessage by remember { mutableStateOf("") }
    val activePairing by viewModel.activePairing.collectAsState()
    val wifiIp by viewModel.wifiIpAddress.collectAsState()

    Dialog(
        onDismissRequest = {
            viewModel.dismissPairingDialog()
            onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.92f),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.background
        ) {
            when (mode) {
                PairingFlowMode.MENU -> {
                    PairingMenuContent(
                        viewModel = viewModel,
                        onJoinHost = { targetHost ->
                            val myDeviceName = viewModel.deviceName.value?.ifBlank { null } ?: viewModel.getSuggestedDeviceName()
                            pairingResultSuccess = null
                            pairingResultMessage = "Sending join request to ${targetHost.deviceName}..."
                            mode = PairingFlowMode.PAIRING_RESULT
                            viewModel.sendJoinRequest(targetHost, myDeviceName) { success, msg ->
                                pairingResultSuccess = success
                                pairingResultMessage = msg
                            }
                        },
                        onSelectIosShortcut = {
                            deviceLabel = "Dave's iPhone"
                            mode = PairingFlowMode.IOS_SHORTCUT_SETUP
                        },
                        onSelectIosPwa = {
                            if (SystemQrScanner.isAvailable(context)) {
                                SystemQrScanner.startScan(
                                    context = context,
                                    onSuccess = { qrText ->
                                        val success = viewModel.enrollScannedIosPwa(qrText)
                                        if (success) {
                                            onDismiss()
                                        } else {
                                            mode = PairingFlowMode.MENU
                                        }
                                    },
                                    onCanceled = { mode = PairingFlowMode.MENU },
                                    onFailure = {
                                        mode = PairingFlowMode.IOS_PWA_CAMERA_SCAN
                                    }
                                )
                            } else {
                                mode = PairingFlowMode.IOS_PWA_CAMERA_SCAN
                            }
                        },
                        onSelectAndroidHost = {
                            val isPrimary = viewModel.deviceRepository.isPrimaryHost
                            if (!isPrimary) {
                                viewModel.requestDesignateAsHost(
                                    onBlocked = { hostName, _ ->
                                        pairingResultSuccess = false
                                        pairingResultMessage = "Cannot host: '$hostName' is already the Household Host. Only one host is permitted on this network."
                                        mode = PairingFlowMode.PAIRING_RESULT
                                    },
                                    onSuccess = {
                                        val myName = viewModel.deviceName.value?.ifBlank { null } ?: "Household Host"
                                        deviceLabel = myName
                                        viewModel.startAndroidPairing(myName)
                                        mode = PairingFlowMode.ANDROID_SHOW_QR
                                    }
                                )
                            } else {
                                val myName = viewModel.deviceName.value?.ifBlank { null } ?: "Household Host"
                                deviceLabel = myName
                                viewModel.startAndroidPairing(myName)
                                mode = PairingFlowMode.ANDROID_SHOW_QR
                            }
                        },
                        onSelectAndroidPeer = {
                            val myDeviceName = viewModel.deviceName.value?.ifBlank { null } ?: "Android Peer"
                            if (SystemQrScanner.isAvailable(context)) {
                                SystemQrScanner.startScan(
                                    context = context,
                                    onSuccess = { qrText ->
                                        pairingResultSuccess = null
                                        pairingResultMessage = "Connecting to device..."
                                        mode = PairingFlowMode.PAIRING_RESULT
                                        viewModel.executePeerHandshakeFromQr(qrText, myDeviceName) { success, msg ->
                                            pairingResultSuccess = success
                                            pairingResultMessage = msg
                                        }
                                    },
                                    onCanceled = { mode = PairingFlowMode.MENU },
                                    onFailure = { err ->
                                        android.util.Log.w("PairingDialog", "Google Code Scanner unavailable or failed: ${err.message}")
                                        mode = PairingFlowMode.ANDROID_SCAN_QR
                                    }
                                )
                            } else {
                                mode = PairingFlowMode.ANDROID_SCAN_QR
                            }
                        },
                        onClose = onDismiss
                    )
                }

                PairingFlowMode.IOS_SHORTCUT_SETUP -> {
                    IosShortcutSetupContent(
                        viewModel = viewModel,
                        deviceLabel = deviceLabel,
                        wifiIp = wifiIp ?: "192.168.1.100",
                        onLabelChange = { deviceLabel = it },
                        onBack = {
                            viewModel.dismissPairingDialog()
                            mode = PairingFlowMode.MENU
                        }
                    )
                }

                PairingFlowMode.IOS_PWA_CAMERA_SCAN -> {
                    IosPwaCameraScanContent(
                        onScanComplete = { qrText ->
                            val success = viewModel.enrollScannedIosPwa(qrText)
                            if (success) {
                                onDismiss()
                            } else {
                                mode = PairingFlowMode.MENU
                            }
                        },
                        onCancel = { mode = PairingFlowMode.MENU }
                    )
                }

                PairingFlowMode.ANDROID_SHOW_QR -> {
                    AndroidHostQrContent(
                        viewModel = viewModel,
                        wifiIp = wifiIp ?: "192.168.1.100",
                        onBack = {
                            viewModel.dismissPairingDialog()
                            mode = PairingFlowMode.MENU
                        }
                    )
                }

                PairingFlowMode.ANDROID_SCAN_QR -> {
                    AndroidPeerScanContent(
                        onScanComplete = { qrText ->
                            val myDeviceName = viewModel.deviceName.value?.ifBlank { null } ?: "Android Peer"
                            pairingResultSuccess = null
                            pairingResultMessage = "Connecting to device..."
                            mode = PairingFlowMode.PAIRING_RESULT
                            viewModel.executePeerHandshakeFromQr(qrText, myDeviceName) { success, msg ->
                                pairingResultSuccess = success
                                pairingResultMessage = msg
                            }
                        },
                        onCancel = { mode = PairingFlowMode.MENU }
                    )
                }

                PairingFlowMode.PAIRING_RESULT -> {
                    PairingResultContent(
                        success = pairingResultSuccess,
                        message = pairingResultMessage,
                        onDone = onDismiss,
                        onRetry = { mode = PairingFlowMode.MENU }
                    )
                }
            }
        }
    }
}

@Composable
fun PairingMenuContent(
    viewModel: MainViewModel,
    onJoinHost: (com.buzzme.app.network.DiscoveredHost) -> Unit,
    onSelectIosShortcut: () -> Unit,
    onSelectIosPwa: () -> Unit,
    onSelectAndroidHost: () -> Unit,
    onSelectAndroidPeer: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val isPrimaryHost by viewModel.isPrimaryHost.collectAsState()
    val discoveredHost by viewModel.discoveredHost.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val wifiIp by viewModel.wifiIpAddress.collectAsState()
    val deviceName by viewModel.deviceName.collectAsState()
    val hostName = deviceName?.ifBlank { "Household Host" } ?: "Household Host"
    val peers by viewModel.peers.collectAsState()
    val hostPeer = peers.find { it.targetScope == "default" && !it.keyId.startsWith("relay-") }

    LaunchedEffect(Unit) {
        viewModel.startDiscovery()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Zero-Scan Household Join", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (isPrimaryHost) {
            // Primary Host Status Banner
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFD1FAE5))
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("👑", fontSize = 24.sp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Household Host: $hostName",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF065F46)
                            )
                            Text(
                                text = "Local IP: ${wifiIp ?: "Waiting for Wi-Fi..."}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF047857)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "This device coordinates buzzes and directory sync for all household devices.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF065F46)
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        } else {
            // Auto-Discovered Host Card (Zero-Scan) for Member devices
            if (discoveredHost != null) {
                val host = discoveredHost!!
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFD1FAE5))
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("👑", fontSize = 24.sp)
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Host Found: ${host.deviceName}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF065F46)
                                )
                                Text(
                                    text = "Local IP: ${host.ipAddress}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF047857)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Button(
                            onClick = { onJoinHost(host) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Join ${host.deviceName}'s Household", fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            } else if (hostPeer != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE0F2FE))
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🔗", fontSize = 24.sp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Joined to ${hostPeer.label}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF0369A1)
                            )
                            Text(
                                text = "Host IP: ${hostPeer.hostIp ?: "Auto-syncing"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF0284C7)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            } else if (isScanning) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(14.dp))
                        Text(
                            "Searching for Household Host on Wi-Fi...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "No Household Host Detected",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "If this is the main household device, designate it as the Host below so other family members can connect.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = onSelectAndroidHost,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Make This Device Household Host")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        // iPhone Mobile Web Card
        val targetHostIp = if (isPrimaryHost) wifiIp else (discoveredHost?.ipAddress ?: hostPeer?.hostIp)
        val targetHostLabel = if (isPrimaryHost) hostName else (discoveredHost?.deviceName ?: hostPeer?.label ?: "Household Host")

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = "iPhone / Web Device Access",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (targetHostIp != null) {
                        "Open Safari on iPhone and visit:\nhttp://buzzme.local:8900  or  http://$targetHostIp:8900"
                    } else {
                        "Open Safari on iPhone and visit:\nhttp://buzzme.local:8900"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                OutlinedButton(
                    onClick = {
                        val inviteUrl = "http://${targetHostIp ?: "buzzme.local"}:8900"
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "Join BuzzMe Household")
                            putExtra(Intent.EXTRA_TEXT, "Open Safari to join BuzzMe Household ($targetHostLabel): $inviteUrl")
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Share Household Invite"))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Share, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Share 1-Tap Invite Link")
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "Other Options",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.align(Alignment.Start)
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (isPrimaryHost) {
            PairingOptionCard(
                title = "Android Host (Show QR)",
                subtitle = "Display coordinator QR code for manual scanning.",
                icon = Icons.Default.QrCode,
                onClick = onSelectAndroidHost
            )
            Spacer(modifier = Modifier.height(10.dp))
            PairingOptionCard(
                title = "iOS Outbound Shortcut",
                subtitle = "Two-stage confirmed setup for iOS Shortcut integration.",
                icon = Icons.Default.PhoneIphone,
                onClick = onSelectIosShortcut
            )
        } else {
            PairingOptionCard(
                title = "Android Peer (Scan QR)",
                subtitle = "Scan coordinator QR code manually using camera.",
                icon = Icons.Default.CameraAlt,
                onClick = onSelectAndroidPeer
            )
            if (discoveredHost == null && hostPeer == null) {
                Spacer(modifier = Modifier.height(10.dp))
                PairingOptionCard(
                    title = "Android Host (Show QR)",
                    subtitle = "Set up this device as the Household Coordinator.",
                    icon = Icons.Default.QrCode,
                    onClick = onSelectAndroidHost
                )
            }
        }
    }
}

@Composable
fun PairingOptionCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
fun IosShortcutSetupContent(
    viewModel: MainViewModel,
    deviceLabel: String,
    wifiIp: String,
    onLabelChange: (String) -> Unit,
    onBack: () -> Unit
) {
    val activePairing by viewModel.activePairing.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
            Text("Two-Stage iOS Shortcut Setup", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (activePairing == null) {
            OutlinedTextField(
                value = deviceLabel,
                onValueChange = onLabelChange,
                label = { Text("Device / User Name") },
                placeholder = { Text("e.g. Dave's iPhone") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = { viewModel.startShortcutPairing(deviceLabel) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Generate Setup QR")
            }
        } else {
            val session = activePairing!!
            val setupUrl = "http://$wifiIp:8900/api/v1/setup-shortcut?pairingId=${session.pairingId}&nonce=${session.pairingNonce}"

            Text(
                "1. iPhone: Scan QR Code with Camera",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "The setup QR contains only pairingId and nonce. Zero tokens are transmitted in URLs or QR codes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))
            QrCodeView(content = setupUrl, size = 200.dp)
            Spacer(modifier = Modifier.height(12.dp))

            Text("Verification Code:", style = MaterialTheme.typography.labelMedium)
            Text(
                text = session.verificationCode,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 4.sp,
                color = Color(0xFFF59E0B)
            )

            Spacer(modifier = Modifier.height(8.dp))

            val state = session.state
            if (state == PairingState.PENDING) {
                Text(
                    "Compare code above with iPhone Safari screen, then tap Approve:",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { viewModel.rejectPairing(session.pairingId) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Reject")
                    }
                    Button(
                        onClick = { viewModel.approvePairing(session.pairingId) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Approve")
                    }
                }
            } else if (state == PairingState.APPROVED_PENDING_CLAIM) {
                Text(
                    "Approved! Now tap 'Confirm & Install' on iPhone to receive capability token.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF10B981),
                    textAlign = TextAlign.Center
                )
            } else if (state == PairingState.CONSUMED) {
                Text(
                    "Pairing Complete! Capability token issued to Shortcut.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF10B981),
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = onBack) {
                    Text("Done")
                }
            }
        }
    }
}

@Composable
fun IosPwaCameraScanContent(
    onScanComplete: (String) -> Unit,
    onCancel: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        CameraQrScanner(
            onBarcodeScanned = onScanComplete,
            onClose = onCancel,
            timeoutSeconds = 120
        )
    }
}

@Composable
fun AndroidHostQrContent(
    viewModel: MainViewModel,
    wifiIp: String,
    onBack: () -> Unit
) {
    val activePairing by viewModel.activePairing.collectAsState()

    LaunchedEffect(activePairing) {
        if (activePairing == null || activePairing?.state == PairingState.EXPIRED) {
            val myName = viewModel.deviceName.value?.ifBlank { null } ?: "Household Host"
            viewModel.startAndroidPairing(myName)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
            Text("Android Host Coordinator", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(12.dp))

        val isEmulatorIp = wifiIp.startsWith("10.0.2.")
        if (isEmulatorIp) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF3C7)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFD97706))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Emulator Virtual IP ($wifiIp): Outside physical devices cannot connect to an emulator host directly. To pair with your physical phone, tap 'Android Host (Show QR)' on your physical phone and scan it with this emulator.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF92400E)
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        } else if (wifiIp.isBlank() || wifiIp == "192.168.1.100") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF3C7)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFD97706))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Wi-Fi IP not confirmed ($wifiIp). Ensure both devices are connected to the same Wi-Fi network.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF92400E)
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        if (activePairing != null) {
            val session = activePairing!!
            val hostQrData = MainViewModel.HostQrPayload(
                hostIp = wifiIp,
                port = 8900,
                pairingId = session.pairingId,
                nonce = session.pairingNonce,
                hostPubKey = session.hostEphemeralPublicKeyBytes ?: "",
                hostDeviceId = viewModel.deviceRepository.hostDeviceId.toString(),
                hostDeviceName = viewModel.deviceName.value?.ifBlank { "Host" } ?: "Host"
            )
            val qrContent = hostQrData.toDeepLinkUrl()

            Text(
                "Scan with BuzzMe or your phone's Camera app to pair automatically.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))
            QrCodeView(content = qrContent, size = 200.dp)
            Spacer(modifier = Modifier.height(12.dp))

            val context = LocalContext.current
            OutlinedButton(
                onClick = {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Pair with BuzzMe")
                        putExtra(Intent.EXTRA_TEXT, "Connect to BuzzMe: ${hostQrData.toDeepLinkUrl()}")
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "Share Pairing Link"))
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Share, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Share Pairing Link")
            }
            Spacer(modifier = Modifier.height(16.dp))

            if (session.verificationCode != "PENDING" && session.verificationCode.isNotBlank()) {
                Text("Verification Code:", style = MaterialTheme.typography.labelMedium)
                Text(
                    text = session.verificationCode,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 4.sp,
                    color = Color(0xFFF59E0B)
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (session.state == PairingState.CONSUMED) {
                Text(
                    "Pairing Complete! ${session.label ?: "Peer"} enrolled.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF10B981),
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        val myName = viewModel.deviceName.value?.ifBlank { null } ?: "Household Host"
                        viewModel.startAndroidPairing(myName)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Pair Another Device", fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Done")
                }
            } else if (session.state == PairingState.PENDING) {
                Button(
                    onClick = { viewModel.approvePairing(session.pairingId) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Approve Peer Handshake")
                }
            } else {
                Text(
                    "Status: ${session.state}",
                    color = Color(0xFF10B981),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun AndroidPeerScanContent(
    onScanComplete: (String) -> Unit,
    onCancel: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        CameraQrScanner(
            onBarcodeScanned = onScanComplete,
            onClose = onCancel,
            timeoutSeconds = 120
        )
    }
}

@Composable
fun PairingResultContent(
    success: Boolean?,
    message: String,
    onDone: () -> Unit,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when (success) {
            null -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(56.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 4.dp
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "Pairing in Progress...",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = message.ifBlank { "Connecting and exchanging encryption keys..." },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            true -> {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Success",
                    tint = Color(0xFF10B981),
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Successfully Paired!",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF10B981)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = onDone,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Done")
                }
            }
            false -> {
                Icon(
                    Icons.Default.Error,
                    contentDescription = "Failed",
                    tint = Color(0xFFEF4444),
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Pairing Failed",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFEF4444)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Try Again")
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onDone,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Cancel")
                }
            }
        }
    }
}
