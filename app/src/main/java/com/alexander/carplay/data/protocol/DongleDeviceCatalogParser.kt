package com.alexander.carplay.data.protocol

data class DongleKnownDevice(
    val id: String,
    val name: String? = null,
    val type: String? = null,
    val index: String? = null,
)

data class DongleBoxSettingsSnapshot(
    val activeDeviceId: String? = null,
    val activeDeviceName: String? = null,
    val linkType: String? = null,
    val devices: List<DongleKnownDevice> = emptyList(),
)

object DongleDeviceCatalogParser {
    private val devListPattern = Regex(
        "\"DevList\"\\s*:\\s*\\[(.*?)]",
        setOf(RegexOption.DOT_MATCHES_ALL),
    )
    private val objectPattern = Regex(
        "\\{(.*?)\\}",
        setOf(RegexOption.DOT_MATCHES_ALL),
    )

    fun parsePairedList(payload: ByteArray): List<DongleKnownDevice> =
        payload.toString(Charsets.UTF_8)
            .lineSequence()
            .map { it.trim() }
            .filter { it.length > 17 }
            .mapNotNull { line ->
                val rawId = line.substring(0, 17)
                val id = Cpc200Protocol.normalizeDeviceIdentifier(rawId)
                if (id.isBlank()) {
                    null
                } else {
                    DongleKnownDevice(
                        id = id,
                        name = line.substring(17).trim().ifBlank { null },
                    )
                }
            }
            .toList()

    fun parseBoxSettings(payload: ByteArray): DongleBoxSettingsSnapshot? {
        val text = payload.toString(Charsets.UTF_8).trim()
        if (text.isBlank()) return null

        val devices = devListPattern.find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { devListBody ->
                objectPattern.findAll(devListBody)
                    .mapNotNull { match ->
                        parseDeviceBlock(match.groupValues[1])
                    }
                    .toList()
            }
            .orEmpty()

        return DongleBoxSettingsSnapshot(
            activeDeviceId = extractString(text, "btMacAddr")
                ?.let(Cpc200Protocol::normalizeDeviceIdentifier)
                ?.ifBlank { null },
            activeDeviceName = extractString(text, "btName"),
            linkType = extractString(text, "MDLinkType"),
            devices = devices,
        )
    }

    private fun parseDeviceBlock(body: String): DongleKnownDevice? {
        val id = extractString(body, "id")
            ?.let(Cpc200Protocol::normalizeDeviceIdentifier)
            ?.ifBlank { null }
            ?: return null

        return DongleKnownDevice(
            id = id,
            name = extractString(body, "name"),
            type = extractString(body, "type"),
            index = extractString(body, "index"),
        )
    }

    private fun extractString(
        source: String,
        key: String,
    ): String? = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"")
        .find(source)
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()
        ?.ifBlank { null }
}
