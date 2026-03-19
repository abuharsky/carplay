package com.alexander.carplay.data.automotive

import com.google.common.truth.Truth.assertThat
import com.incall.serversdk.power.PowerConstant
import org.junit.Test

class AutomotivePowerPolicyTest {
    @Test
    fun `vehicle is active when ACC is on`() {
        val snapshot = AutomotivePowerSnapshot(accState = PowerConstant.ACC_ON)

        assertThat(AutomotivePowerPolicy.isVehicleActive(snapshot)).isTrue()
    }

    @Test
    fun `vehicle is active when ACC is starting`() {
        val snapshot = AutomotivePowerSnapshot(accState = PowerConstant.ACC_START)

        assertThat(AutomotivePowerPolicy.isVehicleActive(snapshot)).isTrue()
    }

    @Test
    fun `vehicle is inactive when ACC is off`() {
        val snapshot = AutomotivePowerSnapshot(accState = PowerConstant.ACC_OFF)

        assertThat(AutomotivePowerPolicy.isVehicleActive(snapshot)).isFalse()
    }

    @Test
    fun `power state is used as fallback when ACC is unavailable`() {
        val activeSnapshot = AutomotivePowerSnapshot(powerState = PowerConstant.POWER_WORKING)
        val inactiveSnapshot = AutomotivePowerSnapshot(powerState = PowerConstant.POWER_UNWOKGING)

        assertThat(AutomotivePowerPolicy.isVehicleActive(activeSnapshot)).isTrue()
        assertThat(AutomotivePowerPolicy.isVehicleActive(inactiveSnapshot)).isFalse()
    }

    @Test
    fun `auto connect is allowed when ACC is on`() {
        val snapshot = AutomotivePowerSnapshot(accState = PowerConstant.ACC_ON)

        assertThat(AutomotivePowerPolicy.isReadyForAutoConnect(snapshot)).isTrue()
    }

    @Test
    fun `auto connect is allowed when ACC is starting`() {
        val snapshot = AutomotivePowerSnapshot(accState = PowerConstant.ACC_START)

        assertThat(AutomotivePowerPolicy.isReadyForAutoConnect(snapshot)).isTrue()
    }

    @Test
    fun `auto connect uses power state fallback when ACC is unavailable`() {
        val activeSnapshot = AutomotivePowerSnapshot(powerState = PowerConstant.POWER_WORKING)
        val inactiveSnapshot = AutomotivePowerSnapshot(powerState = PowerConstant.POWER_UNWOKGING)

        assertThat(AutomotivePowerPolicy.isReadyForAutoConnect(activeSnapshot)).isTrue()
        assertThat(AutomotivePowerPolicy.isReadyForAutoConnect(inactiveSnapshot)).isFalse()
    }
}
