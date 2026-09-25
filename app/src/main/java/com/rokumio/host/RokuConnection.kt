package com.rokumio.host

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The app's live Roku connection: which device the user picked during this
 * session. Deliberately NOT persisted — a fresh launch starts disconnected and
 * the user re-scans/searches, mirroring the always-current LAN reality.
 */
object RokuConnection {

    private val _device = MutableStateFlow<RokuDevice?>(null)

    /** The selected device, or null when disconnected. */
    val device: StateFlow<RokuDevice?> = _device

    /** Pick a device (or pass null to disconnect). */
    fun select(device: RokuDevice?) {
        _device.value = device
    }

    val connected: Boolean get() = _device.value != null
}