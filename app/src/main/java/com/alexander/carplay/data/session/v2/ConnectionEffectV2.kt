package com.alexander.carplay.data.session.v2

sealed interface ConnectionEffectV2 {
    object StartHeartbeat : ConnectionEffectV2

    object StopFrameRequests : ConnectionEffectV2

    object StartBleAdvertising : ConnectionEffectV2

    object StopBleAdvertising : ConnectionEffectV2

    object RequestAutoConnect : ConnectionEffectV2

    object ClearPendingAutoConnect : ConnectionEffectV2

    object RequestKeyFrame : ConnectionEffectV2

    object StartFrameRequests : ConnectionEffectV2

    data class QueueInitSequence(
        val includeBrandingAssets: Boolean,
    ) : ConnectionEffectV2
}
