package com.alexander.carplay.platform.service

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import com.alexander.carplay.data.logging.DiagnosticLogStore

/**
 * Read-only diagnostic observer for active Android playback clients.
 *
 * The callback never changes routes, focus, sessions, or transport state. It only logs
 * normalized snapshots when the platform-reported playback set changes.
 */
class CarPlayAudioPlaybackObserver(
    private val audioManager: AudioManager,
    private val logStore: DiagnosticLogStore,
) {
    companion object {
        private const val SOURCE = "PlaybackDiag"
    }

    private var isRegistered = false
    private var lastSnapshot: String? = null

    private val callback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            logSnapshot("callback", configs)
        }
    }

    fun start() {
        if (isRegistered) return
        runCatching {
            audioManager.registerAudioPlaybackCallback(callback, null)
            isRegistered = true
            logStore.info(SOURCE, "AudioPlaybackCallback registered (read-only)")
            logSnapshot("initial", audioManager.activePlaybackConfigurations.orEmpty())
        }.onFailure { error ->
            logStore.error(SOURCE, "Unable to register AudioPlaybackCallback", error)
        }
    }

    fun stop() {
        if (!isRegistered) return
        runCatching {
            audioManager.unregisterAudioPlaybackCallback(callback)
            logStore.info(SOURCE, "AudioPlaybackCallback unregistered")
        }.onFailure { error ->
            logStore.error(SOURCE, "Unable to unregister AudioPlaybackCallback", error)
        }
        isRegistered = false
        lastSnapshot = null
    }

    private fun logSnapshot(
        reason: String,
        configs: List<AudioPlaybackConfiguration>,
    ) {
        val snapshot = configs
            .map(::describeConfig)
            .sorted()
            .joinToString(" | ")
            .ifBlank { "none" }
        if (snapshot == lastSnapshot) return
        lastSnapshot = snapshot
        logStore.info(SOURCE, "Active playback ($reason): $snapshot")
    }

    private fun describeConfig(config: AudioPlaybackConfiguration): String {
        val attributes = config.audioAttributes
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) config.audioDeviceInfo else null
        val raw = config.toString()
            .replace(Regex("\\s+"), " ")
            .take(200)
        return buildString {
            append(" usage=").append(describeUsage(attributes.usage))
            append(" content=").append(describeContentType(attributes.contentType))
            if (device != null) {
                append(" deviceType=").append(describeDeviceType(device.type))
                append(" deviceId=").append(device.id)
                device.productName
                    ?.toString()
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { append(" device=").append(it) }
            }
            append(" raw=").append(raw)
        }
    }

    private fun describeUsage(usage: Int): String = when (usage) {
        AudioAttributes.USAGE_MEDIA -> "MEDIA"
        AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE -> "NAVIGATION"
        AudioAttributes.USAGE_VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
        AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING -> "VOICE_COMMUNICATION_SIGNALLING"
        AudioAttributes.USAGE_NOTIFICATION_RINGTONE -> "RINGTONE"
        else -> usage.toString()
    }

    private fun describeContentType(contentType: Int): String = when (contentType) {
        AudioAttributes.CONTENT_TYPE_MUSIC -> "MUSIC"
        AudioAttributes.CONTENT_TYPE_SPEECH -> "SPEECH"
        else -> contentType.toString()
    }

    private fun describeDeviceType(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BT_A2DP"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT_SCO"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "SPEAKER"
        else -> type.toString()
    }
}
