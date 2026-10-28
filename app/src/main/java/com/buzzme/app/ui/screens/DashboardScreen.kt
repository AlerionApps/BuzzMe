package com.buzzme.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.buzzme.app.ui.MainViewModel
import com.buzzme.app.ui.theme.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: MainViewModel,
    onOpenPairing: () -> Unit,
    onOpenDeviceManage: () -> Unit
) {
    val context = LocalContext.current
    val peers by viewModel.peers.collectAsState()
    val wifiIp by viewModel.wifiIpAddress.collectAsState()
    val deviceName by viewModel.deviceName.collectAsState()
    val statusMsg by viewModel.statusMessage.collectAsState()
    val lastReceivedBuzz by viewModel.lastReceivedBuzz.collectAsState()
    val pendingJoinRequests by viewModel.pendingJoinRequests.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()

    var showEditDeviceNameDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var peerForNoteDialog by remember { mutableStateOf<com.buzzme.app.data.model.AndroidPeerAuth?>(null) }

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
                            modifier = Modifier.size(34.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "BuzzMe",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Family Notification",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showThemeDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.BrightnessMedium,
                            contentDescription = "Theme / Appearance",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(onClick = { viewModel.triggerTestAlert() }) {
                        Icon(
                            imageVector = Icons.Default.Notifications,
                            contentDescription = "Test Buzz",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(onClick = onOpenDeviceManage) {
                        Icon(
                            imageVector = Icons.Default.Devices,
                            contentDescription = "Manage Devices",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
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
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Pair Device", fontWeight = FontWeight.Bold) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
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

            // 1. Plain-Language Household Status Card
            item {
                val hostPeer = peers.find { it.targetScope == "default" && !it.keyId.startsWith("relay-") }
                val resolvedHostName = currentActiveHost?.deviceName ?: discoveredHost?.deviceName ?: hostPeer?.label
                HouseholdStatusCard(
                    wifiConnected = !wifiIp.isNullOrBlank(),
                    deviceName = deviceName,
                    isPrimaryHost = isPrimaryHost,
                    isActingHost = isActingHost,
                    activeHostName = resolvedHostName,
                    onEditDeviceName = { showEditDeviceNameDialog = true },
                    onDesignateAsHost = { viewModel.requestDesignateAsHost() }
                )
            }

            // 2. Quick Messages Household Broadcast Bar
            item {
                QuickPresetsSection(
                    onSendPreset = { presetText ->
                        viewModel.broadcastQuickPreset(presetText)
                    }
                )
            }

            // 3. Pending Join Requests Banner
            items(pendingJoinRequests) { req ->
                PendingJoinRequestBanner(
                    request = req,
                    onApprove = { viewModel.approveJoinRequest(req.requestId) },
                    onDecline = { viewModel.declineJoinRequest(req.requestId) }
                )
            }

            // 4. Recent Buzz Note Banner (if available)
            lastReceivedBuzz?.let { buzz ->
                item {
                    val matchingPeer = peers.find { it.label.equals(buzz.senderLabel, ignoreCase = true) }
                    RecentBuzzBanner(
                        buzz = buzz,
                        onDismiss = { viewModel.clearLastReceivedBuzz() },
                        onBuzzBack = matchingPeer?.let { { viewModel.sendBuzzToPeer(it, message = "I'm on my way!") } }
                    )
                }
            }

            // 5. Family Members Section
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Family Devices",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    if (peers.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Text(
                                text = "${peers.size} Connected",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            if (peers.isEmpty()) {
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
            }

            item { Spacer(modifier = Modifier.height(72.dp)) }
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

        if (showThemeDialog) {
            ThemeSelectionDialog(
                currentTheme = themeMode,
                onSelectTheme = { selected ->
                    viewModel.setThemeMode(selected)
                    showThemeDialog = false
                },
                onDismiss = { showThemeDialog = false }
            )
        }
    }
}

/**
 * Plain-language, reassuring household status card.
 * Zero engineering jargon (no 5G, sub-GHz, master node, packet rate, or dBm).
 */
@Composable
fun HouseholdStatusCard(
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
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            // Wi-Fi Connection & Device Name
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (wifiConnected) Color(0xFF10B981) else Color(0xFF94A3B8))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (wifiConnected) "Connected to Home Wi-Fi" else "Wi-Fi Disconnected",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (wifiConnected) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (!deviceName.isNullOrBlank()) {
                    Surface(
                        onClick = onEditDeviceName,
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = deviceName,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = "Edit Device Name",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Reassuring Household Status
            Text(
                text = "Household Network: Ready",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "All family pagers in your home can send and receive buzzes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Role Badge & Action
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val hostRoleLabel = when {
                    isPrimaryHost -> "👑 Household Host"
                    isActingHost -> "⚡ Backup Host"
                    !activeHostName.isNullOrBlank() -> "🔗 Family Member (Host: $activeHostName)"
                    else -> "🔗 Family Member"
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isPrimaryHost) Color(0xFF0369A1).copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, if (isPrimaryHost) Color(0xFF0284C7) else MaterialTheme.colorScheme.outline)
                ) {
                    Text(
                        text = hostRoleLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isPrimaryHost) Color(0xFF0284C7) else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }

                if (!isPrimaryHost && activeHostName.isNullOrBlank()) {
                    OutlinedButton(
                        onClick = onDesignateAsHost,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("Claim Host Role", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/**
 * Common text-only household presets for quick one-tap alerts.
 * Strictly text-only (no emojis).
 */
@Composable
fun QuickPresetsSection(
    onSendPreset: (String) -> Unit
) {
    val presets = listOf(
        "Dinner is ready",
        "Please come here",
        "Someone at the door",
        "Phone call for you",
        "Time to leave",
        "Need a hand"
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Quick Household Messages",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "1-Tap Broadcast",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            presets.forEach { preset ->
                Surface(
                    onClick = { onSendPreset(preset) },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.height(40.dp)
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = preset,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

/**
 * Clean family device card:
 * Displays user label, simple Online / Offline status, and 3 distinct high-visibility tactile action buttons.
 */
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
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            // Member Header (Avatar, Name, Online Dot, Note/Widget Actions)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    // Avatar Circle
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF0284C7).copy(alpha = 0.2f))
                            .border(1.dp, Color(0xFF0284C7).copy(alpha = 0.5f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = peer.label.take(1).uppercase(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFF0284C7)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = peer.label,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (isOnline) Color(0xFF10B981) else Color(0xFF94A3B8))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isOnline) "Online" else "Offline / Out of Range",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isOnline) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Action icons: Note
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

            Spacer(modifier = Modifier.height(16.dp))

            // 3 Tactile Tri-Action Buttons (Nudge, Alert, Emergency)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 1. Nudge (Level 1)
                Button(
                    onClick = { onBuzzLevel(1) },
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Nudge", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.White)
                        Text("Gentle", fontSize = 10.sp, color = Color.White.copy(alpha = 0.85f))
                    }
                }

                // 2. Alert (Level 2)
                Button(
                    onClick = { onBuzzLevel(2) },
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706)),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Alert", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.White)
                        Text("Attention", fontSize = 10.sp, color = Color.White.copy(alpha = 0.85f))
                    }
                }

                // 3. Emergency (Level 3)
                Button(
                    onClick = { onBuzzLevel(3) },
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Emergency", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.White)
                        Text("Urgent", fontSize = 10.sp, color = Color.White.copy(alpha = 0.85f))
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
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .padding(28.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.GroupAdd,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                "No Family Members Added",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                "Tap 'Pair Device' below to connect a family member's phone or tablet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp, bottom = 20.dp)
            )
            Button(
                onClick = onOpenPairing,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Pair Device", fontWeight = FontWeight.Bold)
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.5.dp, Color(0xFFF59E0B))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Message from ${buzz.senderLabel}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFD97706)
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Dismiss",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "\"${buzz.message}\"",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (onBuzzBack != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = onBuzzBack,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("I'm on my way!", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun PendingJoinRequestBanner(
    request: com.buzzme.app.data.model.PendingJoinRequest,
    onApprove: () -> Unit,
    onDecline: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Join Request from '${request.deviceName}'",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Wants to connect to your household notifications.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )

            Spacer(modifier = Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onDecline,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Decline", color = Color(0xFFEF4444), fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick = onApprove,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
                ) {
                    Text("Approve", fontWeight = FontWeight.Bold)
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
    val commonSuggestions = listOf(
        "Dinner is ready",
        "Please come here",
        "Someone at the door",
        "Phone call for you",
        "Time to leave",
        "Need a hand"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Buzz $targetName",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Add a note to send with your buzz:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { if (it.length <= maxChars) noteText = it },
                    placeholder = { Text("e.g. Dinner is ready...") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                    supportingText = {
                        Text(
                            text = "${noteText.length}/$maxChars",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.End,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Quick options:",
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
                    commonSuggestions.forEach { suggestion ->
                        SuggestionChip(
                            onClick = { noteText = suggestion },
                            label = { Text(suggestion, fontSize = 12.sp) }
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
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Send Buzz", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * 3-Way Theme Selection Dialog:
 * System Default, Light Mode, Dark Mode
 */
@Composable
fun ThemeSelectionDialog(
    currentTheme: ThemeMode,
    onSelectTheme: (ThemeMode) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Appearance",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                ThemeOptionItem(
                    title = "Follow Android System",
                    subtitle = "Matches your device's light or dark setting",
                    selected = currentTheme == ThemeMode.SYSTEM,
                    onClick = { onSelectTheme(ThemeMode.SYSTEM) }
                )
                Spacer(modifier = Modifier.height(6.dp))
                ThemeOptionItem(
                    title = "Light Theme",
                    subtitle = "High-contrast bright background for easy reading",
                    selected = currentTheme == ThemeMode.LIGHT,
                    onClick = { onSelectTheme(ThemeMode.LIGHT) }
                )
                Spacer(modifier = Modifier.height(6.dp))
                ThemeOptionItem(
                    title = "Dark Theme",
                    subtitle = "Dark slate background, easy on the eyes at night",
                    selected = currentTheme == ThemeMode.DARK,
                    onClick = { onSelectTheme(ThemeMode.DARK) }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
fun ThemeOptionItem(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected,
                onClick = onClick
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
