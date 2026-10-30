/*
 * Copyright 2026 Morphe.
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.extension.youtube.patches

import android.content.Context
import org.fcast.sender_sdk.DeviceDiscovererEventHandler
import org.fcast.sender_sdk.DeviceInfo
import org.fcast.sender_sdk.NsdDeviceDiscoverer

/**
 * Discovery adapter using the same FCast sender SDK as Grayjay.
 *
 * NsdDeviceDiscoverer owns service discovery and device-info normalization;
 * this class only forwards its events to the independent Morphe picker.
 */
class SecondaryCastDiscovery(
    context: Context,
    private val callback: Callback
) {
    interface Callback {
        fun onDeviceAvailable(name: String, deviceInfo: DeviceInfo)
        fun onDeviceChanged(name: String, deviceInfo: DeviceInfo)
        fun onDeviceRemoved(name: String)
    }

    private var discoverer: NsdDeviceDiscoverer? = NsdDeviceDiscoverer(
        context.applicationContext,
        object : DeviceDiscovererEventHandler {
            override fun deviceAvailable(deviceInfo: DeviceInfo) {
                callback.onDeviceAvailable(deviceInfo.name, deviceInfo)
            }

            override fun deviceChanged(deviceInfo: DeviceInfo) {
                callback.onDeviceChanged(deviceInfo.name, deviceInfo)
            }

            override fun deviceRemoved(deviceName: String) {
                callback.onDeviceRemoved(deviceName)
            }
        }
    )

    /**
     * Mirrors Grayjay's lifecycle: releasing the discoverer stops retaining
     * the native discovery handle.
     */
    fun stop() {
        discoverer = null
    }
}
