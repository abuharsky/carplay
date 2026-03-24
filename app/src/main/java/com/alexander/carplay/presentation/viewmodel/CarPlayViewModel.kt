package com.alexander.carplay.presentation.viewmodel

import android.view.MotionEvent
import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alexander.carplay.R
import com.alexander.carplay.domain.model.DiagnosticLogEntry
import com.alexander.carplay.domain.model.ProjectionConnectionState
import com.alexander.carplay.domain.model.ProjectionDeviceSettings
import com.alexander.carplay.domain.model.ProjectionDeviceSnapshot
import com.alexander.carplay.domain.model.ProjectionProtocolPhase
import com.alexander.carplay.domain.model.ProjectionSessionSnapshot
import com.alexander.carplay.domain.model.ProjectionUiEvent
import com.alexander.carplay.domain.usecase.AttachProjectionSurfaceUseCase
import com.alexander.carplay.domain.usecase.BindProjectionUiUseCase
import com.alexander.carplay.domain.usecase.CancelProjectionDeviceConnectionUseCase
import com.alexander.carplay.domain.usecase.DetachProjectionSurfaceUseCase
import com.alexander.carplay.domain.usecase.LoadProjectionAdapterNameUseCase
import com.alexander.carplay.domain.usecase.LoadProjectionAutoConnectUseCase
import com.alexander.carplay.domain.usecase.LoadProjectionAdapterDpiUseCase
import com.alexander.carplay.domain.usecase.LoadProjectionClimatePanelEnabledUseCase
import com.alexander.carplay.domain.usecase.LoadProjectionDeviceSettingsUseCase
import com.alexander.carplay.domain.usecase.ObserveDiagnosticLogsUseCase
import com.alexander.carplay.domain.usecase.ObserveProjectionUiEventsUseCase
import com.alexander.carplay.domain.usecase.ObserveProjectionStateUseCase
import com.alexander.carplay.domain.usecase.PreviewProjectionRuntimeSettingsUseCase
import com.alexander.carplay.domain.usecase.RefreshProjectionRuntimeSettingsUseCase
import com.alexander.carplay.domain.usecase.RequestProjectionReconnectUseCase
import com.alexander.carplay.domain.usecase.SaveProjectionAdapterNameUseCase
import com.alexander.carplay.domain.usecase.SaveProjectionAutoConnectUseCase
import com.alexander.carplay.domain.usecase.SaveProjectionAdapterDpiUseCase
import com.alexander.carplay.domain.usecase.SaveProjectionClimatePanelEnabledUseCase
import com.alexander.carplay.domain.usecase.SaveProjectionDeviceSettingsUseCase
import com.alexander.carplay.domain.usecase.SendProjectionMotionUseCase
import com.alexander.carplay.domain.usecase.SelectProjectionDeviceUseCase
import com.alexander.carplay.domain.usecase.SetProjectionVideoStreamEnabledUseCase
import com.alexander.carplay.domain.usecase.StartReplaySessionUseCase
import com.alexander.carplay.domain.usecase.StartProjectionServiceUseCase
import com.alexander.carplay.domain.usecase.StartUsbSessionUseCase
import com.alexander.carplay.domain.usecase.UnbindProjectionUiUseCase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CarPlayUiState(
    val stateLabel: String,
    val statusMessage: String,
    val protocolPhaseLabel: String?,
    val protocolPhaseTitle: String?,
    val overlayColorRes: Int,
    val showConnectButton: Boolean,
    val showConnectionOverlay: Boolean,
    val isStreaming: Boolean,
    val devices: List<CarPlayDeviceUiState>,
    val diagnosticsText: String,
    val videoWidth: Int?,
    val videoHeight: Int?,
)

data class CarPlayDeviceUiState(
    val id: String,
    val title: String,
    val subtitle: String,
    val isAvailable: Boolean,
    val isActive: Boolean,
    val isSelected: Boolean,
    val isConnecting: Boolean,
)

class CarPlayViewModel(
    observeProjectionStateUseCase: ObserveProjectionStateUseCase,
    observeDiagnosticLogsUseCase: ObserveDiagnosticLogsUseCase,
    observeProjectionUiEventsUseCase: ObserveProjectionUiEventsUseCase,
    private val startProjectionServiceUseCase: StartProjectionServiceUseCase,
    private val startUsbSessionUseCase: StartUsbSessionUseCase,
    private val startReplaySessionUseCase: StartReplaySessionUseCase,
    private val bindProjectionUiUseCase: BindProjectionUiUseCase,
    private val unbindProjectionUiUseCase: UnbindProjectionUiUseCase,
    private val requestProjectionReconnectUseCase: RequestProjectionReconnectUseCase,
    private val refreshProjectionRuntimeSettingsUseCase: RefreshProjectionRuntimeSettingsUseCase,
    private val previewProjectionRuntimeSettingsUseCase: PreviewProjectionRuntimeSettingsUseCase,
    private val setProjectionVideoStreamEnabledUseCase: SetProjectionVideoStreamEnabledUseCase,
    private val loadProjectionDeviceSettingsUseCase: LoadProjectionDeviceSettingsUseCase,
    private val saveProjectionDeviceSettingsUseCase: SaveProjectionDeviceSettingsUseCase,
    private val loadProjectionAdapterNameUseCase: LoadProjectionAdapterNameUseCase,
    private val saveProjectionAdapterNameUseCase: SaveProjectionAdapterNameUseCase,
    private val loadProjectionAutoConnectUseCase: LoadProjectionAutoConnectUseCase,
    private val saveProjectionAutoConnectUseCase: SaveProjectionAutoConnectUseCase,
    private val loadProjectionAdapterDpiUseCase: LoadProjectionAdapterDpiUseCase,
    private val saveProjectionAdapterDpiUseCase: SaveProjectionAdapterDpiUseCase,
    private val loadProjectionClimatePanelEnabledUseCase: LoadProjectionClimatePanelEnabledUseCase,
    private val saveProjectionClimatePanelEnabledUseCase: SaveProjectionClimatePanelEnabledUseCase,
    private val selectProjectionDeviceUseCase: SelectProjectionDeviceUseCase,
    private val cancelProjectionDeviceConnectionUseCase: CancelProjectionDeviceConnectionUseCase,
    private val attachProjectionSurfaceUseCase: AttachProjectionSurfaceUseCase,
    private val detachProjectionSurfaceUseCase: DetachProjectionSurfaceUseCase,
    private val sendProjectionMotionUseCase: SendProjectionMotionUseCase,
    private val refreshSeatAutoComfort: () -> Unit,
) : ViewModel() {
    companion object {
        const val DEFAULT_REPLAY_CAPTURE_PATH =
            "/data/local/tmp/test-capture.bin"
    }

    private val timeFormatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val projectionState = observeProjectionStateUseCase()

    val sessionSnapshot: StateFlow<ProjectionSessionSnapshot> = projectionState
    val uiEvents: SharedFlow<ProjectionUiEvent> = observeProjectionUiEventsUseCase()

    val uiState: StateFlow<CarPlayUiState> = combine(
        projectionState,
        observeDiagnosticLogsUseCase(),
    ) { snapshot, logs ->
        snapshot.toUiState(logs)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ProjectionSessionSnapshot().toUiState(emptyList()),
    )

    fun onStart() {
        startProjectionServiceUseCase()
    }

    fun onBindUi() {
        startProjectionServiceUseCase()
        bindProjectionUiUseCase()
        setProjectionVideoStreamEnabledUseCase(true)
    }

    fun onUnbindUi() {
        setProjectionVideoStreamEnabledUseCase(false)
        detachProjectionSurfaceUseCase()
        unbindProjectionUiUseCase()
    }

    fun onWindowFocusGained() {
        setProjectionVideoStreamEnabledUseCase(true)
    }

    fun onConnectClicked() {
        startUsbSessionUseCase()
    }

    fun onReplayClicked(
        capturePath: String = DEFAULT_REPLAY_CAPTURE_PATH,
    ) {
        startReplaySessionUseCase(capturePath)
    }

    fun onDeviceSelected(deviceId: String) {
        selectProjectionDeviceUseCase(deviceId)
    }

    fun onCancelDeviceConnection() {
        cancelProjectionDeviceConnectionUseCase()
    }

    fun onSurfaceAvailable(surface: Surface) {
        attachProjectionSurfaceUseCase(surface)
    }

    fun onSurfaceDestroyed() {
        detachProjectionSurfaceUseCase()
    }

    fun onTouchEvent(
        event: MotionEvent,
        surfaceWidth: Int,
        surfaceHeight: Int,
    ) {
        sendProjectionMotionUseCase(event, surfaceWidth, surfaceHeight)
    }

    fun loadDeviceSettings(deviceId: String?): ProjectionDeviceSettings {
        return loadProjectionDeviceSettingsUseCase(deviceId)
    }

    fun loadAdapterName(): String {
        return loadProjectionAdapterNameUseCase()
    }

    fun saveAdapterName(name: String) {
        saveProjectionAdapterNameUseCase(name)
    }

    fun loadAutoConnectEnabled(): Boolean {
        return loadProjectionAutoConnectUseCase()
    }

    fun saveAutoConnectEnabled(enabled: Boolean) {
        saveProjectionAutoConnectUseCase(enabled)
    }

    fun loadAdapterDpi(): Int {
        return loadProjectionAdapterDpiUseCase()
    }

    fun saveAdapterDpi(dpi: Int) {
        saveProjectionAdapterDpiUseCase(dpi)
    }

    fun loadClimatePanelEnabled(): Boolean {
        return loadProjectionClimatePanelEnabledUseCase()
    }

    fun saveClimatePanelEnabled(enabled: Boolean) {
        saveProjectionClimatePanelEnabledUseCase(enabled)
    }

    fun disconnectAndDisableAutoConnect() {
        saveProjectionAutoConnectUseCase(false)
        cancelProjectionDeviceConnectionUseCase()
    }

    fun saveDeviceSettings(
        settings: ProjectionDeviceSettings,
        reconnectRequired: Boolean,
    ) {
        saveProjectionDeviceSettingsUseCase(settings)
        refreshSeatAutoComfort()
        if (reconnectRequired) {
            requestProjectionReconnectUseCase()
        } else {
            refreshProjectionRuntimeSettingsUseCase()
        }
    }

    fun previewDeviceSettings(settings: ProjectionDeviceSettings) {
        previewProjectionRuntimeSettingsUseCase(settings)
    }

    fun restoreSavedRuntimeSettings() {
        refreshProjectionRuntimeSettingsUseCase()
    }

    private fun ProjectionSessionSnapshot.toUiState(logs: List<DiagnosticLogEntry>): CarPlayUiState {
        val stateLabel = when (state) {
            ProjectionConnectionState.IDLE -> "IDLE"
            ProjectionConnectionState.WAITING_VEHICLE -> "WAITING_VEHICLE"
            ProjectionConnectionState.SEARCHING -> "SEARCHING"
            ProjectionConnectionState.WAITING_PERMISSION -> "WAITING_PERMISSION"
            ProjectionConnectionState.CONNECTING -> "CONNECTING"
            ProjectionConnectionState.INIT -> "INIT"
            ProjectionConnectionState.WAITING_PHONE -> "WAITING_PHONE"
            ProjectionConnectionState.STREAMING -> "STREAMING"
            ProjectionConnectionState.ERROR -> "ERROR"
        }

        val metadata = buildList {
            if (protocolPhase != ProjectionProtocolPhase.NONE) {
                add("phase=${protocolPhase.shortLabel}:${protocolPhase.title}")
            }
            adapterDescription?.let { add("adapter=$it") }
            phoneDescription?.let { add("phone=$it") }
            streamDescription?.let { add("stream=$it") }
            lastError?.let { add("error=$it") }
        }.joinToString(" | ")

        val diagnosticsText = buildString {
            if (metadata.isNotBlank()) {
                append(metadata)
                append('\n')
                append('\n')
            }
            if (logs.isEmpty()) {
                append("Логи пока пусты")
            } else {
                logs.forEach { entry ->
                    append(timeFormatter.format(Date(entry.timestampMillis)))
                    append("  ")
                    append(entry.source)
                    append("  ")
                    append(entry.message)
                    append('\n')
                }
            }
        }.trimEnd()

        val deviceItems = devices.map { it.toUiModel() }
        val showConnectionOverlay = state != ProjectionConnectionState.STREAMING || !surfaceAttached
        val overlayStatusMessage = overlayStatusMessage()

        return CarPlayUiState(
            stateLabel = stateLabel,
            statusMessage = overlayStatusMessage,
            protocolPhaseLabel = protocolPhase.takeIf { it != ProjectionProtocolPhase.NONE }?.shortLabel,
            protocolPhaseTitle = protocolPhase.takeIf { it != ProjectionProtocolPhase.NONE }?.overlayTitle(),
            overlayColorRes = when (state) {
                ProjectionConnectionState.IDLE -> R.color.status_idle
                ProjectionConnectionState.WAITING_VEHICLE -> R.color.status_idle
                ProjectionConnectionState.SEARCHING -> R.color.status_searching
                ProjectionConnectionState.WAITING_PERMISSION -> R.color.status_searching
                ProjectionConnectionState.CONNECTING -> R.color.status_connecting
                ProjectionConnectionState.INIT -> R.color.status_init
                ProjectionConnectionState.WAITING_PHONE -> R.color.status_waiting_phone
                ProjectionConnectionState.STREAMING -> R.color.status_streaming
                ProjectionConnectionState.ERROR -> R.color.status_error
            },
            showConnectButton = state != ProjectionConnectionState.STREAMING,
            showConnectionOverlay = showConnectionOverlay,
            isStreaming = state == ProjectionConnectionState.STREAMING && surfaceAttached,
            devices = deviceItems,
            diagnosticsText = diagnosticsText,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
        )
    }

    private fun ProjectionSessionSnapshot.overlayStatusMessage(): String {
        val knownDevicesAvailable = devices.isNotEmpty()
        val manualSelectionRequired = devices.size > 1 && devices.none { it.isConnecting || it.isActive }
        val currentName = currentDeviceName?.takeIf { it.isNotBlank() }

        return when (protocolPhase) {
            ProjectionProtocolPhase.HOST_INIT -> "Подготовка адаптера"
            ProjectionProtocolPhase.INIT_ECHO -> if (manualSelectionRequired) "Выберите iPhone" else "Ожидание iPhone"
            ProjectionProtocolPhase.PHONE_SEARCH -> if (manualSelectionRequired) "Выберите iPhone" else "Поиск iPhone"
            ProjectionProtocolPhase.PHONE_FOUND_BT_CONNECTED -> "Связь с iPhone"
            ProjectionProtocolPhase.CARPLAY_SESSION_SETUP -> "Запуск CarPlay"
            ProjectionProtocolPhase.AIRPLAY_NEGOTIATING -> "Открытие CarPlay"
            ProjectionProtocolPhase.STREAMING_ACTIVE -> {
                if (surfaceAttached) {
                    "CarPlay активен"
                } else {
                    "CarPlay готов"
                }
            }

            ProjectionProtocolPhase.SESSION_ENDED -> "Перезапуск"
            ProjectionProtocolPhase.NEGOTIATION_FAILED -> "Повтор подключения"
            ProjectionProtocolPhase.WAITING_RETRY -> if (manualSelectionRequired) "Выберите iPhone" else "Поиск iPhone"

            ProjectionProtocolPhase.NONE -> when (state) {
                ProjectionConnectionState.IDLE -> "Ожидание USB адаптера"
                ProjectionConnectionState.WAITING_VEHICLE -> "Ожидание USB адаптера"
                ProjectionConnectionState.SEARCHING -> "Ожидание USB адаптера"
                ProjectionConnectionState.WAITING_PERMISSION -> "Доступ к USB"
                ProjectionConnectionState.CONNECTING -> {
                    when {
                        currentName != null -> "Подключение: $currentName"
                        knownDevicesAvailable -> "Подключение iPhone"
                        else -> "Подключение адаптера"
                    }
                }

                ProjectionConnectionState.INIT -> "Подготовка адаптера"
                ProjectionConnectionState.WAITING_PHONE -> {
                    if (knownDevicesAvailable) {
                        "Выберите iPhone"
                    } else {
                        "Ожидание iPhone"
                    }
                }

                ProjectionConnectionState.STREAMING -> "CarPlay активен"
                ProjectionConnectionState.ERROR -> "Переподключаем"
            }
        }
    }

    private fun ProjectionProtocolPhase.overlayTitle(): String = when (this) {
        ProjectionProtocolPhase.NONE -> "Нет активной фазы"
        ProjectionProtocolPhase.HOST_INIT -> "Запуск адаптера"
        ProjectionProtocolPhase.INIT_ECHO -> "Адаптер готов"
        ProjectionProtocolPhase.PHONE_SEARCH -> "Поиск iPhone"
        ProjectionProtocolPhase.PHONE_FOUND_BT_CONNECTED -> "Bluetooth подключен"
        ProjectionProtocolPhase.CARPLAY_SESSION_SETUP -> "Запуск CarPlay"
        ProjectionProtocolPhase.AIRPLAY_NEGOTIATING -> "Согласование AirPlay"
        ProjectionProtocolPhase.STREAMING_ACTIVE -> "Видео активно"
        ProjectionProtocolPhase.SESSION_ENDED -> "Сеанс завершен"
        ProjectionProtocolPhase.NEGOTIATION_FAILED -> "Ошибка согласования"
        ProjectionProtocolPhase.WAITING_RETRY -> "Повторный поиск"
    }

    private fun ProjectionDeviceSnapshot.toUiModel(): CarPlayDeviceUiState {
        val subtitle = when {
            isConnecting -> "Подключение..."
            isActive -> "Подключено сейчас"
            isAvailable -> "Доступно сейчас"
            isSelected -> "Использовалось ранее"
            type != null -> type
            else -> "Ранее использовалось"
        }

        return CarPlayDeviceUiState(
            id = id,
            title = name,
            subtitle = subtitle,
            isAvailable = isAvailable,
            isActive = isActive,
            isSelected = isSelected,
            isConnecting = isConnecting,
        )
    }
}
