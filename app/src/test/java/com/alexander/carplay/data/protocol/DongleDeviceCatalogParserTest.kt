package com.alexander.carplay.data.protocol

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DongleDeviceCatalogParserTest {
    @Test
    fun `parsePairedList extracts mac and device names`() {
        val payload = (
            "D0:6B:78:53:8E:0CiPhone Alexander\n" +
                "AA:BB:CC:DD:EE:FFWork Phone\n"
            ).toByteArray(Charsets.UTF_8)

        val devices = DongleDeviceCatalogParser.parsePairedList(payload)

        assertThat(devices).containsExactly(
            DongleKnownDevice(id = "D0:6B:78:53:8E:0C", name = "iPhone Alexander"),
            DongleKnownDevice(id = "AA:BB:CC:DD:EE:FF", name = "Work Phone"),
        ).inOrder()
    }

    @Test
    fun `parseBoxSettings extracts active link and dev list`() {
        val payload = """
            {
              "MDLinkType":"CarPlay",
              "btMacAddr":"D0:6B:78:53:8E:0C",
              "btName":"iPhone Alexander",
              "DevList":[
                {"id":"D0:6B:78:53:8E:0C","type":"CarPlay","name":"iPhone Alexander","index":"1"},
                {"id":"AA:BB:CC:DD:EE:FF","type":"AndroidAuto","name":"Pixel","index":"2"}
              ]
            }
        """.trimIndent().toByteArray(Charsets.UTF_8)

        val snapshot = DongleDeviceCatalogParser.parseBoxSettings(payload)

        assertThat(snapshot).isEqualTo(
            DongleBoxSettingsSnapshot(
                activeDeviceId = "D0:6B:78:53:8E:0C",
                activeDeviceName = "iPhone Alexander",
                linkType = "CarPlay",
                devices = listOf(
                    DongleKnownDevice(
                        id = "D0:6B:78:53:8E:0C",
                        name = "iPhone Alexander",
                        type = "CarPlay",
                        index = "1",
                    ),
                    DongleKnownDevice(
                        id = "AA:BB:CC:DD:EE:FF",
                        name = "Pixel",
                        type = "AndroidAuto",
                        index = "2",
                    ),
                ),
            ),
        )
    }
}
