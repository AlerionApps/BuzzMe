package com.buzzme.app.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.buzzme.app.data.model.SessionEndReason
import com.buzzme.app.data.model.SessionState
import com.buzzme.app.ui.MainViewModel
import com.buzzme.app.widget.BuzzWidgetReceiver

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: MainViewModel,
    onOpenPairing: () -> Unit,
    onOpenDeviceManage: () -> Unit
) {
    val context = LocalContext.current
    val sessionState by viewModel.sessionState.collectAsState()
    val peers by viewModel.peers.collectAsState()
    val subscriptions by viewModel.subscriptions.collectAsState()
    val capabilityTokens by viewModel.capabilityTokens.collectAsState()
    val wifiIp by viewModel.wifiIpAddress.collectAsState()
    val deviceName by viewModel.deviceName.collectAsState()
    val statusMsg by viewModel.statusMessage.collectAsState()
    val lastReceivedBuzz by viewModel.lastReceivedBuzz.collectAsState()
    val pendingJoinRequests by viewModel.pendingJoinRequests.collectAsState()

    var showEditDeviceNameDialog by remember { mutableStateOf(false) }
    var peerForNoteDialog by remember { mutableStateOf<com.buzzme.app.data.model.AndroidPeerAuth?>(null) }
    var subForNoteDialog by remember { mutableStateOf<com.buzzme.app.data.model.PushSubscription?>(null) }
    var tokenForNoteDialog by remember { mutableStateOf<com.buzzme.app.data.model.CapabilityToken?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(statusMsg) {
        statusMsg?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearStatus()
        }
    }

    val currentActiveHost by viewModel.currentActiveHost.collectAsState()
    val isActingHost by viewModel.isActingHost.collectAsState()
    val isPrimaryHost by viewModel.isPrimaryHost.collectAsState()
    val discoveredHost by viewModel.discoveredHost.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.refreshPeerDirectory()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.foundation.Image(
                            painter = androidx.compose.ui.res.painterResource(id = com.buzzme.app.R.drawable.ic_buzzme_logo),
                            contentDescription = "BuzzMe Logo",
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "BuzzMe",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.triggerTestAlert() }) {
                        Icon(Icons.Default.Notifications, contentDescription = "Test Buzz")
                    }
                    IconButton(onClick = onOpenDeviceManage) {
                        Icon(Icons.Default.Devices, contentDescription = "Manage Devices")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onOpenPairing,
                icon = { Icon(Icons.Default.AddLink, contentDescription = null) },
                text = { Text("Pair Device") },
                containerColor = MaterialTheme.colorScheme.primary
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { Spacer(modifier = Modifier.height(4.dp)) }

            // 1. Connection Status Card
            item {
                val hostPeer = peers.find { it.targetScope == "default" && !it.keyId.startsWith("relay-") }
                val resolvedHostName = currentActiveHost?.deviceName ?: discoveredHost?.deviceName ?: hostPeer?.label
                SimpleStatusHeader(
                    wifiConnected = !wifiIp.isNullOrBlank(),
                    deviceName = deviceName,
                    isPrimaryHost = isPrimaryHost,
                    isActingHost = isActingHost,
                    activeHostName = resolvedHostName,
                    onEditDeviceName = { showEditDeviceNameDialog = true },
                    onDesignateAsHost = {
                        viewModel.requestDesignateAsHost()
                    }
                )
            }

            // 1.5 Pending Join Requests Banner
            items(pendingJoinRequests) { req ->
                PendingJoinRequestBanner(
                    request = req,
                    onApprove = { viewModel.approveJoinRequest(req.requestId) },
                    onDecline = { viewModel.declineJoinRequest(req.requestId) }
                )
            }

            // 2. Recent Buzz Note Banner (if available)
            lastReceivedBuzz?.let { buzz ->
                item {
                    val matchingPeer = peers.find { it.label.equals(buzz.senderLabel, ignoreCase = true) }
                    RecentBuzzBanner(
                        buzz = buzz,
                        onDismiss = { viewModel.clearLastReceivedBuzz() },
                        onBuzzBack = matchingPeer?.let { { viewModel.sendBuzzToPeer(it) } }
                    )
                }
            }

            // 3. Family Devices Section
            item {
                Text(
                    text = "Family Devices",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            // Filter capability tokens to only those not already represented in push subscriptions
            val standaloneTokens = capabilityTokens.filter { token ->
                !token.revoked && subscriptions.none { it.label.equals(token.label, ignoreCase = true) || it.deviceId == token.targetDeviceId }
            }

            if (peers.isEmpty() && subscriptions.isEmpty() && standaloneTokens.isEmpty()) {
                item {
                    EmptyTargetsPlaceholder(onOpenPairing)
                }
            } else {
                items(peers) { peer ->
                    PeerCard(
                        peer = peer,
                        onBuzzLevel = { level -> viewModel.sendEscalatedBuzzToPeer(peer, level) },
                        onSendNote = { peerForNoteDialog = peer },
                        onUpdateIp = { newIp -> viewModel.updatePeerIp(peer.keyId, newIp) }
                    )
                }

                items(subscriptions) { sub ->
                    SubscriptionCard(
                        sub = sub,
                        onBuzzLevel = { level -> viewModel.sendEscalatedBuzzToPush(sub, level) },
                        onSendNote = { subForNoteDialog = sub }
                    )
                }

                items(standaloneTokens) { token ->
                    CapabilityTokenCard(
                        token = token,
                        onBuzzLevel = { level -> viewModel.sendEscalatedBuzzToCapabilityToken(token, level) },
                        onSendNote = { tokenForNoteDialog = token }
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(64.dp)) }
        }

        if (peerForNoteDialog != null) {
            BuzzNoteDialog(
                targetName = peerForNoteDialog!!.label,
                onDismiss = { peerForNoteDialog = null },
                onSendNote = { noteText ->
                    viewModel.sendBuzzToPeer(peerForNoteDialog!!, message = noteText)
                    peerForNoteDialog = null
                }
            )
        }

        if (subForNoteDialog != null) {
            BuzzNoteDialog(
                targetName = subForNoteDialog!!.label,
                onDismiss = { subForNoteDialog = null },
                onSendNote = { noteText ->
                    viewModel.sendBuzzToPush(subForNoteDialog!!, message = noteText)
                    subForNoteDialog = null
                }
            )
        }

        if (tokenForNoteDialog != null) {
            BuzzNoteDialog(
                targetName = tokenForNoteDialog!!.label,
                onDismiss = { tokenForNoteDialog = null },
                onSendNote = { noteText ->
                    viewModel.sendBuzzToCapabilityToken(tokenForNoteDialog!!, message = noteText)
                    tokenForNoteDialog = null
                }
            )
        }

        if (showEditDeviceNameDialog) {
            DeviceNameSetupDialog(
                initialName = deviceName ?: "",
                suggestedName = viewModel.getSuggestedDeviceName(),
                onDismiss = { showEditDeviceNameDialog = false },
                onSave = { newName ->
                    viewModel.setDeviceName(newName)
                    showEditDeviceNameDialog = false
                }
            )
        }
    }
}

// Clean, simple header section with Wi-Fi status, editable device name, and live host role badge
@Composable
fun SimpleStatusHeader(
    wifiConnected: Boolean,
    deviceName: String? = null,
    isPrimaryHost: Boolean = false,
    isActingHost: Boolean = false,
    activeHostName: String? = null,
    onEditDeviceName: () -> Unit = {},
    onDesignateAsHost: () -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (wifiConnected) Color(0xFFD1FAE5) else MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(if (wifiConnected) Color(0xFF10B981) else Color(0xFFF59E0B))
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (wifiConnected) "Ready to Buzz" else "Connect to Wi-Fi",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (wifiConnected) Color(0xFF065F46) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (!deviceName.isNullOrBlank()) {
                    Surface(
                        onClick = onEditDeviceName,
                        shape = RoundedCornerShape(10.dp),
                        color = if (wifiConnected) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.surface
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = deviceName,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (wifiConnected) Color(0xFF065F46) else MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = "Edit Device Name",
                                tint = if (wifiConnected) Color(0xFF065F46) else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }
                }
            }

            if (wifiConnected) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val hostBadgeText = when {
                        isPrimaryHost -> "👑 Main Household Host"
                        isActingHost -> "⚡ Backup Host (Active Failover)"
                        !activeHostName.isNullOrBlank() -> "🔗 Household Member (Host: $activeHostName)"
                        else -> "🔗 Household Member"
                    }
                    val badgeBg = when {
                        isPrimaryHost -> Color(0xFF047857)
                        isActingHost -> Color(0xFFD97706)
                        else -> Color(0xFF0369A1)
                    }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = badgeBg
                    ) {
                        Text(
                            text = hostBadgeText,
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    if (!isPrimaryHost && activeHostName.isNullOrBlank()) {
                        OutlinedButton(
                            onClick = onDesignateAsHost,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Designate as Host", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DndControlsCard(
    onTestAlert: () -> Unit,
    onOpenDndSettings: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Alert Testing & DND Verification",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Verify that BuzzMe can pierce Do Not Disturb (DND) on this device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onTestAlert,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Vibration, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Test Alert", fontSize = 13.sp)
                }

                OutlinedButton(
                    onClick = onOpenDndSettings,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("DND Settings", fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
fun PeerCard(
    peer: com.buzzme.app.data.model.AndroidPeerAuth,
    onBuzzLevel: (Int) -> Unit,
    onSendNote: () -> Unit,
    onUpdateIp: ((String) -> Unit)? = null
) {
    var showEditDialog by remember { mutableStateOf(false) }
    var inputIp by remember(peer.hostIp) { mutableStateOf(peer.hostIp ?: "") }
    val context = LocalContext.current
    val isOnline = !peer.hostIp.isNullOrBlank()

    if (showEditDialog && onUpdateIp != null) {
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text("Update Device Connection") },
            text = {
                Column {
                    Text("Connection address for ${peer.label}:", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = inputIp,
                        onValueChange = { inputIp = it.trim() },
                        label = { Text("Address / IP") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (inputIp.isNotBlank()) {
                        onUpdateIp(inputIp)
                    }
                    showEditDialog = false
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .then(if (onUpdateIp != null) Modifier.clickable { showEditDialog = true } else Modifier)
                ) {
                    Text(peer.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (isOnline) Color(0xFF10B981) else Color(0xFF94A3B8))
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isOnline) "${peer.label} is online" else "Out of Range",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isOnline) Color(0xFF047857) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onSendNote) {
                        Icon(
                            Icons.Default.ChatBubbleOutline,
                            contentDescription = "Send Note with Buzz",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = { BuzzWidgetReceiver.requestPinWidget(context, peer.keyId, peer.label) }
                    ) {
                        Icon(
                            Icons.Default.Widgets,
                            contentDescription = "Add Widget to Home Screen",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 3 Direct Escalation Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 1. Nudge (Level 1)
                Button(
                    onClick = { onBuzzLevel(1) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38BDF8)),
                    contentPadding = PaddingValues(vertical = 10.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Nudge", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("Checking in", fontSize = 10.sp, color = Color.White.copy(alpha = 0.9f))
                    }
                }

                // 2. Alert (Level 2)
                Button(
                    onClick = { onBuzzLevel(2) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B)),
                    contentPadding = PaddingValues(vertical = 10.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Alert", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("Attention", fontSize = 10.sp, color = Color.White.copy(alpha = 0.9f))
                    }
                }

                // 3. Emergency (Level 3)
                Button(
                    onClick = { onBuzzLevel(3) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                    contentPadding = PaddingValues(vertical = 10.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Emergency", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("Urgent", fontSize = 10.sp, color = Color.White.copy(alpha = 0.9f))
                    }
                }
            }
        }
    }
}

@Composable
fun SubscriptionCard(
    sub: com.buzzme.app.data.model.PushSubscription,
    onBuzzLevel: (Int) -> Unit,
    onSendNote: () -> Unit
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(sub.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF10B981))
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "${sub.label} is online",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF047857)
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onSendNote) {
                        Icon(
                            Icons.Default.ChatBubbleOutline,
                            contentDescription = "Send Note with Buzz",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = { BuzzWidgetReceiver.requestPinWidget(context, sub.id, sub.label) }
                    ) {
                        Icon(
                            Icons.Default.Widgets,
                            contentDescription = "Add Widget to Home Screen",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 3 Direct Escalation Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { onBuzzLevel(1) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38BDF8)),
                    contentPadding = PaddingValues(vertical = 10.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Nudge", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("Checking in", fontSize = 10.sp, color = Color.White.copy(alpha = 0.9f))
                    }
                }

                Button(
                    onClick = { onBuzzLevel(2) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B)),
                    contentPadding = PaddingValues(vertical = 10.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Alert", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("Attention", fontSize = 10.sp, color = Color.White.copy(alpha = 0.9f))
                    }
                }

                Button(
                    onClick = { onBuzzLevel(3) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                    contentPadding = PaddingValues(vertical = 10.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Emergency", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("Urgent", fontSize = 10.sp, color = Color.White.copy(alpha = 0.9f))
                    }
                }
            }
        }
    }
}

@Composable
fun CapabilityTokenCard(
    token: com.buzzme.app.data.model.CapabilityToken,
    onBuzzLevel: (Int) -> Unit,
    onSendNote: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(token.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF38BDF8))
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "iPhone Enrolled",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF0284C7)
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onSendNote) {
                        Icon(
                            Icons.Default.ChatBubbleOutline,
                            contentDescription = "Send Note with Buzz",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 3 Direct Escalation Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { onBuzzLevel(1) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38BDF8)),
                    contentPadding = PaddingValues(vertical = 10.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Nudge", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("Checking in", fontSize = 10.sp, color = Color.White.copy(alpha = 0.9f))
                    }
                }

                Button(
                    onClick = { onBuzzLevel(2) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B)),
                    contentPadding = PaddingValues(vertical = 10.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Alert", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("Attention", fontSize = 10.sp, color = Color.White.copy(alpha = 0.9f))
                    }
                }

                Button(
                    onClick = { onBuzzLevel(3) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                    contentPadding = PaddingValues(vertical = 10.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Emergency", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("Urgent", fontSize = 10.sp, color = Color.White.copy(alpha = 0.9f))
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyTargetsPlaceholder(onOpenPairing: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier.padding(24.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.GroupAdd,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text("No Family Members Added", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text(
                "Tap 'Pair Device' below to connect a family member's phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
            )
            Button(onClick = onOpenPairing) {
                Text("Pair New Device")
            }
        }
    }
}

@Composable
fun RecentBuzzBanner(
    buzz: com.buzzme.app.ui.ReceivedBuzz,
    onDismiss: () -> Unit,
    onBuzzBack: (() -> Unit)? = null
) {
    val relativeTime = remember(buzz.timestampMs) {
        val diffMs = System.currentTimeMillis() - buzz.timestampMs
        val mins = (diffMs / 60000).toInt()
        when {
            mins < 1 -> "Just now"
            mins == 1 -> "1 min ago"
            mins < 60 -> "$mins mins ago"
            else -> "${mins / 60}h ago"
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF3C7))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🐝", fontSize = 18.sp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Message from ${buzz.senderLabel}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF92400E)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = relativeTime,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFB45309)
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(24.dp).padding(start = 4.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Dismiss",
                            tint = Color(0xFF92400E),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = buzz.message,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF78350F)
            )

            if (onBuzzBack != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    FilledTonalButton(
                        onClick = onBuzzBack,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = Color(0xFFF59E0B),
                            contentColor = Color.White
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Buzz Back", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun BuzzNoteDialog(
    targetName: String,
    onDismiss: () -> Unit,
    onSendNote: (String) -> Unit
) {
    var noteText by remember { mutableStateOf("") }
    val maxChars = 160
    val quickSuggestions = listOf(
        "Dinner is ready! 🍽️",
        "Come downstairs",
        "Call me 📞",
        "Time to go!"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Bolt,
                    contentDescription = null,
                    tint = Color(0xFFF59E0B),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Buzz $targetName",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Add a quick note to send with your buzz:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { if (it.length <= maxChars) noteText = it },
                    placeholder = { Text("e.g., Dinner is ready! Pick up phone...") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                    supportingText = {
                        Text(
                            text = "${noteText.length}/$maxChars",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = androidx.compose.ui.text.style.TextAlign.End,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Quick suggestions:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    quickSuggestions.forEach { suggestion ->
                        SuggestionChip(
                            onClick = { noteText = suggestion },
                            label = { Text(suggestion, fontSize = 12.sp) },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = if (noteText == suggestion) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                            )
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val finalMsg = noteText.trim().ifBlank { "Buzz!" }
                    onSendNote(finalMsg)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B))
            ) {
                Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("BUZZ NOW", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun PendingJoinRequestBanner(
    request: com.buzzme.app.data.model.PendingJoinRequest,
    onApprove: () -> Unit,
    onDecline: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("👋", fontSize = 22.sp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Join Request from '${request.deviceName}'",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1E40AF)
                        )
                        Text(
                            text = "Device IP: ${request.deviceIp} (${request.deviceType.uppercase()})",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF3B82F6)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onDecline,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Decline", color = Color(0xFFEF4444))
                }
                Button(
                    onClick = onApprove,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                ) {
                    Text("Approve")
                }
            }
        }
    }
}


