package com.buzzme.app.data.model

enum class SessionEndReason {
    STOPPED_BY_USER,
    RESTARTING,           // OS killed the service; START_STICKY will restart it automatically
    NETWORK_BIND_FAILED,
    START_NOT_ALLOWED,
    SERVICE_DESTROYED
}

sealed class SessionState {
    data class Active(val startTimeMs: Long) : SessionState()
    data class Stopped(val reason: SessionEndReason? = null) : SessionState()
}
