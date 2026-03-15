package com.alexander.carplay.data.automotive

import com.alexander.carplay.data.logging.DiagnosticLogStore
import com.incall.serversdk.power.PowerCallBack
import com.incall.serversdk.power.PowerConstant
import com.incall.serversdk.power.PowerProxy
import com.incall.serversdk.power.ScreenStateCallBack
import com.incall.serversdk.server.OnSrvConnChangeListener

class AutomotivePowerMonitor(
    private val logStore: DiagnosticLogStore,
    private val onSnapshotChanged: (AutomotivePowerSnapshot, String) -> Unit = { _, _ -> },
) {
    companion object {
        private const val SOURCE = "Power"
    }

    private val powerProxy = PowerProxy.getInstance()

    @Volatile
    private var snapshot = AutomotivePowerSnapshot()

    @Volatile
    private var started = false

    private val powerCallback = object : PowerCallBack() {
        override fun onACCStateChange(state: Int) {
            val normalizedState = normalizeState(state)
            applySnapshotUpdate("acc changed") { current ->
                current.copy(accState = normalizedState)
            }
            logStore.info(
                SOURCE,
                "ACC event: ${AutomotivePowerPolicy.describeAccState(normalizedState)} (raw=$state) | ${AutomotivePowerPolicy.describeSnapshot(snapshot)}",
            )
        }

        override fun onPowerStateChanage(state: Int) {
            val normalizedState = normalizeState(state)
            applySnapshotUpdate("power changed") { current ->
                current.copy(powerState = normalizedState)
            }
            logStore.info(
                SOURCE,
                "Power event: ${AutomotivePowerPolicy.describePowerState(normalizedState)} (raw=$state) | ${AutomotivePowerPolicy.describeSnapshot(snapshot)}",
            )
        }

        override fun onScreenSignalChange(screenType: Int, state: Int) {
            val normalizedState = normalizeState(state)
            applySnapshotUpdate("screen signal changed") { current ->
                current.copy(
                    currentScreenSignal = normalizedState ?: current.currentScreenSignal,
                    screenStates = current.screenStates + (screenType to normalizedState.orMinusOne()),
                )
            }
            logStore.info(
                SOURCE,
                "Screen signal event: ${AutomotivePowerPolicy.describeScreenType(screenType)} = ${AutomotivePowerPolicy.describeScreenVisibility(normalizedState)} (raw=$state) | ${AutomotivePowerPolicy.describeSnapshot(snapshot)}",
            )
        }

        override fun onSleeping() {
            applySnapshotUpdate("sleeping") { current ->
                current.copy(powerState = PowerConstant.POWER_UNWOKGING)
            }
            logStore.info(
                SOURCE,
                "Sleeping event received | ${AutomotivePowerPolicy.describeSnapshot(snapshot)}",
            )
        }

        override fun onEssentialTaskReq() {
            logStore.info(
                SOURCE,
                "Essential task request received | ${AutomotivePowerPolicy.describeSnapshot(snapshot)}",
            )
        }
    }

    private val screenStateCallback = ScreenStateCallBack { state ->
        val normalizedState = normalizeState(state)
        applySnapshotUpdate("welcome screen changed") { current ->
            current.copy(welcomeScreenState = normalizedState)
        }
        logStore.info(
            SOURCE,
            "Welcome screen event: ${AutomotivePowerPolicy.describeScreenVisibility(normalizedState)} (raw=$state) | ${AutomotivePowerPolicy.describeSnapshot(snapshot)}",
        )
    }

    private val connChangeListener = OnSrvConnChangeListener { connected ->
        applySnapshotUpdate("power service connection changed") { current ->
            current.copy(powerServiceConnected = connected)
        }
        logStore.info(
            SOURCE,
            "Power service ${if (connected) "connected" else "disconnected"} | ${AutomotivePowerPolicy.describeSnapshot(snapshot)}",
        )
        if (connected) {
            // The SDK only auto re-registers PowerCallBack after reconnect, so we restore
            // the welcome-screen callback here as well and refresh the current snapshot.
            powerProxy.registerScreenStateCallBack(screenStateCallback)
            captureSnapshot("service connected")
        }
    }

    fun start() {
        if (started) return
        started = true
        logStore.info(SOURCE, "Starting automotive power monitor")
        powerProxy.registerConnChangeListener(connChangeListener)
        powerProxy.registerPowerCallBack(powerCallback)
        powerProxy.registerScreenStateCallBack(screenStateCallback)
        captureSnapshot("start")
    }

    fun stop() {
        if (!started) return
        started = false
        logStore.info(SOURCE, "Stopping automotive power monitor")
        powerProxy.unregisterConnChangeListener(connChangeListener)
        powerProxy.unregisterScreenStateCallBack(screenStateCallback)
        powerProxy.unregisterPowerCallBack(powerCallback)
    }

    fun currentSnapshot(): AutomotivePowerSnapshot = snapshot

    private fun captureSnapshot(reason: String) {
        val accState = normalizeState(powerProxy.getACCState())
        val currentScreenSignal = normalizeState(powerProxy.getCurSreenSignal())
        val longPressPower = runCatching { powerProxy.isLongPressPower() }.getOrNull()

        applySnapshotUpdate(reason) { current ->
            current.copy(
                powerServiceConnected = current.powerServiceConnected ||
                    accState != null ||
                    currentScreenSignal != null ||
                    longPressPower != null,
                accState = accState ?: current.accState,
                currentScreenSignal = currentScreenSignal ?: current.currentScreenSignal,
                longPressPower = longPressPower ?: current.longPressPower,
            )
        }

        logStore.info(
            SOURCE,
            "Snapshot($reason): ${AutomotivePowerPolicy.describeSnapshot(snapshot)}",
        )
    }

    private fun applySnapshotUpdate(
        reason: String,
        update: (AutomotivePowerSnapshot) -> AutomotivePowerSnapshot,
    ) {
        val updatedSnapshot = update(snapshot).sanitize()
        snapshot = updatedSnapshot
        onSnapshotChanged(updatedSnapshot, reason)
    }

    private fun AutomotivePowerSnapshot.sanitize(): AutomotivePowerSnapshot {
        val sanitizedScreenStates = screenStates
            .mapNotNull { entry ->
                entry.value.takeIf { it >= 0 }?.let { entry.key to it }
            }
            .toMap()
        return copy(screenStates = sanitizedScreenStates)
    }

    private fun normalizeState(value: Int): Int? = value.takeIf { it >= 0 }

    private fun Int?.orMinusOne(): Int = this ?: -1
}
