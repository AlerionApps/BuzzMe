package com.buzzme.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.buzzme.app.ui.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceManageScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val peers by viewModel.peers.collectAsState()
    val subscriptions by viewModel.subscriptions.collectAsState()
    val tokens by viewModel.capabilityTokens.collectAsState()
    val deviceName by viewModel.deviceName.collectAsState()
    val hostDeviceId = viewModel.deviceRepository.hostDeviceId
    var showEditDeviceNameDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Manage Enrolled Devices", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
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
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("This Device", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Name: ${deviceName ?: "Not set"}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text("Device UUID: $hostDeviceId", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { showEditDeviceNameDialog = true }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit Device Name")
                        }
                    }
                }
            }

            // 1. Android Peers Section
            item {
                Text("Android Peers (${peers.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }

            if (peers.isEmpty()) {
                item {
                    Text("No Android peers enrolled.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                items(peers) { peer ->
                    var showEditIpDialog by remember { mutableStateOf(false) }
                    var editedIp by remember(peer.hostIp) { mutableStateOf(peer.hostIp ?: "") }

                    if (showEditIpDialog) {
                        AlertDialog(
                            onDismissRequest = { showEditIpDialog = false },
                            title = { Text("Edit Peer IP Address") },
                            text = {
                                Column {
                                    Text("Enter the Wi-Fi IP address for ${peer.label}:", style = MaterialTheme.typography.bodyMedium)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    OutlinedTextField(
                                        value = editedIp,
                                        onValueChange = { editedIp = it.trim() },
                                        label = { Text("IP Address") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        if (editedIp.isNotBlank()) {
                                            viewModel.updatePeerIp(peer.keyId, editedIp)
                                        }
                                        showEditIpDialog = false
                                    }
                                ) {
                                    Text("Save")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showEditIpDialog = false }) {
                                    Text("Cancel")
                                }
                            }
                        )
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(peer.label, fontWeight = FontWeight.Bold)
                                Text("Key ID: ${peer.keyId.take(8)}...", style = MaterialTheme.typography.labelSmall)
                                Text("Seq: ${peer.highestSeq} • IP: ${peer.hostIp ?: "unknown"}", style = MaterialTheme.typography.labelSmall)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { showEditIpDialog = true }) {
                                    Icon(Icons.Default.Edit, contentDescription = "Edit IP", tint = MaterialTheme.colorScheme.primary)
                                }
                                IconButton(onClick = { viewModel.removePeer(peer.keyId) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Remove", tint = Color(0xFFEF4444))
                                }
                            }
                        }
                    }
                }
            }

            // 2. iOS Shortcuts (Capability Tokens)
            item {
                Text("iOS Shortcut Capability Tokens (${tokens.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }

            if (tokens.isEmpty()) {
                item {
                    Text("No capability tokens issued.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                items(tokens) { token ->
                    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    val createdStr = dateFormat.format(Date(token.createdAt))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(token.label, fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    if (token.revoked) {
                                        Text("[REVOKED]", color = Color(0xFFEF4444), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                    }
                                }
                                Text("Lookup Hash: ${token.lookupId.take(12)}...", style = MaterialTheme.typography.labelSmall)
                                Text("Issued: $createdStr", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            }

                            if (!token.revoked) {
                                Button(
                                    onClick = { viewModel.revokeCapabilityToken(token.lookupId) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                                ) {
                                    Text("Revoke")
                                }
                            }
                        }
                    }
                }
            }

            // 3. iOS PWA Push Receivers
            item {
                Text("iOS Web Push Receivers (${subscriptions.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }

            if (subscriptions.isEmpty()) {
                item {
                    Text("No iOS Web Push receivers enrolled.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                items(subscriptions) { sub ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(sub.label, fontWeight = FontWeight.Bold)
                                Text("Endpoint: ${sub.endpoint.take(30)}...", style = MaterialTheme.typography.labelSmall)
                            }
                            IconButton(onClick = { viewModel.removeSubscription(sub.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove", tint = Color(0xFFEF4444))
                            }
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(32.dp)) }
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
