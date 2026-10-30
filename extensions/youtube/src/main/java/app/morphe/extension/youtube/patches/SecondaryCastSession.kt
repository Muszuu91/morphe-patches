/*
 * Copyright 2026 Morphe.
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.extension.youtube.patches

import android.os.Build
import org.fcast.sender_sdk.ApplicationInfo
import org.fcast.sender_sdk.CastContext
import org.fcast.sender_sdk.CastingDevice
import org.fcast.sender_sdk.DeviceConnectionState
import org.fcast.sender_sdk.DeviceEventHandler
import org.fcast.sender_sdk.DeviceInfo
import org.fcast.sender_sdk.MediaTrack
import org.fcast.sender_sdk.MediaTrackType
import org.fcast.sender_sdk.PlaybackState
import org.fcast.sender_sdk.QueueState
import org.fcast.sender_sdk.ReceiverError
import org.fcast.sender_sdk.Source
import org.fcast.sender_sdk.TrackList

/**
 * Thin connection adapter around the same sender SDK used by Grayjay.
 * It deliberately does not implement media loading yet.
 */
class SecondaryCastSession(
    deviceInfo: DeviceInfo,
    private val listener: Listener
) {
    interface Listener {
        fun onConnectionStateChanged(state: String)
        fun onPlaybackError(message: String)
    }

    private val device: CastingDevice =
        CastContext().createDeviceFromInfo(deviceInfo)

    private val eventHandler = object : DeviceEventHandler {
        override fun connectionStateChanged(state: DeviceConnectionState) {
            listener.onConnectionStateChanged(state.toString())
        }

        override fun volumeChanged(volume: Double) {}
        override fun timeChanged(time: Double) {}
        override fun playbackStateChanged(state: PlaybackState) {}
        override fun durationChanged(duration: Double) {}
        override fun speedChanged(speed: Double) {}
        override fun sourceChanged(source: Source) {}
        override fun playbackError(message: String) {
            listener.onPlaybackError(message)
        }
        override fun tracksAvailable(tracks: List<MediaTrack>) {}
        override fun trackSelected(id: UInt?, typ: MediaTrackType) {}
        override fun playbackStopped() {}
        override fun tracksChanged(tracks: TrackList) {}
        override fun queueChanged(queue: QueueState) {}
        override fun commandError(error: ReceiverError) {
            listener.onPlaybackError(error.toString())
        }
    }

    fun connect() {
        device.connect(
            ApplicationInfo(
                "Morphe",
                "Secondary Cast V2",
                "${Build.MANUFACTURER} ${Build.MODEL}"
            ),
            eventHandler,
            1000uL
        )
    }

    fun disconnect() {
        device.disconnect()
    }
}
