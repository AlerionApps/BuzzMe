package com.buzzme.app.network

import android.util.Log
import com.buzzme.app.data.model.HostBeacon
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

data class DiscoveredHost(
    val deviceId: String,
    val deviceName: String,
    val ipAddress: String,
    val httpPort: Int = 8900,
    val socketPort: Int = 8899,
    val isPrimaryHost: Boolean = true
)

/**
 * Discovers the BuzzMe household host on the local Wi-Fi network using multiple redundant strategies:
 * 1. Listening to HostBeaconManager UDP beacons (port 8898).
 * 2. mDNS discovery (NsdServiceManager).
 * 3. Fast parallel subnet sweep on port 8900 (reliable fallback if router blocks multicast/broadcast).
 */
class NetworkDiscoveryManager(
    private val hostBeaconManager: HostBeaconManager,
    private val nsdServiceManager: NsdServiceManager,
    private val networkManager: NetworkManager,
    private val deviceRepository: com.buzzme.app.data.repository.DeviceRepository
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(1, TimeUnit.SECONDS)
        .readTimeout(1, TimeUnit.SECONDS)
        .build()

    private val _discoveredHost = MutableStateFlow<DiscoveredHost?>(null)
    val discoveredHost: StateFlow<DiscoveredHost?> = _discoveredHost.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private var scanJob: Job? = null

    init {
        // 1. Observe UDP beacon election — ONLY record external Primary Hosts
        scope.launch {
            hostBeaconManager.currentActiveHost.collect { beacon ->
                val myDeviceId = deviceRepository.hostDeviceId.toString()
                if (beacon != null && beacon.deviceId != myDeviceId && beacon.role == com.buzzme.app.data.model.HostRole.PRIMARY_HOST && beacon.ipAddress.isNotBlank() && beacon.ipAddress != "127.0.0.1") {
                    Log.i(TAG, "Discovered external primary host via UDP beacon: '${beacon.deviceName}' at ${beacon.ipAddress}")
                    _discoveredHost.value = DiscoveredHost(
                        deviceId = beacon.deviceId,
                        deviceName = beacon.deviceName,
                        ipAddress = beacon.ipAddress
                    )
                } else if (beacon != null && beacon.deviceId == myDeviceId) {
                    // Local device is the host — clear discoveredHost so we never join ourselves
                    if (_discoveredHost.value?.deviceId == myDeviceId) {
                        _discoveredHost.value = null
                    }
                }
            }
        }

        // 2. Observe mDNS discovery
        scope.launch {
            nsdServiceManager.discoveredHostAddress.collect { mDnsIp ->
                val myIp = networkManager.wifiIpAddress.value
                if (!mDnsIp.isNullOrBlank() && mDnsIp != myIp && _discoveredHost.value == null) {
                    Log.i(TAG, "Discovered host via mDNS at $mDnsIp, querying host info...")
                    probeHost(mDnsIp)
                }
            }
        }
    }

    fun clearDiscoveredHost() {
        _discoveredHost.value = null
    }

    fun startDiscovery() {
        nsdServiceManager.startDiscovery()
        triggerSubnetSweep()
    }

    fun stopDiscovery() {
        scanJob?.cancel()
        scanJob = null
        nsdServiceManager.stopDiscovery()
        _isScanning.value = false
    }

    fun triggerSubnetSweep() {
        if (_isScanning.value) return
        scanJob?.cancel()
        scanJob = scope.launch {
            _isScanning.value = true
            try {
                val wifiIp = networkManager.wifiIpAddress.value
                if (wifiIp.isNullOrBlank() || wifiIp == "127.0.0.1") {
                    Log.w(TAG, "Cannot sweep subnet: WiFi IP not available")
                    return@launch
                }

                val prefix = wifiIp.substringBeforeLast(".")
                Log.i(TAG, "Starting parallel subnet sweep on $prefix.1 - 254 (port 8900)...")

                val candidateIps = (1..254).map { "$prefix.$it" }.filter { it != wifiIp }

                // Probe in parallel chunks of 32
                candidateIps.chunked(32).forEach { chunk ->
                    if (!isActive || _discoveredHost.value != null) return@launch
                    val jobs = chunk.map { ip ->
                        async {
                            if (checkTcpPort(ip, 8900, 300)) {
                                Log.i(TAG, "Port 8900 open at $ip — querying info...")
                                probeHost(ip)
                            }
                        }
                    }
                    jobs.awaitAll()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Subnet sweep encountered error: ${e.message}")
            } finally {
                _isScanning.value = false
            }
        }
    }

    private fun checkTcpPort(ip: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun probeHost(ip: String): Boolean = withContext(Dispatchers.IO) {
        val myIp = networkManager.wifiIpAddress.value
        val myDeviceId = deviceRepository.hostDeviceId.toString()
        if (ip == myIp || ip == "127.0.0.1") return@withContext false

        try {
            val req = Request.Builder()
                .url("http://$ip:8900/api/v1/info")
                .get()
                .build()
            val response = httpClient.newCall(req).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                val hostInfo = runCatching {
                    kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                        .decodeFromString<HostInfoResponse>(body)
                }.getOrNull() ?: return@withContext false

                // MUST be a designated host, and MUST NOT be ourselves!
                if (!hostInfo.isHost || hostInfo.deviceId == myDeviceId) {
                    return@withContext false
                }

                val hostName = hostInfo.deviceName.ifBlank { "Household Host" }
                val hostDeviceId = hostInfo.deviceId.ifBlank { ip }

                Log.i(TAG, "Successfully confirmed BuzzMe Host '$hostName' ($hostDeviceId) at $ip")
                _discoveredHost.value = DiscoveredHost(
                    deviceId = hostDeviceId,
                    deviceName = hostName,
                    ipAddress = ip
                )
                return@withContext true
            }
        } catch (e: Exception) {
            // Not a BuzzMe host or request failed
        }
        return@withContext false
    }

    @kotlinx.serialization.Serializable
    data class HostInfoResponse(
        val deviceId: String = "",
        val deviceName: String = "",
        val status: String = "online",
        val isHost: Boolean = false
    )

    companion object {
        private const val TAG = "NetworkDiscoveryManager"
    }
}
