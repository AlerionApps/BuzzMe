package com.buzzme.app

import android.app.Application
import com.buzzme.app.data.repository.DeviceRepository
import com.buzzme.app.data.repository.SessionController
import com.buzzme.app.network.NetworkManager
import com.buzzme.app.network.SocketServer
import com.buzzme.app.network.web.HttpGateway
import com.buzzme.app.network.web.WebPushManager
import com.buzzme.app.service.AlertEngine

class BuzzMeApp : Application() {

    lateinit var deviceRepository: DeviceRepository
        private set

    lateinit var networkManager: NetworkManager
        private set

    lateinit var socketServer: SocketServer
        private set

    lateinit var httpGateway: HttpGateway
        private set

    lateinit var webPushManager: WebPushManager
        private set

    lateinit var hostBeaconManager: com.buzzme.app.network.HostBeaconManager
        private set

    lateinit var nsdServiceManager: com.buzzme.app.network.NsdServiceManager
        private set

    lateinit var networkDiscoveryManager: com.buzzme.app.network.NetworkDiscoveryManager
        private set

    lateinit var sessionController: SessionController
        private set

    override fun onCreate() {
        super.onCreate()

        AlertEngine.initChannels(this)

        deviceRepository = DeviceRepository(this)
        networkManager = NetworkManager(this)
        networkManager.startMonitoring()

        socketServer = SocketServer(this, deviceRepository)
        httpGateway = HttpGateway(this, deviceRepository)
        webPushManager = WebPushManager(deviceRepository)
        hostBeaconManager = com.buzzme.app.network.HostBeaconManager(this, deviceRepository, networkManager)
        nsdServiceManager = com.buzzme.app.network.NsdServiceManager(this)
        networkDiscoveryManager = com.buzzme.app.network.NetworkDiscoveryManager(hostBeaconManager, nsdServiceManager, networkManager, deviceRepository)

        sessionController = SessionController(
            context = this,
            socketServer = socketServer,
            httpGateway = httpGateway,
            networkManager = networkManager,
            hostBeaconManager = hostBeaconManager,
            nsdServiceManager = nsdServiceManager,
            deviceRepository = deviceRepository
        )
    }
}
