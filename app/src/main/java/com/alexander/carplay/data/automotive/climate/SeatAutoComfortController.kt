package com.alexander.carplay.data.automotive.climate

import android.os.SystemClock
import com.alexander.carplay.data.automotive.AutomotivePowerMonitor
import com.alexander.carplay.data.automotive.AutomotivePowerSnapshot
import com.alexander.carplay.data.logging.DiagnosticLogStore
import com.alexander.carplay.domain.model.ClimateSnapshot
import com.alexander.carplay.domain.model.ProjectionSeatAutoComfortSettings
import com.alexander.carplay.domain.model.ProjectionSeatAutoModeSettings
import com.alexander.carplay.domain.model.nextProjectionSeatAutoTransitionDelayMillis
import com.alexander.carplay.domain.model.resolveProjectionSeatAutoLevel
import com.alexander.carplay.domain.port.ProjectionSettingsPort
import com.incall.serversdk.power.PowerConstant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

class SeatAutoComfortController(
    private val climateController: ClimateController,
    private val settingsPort: ProjectionSettingsPort,
    private val logStore: DiagnosticLogStore,
) {
    companion object {
        private const val SOURCE = "SeatAuto"
        private const val CLIMATE_CLIENT_ID = "seat-auto"
        private const val CLIMATE_RETRY_DELAY_MS = 5_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val powerSnapshotFlow = MutableStateFlow(AutomotivePowerSnapshot())
    private val powerMonitor = AutomotivePowerMonitor(logStore) { snapshot, reason ->
        powerSnapshotFlow.value = snapshot
        requestEvaluation("power changed: $reason")
    }

    private var climateCollectionJob: Job? = null
    private var evaluationJob: Job? = null
    private var scheduledEvaluationJob: Job? = null

    private var driverRuntime: SeatAutomationRuntime? = null
    private var passengerRuntime: SeatAutomationRuntime? = null
    private val suspendedSeats = mutableSetOf<SeatId>()

    @Volatile
    private var started = false

    fun start() {
        if (started) return
        started = true
        logStore.info(SOURCE, "Starting seat auto comfort controller")
        logStore.info(SOURCE, "Temperature source: HVAC zone setpoint proxy (driver/passenger)")
        climateController.connect(CLIMATE_CLIENT_ID)
        powerMonitor.start()
        powerSnapshotFlow.value = powerMonitor.currentSnapshot()
        climateCollectionJob = scope.launch {
            climateController.snapshot.collectLatest {
                requestEvaluation("climate snapshot changed")
            }
        }
        requestEvaluation("start")
    }

    fun stop() {
        if (!started) return
        started = false
        scheduledEvaluationJob?.cancel()
        evaluationJob?.cancel()
        climateCollectionJob?.cancel()
        clearAutomatedSeats("controller stopped")
        powerMonitor.stop()
        climateController.disconnect(CLIMATE_CLIENT_ID)
        scope.cancel()
    }

    fun refreshNow(reason: String = "manual refresh") {
        requestEvaluation(reason)
    }

    fun suspendSeatAutomation(
        isDriver: Boolean,
        reason: String,
    ) {
        val seatId = if (isDriver) SeatId.DRIVER else SeatId.PASSENGER
        if (suspendedSeats.add(seatId)) {
            logStore.info(SOURCE, "${seatId.label} auto suspended until ignition off: $reason")
        }
        if (seatId == SeatId.DRIVER) {
            driverRuntime = null
        } else {
            passengerRuntime = null
        }
        requestEvaluation("manual seat override: ${seatId.label.lowercase(Locale.US)}")
    }

    private fun requestEvaluation(reason: String) {
        if (!started) return
        evaluationJob?.cancel()
        evaluationJob = scope.launch {
            evaluate(reason)
        }
    }

    private fun evaluate(reason: String) {
        val climateSnapshot = climateController.snapshot.value
        val powerSnapshot = powerSnapshotFlow.value
        val ignitionOn = isIgnitionAvailable(powerSnapshot)

        if (!ignitionOn) {
            clearAutomatedSeats("ignition unavailable: $reason")
            if (suspendedSeats.isNotEmpty()) {
                logStore.info(SOURCE, "Clearing manual seat auto suspensions on ignition off")
                suspendedSeats.clear()
            }
            cancelScheduledEvaluation()
            return
        }

        if (!climateSnapshot.isConnected) {
            climateController.connect(CLIMATE_CLIENT_ID)
            scheduleNextEvaluation(CLIMATE_RETRY_DELAY_MS)
            return
        }

        val activeDeviceId = settingsPort.getLastConnectedDeviceId()
        val settings = settingsPort.getSettings(activeDeviceId)
        val now = SystemClock.elapsedRealtime()

        val driverEvaluation = evaluateSeat(
            seatId = SeatId.DRIVER,
            tempProxyC = climateSnapshot.driverTemp,
            comfortSettings = settings.driverSeatAutoComfort,
            climateSnapshot = climateSnapshot,
            currentRuntime = driverRuntime,
            nowElapsedMs = now,
            reason = reason,
        )
        val passengerEvaluation = evaluateSeat(
            seatId = SeatId.PASSENGER,
            tempProxyC = climateSnapshot.passengerTemp,
            comfortSettings = settings.passengerSeatAutoComfort,
            climateSnapshot = climateSnapshot,
            currentRuntime = passengerRuntime,
            nowElapsedMs = now,
            reason = reason,
        )

        driverRuntime = driverEvaluation.runtime
        passengerRuntime = passengerEvaluation.runtime

        scheduleNextEvaluation(
            listOfNotNull(driverEvaluation.nextDelayMs, passengerEvaluation.nextDelayMs).minOrNull(),
        )
    }

    private fun evaluateSeat(
        seatId: SeatId,
        tempProxyC: Float,
        comfortSettings: ProjectionSeatAutoComfortSettings,
        climateSnapshot: ClimateSnapshot,
        currentRuntime: SeatAutomationRuntime?,
        nowElapsedMs: Long,
        reason: String,
    ): SeatEvaluation {
        if (seatId in suspendedSeats) {
            return SeatEvaluation(runtime = null, nextDelayMs = null)
        }

        val desiredMode = selectDesiredMode(tempProxyC, comfortSettings)
        if (desiredMode == null) {
            if (currentRuntime != null) {
                logStore.info(
                    SOURCE,
                    "${seatId.label} auto stopped: proxy=${formatTemp(tempProxyC)}C outside thresholds | $reason",
                )
                climateController.applySeatComfort(
                    isDriver = seatId.isDriver,
                    heatLevel = 0,
                    ventLevel = 0,
                    reason = "seat auto stop (${seatId.label.lowercase(Locale.US)})",
                )
            }
            return SeatEvaluation(runtime = null, nextDelayMs = null)
        }

        val runtime = if (currentRuntime == null ||
            currentRuntime.mode != desiredMode.mode ||
            currentRuntime.settings != desiredMode.settings
        ) {
            logStore.info(
                SOURCE,
                "${seatId.label} auto started: mode=${desiredMode.mode.name.lowercase(Locale.US)} " +
                    "proxy=${formatTemp(tempProxyC)}C threshold=${desiredMode.settings.thresholdC}C " +
                    "start=${desiredMode.settings.startLevel} duration=${desiredMode.settings.durationMinutes}m",
            )
            SeatAutomationRuntime(
                mode = desiredMode.mode,
                settings = desiredMode.settings,
                startedAtElapsedMs = nowElapsedMs,
            )
        } else {
            currentRuntime
        }

        val elapsedMs = (nowElapsedMs - runtime.startedAtElapsedMs).coerceAtLeast(0L)
        val activeLevel = resolveProjectionSeatAutoLevel(
            startLevel = runtime.settings.startLevel,
            totalMinutes = runtime.settings.durationMinutes,
            elapsedMillis = elapsedMs,
        )

        if (activeLevel == null) {
            logStore.info(
                SOURCE,
                "${seatId.label} auto finished: mode=${runtime.mode.name.lowercase(Locale.US)} duration=${runtime.settings.durationMinutes}m",
            )
            climateController.applySeatComfort(
                isDriver = seatId.isDriver,
                heatLevel = 0,
                ventLevel = 0,
                reason = "seat auto finished (${seatId.label.lowercase(Locale.US)})",
            )
            return SeatEvaluation(runtime = null, nextDelayMs = null)
        }

        val expectedHeatLevel = if (runtime.mode == SeatAutomationMode.HEAT) activeLevel else 0
        val expectedVentLevel = if (runtime.mode == SeatAutomationMode.VENT) activeLevel else 0
        val currentHeatLevel = if (seatId.isDriver) climateSnapshot.driverSeatHeat else climateSnapshot.passengerSeatHeat
        val currentVentLevel = if (seatId.isDriver) climateSnapshot.driverSeatVent else climateSnapshot.passengerSeatVent

        if (currentHeatLevel != expectedHeatLevel || currentVentLevel != expectedVentLevel) {
            climateController.applySeatComfort(
                isDriver = seatId.isDriver,
                heatLevel = expectedHeatLevel,
                ventLevel = expectedVentLevel,
                reason = "seat auto ${runtime.mode.name.lowercase(Locale.US)} level=$activeLevel",
            )
            logStore.info(
                SOURCE,
                "${seatId.label} auto apply: mode=${runtime.mode.name.lowercase(Locale.US)} " +
                    "level=$activeLevel elapsed=${elapsedMs / 1000}s proxy=${formatTemp(tempProxyC)}C",
            )
        }

        return SeatEvaluation(
            runtime = runtime,
            nextDelayMs = nextProjectionSeatAutoTransitionDelayMillis(
                startLevel = runtime.settings.startLevel,
                totalMinutes = runtime.settings.durationMinutes,
                elapsedMillis = elapsedMs,
            ),
        )
    }

    private fun selectDesiredMode(
        tempProxyC: Float,
        settings: ProjectionSeatAutoComfortSettings,
    ): DesiredSeatAutomation? {
        if (settings.heat.enabled && tempProxyC <= settings.heat.thresholdC.toFloat()) {
            return DesiredSeatAutomation(
                mode = SeatAutomationMode.HEAT,
                settings = settings.heat,
            )
        }
        if (settings.vent.enabled && tempProxyC >= settings.vent.thresholdC.toFloat()) {
            return DesiredSeatAutomation(
                mode = SeatAutomationMode.VENT,
                settings = settings.vent,
            )
        }
        return null
    }

    private fun clearAutomatedSeats(reason: String) {
        if (driverRuntime != null) {
            climateController.applySeatComfort(
                isDriver = true,
                heatLevel = 0,
                ventLevel = 0,
                reason = "seat auto clear (driver): $reason",
            )
            logStore.info(SOURCE, "Driver auto cleared: $reason")
            driverRuntime = null
        }
        if (passengerRuntime != null) {
            climateController.applySeatComfort(
                isDriver = false,
                heatLevel = 0,
                ventLevel = 0,
                reason = "seat auto clear (passenger): $reason",
            )
            logStore.info(SOURCE, "Passenger auto cleared: $reason")
            passengerRuntime = null
        }
    }

    private fun scheduleNextEvaluation(nextDelayMs: Long?) {
        cancelScheduledEvaluation()
        val delayMs = nextDelayMs ?: return
        scheduledEvaluationJob = scope.launch {
            delay(delayMs)
            evaluate("scheduled decay transition")
        }
    }

    private fun cancelScheduledEvaluation() {
        scheduledEvaluationJob?.cancel()
        scheduledEvaluationJob = null
    }

    private fun formatTemp(value: Float): String = String.format(Locale.US, "%.1f", value)

    private fun isIgnitionAvailable(snapshot: AutomotivePowerSnapshot): Boolean {
        snapshot.accState?.let { accState ->
            return accState == PowerConstant.ACC_ON || accState == PowerConstant.ACC_START
        }
        snapshot.powerState?.let { powerState ->
            return powerState == PowerConstant.POWER_WORKING
        }
        return false
    }

    private data class DesiredSeatAutomation(
        val mode: SeatAutomationMode,
        val settings: ProjectionSeatAutoModeSettings,
    )

    private data class SeatEvaluation(
        val runtime: SeatAutomationRuntime?,
        val nextDelayMs: Long?,
    )

    private data class SeatAutomationRuntime(
        val mode: SeatAutomationMode,
        val settings: ProjectionSeatAutoModeSettings,
        val startedAtElapsedMs: Long,
    )

    private enum class SeatAutomationMode {
        HEAT,
        VENT,
    }

    private enum class SeatId(
        val isDriver: Boolean,
        val label: String,
    ) {
        DRIVER(isDriver = true, label = "Driver"),
        PASSENGER(isDriver = false, label = "Passenger"),
    }
}
