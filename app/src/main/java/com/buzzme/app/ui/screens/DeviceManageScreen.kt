package com.buzzme.app.ui.screens

import androidx.compose.foundation.BorderStroke
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceManageScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val peers by viewModel.peers.collectAsState()
    val deviceName by viewModel.deviceName.collectAsState()
    val hostDeviceId = viewModel.deviceRepository.hostDeviceId
    var showEditDeviceNameDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Manage Family Devices", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
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
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("This Device", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = deviceName ?: "Unnamed Device",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Device ID: ${hostDeviceId.toString().take(8)}...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { showEditDeviceNameDialog = true }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit Device Name")
                        }
                    }
                }
            }

            item {
                Text(
                    text = "Paired Family Devices (${peers.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            if (peers.isEmpty()) {
                item {
                    Text(
                        text = "No family devices currently paired.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(peers) { peer ->
                    var showEditIpDialog by remember { mutableStateOf(false) }
                    var editedIp by remember(peer.hostIp) { mutableStateOf(peer.hostIp ?: "") }
                    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

                    if (showEditIpDialog) {
                        AlertDialog(
                            onDismissRequest = { showEditIpDialog = false },
                            title = { Text("Edit Device Address") },
                            text = {
                                Column {
                                    Text("Wi-Fi IP address for ${peer.label}:", style = MaterialTheme.typography.bodyMedium)
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

                    if (showDeleteConfirmDialog) {
                        AlertDialog(
                            onDismissRequest = { showDeleteConfirmDialog = false },
                            title = { Text("Remove Device") },
                            text = {
                                Text("Are you sure you want to remove ${peer.label}? You will need to pair again to send buzzes.")
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        viewModel.removePeer(peer.keyId)
                                        showDeleteConfirmDialog = false
                                    }
                                ) {
                                    Text("Remove", color = Color(0xFFDC2626), fontWeight = FontWeight.Bold)
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                                    Text("Cancel")
                                }
                            }
                        )
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = peer.label,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (!peer.hostIp.isNullOrBlank()) "Direct Wi-Fi (${peer.hostIp})" else "Household Relay",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { showEditIpDialog = true }) {
                                    Icon(Icons.Default.Edit, contentDescription = "Edit IP", tint = MaterialTheme.colorScheme.primary)
                                }
                                IconButton(onClick = { showDeleteConfirmDialog = true }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Remove", tint = Color(0xFFEF4444))
                                }
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
