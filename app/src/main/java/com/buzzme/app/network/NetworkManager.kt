package com.buzzme.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.NetworkInterface

class NetworkManager(private val context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _wifiIpAddress = MutableStateFlow<String?>(resolveLocalWifiIp())
    val wifiIpAddress: StateFlow<String?> = _wifiIpAddress.asStateFlow()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            updateIp()
        }

        override fun onLost(network: Network) {
            updateIp()
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            updateIp()
        }
    }

    fun startMonitoring() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        try {
            connectivityManager.registerNetworkCallback(request, networkCallback)
        } catch (e: Exception) {
            // Fallback for restricted environments
        }
        updateIp()
    }

    fun stopMonitoring() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            // Ignore
        }
    }

    fun updateIp() {
        _wifiIpAddress.value = resolveLocalWifiIp()
    }

    fun resolveLocalWifiIp(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: return null
            val validCandidates = mutableListOf<Pair<String, String>>() // (interfaceName, hostAddress)

            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val name = intf.name.lowercase()
                // Strictly exclude VPN tunnels, cellular data, p2p, virtual bridges, and dummy interfaces
                if (name.startsWith("tun") || name.startsWith("tap") || name.startsWith("dummy") ||
                    name.startsWith("p2p") || name.startsWith("rmnet") || name.startsWith("ccmni") ||
                    name.startsWith("pdp_ip") || name.startsWith("ppp")) {
                    continue
                }

                val addresses = intf.inetAddresses
                for (addr in addresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address && !addr.isLinkLocalAddress) {
                        val hostAddr = addr.hostAddress ?: continue
                        // Exclude loopback, link-local, and emulator NAT (10.0.2.15) if physical Wi-Fi might exist
                        if (!hostAddr.startsWith("127.") && !hostAddr.startsWith("169.254.")) {
                            validCandidates.add(Pair(name, hostAddr))
                        }
                    }
                }
            }

            // 1. Prioritize Wi-Fi interface (wlan* / en* on iOS or standard Wi-Fi)
            validCandidates.firstOrNull { 
                (it.first.startsWith("wlan") || it.first.startsWith("en") || it.first.startsWith("wifi")) && !it.second.startsWith("10.0.2.") 
            }?.let {
                return it.second
            }

            // 2. Physical Ethernet (eth*) excluding emulator default
            validCandidates.firstOrNull { it.first.startsWith("eth") && !it.second.startsWith("10.0.2.") }?.let {
                return it.second
            }

            // 3. Any other non-emulator candidate
            validCandidates.firstOrNull { !it.second.startsWith("10.0.2.") }?.let {
                return it.second
            }

            // 4. Fallback to any valid candidate (including emulator if in development/testing)
            return validCandidates.firstOrNull()?.second
        } catch (e: Exception) {
            // Log / return null
        }
        return null
    }
}
