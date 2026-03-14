package com.alexander.carplay.presentation.ui

import android.graphics.SurfaceTexture
import android.view.Surface as AndroidSurface
import android.view.TextureView
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alexander.carplay.R
import com.alexander.carplay.domain.model.ProjectionAudioPlayerType
import com.alexander.carplay.domain.model.ProjectionAudioRoute
import com.alexander.carplay.domain.model.ProjectionDeviceSettings
import com.alexander.carplay.domain.model.ProjectionEqPreset
import com.alexander.carplay.domain.model.ProjectionMicRoute
import com.alexander.carplay.domain.model.ProjectionPlayerAudioSettings
import com.alexander.carplay.domain.model.ProjectionUiEvent
import com.alexander.carplay.presentation.viewmodel.CarPlayDeviceUiState
import com.alexander.carplay.presentation.viewmodel.CarPlayUiState
import com.alexander.carplay.presentation.viewmodel.CarPlayViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.roundToInt

private val AppColorScheme = darkColorScheme(
    background = Color(0xFF050816),
    surface = Color(0xF0172233),
    onSurface = Color(0xFFF4F7FF),
    surfaceVariant = Color(0x66141D2B),
    onSurfaceVariant = Color(0xFFB6C2D9),
    primary = Color(0xFFF6F9FF),
    onPrimary = Color(0xFF09111E),
    secondary = Color(0xFF8BC5FF),
    onSecondary = Color(0xFF071019),
)

@Composable
fun CarPlayComposeTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = AppColorScheme,
        content = content,
    )
}

@Composable
fun CarPlayRoute(
    viewModel: CarPlayViewModel,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val sessionSnapshot by viewModel.sessionSnapshot.collectAsStateWithLifecycle()
    val devices = remember(uiState.devices) {
        uiState.devices.sortedWith(
            compareByDescending<CarPlayDeviceUiState> { it.isActive }
                .thenByDescending { it.isConnecting }
                .thenByDescending { it.isSelected }
                .thenBy { it.title.lowercase() },
        )
    }

    var selectedDeviceId by rememberSaveable { mutableStateOf<String?>(null) }
    var isSelectorExpanded by rememberSaveable { mutableStateOf(false) }
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(devices, sessionSnapshot.currentDeviceId) {
        val availableIds = devices.map { it.id }.toSet()
        val preferredId = sessionSnapshot.currentDeviceId
            ?: devices.firstOrNull { it.isConnecting || it.isActive || it.isSelected }?.id
            ?: devices.firstOrNull()?.id

        selectedDeviceId = when {
            preferredId != null && preferredId != selectedDeviceId -> preferredId
            selectedDeviceId == null -> preferredId
            selectedDeviceId !in availableIds -> preferredId
            else -> selectedDeviceId
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.uiEvents.collectLatest { event ->
            when (event) {
                ProjectionUiEvent.OpenSettings -> showSettings = true
            }
        }
    }

    val selectedDevice = devices.firstOrNull { it.id == selectedDeviceId } ?: devices.firstOrNull()
    val connectActionEnabled = selectedDevice != null
    val shouldShowStop = selectedDevice?.isConnecting == true || selectedDevice?.isActive == true

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        ProjectionTextureSurface(
            viewModel = viewModel,
            videoWidth = uiState.videoWidth,
            videoHeight = uiState.videoHeight,
            touchEnabled = !uiState.showConnectionOverlay,
        )

        AnimatedVisibility(
            visible = uiState.showConnectionOverlay,
            enter = fadeIn(animationSpec = tween(320)),
            exit = fadeOut(animationSpec = tween(420)),
            modifier = Modifier.fillMaxSize(),
        ) {
            ConnectionOverlay(
                uiState = uiState,
                devices = devices,
                selectedDevice = selectedDevice,
                isSelectorExpanded = isSelectorExpanded,
                onSelectorToggle = { isSelectorExpanded = !isSelectorExpanded },
                onSelectorDismiss = { isSelectorExpanded = false },
                onDeviceSelected = { device ->
                    selectedDeviceId = device.id
                    isSelectorExpanded = false
                },
                onActionClick = {
                    when {
                        shouldShowStop -> viewModel.onCancelDeviceConnection()
                        selectedDevice != null -> viewModel.onDeviceSelected(selectedDevice.id)
                        else -> viewModel.onConnectClicked()
                    }
                },
                onReplayClick = { viewModel.onReplayClicked() },
                connectActionEnabled = connectActionEnabled,
                showStopAction = shouldShowStop,
            )
        }

        MinimalDiagnosticsButton(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(
                    end = 20.dp,
                    bottom = 20.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                ),
            onClick = { showDiagnostics = true },
        )

        if (showDiagnostics) {
            DiagnosticsDialog(
                diagnosticsText = uiState.diagnosticsText,
                onDismiss = { showDiagnostics = false },
            )
        }

        if (showSettings) {
            ProjectionSettingsScreen(
                deviceId = sessionSnapshot.currentDeviceId,
                deviceName = sessionSnapshot.currentDeviceName,
                viewModel = viewModel,
                onDismiss = { showSettings = false },
            )
        }
    }
}

@Composable
private fun ProjectionTextureSurface(
    viewModel: CarPlayViewModel,
    videoWidth: Int?,
    videoHeight: Int?,
    touchEnabled: Boolean,
) {
    val latestViewModel by rememberUpdatedState(viewModel)
    val latestVideoWidth by rememberUpdatedState(videoWidth)
    val latestVideoHeight by rememberUpdatedState(videoHeight)
    var textureSurface by remember { mutableStateOf<AndroidSurface?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            latestViewModel.onSurfaceDestroyed()
            textureSurface?.release()
            textureSurface = null
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            TextureView(context).apply {
                keepScreenOn = true

                fun attachSurface(surfaceTexture: SurfaceTexture) {
                    val width = latestVideoWidth
                    val height = latestVideoHeight
                    if (width != null && height != null) {
                        surfaceTexture.setDefaultBufferSize(width, height)
                    }
                    if (textureSurface == null) {
                        textureSurface = AndroidSurface(surfaceTexture)
                        latestViewModel.onSurfaceAvailable(requireNotNull(textureSurface))
                    }
                }

                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(
                        surface: SurfaceTexture,
                        width: Int,
                        height: Int,
                    ) {
                        attachSurface(surface)
                    }

                    override fun onSurfaceTextureSizeChanged(
                        surface: SurfaceTexture,
                        width: Int,
                        height: Int,
                    ) {
                        val videoW = latestVideoWidth
                        val videoH = latestVideoHeight
                        if (videoW != null && videoH != null) {
                            surface.setDefaultBufferSize(videoW, videoH)
                        }
                    }

                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                        textureSurface?.release()
                        textureSurface = null
                        return true
                    }

                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                }

                if (isAvailable) {
                    surfaceTexture?.let(::attachSurface)
                }
            }
        },
        update = { textureView ->
            val width = videoWidth
            val height = videoHeight
            if (width != null && height != null) {
                textureView.surfaceTexture?.setDefaultBufferSize(width, height)
            }
            if (textureView.isAvailable && textureSurface == null) {
                textureView.surfaceTexture?.let { surfaceTexture ->
                    if (width != null && height != null) {
                        surfaceTexture.setDefaultBufferSize(width, height)
                    }
                    textureSurface = AndroidSurface(surfaceTexture)
                    latestViewModel.onSurfaceAvailable(requireNotNull(textureSurface))
                }
            }
            textureView.setOnTouchListener(
                if (touchEnabled) {
                    View.OnTouchListener { view, motionEvent ->
                        latestViewModel.onTouchEvent(
                            event = motionEvent,
                            surfaceWidth = view.width.coerceAtLeast(1),
                            surfaceHeight = view.height.coerceAtLeast(1),
                        )
                        true
                    }
                } else {
                    null
                },
            )
        },
    )
}

@Composable
private fun ConnectionOverlay(
    uiState: CarPlayUiState,
    devices: List<CarPlayDeviceUiState>,
    selectedDevice: CarPlayDeviceUiState?,
    isSelectorExpanded: Boolean,
    onSelectorToggle: () -> Unit,
    onSelectorDismiss: () -> Unit,
    onDeviceSelected: (CarPlayDeviceUiState) -> Unit,
    onActionClick: () -> Unit,
    onReplayClick: () -> Unit,
    connectActionEnabled: Boolean,
    showStopAction: Boolean,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xC8131824),
                        Color(0x9E0A0F18),
                        Color(0xCE070B12),
                    ),
                ),
            )
            .padding(WindowInsets.safeDrawing.asPaddingValues()),
            contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp, vertical = 28.dp),
        ) {
            Spacer(modifier = Modifier.weight(1f))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_overlay_carplay_mark),
                    contentDescription = stringResource(id = R.string.carplay_logo),
                    modifier = Modifier.size(34.dp),
                    contentScale = ContentScale.Fit,
                )
                Text(
                    text = stringResource(id = R.string.carplay_wordmark),
                    style = MaterialTheme.typography.displaySmall.copy(
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = uiState.statusMessage,
                color = Color.White.copy(alpha = 0.58f),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 420.dp),
            )

            Spacer(modifier = Modifier.height(28.dp))

            Row(
                modifier = Modifier
                    .wrapContentWidth()
                    .widthIn(max = 520.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                DeviceSelectionField(
                    modifier = Modifier.widthIn(min = 250.dp, max = 420.dp),
                    devices = devices,
                    selectedDevice = selectedDevice,
                    isExpanded = isSelectorExpanded,
                    onToggle = onSelectorToggle,
                    onDismiss = onSelectorDismiss,
                    onSelect = onDeviceSelected,
                    fallbackStatus = if (devices.isEmpty()) {
                        stringResource(id = R.string.overlay_no_device_title)
                    } else {
                        uiState.statusMessage
                    },
                )

                OverlayActionButton(
                    isEnabled = connectActionEnabled,
                    showStopAction = showStopAction,
                    onClick = onActionClick,
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            if (devices.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = Color.White.copy(alpha = 0.08f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                    modifier = Modifier.wrapContentWidth(),
                ) {
                    Text(
                        text = stringResource(id = R.string.replay_dump),
                        color = Color.White.copy(alpha = 0.92f),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier
                            .clickable(onClick = onReplayClick)
                            .padding(horizontal = 18.dp, vertical = 12.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun DeviceSelectionField(
    modifier: Modifier,
    devices: List<CarPlayDeviceUiState>,
    selectedDevice: CarPlayDeviceUiState?,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onDismiss: () -> Unit,
    onSelect: (CarPlayDeviceUiState) -> Unit,
    fallbackStatus: String,
) {
    Box(modifier = modifier) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    enabled = devices.isNotEmpty(),
                    onClick = onToggle,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ),
            color = Color(0x8C0D1320),
            contentColor = Color.White,
            shape = RoundedCornerShape(32.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
            shadowElevation = 10.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ActiveStateDot(isActive = selectedDevice?.isActive == true)

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = selectedDevice?.title ?: stringResource(id = R.string.overlay_choose_device),
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = selectedDevice?.subtitle ?: fallbackStatus,
                        color = Color.White.copy(alpha = 0.68f),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (selectedDevice?.isConnecting == true) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Image(
                        painter = painterResource(id = R.drawable.ic_overlay_chevron_down),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        DropdownMenu(
            expanded = isExpanded && devices.isNotEmpty(),
            onDismissRequest = onDismiss,
            modifier = Modifier
                .widthIn(min = 250.dp, max = 420.dp)
                .background(Color(0xF4131A28), RoundedCornerShape(24.dp)),
        ) {
            devices.forEach { device ->
                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            ActiveStateDot(isActive = device.isActive)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = device.title,
                                    color = Color.White,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = device.subtitle,
                                    color = Color.White.copy(alpha = 0.64f),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (device.isConnecting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp,
                                )
                            }
                        }
                    },
                    onClick = { onSelect(device) },
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ActiveStateDot(
    isActive: Boolean,
) {
    Box(
        modifier = Modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(if (isActive) Color(0xFF46D189) else Color.White.copy(alpha = 0.14f))
            .border(1.dp, Color.White.copy(alpha = if (isActive) 0.16f else 0.06f), CircleShape),
    )
}

@Composable
private fun OverlayActionButton(
    isEnabled: Boolean,
    showStopAction: Boolean,
    onClick: () -> Unit,
) {
    val backgroundColor = if (showStopAction) {
        colorResource(id = R.color.overlay_action_stop_bg)
    } else {
        colorResource(id = R.color.overlay_action_primary_bg)
    }
    val iconTint = if (showStopAction) {
        colorResource(id = R.color.overlay_action_stop_icon)
    } else {
        colorResource(id = R.color.overlay_action_primary_icon)
    }

    Surface(
        modifier = Modifier
            .size(76.dp)
            .alpha(if (isEnabled) 1f else 0.35f)
            .clickable(enabled = isEnabled, onClick = onClick),
        color = backgroundColor,
        shape = CircleShape,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
        shadowElevation = 10.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(
                    id = if (showStopAction) {
                        R.drawable.ic_overlay_close
                    } else {
                        R.drawable.ic_overlay_arrow_right
                    },
                ),
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = iconTint,
            )
        }
    }
}

@Composable
private fun MinimalDiagnosticsButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier
            .size(44.dp)
            .clickable(onClick = onClick),
        color = Color(0x660B111C),
        shape = CircleShape,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Image(
                painter = painterResource(id = R.drawable.ic_overlay_diagnostics),
                contentDescription = stringResource(id = R.string.logs_title),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun DiagnosticsDialog(
    diagnosticsText: String,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.72f)
                .padding(horizontal = 20.dp, vertical = 28.dp),
            shape = RoundedCornerShape(28.dp),
            color = Color(0xF6131822),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
            ) {
                Text(
                    text = stringResource(id = R.string.logs_title),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = Color.White,
                )
                Spacer(modifier = Modifier.height(14.dp))
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(22.dp),
                    color = Color.White.copy(alpha = 0.04f),
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                    ) {
                        item {
                            Text(
                                text = diagnosticsText,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.82f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectionSettingsScreen(
    deviceId: String?,
    deviceName: String?,
    viewModel: CarPlayViewModel,
    onDismiss: () -> Unit,
) {
    val loadedSettings = remember(deviceId, deviceName) {
        viewModel.loadDeviceSettings(deviceId)
    }
    var savedSettings by remember(deviceId, deviceName) { mutableStateOf(loadedSettings) }
    var workingSettings by remember(deviceId, deviceName) { mutableStateOf(loadedSettings) }

    val reconnectRequired = remember(workingSettings, savedSettings) {
        workingSettings.audioRoute != savedSettings.audioRoute ||
            workingSettings.micRoute != savedSettings.micRoute
    }

    BackHandler(onBack = onDismiss)

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.44f)),
        color = Color(0xF20A1019),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            SettingsHeader(
                title = stringResource(id = R.string.settings_sheet_title),
                deviceName = deviceName?.takeIf { it.isNotBlank() }
                    ?: stringResource(id = R.string.settings_sheet_default_device),
                saveLabel = stringResource(id = R.string.settings_save),
                saveEnabled = reconnectRequired,
                onBack = onDismiss,
                onSave = {
                    viewModel.saveDeviceSettings(workingSettings, reconnectRequired)
                    onDismiss()
                },
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 6.dp),
            ) {
                item {
                    PlayerAudioSection(
                        isEnabled = workingSettings.audioRoute == ProjectionAudioRoute.ADAPTER,
                        settings = workingSettings,
                        onToggleEnabled = { enabled ->
                            workingSettings = workingSettings.copy(
                                audioRoute = if (enabled) {
                                    ProjectionAudioRoute.ADAPTER
                                } else {
                                    ProjectionAudioRoute.CAR_BLUETOOTH
                                },
                            )
                        },
                        onSelectPlayer = { player ->
                            workingSettings = workingSettings.copy(selectedPlayer = player)
                        },
                        onRoutePlayerSettingsChanged = { updatedPlayerSettings ->
                            val updatedWorking = workingSettings.copy(
                                playerSettings = workingSettings.playerSettings.toMutableMap().apply {
                                    put(workingSettings.selectedPlayer, updatedPlayerSettings)
                                },
                            )
                            workingSettings = updatedWorking

                            val realtimePersisted = savedSettings.copy(
                                selectedPlayer = updatedWorking.selectedPlayer,
                                playerSettings = updatedWorking.playerSettings,
                            )
                            savedSettings = realtimePersisted
                            viewModel.saveDeviceSettings(realtimePersisted, reconnectRequired = false)
                        },
                    )
                }

                item {
                    MicrophoneSection(
                        isEnabled = workingSettings.micRoute == ProjectionMicRoute.ADAPTER,
                        onToggleEnabled = { enabled ->
                            workingSettings = workingSettings.copy(
                                micRoute = if (enabled) {
                                    ProjectionMicRoute.ADAPTER
                                } else {
                                    ProjectionMicRoute.PHONE
                                },
                            )
                        },
                        gainMultiplier = workingSettings.micSettings.gainMultiplier,
                        onGainChanged = {
                            workingSettings = workingSettings.copy(
                                micSettings = workingSettings.micSettings.copy(gainMultiplier = it),
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsHeader(
    title: String,
    deviceName: String,
    saveLabel: String,
    saveEnabled: Boolean,
    onBack: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderButton(
            label = stringResource(id = R.string.settings_close),
            onClick = onBack,
        )

        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = deviceName,
                color = Color.White.copy(alpha = 0.62f),
                style = MaterialTheme.typography.titleMedium,
            )
        }

        HeaderButton(
            label = saveLabel,
            enabled = saveEnabled,
            onClick = onSave,
            emphasized = true,
        )
    }
}

@Composable
private fun HeaderButton(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
    emphasized: Boolean = false,
) {
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = if (emphasized) {
            Color.White.copy(alpha = if (enabled) 0.92f else 0.22f)
        } else {
            Color.White.copy(alpha = 0.08f)
        },
        modifier = Modifier.wrapContentWidth(),
    ) {
        Text(
            text = label,
            color = if (emphasized) {
                if (enabled) Color(0xFF09111E) else Color.White.copy(alpha = 0.58f)
            } else {
                Color.White
            },
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 18.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp, bottomStart = 0.dp, bottomEnd = 0.dp),
        color = Color.White.copy(alpha = 0.06f),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 26.dp, vertical = 24.dp),
        ) {
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(20.dp))
            } else {
                Spacer(modifier = Modifier.height(18.dp))
            }
            content()
        }
    }
}

@Composable
private fun PlayerAudioSection(
    isEnabled: Boolean,
    settings: ProjectionDeviceSettings,
    onToggleEnabled: (Boolean) -> Unit,
    onSelectPlayer: (ProjectionAudioPlayerType) -> Unit,
    onRoutePlayerSettingsChanged: (ProjectionPlayerAudioSettings) -> Unit,
) {
    val playerSettings = settings.playerSettings[settings.selectedPlayer] ?: ProjectionPlayerAudioSettings()

    SettingsSectionCard(
        title = stringResource(id = R.string.settings_players_title),
    ) {
        AdapterToggleRow(
            title = stringResource(id = R.string.settings_audio_route_adapter),
            checked = isEnabled,
            onCheckedChange = onToggleEnabled,
        )

        if (isEnabled) {
            Spacer(modifier = Modifier.height(20.dp))

            PlayerTabs(
                selected = settings.selectedPlayer,
                onSelect = onSelectPlayer,
            )

            Spacer(modifier = Modifier.height(18.dp))

            PresetTabs(
                selected = playerSettings.eqPreset,
                onSelect = { preset: ProjectionEqPreset ->
                    val updated = if (preset == ProjectionEqPreset.CUSTOM) {
                        playerSettings.copy(eqPreset = ProjectionEqPreset.CUSTOM)
                    } else {
                        playerSettings.copy(
                            eqPreset = preset,
                            eqBandsDb = preset.bandsDb,
                        )
                    }
                    onRoutePlayerSettingsChanged(updated)
                },
            )

            Spacer(modifier = Modifier.height(18.dp))

            SliderCard(
                title = stringResource(id = R.string.settings_gain),
                valueLabel = formatGain(playerSettings.gainMultiplier),
            ) {
                Slider(
                    value = playerSettings.gainMultiplier,
                    onValueChange = {
                        onRoutePlayerSettingsChanged(
                            playerSettings.copy(gainMultiplier = it),
                        )
                    },
                    valueRange = 1f..3f,
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            SliderCard(
                title = stringResource(id = R.string.settings_loudness),
                valueLabel = "${playerSettings.loudnessBoostPercent}%",
            ) {
                Slider(
                    value = playerSettings.loudnessBoostPercent.toFloat(),
                    onValueChange = {
                        onRoutePlayerSettingsChanged(
                            playerSettings.copy(loudnessBoostPercent = it.roundToInt()),
                        )
                    },
                    valueRange = 0f..100f,
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            SliderCard(
                title = stringResource(id = R.string.settings_bass_boost),
                valueLabel = "${playerSettings.bassBoostPercent}%",
            ) {
                Slider(
                    value = playerSettings.bassBoostPercent.toFloat(),
                    onValueChange = {
                        onRoutePlayerSettingsChanged(
                            playerSettings.copy(bassBoostPercent = it.roundToInt()),
                        )
                    },
                    valueRange = 0f..100f,
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            VerticalEqEditor(
                bandsDb = playerSettings.eqBandsDb,
                onBandChange = { index: Int, value: Float ->
                    val newBands = playerSettings.eqBandsDb.toMutableList().apply {
                        this[index] = value
                    }
                    onRoutePlayerSettingsChanged(
                        playerSettings.copy(
                            eqPreset = ProjectionEqPreset.detect(newBands),
                            eqBandsDb = newBands,
                        ),
                    )
                },
            )
        }
    }
}

@Composable
private fun PlayerTabs(
    selected: ProjectionAudioPlayerType,
    onSelect: (ProjectionAudioPlayerType) -> Unit,
) {
    val scrollState = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ProjectionAudioPlayerType.entries.forEach { player ->
            val isSelected = player == selected
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = if (isSelected) Color.White.copy(alpha = 0.94f) else Color.White.copy(alpha = 0.06f),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = if (isSelected) 0f else 0.06f)),
            ) {
                Text(
                    text = player.title,
                    color = if (isSelected) Color(0xFF09111E) else Color.White.copy(alpha = 0.76f),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier
                        .clickable { onSelect(player) }
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun PresetTabs(
    selected: ProjectionEqPreset,
    onSelect: (ProjectionEqPreset) -> Unit,
) {
    val presets = listOf(
        ProjectionEqPreset.FLAT,
        ProjectionEqPreset.LOUDNESS,
        ProjectionEqPreset.BASS,
        ProjectionEqPreset.VOCAL,
        ProjectionEqPreset.BRIGHT,
        ProjectionEqPreset.CUSTOM,
    )
    val scrollState = rememberScrollState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        presets.forEach { preset ->
            val selectedChip = preset == selected
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (selectedChip) Color(0x3349A6FF) else Color.White.copy(alpha = 0.04f),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (selectedChip) Color(0x6649A6FF) else Color.White.copy(alpha = 0.06f),
                ),
            ) {
                Text(
                    text = presetLabel(preset),
                    color = Color.White.copy(alpha = if (selectedChip) 0.98f else 0.72f),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier
                        .clickable { onSelect(preset) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun SliderCard(
    title: String,
    valueLabel: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = Color.White.copy(alpha = 0.04f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                )
                Text(
                    text = valueLabel,
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun VerticalEqEditor(
    bandsDb: List<Float>,
    onBandChange: (Int, Float) -> Unit,
) {
    val labels = remember {
        listOf("32", "64", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = Color.White.copy(alpha = 0.04f),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.04f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 18.dp),
        ) {
            Text(
                text = stringResource(id = R.string.settings_eq_title),
                color = Color.White,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            )
            Spacer(modifier = Modifier.height(18.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 300.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                labels.forEachIndexed { index, label ->
                    VerticalEqBand(
                        width = 48.dp,
                        label = label,
                        value = bandsDb.getOrElse(index) { 0f },
                        onValueChange = { onBandChange(index, it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun VerticalEqBand(
    width: Dp,
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.width(width),
    ) {
        Text(
            text = formatEq(value),
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
        )

        val animatedValue by animateFloatAsState(
            targetValue = value,
            animationSpec = tween(140),
            label = "eqBandValue",
        )

        Slider(
            value = animatedValue,
            onValueChange = onValueChange,
            valueRange = -12f..12f,
            modifier = Modifier
                .height(220.dp)
                .graphicsLayer {
                    rotationZ = -90f
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0.5f)
                }
                .width(220.dp),
        )

        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun MicrophoneSection(
    isEnabled: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    gainMultiplier: Float,
    onGainChanged: (Float) -> Unit,
) {
    SettingsSectionCard(
        title = stringResource(id = R.string.settings_mic_title),
    ) {
        AdapterToggleRow(
            title = stringResource(id = R.string.settings_mic_route_adapter),
            checked = isEnabled,
            onCheckedChange = onToggleEnabled,
        )

        if (isEnabled) {
            Spacer(modifier = Modifier.height(20.dp))
            SliderCard(
                title = stringResource(id = R.string.settings_mic_gain),
                valueLabel = formatGain(gainMultiplier),
            ) {
                Slider(
                    value = gainMultiplier,
                    onValueChange = onGainChanged,
                    valueRange = 1f..3f,
                )
            }
        }
    }
}

@Composable
private fun AdapterToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = Color.White.copy(alpha = 0.05f),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onCheckedChange(!checked) }
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                )
                Text(
                    text = if (checked) "On" else "Off",
                    color = Color.White.copy(alpha = 0.56f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Color(0xFF34C759),
                    uncheckedThumbColor = Color.White.copy(alpha = 0.94f),
                    uncheckedTrackColor = Color.White.copy(alpha = 0.18f),
                    uncheckedBorderColor = Color.Transparent,
                    checkedBorderColor = Color.Transparent,
                ),
            )
        }
    }
}

private fun presetLabel(preset: ProjectionEqPreset): String {
    return when (preset) {
        ProjectionEqPreset.FLAT -> "Flat"
        ProjectionEqPreset.LOUDNESS -> "Loud"
        ProjectionEqPreset.BASS -> "Bass"
        ProjectionEqPreset.VOCAL -> "Vocal"
        ProjectionEqPreset.BRIGHT -> "Bright"
        ProjectionEqPreset.CUSTOM -> "Custom"
    }
}

private fun formatGain(value: Float): String = String.format("%.1fx", value)

private fun formatEq(value: Float): String = String.format("%+.0f dB", value)
