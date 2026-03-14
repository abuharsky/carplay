package com.alexander.carplay.data.settings

import android.content.Context
import android.content.SharedPreferences
import com.alexander.carplay.domain.model.ProjectionAudioPlayerType
import com.alexander.carplay.domain.model.ProjectionAudioRoute
import com.alexander.carplay.domain.model.ProjectionDeviceSettings
import com.alexander.carplay.domain.model.ProjectionEqPreset
import com.alexander.carplay.domain.model.ProjectionMicRoute
import com.alexander.carplay.domain.model.ProjectionMicSettings
import com.alexander.carplay.domain.model.ProjectionPlayerAudioSettings
import com.alexander.carplay.domain.port.ProjectionSettingsPort
import org.json.JSONArray
import org.json.JSONObject

class SharedPreferencesProjectionSettingsStore(
    context: Context,
) : ProjectionSettingsPort {
    companion object {
        private const val PREFS_NAME = "projection_device_settings"
        private const val KEY_LAST_CONNECTED_DEVICE = "last_connected_device"
        private const val KEY_PREFIX = "device:"
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun getSettings(deviceId: String?): ProjectionDeviceSettings {
        val normalizedId = normalizeDeviceId(deviceId)
        val rawJson = prefs.getString(KEY_PREFIX + normalizedId, null) ?: return ProjectionDeviceSettings.defaults(normalizedId)
        return runCatching { decodeSettings(normalizedId, JSONObject(rawJson)) }
            .getOrElse { ProjectionDeviceSettings.defaults(normalizedId) }
    }

    override fun saveSettings(settings: ProjectionDeviceSettings) {
        val normalizedId = normalizeDeviceId(settings.deviceId)
        prefs.edit()
            .putString(KEY_PREFIX + normalizedId, encodeSettings(settings.copy(deviceId = normalizedId)).toString())
            .apply()
    }

    override fun getLastConnectedDeviceId(): String? = prefs.getString(KEY_LAST_CONNECTED_DEVICE, null)

    override fun setLastConnectedDeviceId(deviceId: String?) {
        prefs.edit()
            .putString(KEY_LAST_CONNECTED_DEVICE, deviceId?.takeIf { it.isNotBlank() })
            .apply()
    }

    private fun normalizeDeviceId(deviceId: String?): String {
        return deviceId?.trim()?.takeIf { it.isNotEmpty() } ?: ProjectionDeviceSettings.DEFAULT_DEVICE_ID
    }

    private fun encodeSettings(settings: ProjectionDeviceSettings): JSONObject {
        return JSONObject().apply {
            put("audioRoute", settings.audioRoute.name)
            put("micRoute", settings.micRoute.name)
            put("selectedPlayer", settings.selectedPlayer.name)
            put("micGainMultiplier", settings.micSettings.gainMultiplier.toDouble())

            val players = JSONObject()
            settings.playerSettings.forEach { (player, playerSettings) ->
                players.put(player.name, JSONObject().apply {
                    put("gainMultiplier", playerSettings.gainMultiplier.toDouble())
                    put("loudnessBoostPercent", playerSettings.loudnessBoostPercent)
                    put("bassBoostPercent", playerSettings.bassBoostPercent)
                    put("eqPreset", playerSettings.eqPreset.name)
                    put("eqBandsDb", JSONArray().apply {
                        playerSettings.eqBandsDb.forEach { put(it.toDouble()) }
                    })
                })
            }
            put("players", players)
        }
    }

    private fun decodeSettings(
        deviceId: String,
        json: JSONObject,
    ): ProjectionDeviceSettings {
        val defaults = ProjectionDeviceSettings.defaults(deviceId)
        val playersJson = json.optJSONObject("players")

        val playerSettings = ProjectionAudioPlayerType.entries.associateWith { player ->
            decodePlayerSettings(playersJson?.optJSONObject(player.name))
        }

        return defaults.copy(
            audioRoute = json.optString("audioRoute")
                .takeIf { it.isNotBlank() }
                ?.let { runCatching { ProjectionAudioRoute.valueOf(it) }.getOrNull() }
                ?: defaults.audioRoute,
            micRoute = json.optString("micRoute")
                .takeIf { it.isNotBlank() }
                ?.let { runCatching { ProjectionMicRoute.valueOf(it) }.getOrNull() }
                ?: defaults.micRoute,
            selectedPlayer = json.optString("selectedPlayer")
                .takeIf { it.isNotBlank() }
                ?.let { runCatching { ProjectionAudioPlayerType.valueOf(it) }.getOrNull() }
                ?: defaults.selectedPlayer,
            micSettings = ProjectionMicSettings(
                gainMultiplier = json.optDouble("micGainMultiplier", defaults.micSettings.gainMultiplier.toDouble()).toFloat(),
            ),
            playerSettings = playerSettings,
        )
    }

    private fun decodePlayerSettings(json: JSONObject?): ProjectionPlayerAudioSettings {
        val defaults = ProjectionPlayerAudioSettings()
        if (json == null) return defaults

        val eqArray = json.optJSONArray("eqBandsDb")
        val eqBands = buildList(10) {
            repeat(10) { index ->
                add(eqArray?.optDouble(index, 0.0)?.toFloat() ?: 0f)
            }
        }

        return defaults.copy(
            gainMultiplier = json.optDouble("gainMultiplier", defaults.gainMultiplier.toDouble()).toFloat(),
            loudnessBoostPercent = json.optInt("loudnessBoostPercent", defaults.loudnessBoostPercent),
            bassBoostPercent = json.optInt("bassBoostPercent", defaults.bassBoostPercent),
            eqPreset = json.optString("eqPreset")
                .takeIf { it.isNotBlank() }
                ?.let { runCatching { ProjectionEqPreset.valueOf(it) }.getOrNull() }
                ?: ProjectionEqPreset.detect(eqBands),
            eqBandsDb = eqBands,
        )
    }
}
