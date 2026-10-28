package com.buzzme.app.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress

/**
 * Manages mDNS / DNS-SD service advertisement and discovery via Android NsdManager.
 * Advertises "buzzme" on _http._tcp (port 8900) for zero-configuration LAN host discovery.
 */
class NsdServiceManager(private val context: Context) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private val _discoveredHostAddress = MutableStateFlow<String?>(null)
    val discoveredHostAddress: StateFlow<String?> = _discoveredHostAddress.asStateFlow()

    @Volatile
    var isAdvertising = false
        private set

    @Volatile
    var isDiscovering = false
        private set

    fun startAdvertising(port: Int = 8900, serviceName: String = "buzzme") {
        if (isAdvertising || nsdManager == null) return

        val serviceInfo = NsdServiceInfo().apply {
            this.serviceName = serviceName
            this.serviceType = "_http._tcp."
            this.port = port
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                isAdvertising = true
                Log.i(TAG, "mDNS service registered: ${info.serviceName} on port ${info.port}. Accessible via http://buzzme.local:${info.port}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "mDNS registration failed: errorCode=$errorCode")
                isAdvertising = false
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                isAdvertising = false
                Log.i(TAG, "mDNS service unregistered: ${info.serviceName}")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "mDNS unregistration failed: errorCode=$errorCode")
            }
        }

        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.e(TAG, "Exception during registerService", e)
        }
    }

    fun stopAdvertising() {
        if (!isAdvertising || nsdManager == null) return
        try {
            registrationListener?.let { nsdManager.unregisterService(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering service", e)
        }
        registrationListener = null
        isAdvertising = false
    }

    fun startDiscovery() {
        if (isDiscovering || nsdManager == null) return

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                isDiscovering = true
                Log.i(TAG, "Service discovery started for $regType")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.i(TAG, "Service found: ${service.serviceName}")
                if (service.serviceName.contains("buzzme", ignoreCase = true)) {
                    resolveService(service)
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.i(TAG, "Service lost: ${service.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                isDiscovering = false
                Log.i(TAG, "Service discovery stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                isDiscovering = false
                Log.w(TAG, "Start discovery failed: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Stop discovery failed: $errorCode")
            }
        }

        try {
            nsdManager.discoverServices("_http._tcp.", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting NSD discovery", e)
        }
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        val resolver = object : NsdManager.ResolveListener {
            override fun onResolveFailed(service: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Resolve failed: $errorCode")
            }

            override fun onServiceResolved(resolved: NsdServiceInfo) {
                val host = resolved.host?.hostAddress
                Log.i(TAG, "Resolved BuzzMe service at $host:${resolved.port}")
                if (!host.isNullOrBlank() && host != "127.0.0.1") {
                    _discoveredHostAddress.value = host
                }
            }
        }

        try {
            nsdManager?.resolveService(serviceInfo, resolver)
        } catch (e: Exception) {
            Log.w(TAG, "Exception resolving service", e)
        }
    }

    fun stopDiscovery() {
        if (!isDiscovering || nsdManager == null) return
        try {
            discoveryListener?.let { nsdManager.stopServiceDiscovery(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping discovery", e)
        }
        discoveryListener = null
        isDiscovering = false
    }

    companion object {
        private const val TAG = "NsdServiceManager"
    }
}
