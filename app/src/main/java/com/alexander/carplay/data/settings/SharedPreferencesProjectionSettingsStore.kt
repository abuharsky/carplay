package com.alexander.carplay.data.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import com.alexander.carplay.domain.model.ProjectionAudioPlayerType
import com.alexander.carplay.domain.model.ProjectionAudioRoute
import com.alexander.carplay.domain.model.ProjectionDeviceSettings
import com.alexander.carplay.domain.model.ProjectionEqPreset
import com.alexander.carplay.domain.model.ProjectionMicRoute
import com.alexander.carplay.domain.model.ProjectionMicSettings
import com.alexander.carplay.domain.model.ProjectionPlayerAudioSettings
import com.alexander.carplay.domain.model.ProjectionSeatAutoComfortSettings
import com.alexander.carplay.domain.model.ProjectionSeatAutoModeSettings
import com.alexander.carplay.domain.model.ProjectionCarPlaySafeAreaBottomDp
import com.alexander.carplay.domain.port.ProjectionSettingsPort
import org.json.JSONArray
import org.json.JSONObject

class SharedPreferencesProjectionSettingsStore(
    context: Context,
) : ProjectionSettingsPort {
    companion object {
        private const val PREFS_NAME = "projection_device_settings"
        private const val KEY_LAST_CONNECTED_DEVICE = "last_connected_device"
        private const val KEY_ADAPTER_NAME = "adapter_name"
        private const val KEY_AUTO_CONNECT_ENABLED = "auto_connect_enabled"
        private const val KEY_CLIMATE_PANEL_ENABLED = "climate_panel_enabled"
        private const val KEY_CARPLAY_SAFE_AREA_BOTTOM_DP = "carplay_safe_area_bottom_dp"
        private const val KEY_PREFIX = "device:"
        private const val KEY_DEVICE_NAME_CACHE_PREFIX = "device_name:"
        private const val KEY_MEDIA_SESSION_FIX_ENABLED = "bt_media_session_fix_enabled"
        private const val KEY_AUDIO_FOCUS_FIX_ENABLED = "bt_audio_focus_fix_enabled"
        private const val KEY_A2DP_DISCONNECT_FIX_ENABLED = "bt_a2dp_disconnect_fix_enabled"
        private const val KEY_MEDIA_SESSION_METADATA_ENABLED = "bt_media_session_metadata_enabled"
        private const val DEFAULT_ADAPTER_NAME_PREFIX = "Carlink-"
        private const val DEFAULT_ADAPTER_NAME_DIGITS = 4
        private const val MAX_ADAPTER_NAME_LENGTH = 16
    }

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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

    override fun getAdapterName(): String {
        return prefs.getString(KEY_ADAPTER_NAME, null)
            ?.let(::normalizeAdapterName)
            ?: buildDefaultAdapterName()
    }

    override fun saveAdapterName(name: String) {
        prefs.edit()
            .putString(KEY_ADAPTER_NAME, normalizeAdapterName(name))
            .apply()
    }

    override fun isAutoConnectEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_CONNECT_ENABLED, true)

    override fun setAutoConnectEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_AUTO_CONNECT_ENABLED, enabled)
            .apply()
    }

    override fun isClimatePanelEnabled(): Boolean = prefs.getBoolean(KEY_CLIMATE_PANEL_ENABLED, true)

    override fun setClimatePanelEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_CLIMATE_PANEL_ENABLED, enabled)
            .apply()
    }

    override fun getCarPlaySafeAreaBottomDp(): Int {
        return ProjectionCarPlaySafeAreaBottomDp.normalize(
            prefs.getInt(
                KEY_CARPLAY_SAFE_AREA_BOTTOM_DP,
                ProjectionCarPlaySafeAreaBottomDp.DEFAULT,
            ),
        )
    }

    override fun setCarPlaySafeAreaBottomDp(bottomDp: Int) {
        prefs.edit()
            .putInt(
                KEY_CARPLAY_SAFE_AREA_BOTTOM_DP,
                ProjectionCarPlaySafeAreaBottomDp.normalize(bottomDp),
            )
            .apply()
    }

    override fun getLastConnectedDeviceId(): String? = prefs.getString(KEY_LAST_CONNECTED_DEVICE, null)

    override fun setLastConnectedDeviceId(deviceId: String?) {
        prefs.edit()
            .putString(KEY_LAST_CONNECTED_DEVICE, deviceId?.takeIf { it.isNotBlank() })
            .apply()
    }

    override fun getCachedDeviceName(deviceId: String?): String? {
        val normalizedId = normalizeDeviceId(deviceId)
        return prefs.getString(KEY_DEVICE_NAME_CACHE_PREFIX + normalizedId, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    override fun setCachedDeviceName(
        deviceId: String?,
        name: String?,
    ) {
        val normalizedId = normalizeDeviceId(deviceId)
        val normalizedName = normalizeCachedDeviceName(name)
        prefs.edit()
            .putString(KEY_DEVICE_NAME_CACHE_PREFIX + normalizedId, normalizedName)
            .apply()
    }

    override fun isMediaSessionFixEnabled(): Boolean =
        prefs.getBoolean(KEY_MEDIA_SESSION_FIX_ENABLED, false)

    override fun setMediaSessionFixEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MEDIA_SESSION_FIX_ENABLED, enabled).apply()
    }

    override fun isAudioFocusFixEnabled(): Boolean =
        prefs.getBoolean(KEY_AUDIO_FOCUS_FIX_ENABLED, true)

    override fun setAudioFocusFixEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUDIO_FOCUS_FIX_ENABLED, enabled).apply()
    }

    override fun isA2dpDisconnectFixEnabled(): Boolean =
        prefs.getBoolean(KEY_A2DP_DISCONNECT_FIX_ENABLED, false)

    override fun setA2dpDisconnectFixEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_A2DP_DISCONNECT_FIX_ENABLED, enabled).apply()
    }

    override fun isMediaSessionMetadataEnabled(): Boolean =
        prefs.getBoolean(KEY_MEDIA_SESSION_METADATA_ENABLED, false)

    override fun setMediaSessionMetadataEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MEDIA_SESSION_METADATA_ENABLED, enabled).apply()
    }

    private fun normalizeDeviceId(deviceId: String?): String {
        return deviceId?.trim()?.takeIf { it.isNotEmpty() } ?: ProjectionDeviceSettings.DEFAULT_DEVICE_ID
    }

    private fun normalizeCachedDeviceName(name: String?): String? {
        return name
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun normalizeAdapterName(name: String?): String {
        val normalized = name
            ?.filter { it.isLetterOrDigit() || it == '_' || it == '-' || it == ' ' }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.take(MAX_ADAPTER_NAME_LENGTH)
        return normalized ?: buildDefaultAdapterName()
    }

    private fun buildDefaultAdapterName(): String {
        val source = Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER}-${Build.MODEL}-${appContext.packageName}"
        val suffix = ((source.hashCode().toLong() and 0x7fffffffL) % 10_000L)
            .toString()
            .padStart(DEFAULT_ADAPTER_NAME_DIGITS, '0')
        return "$DEFAULT_ADAPTER_NAME_PREFIX$suffix"
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
            put("driverSeatAutoComfort", encodeSeatAutoComfortSettings(settings.driverSeatAutoComfort))
            put("passengerSeatAutoComfort", encodeSeatAutoComfortSettings(settings.passengerSeatAutoComfort))
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
            driverSeatAutoComfort = decodeSeatAutoComfortSettings(
                json.optJSONObject("driverSeatAutoComfort"),
                defaults.driverSeatAutoComfort,
            ),
            passengerSeatAutoComfort = decodeSeatAutoComfortSettings(
                json.optJSONObject("passengerSeatAutoComfort"),
                defaults.passengerSeatAutoComfort,
            ),
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

    private fun encodeSeatAutoComfortSettings(settings: ProjectionSeatAutoComfortSettings): JSONObject {
        return JSONObject().apply {
            put("heat", encodeSeatAutoModeSettings(settings.heat))
            put("vent", encodeSeatAutoModeSettings(settings.vent))
        }
    }

    private fun encodeSeatAutoModeSettings(settings: ProjectionSeatAutoModeSettings): JSONObject {
        return JSONObject().apply {
            put("enabled", settings.enabled)
            put("thresholdC", settings.thresholdC)
            put("startLevel", settings.startLevel)
            put("durationMinutes", settings.durationMinutes)
        }
    }

    private fun decodeSeatAutoComfortSettings(
        json: JSONObject?,
        defaults: ProjectionSeatAutoComfortSettings,
    ): ProjectionSeatAutoComfortSettings {
        if (json == null) return defaults
        return defaults.copy(
            heat = decodeSeatAutoModeSettings(json.optJSONObject("heat"), defaults.heat),
            vent = decodeSeatAutoModeSettings(json.optJSONObject("vent"), defaults.vent),
        )
    }

    private fun decodeSeatAutoModeSettings(
        json: JSONObject?,
        defaults: ProjectionSeatAutoModeSettings,
    ): ProjectionSeatAutoModeSettings {
        if (json == null) return defaults
        return defaults.copy(
            enabled = json.optBoolean("enabled", defaults.enabled),
            thresholdC = json.optInt("thresholdC", defaults.thresholdC),
            startLevel = json.optInt("startLevel", defaults.startLevel).coerceIn(1, 3),
            durationMinutes = json.optInt("durationMinutes", defaults.durationMinutes),
        )
    }
}
