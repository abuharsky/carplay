package com.alexander.carplay.platform.service

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import com.alexander.carplay.data.logging.DiagnosticLogStore

/**
 * Requests and holds [AudioManager.AUDIOFOCUS_GAIN] for the duration of a CarPlay streaming
 * session.
 *
 * When focus is held, Android's audio system registers this app as the active media owner.
 * This suppresses the Bluetooth AVRCP "play" notifications that the system would otherwise
 * send to a paired iPhone when an audio stream starts without a declared focus owner —
 * preventing the iPhone from switching out of CarPlay audio mode.
 */
class CarPlayAudioFocusManager(
    private val audioManager: AudioManager,
    private val logStore: DiagnosticLogStore,
) {
    companion object {
        private const val SOURCE = "AudioFocusFix"
    }

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        // Intentional no-op: we hold focus for the entire session.
        // Log the event for diagnostics without releasing focus.
        logStore.info(
            SOURCE,
            "AudioFocus change received: focusChange=${describeFocusChange(focusChange)} (CarPlay retains focus)",
        )
    }

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        .setOnAudioFocusChangeListener(focusChangeListener)
        .setWillPauseWhenDucked(false)
        .setAcceptsDelayedFocusGain(false)
        .build()

    private var hasFocus = false

    fun requestFocus() {
        if (hasFocus) return
        val result = audioManager.requestAudioFocus(focusRequest)
        hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        logStore.info(
            SOURCE,
            "AudioFocus request: ${if (hasFocus) "GRANTED" else "DENIED (result=$result)"} " +
                "usage=MEDIA content=MUSIC",
        )
    }

    fun abandonFocus() {
        if (!hasFocus) return
        audioManager.abandonAudioFocusRequest(focusRequest)
        hasFocus = false
        logStore.info(SOURCE, "AudioFocus abandoned")
    }

    fun hasFocus(): Boolean = hasFocus

    private fun describeFocusChange(focusChange: Int): String = when (focusChange) {
        AudioManager.AUDIOFOCUS_GAIN -> "GAIN"
        AudioManager.AUDIOFOCUS_LOSS -> "LOSS"
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "LOSS_TRANSIENT"
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "LOSS_TRANSIENT_CAN_DUCK"
        else -> focusChange.toString()
    }
}
