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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
            modifier = Modifier
                .fillMaxSize()
                .zIndex(2f),
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
                connectActionEnabled = connectActionEnabled,
                showStopAction = shouldShowStop,
                uiScale = 1f,
            )
        }

        if (showSettings) {
            ProjectionSettingsScreen(
                deviceId = sessionSnapshot.currentDeviceId,
                deviceName = sessionSnapshot.currentDeviceName,
                diagnosticsText = uiState.diagnosticsText,
                viewModel = viewModel,
                onDismiss = { showSettings = false },
                uiScale = 1f,
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(4f),
            )
        }
    }
}

@Composable
private fun ScaledHeadUnitContent(
    modifier: Modifier = Modifier,
    scale: Float,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        val contentWidth = (maxWidth / scale).coerceAtLeast(maxWidth)
        val contentHeight = (maxHeight / scale).coerceAtLeast(maxHeight)

        Box(
            modifier = Modifier
                .requiredWidth(contentWidth)
                .requiredHeight(contentHeight)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0.5f)
                },
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                content()
            }
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
    val lifecycleOwner = LocalLifecycleOwner.current
    var textureSurface by remember { mutableStateOf<AndroidSurface?>(null) }
    var textureViewRef by remember { mutableStateOf<TextureView?>(null) }
    var surfaceBoundToSession by remember { mutableStateOf(false) }

    fun bindSurfaceIfPossible(textureView: TextureView?) {
        val view = textureView ?: return
        if (!view.isAvailable) return
        val surfaceTexture = view.surfaceTexture ?: return
        val width = latestVideoWidth
        val height = latestVideoHeight
        if (width != null && height != null) {
            surfaceTexture.setDefaultBufferSize(width, height)
        }
        view.isOpaque = true
        view.post {
            view.requestLayout()
            view.invalidate()
        }
        if (textureSurface == null) {
            textureSurface = AndroidSurface(surfaceTexture)
        }
        if (!surfaceBoundToSession) {
            latestViewModel.onSurfaceAvailable(requireNotNull(textureSurface))
            surfaceBoundToSession = true
        }
    }

    fun unbindSurfaceFromSession() {
        if (surfaceBoundToSession) {
            latestViewModel.onSurfaceDestroyed()
            surfaceBoundToSession = false
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            unbindSurfaceFromSession()
            textureSurface?.release()
            textureSurface = null
            textureViewRef = null
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START,
                Lifecycle.Event.ON_RESUME,
                -> bindSurfaceIfPossible(textureViewRef)

                Lifecycle.Event.ON_STOP -> unbindSurfaceFromSession()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            TextureView(context).apply {
                keepScreenOn = true
                textureViewRef = this

                fun attachSurface() {
                    bindSurfaceIfPossible(this)
                }

                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(
                        surface: SurfaceTexture,
                        width: Int,
                        height: Int,
                    ) {
                        attachSurface()
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
                        post {
                            requestLayout()
                            invalidate()
                        }
                    }

                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                        unbindSurfaceFromSession()
                        textureSurface?.release()
                        textureSurface = null
                        textureViewRef = null
                        return true
                    }

                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                }

                if (isAvailable) {
                    attachSurface()
                }
            }
        },
        update = { textureView ->
            textureViewRef = textureView
            val width = videoWidth
            val height = videoHeight
            if (width != null && height != null) {
                textureView.surfaceTexture?.setDefaultBufferSize(width, height)
            }
            textureView.post {
                textureView.requestLayout()
                textureView.invalidate()
            }
            if (textureView.isAvailable) {
                bindSurfaceIfPossible(textureView)
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
    connectActionEnabled: Boolean,
    showStopAction: Boolean,
    uiScale: Float,
) {
    BoxWithConstraints(
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
            ),
    ) {
        val logoTopOffset = maxHeight * 0.3f
        val overlayBottomOffset = maxHeight * 0.08f
        val hasDevices = devices.isNotEmpty()

        ScaledHeadUnitContent(
            modifier = Modifier.fillMaxSize(),
            scale = uiScale,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = logoTopOffset),
                ) {
                    Text(
                        text = stringResource(id = R.string.carplay_wordmark),
                        style = MaterialTheme.typography.displayLarge.copy(
                            color = Color.White,
                            fontWeight = FontWeight.Medium,
                            fontSize = MaterialTheme.typography.displayLarge.fontSize * 2,
                            lineHeight = 96.sp,
                        ),
                    )
                }

                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .wrapContentWidth()
                        .widthIn(max = 676.dp)
                        .padding(start = 28.dp, end = 28.dp, bottom = overlayBottomOffset),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (hasDevices) {
                        DeviceSelectionField(
                            modifier = Modifier.widthIn(min = 325.dp, max = 546.dp),
                            devices = devices,
                            selectedDevice = selectedDevice,
                            isExpanded = isSelectorExpanded,
                            onToggle = onSelectorToggle,
                            onDismiss = onSelectorDismiss,
                            onSelect = onDeviceSelected,
                            fallbackStatus = uiState.statusMessage,
                        )
                    } else {
                        SearchStatusLine(
                            modifier = Modifier.widthIn(min = 325.dp, max = 546.dp),
                            status = if (uiState.statusMessage.isBlank()) {
                                stringResource(id = R.string.overlay_no_device_title)
                            } else {
                                uiState.statusMessage
                            },
                        )
                    }

                    if (hasDevices) {
                        OverlayActionButton(
                            modifier = Modifier.padding(start = 16.dp),
                            isEnabled = connectActionEnabled,
                            showStopAction = showStopAction,
                            onClick = onActionClick,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchStatusLine(
    modifier: Modifier,
    status: String,
) {
    Surface(
        modifier = modifier.height(96.dp),
        color = Color.Transparent,
        contentColor = Color.White,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = status,
                color = Color.White.copy(alpha = 0.46f),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = MaterialTheme.typography.titleMedium.fontSize * 1.3f,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
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
    val showCompactStatusOnly = selectedDevice == null && devices.isEmpty()

    Box(modifier = modifier) {
        Surface(
            modifier = Modifier
                .height(96.dp)
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
                    .padding(horizontal = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                ActiveStateDot(isActive = selectedDevice?.isActive == true)

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(if (showCompactStatusOnly) 0.dp else 4.dp),
                ) {
                    if (showCompactStatusOnly) {
                        Text(
                            text = fallbackStatus,
                            color = Color.White.copy(alpha = 0.84f),
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = MaterialTheme.typography.titleLarge.fontSize * 1.3f,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        Text(
                            text = selectedDevice?.title ?: stringResource(id = R.string.overlay_choose_device),
                            color = Color.White,
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = MaterialTheme.typography.headlineSmall.fontSize * 1.3f,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = selectedDevice?.subtitle ?: fallbackStatus,
                            color = Color.White.copy(alpha = 0.68f),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Medium,
                                fontSize = MaterialTheme.typography.titleMedium.fontSize * 1.3f,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                if (selectedDevice?.isConnecting == true) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Image(
                        painter = painterResource(id = R.drawable.ic_overlay_chevron_down),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }

        DropdownMenu(
            expanded = isExpanded && devices.isNotEmpty(),
            onDismissRequest = onDismiss,
            modifier = Modifier
                .widthIn(min = 325.dp, max = 546.dp)
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
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = MaterialTheme.typography.titleLarge.fontSize * 1.3f,
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = device.subtitle,
                                    color = Color.White.copy(alpha = 0.64f),
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontSize = MaterialTheme.typography.titleSmall.fontSize * 1.3f,
                                    ),
                                )
                            }
                            if (device.isConnecting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(21.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp,
                                )
                            }
                        }
                    },
                    onClick = { onSelect(device) },
                    contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
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
            .size(14.dp)
            .clip(CircleShape)
            .background(if (isActive) Color(0xFF46D189) else Color.White.copy(alpha = 0.14f))
            .border(1.dp, Color.White.copy(alpha = if (isActive) 0.16f else 0.06f), CircleShape),
    )
}

@Composable
private fun OverlayActionButton(
    modifier: Modifier = Modifier,
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

    Box(
        modifier = modifier
            .requiredSize(56.dp)
            .aspectRatio(1f)
            .shadow(
                elevation = 10.dp,
                shape = CircleShape,
                clip = false,
            )
            .clip(CircleShape)
            .background(backgroundColor, CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
            .alpha(if (isEnabled) 1f else 0.35f)
            .clickable(enabled = isEnabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(
                id = if (showStopAction) {
                    R.drawable.ic_overlay_close
                } else {
                    R.drawable.ic_overlay_arrow_right
                },
            ),
            contentDescription = null,
            modifier = Modifier.size(if (showStopAction) 36.dp else 40.dp),
            tint = iconTint,
        )
    }
}

@Composable
private fun ProjectionSettingsScreen(
    deviceId: String?,
    deviceName: String?,
    diagnosticsText: String,
    viewModel: CarPlayViewModel,
    onDismiss: () -> Unit,
    uiScale: Float,
    modifier: Modifier = Modifier,
) {
    val loadedSettings = remember(deviceId, deviceName) {
        viewModel.loadDeviceSettings(deviceId)
    }
    var savedSettings by remember(deviceId, deviceName) { mutableStateOf(loadedSettings) }
    var workingSettings by remember(deviceId, deviceName) { mutableStateOf(loadedSettings) }
    var showDiagnostics by rememberSaveable(deviceId, deviceName) { mutableStateOf(false) }

    val reconnectRequired = remember(workingSettings, savedSettings) {
        workingSettings.audioRoute != savedSettings.audioRoute ||
            workingSettings.micRoute != savedSettings.micRoute
    }

    BackHandler(onBack = onDismiss)

    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
    ) {
        val micCardWidth = (maxWidth * 0.20f).coerceIn(320.dp, 420.dp)
        val audioCardWidth = (maxWidth * 0.22f).coerceIn(360.dp, 460.dp)
        val enhancementCardWidth = (maxWidth * 0.26f).coerceIn(420.dp, 540.dp)
        val eqCardWidth = (maxWidth * 0.52f).coerceIn(880.dp, 1120.dp)
        val horizontalScrollState = rememberScrollState()
        val selectedPlayerSettings =
            workingSettings.playerSettings[workingSettings.selectedPlayer] ?: ProjectionPlayerAudioSettings()
        val audioGroupWidth = if (workingSettings.audioRoute == ProjectionAudioRoute.ADAPTER) {
            audioCardWidth + enhancementCardWidth + eqCardWidth + 72.dp
        } else {
            audioCardWidth + 40.dp
        }

        fun persistRealtimePlayerSettings(updatedPlayerSettings: ProjectionPlayerAudioSettings) {
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
        }

        Surface(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.44f)),
            color = Color(0xF20A1019),
        ) {
            ScaledHeadUnitContent(
                modifier = Modifier.fillMaxSize(),
                scale = uiScale,
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    SettingsHeader(
                        title = if (showDiagnostics) {
                            stringResource(id = R.string.logs_title)
                        } else {
                            stringResource(id = R.string.settings_sheet_title)
                        },
                        deviceName = deviceName?.takeIf { it.isNotBlank() }
                            ?: stringResource(id = R.string.settings_sheet_default_device),
                        saveLabel = stringResource(id = R.string.settings_save),
                        saveEnabled = reconnectRequired && !showDiagnostics,
                        onBack = {
                            if (showDiagnostics) {
                                showDiagnostics = false
                            } else {
                                onDismiss()
                            }
                        },
                        onSave = {
                            viewModel.saveDeviceSettings(workingSettings, reconnectRequired)
                            onDismiss()
                        },
                        secondaryActionLabel = if (showDiagnostics) null else stringResource(id = R.string.logs_title),
                        onSecondaryAction = if (showDiagnostics) {
                            null
                        } else {
                            { showDiagnostics = true }
                        },
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                    )

                    if (showDiagnostics) {
                        DiagnosticsSettingsSection(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(horizontal = 24.dp, vertical = 0.dp),
                            diagnosticsText = diagnosticsText,
                        )
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .horizontalScroll(horizontalScrollState)
                                .padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(24.dp),
                        ) {
                            MicrophoneSection(
                                modifier = Modifier
                                    .width(micCardWidth)
                                    .fillMaxHeight(),
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

                            AudioSettingsGroup(
                                modifier = Modifier
                                    .width(audioGroupWidth)
                                    .fillMaxHeight(),
                            ) {
                                AudioProfilesSection(
                                    modifier = Modifier
                                        .width(audioCardWidth)
                                        .fillMaxHeight(),
                                    isEnabled = workingSettings.audioRoute == ProjectionAudioRoute.ADAPTER,
                                    selectedPlayer = workingSettings.selectedPlayer,
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
                                )

                                if (workingSettings.audioRoute == ProjectionAudioRoute.ADAPTER) {
                                    PlayerEnhancementSection(
                                        modifier = Modifier
                                            .width(enhancementCardWidth)
                                            .fillMaxHeight(),
                                        selectedPlayer = workingSettings.selectedPlayer,
                                        playerSettings = selectedPlayerSettings,
                                        onPlayerSettingsChanged = ::persistRealtimePlayerSettings,
                                    )

                                    EqualizerSection(
                                        modifier = Modifier
                                            .width(eqCardWidth)
                                            .fillMaxHeight(),
                                        selectedPlayer = workingSettings.selectedPlayer,
                                        bandsDb = selectedPlayerSettings.eqBandsDb,
                                        preset = selectedPlayerSettings.eqPreset,
                                        onBandChange = { index, value ->
                                            val newBands = selectedPlayerSettings.eqBandsDb.toMutableList().apply {
                                                this[index] = value
                                            }
                                            persistRealtimePlayerSettings(
                                                selectedPlayerSettings.copy(
                                                    eqPreset = ProjectionEqPreset.detect(newBands),
                                                    eqBandsDb = newBands,
                                                ),
                                            )
                                        },
                                        onPresetSelect = { preset ->
                                            val updated = if (preset == ProjectionEqPreset.CUSTOM) {
                                                selectedPlayerSettings.copy(eqPreset = ProjectionEqPreset.CUSTOM)
                                            } else {
                                                selectedPlayerSettings.copy(
                                                    eqPreset = preset,
                                                    eqBandsDb = preset.bandsDb,
                                                )
                                            }
                                            persistRealtimePlayerSettings(updated)
                                        },
                                    )
                                }
                            }
                        }
                    }
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
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
    onBack: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!secondaryActionLabel.isNullOrBlank() && onSecondaryAction != null) {
            HeaderButton(
                label = secondaryActionLabel,
                onClick = onSecondaryAction,
                subtle = true,
            )
        } else {
            Spacer(modifier = Modifier.width(132.dp))
        }

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

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeaderButton(
                label = stringResource(id = R.string.settings_close),
                onClick = onBack,
                large = true,
            )

            HeaderButton(
                label = saveLabel,
                enabled = saveEnabled,
                onClick = onSave,
                emphasized = true,
                large = true,
            )
        }
    }
}

@Composable
private fun HeaderButton(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
    emphasized: Boolean = false,
    large: Boolean = false,
    subtle: Boolean = false,
) {
    Surface(
        shape = RoundedCornerShape(if (large) 32.dp else 28.dp),
        color = if (emphasized) {
            Color.White.copy(alpha = if (enabled) 0.92f else 0.22f)
        } else if (subtle) {
            Color.White.copy(alpha = 0.045f)
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
                Color.White.copy(alpha = if (subtle) 0.72f else 1f)
            },
            style = if (large) {
                MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
            } else {
                MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            },
            modifier = Modifier
                .clickable(enabled = enabled, onClick = onClick)
                .padding(
                    horizontal = if (large) 28.dp else 18.dp,
                    vertical = if (large) 18.dp else 13.dp,
                ),
        )
    }
}

@Composable
private fun AudioSettingsGroup(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(34.dp),
        color = Color.White.copy(alpha = 0.03f),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 18.dp),
        ) {
            Text(
                text = "Звук",
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Профили, усиление и эквалайзер",
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(18.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun SettingsSectionCard(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(30.dp),
        color = Color.White.copy(alpha = 0.06f),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 16.dp),
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
                Spacer(modifier = Modifier.height(16.dp))
            }
            content()
        }
    }
}

@Composable
private fun AudioProfilesSection(
    modifier: Modifier = Modifier,
    isEnabled: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    selectedPlayer: ProjectionAudioPlayerType,
    onSelectPlayer: (ProjectionAudioPlayerType) -> Unit,
) {
    SettingsSectionCard(
        modifier = modifier,
        title = stringResource(id = R.string.settings_players_title),
    ) {
        AdapterToggleRow(
            title = stringResource(id = R.string.settings_audio_route_adapter),
            checked = isEnabled,
            onCheckedChange = onToggleEnabled,
        )

        Spacer(modifier = Modifier.height(20.dp))

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(ProjectionAudioPlayerType.entries.size) { index ->
                val player = ProjectionAudioPlayerType.entries[index]
                val isSelected = selectedPlayer == player
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = if (isSelected) {
                        Color.White.copy(alpha = if (isEnabled) 0.94f else 0.26f)
                    } else {
                        Color.White.copy(alpha = 0.06f)
                    },
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        Color.White.copy(alpha = if (isSelected) 0.08f else 0.06f),
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectPlayer(player) }
                            .padding(horizontal = 18.dp, vertical = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = playerLabel(player),
                            color = if (isSelected) Color(0xFF09111E) else Color.White,
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                            modifier = Modifier.weight(1f),
                        )
                        if (isSelected) {
                            Text(
                                text = "Выбран",
                                color = if (isEnabled) Color(0xFF09111E).copy(alpha = 0.72f) else Color.White.copy(alpha = 0.78f),
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerEnhancementSection(
    modifier: Modifier = Modifier,
    selectedPlayer: ProjectionAudioPlayerType,
    playerSettings: ProjectionPlayerAudioSettings,
    onPlayerSettingsChanged: (ProjectionPlayerAudioSettings) -> Unit,
) {
    SettingsSectionCard(
        modifier = modifier,
        title = "Усиление: ${playerLabel(selectedPlayer)}",
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            VerticalControlSlider(
                modifier = Modifier.weight(1f),
                title = stringResource(id = R.string.settings_gain),
                valueLabel = formatGain(playerSettings.gainMultiplier),
                value = playerSettings.gainMultiplier,
                valueRange = 1f..3f,
                onValueChange = {
                    onPlayerSettingsChanged(
                        playerSettings.copy(gainMultiplier = it),
                    )
                },
            )
            VerticalControlSlider(
                modifier = Modifier.weight(1f),
                title = stringResource(id = R.string.settings_loudness),
                valueLabel = "${playerSettings.loudnessBoostPercent}%",
                value = playerSettings.loudnessBoostPercent.toFloat(),
                valueRange = 0f..100f,
                onValueChange = {
                    onPlayerSettingsChanged(
                        playerSettings.copy(loudnessBoostPercent = it.roundToInt()),
                    )
                },
            )
            VerticalControlSlider(
                modifier = Modifier.weight(1f),
                title = stringResource(id = R.string.settings_bass_boost),
                valueLabel = "${playerSettings.bassBoostPercent}%",
                value = playerSettings.bassBoostPercent.toFloat(),
                valueRange = 0f..100f,
                onValueChange = {
                    onPlayerSettingsChanged(
                        playerSettings.copy(bassBoostPercent = it.roundToInt()),
                    )
                },
            )
        }
    }
}

@Composable
private fun EqualizerSection(
    modifier: Modifier = Modifier,
    selectedPlayer: ProjectionAudioPlayerType,
    bandsDb: List<Float>,
    preset: ProjectionEqPreset,
    onBandChange: (Int, Float) -> Unit,
    onPresetSelect: (ProjectionEqPreset) -> Unit,
) {
    SettingsSectionCard(
        modifier = modifier,
        title = "Эквалайзер: ${playerLabel(selectedPlayer)}",
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            VerticalEqEditor(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                bandsDb = bandsDb,
                onBandChange = onBandChange,
            )

            PresetSidebar(
                modifier = Modifier
                    .width(228.dp)
                    .fillMaxHeight(),
                selected = preset,
                onSelect = onPresetSelect,
            )
        }
    }
}

@Composable
private fun PresetSidebar(
    modifier: Modifier = Modifier,
    selected: ProjectionEqPreset,
    onSelect: (ProjectionEqPreset) -> Unit,
) {
    val presets = listOf(
        ProjectionEqPreset.FLAT,
        ProjectionEqPreset.ACOUSTIC,
        ProjectionEqPreset.CLASSICAL,
        ProjectionEqPreset.DANCE,
        ProjectionEqPreset.ELECTRONIC,
        ProjectionEqPreset.HIP_HOP,
        ProjectionEqPreset.JAZZ,
        ProjectionEqPreset.POP,
        ProjectionEqPreset.ROCK,
        ProjectionEqPreset.LOUDNESS,
        ProjectionEqPreset.BASS,
        ProjectionEqPreset.SPOKEN_WORD,
        ProjectionEqPreset.TREBLE_BOOSTER,
        ProjectionEqPreset.CUSTOM,
    )

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = Color.White.copy(alpha = 0.04f),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 14.dp),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(presets.size) { index ->
                    val preset = presets[index]
                    val selectedChip = preset == selected
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(18.dp),
                        color = if (selectedChip) Color.White.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.05f),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            Color.White.copy(alpha = if (selectedChip) 0.10f else 0.06f),
                        ),
                    ) {
                        Text(
                            text = presetLabel(preset),
                            color = if (selectedChip) Color(0xFF09111E) else Color.White.copy(alpha = 0.84f),
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                            modifier = Modifier
                                .clickable { onSelect(preset) }
                                .fillMaxSize()
                                .wrapContentHeight(Alignment.CenterVertically)
                                .padding(horizontal = 16.dp),
                            textAlign = TextAlign.Start,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VerticalEqEditor(
    modifier: Modifier = Modifier,
    bandsDb: List<Float>,
    onBandChange: (Int, Float) -> Unit,
) {
    val labels = remember {
        listOf("32", "64", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = Color.White.copy(alpha = 0.04f),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.04f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                labels.forEachIndexed { index, label ->
                    VerticalEqBand(
                        width = 72.dp,
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
    val animatedValue by animateFloatAsState(
        targetValue = value,
        animationSpec = tween(140),
        label = "eqBandValue",
    )

    Column(
        modifier = Modifier
            .width(width)
            .fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = formatEq(value),
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            textAlign = TextAlign.Center,
        )

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            FilledVerticalSlider(
                value = animatedValue,
                valueRange = -12f..12f,
                modifier = Modifier
                    .fillMaxHeight()
                    .width(58.dp),
                baselineValue = 0f,
                activeColor = Color.White,
                inactiveColor = Color.White.copy(alpha = 0.10f),
                onValueChange = onValueChange,
            )
        }

        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun MicrophoneSection(
    modifier: Modifier = Modifier,
    isEnabled: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    gainMultiplier: Float,
    onGainChanged: (Float) -> Unit,
) {
    SettingsSectionCard(
        modifier = modifier,
        title = stringResource(id = R.string.settings_mic_title),
    ) {
        AdapterToggleRow(
            title = stringResource(id = R.string.settings_mic_route_adapter),
            checked = isEnabled,
            onCheckedChange = onToggleEnabled,
        )

        if (isEnabled) {
            Spacer(modifier = Modifier.height(20.dp))
            VerticalControlSlider(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                title = stringResource(id = R.string.settings_mic_gain),
                valueLabel = formatGain(gainMultiplier),
                value = gainMultiplier,
                valueRange = 1f..3f,
                onValueChange = onGainChanged,
            )
        }
    }
}

@Composable
private fun DiagnosticsSettingsSection(
    modifier: Modifier = Modifier,
    diagnosticsText: String,
) {
    SettingsSectionCard(
        modifier = modifier,
        title = stringResource(id = R.string.logs_title),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(24.dp),
            color = Color.White.copy(alpha = 0.04f),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
            ) {
                item {
                    Text(
                        text = diagnosticsText,
                        color = Color.White.copy(alpha = 0.82f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun VerticalControlSlider(
    modifier: Modifier = Modifier,
    title: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    val animatedValue by animateFloatAsState(
        targetValue = value,
        animationSpec = tween(140),
        label = "verticalControlValue",
    )

    Surface(
        modifier = modifier.fillMaxHeight(),
        shape = RoundedCornerShape(24.dp),
        color = Color.White.copy(alpha = 0.04f),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                color = Color.White.copy(alpha = 0.82f),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                textAlign = TextAlign.Center,
            )

            Text(
                text = valueLabel,
                color = Color.White,
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                textAlign = TextAlign.Center,
            )

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                FilledVerticalSlider(
                    value = animatedValue,
                    valueRange = valueRange,
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(76.dp),
                    activeColor = Color.White,
                    inactiveColor = Color.White.copy(alpha = 0.10f),
                    onValueChange = onValueChange,
                )
            }
        }
    }
}

@Composable
private fun FilledVerticalSlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    baselineValue: Float = valueRange.start,
    activeColor: Color = Color(0xFF34C759),
    inactiveColor: Color = Color.White.copy(alpha = 0.10f),
) {
    val coercedValue = value.coerceIn(valueRange.start, valueRange.endInclusive)
    val coercedBaseline = baselineValue.coerceIn(valueRange.start, valueRange.endInclusive)
    val totalRange = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    val topFraction = ((coercedValue - valueRange.start) / totalRange).coerceIn(0f, 1f)
    val baselineFraction = ((coercedBaseline - valueRange.start) / totalRange).coerceIn(0f, 1f)

    fun yToValue(y: Float, heightPx: Float): Float {
        val safeHeight = heightPx.coerceAtLeast(1f)
        val fraction = (1f - (y / safeHeight)).coerceIn(0f, 1f)
        return valueRange.start + (totalRange * fraction)
    }

    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        val trackHeight = maxHeight
        val trackWidth = maxWidth
        val cornerRadius = (trackWidth / 4).coerceAtLeast(10.dp)
        val baselineGuideHeight = 3.dp
        val minFillHeight = 18.dp

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(cornerRadius))
                .background(inactiveColor)
                .pointerInput(valueRange.start, valueRange.endInclusive) {
                    detectTapGestures { offset ->
                        onValueChange(yToValue(offset.y, size.height.toFloat()))
                    }
                }
                .pointerInput(valueRange.start, valueRange.endInclusive) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { change, _ ->
                            onValueChange(yToValue(change.position.y, size.height.toFloat()))
                        },
                    )
                },
        ) {
            val fillStartFraction = minOf(topFraction, baselineFraction)
            val fillEndFraction = maxOf(topFraction, baselineFraction)
            val rawFillTop = (1f - fillEndFraction) * trackHeight.value
            val rawFillBottom = (1f - fillStartFraction) * trackHeight.value
            val rawFillHeight = (rawFillBottom - rawFillTop).coerceAtLeast(0f)
            val displayFillHeight = rawFillHeight.coerceAtLeast(minFillHeight.value)
            val displayFillTop = when {
                rawFillHeight >= minFillHeight.value -> rawFillTop
                coercedBaseline == valueRange.start -> (trackHeight - minFillHeight).value.coerceAtLeast(0f)
                coercedBaseline == valueRange.endInclusive -> 0f
                else -> {
                    val center = (rawFillTop + rawFillBottom) / 2f
                    (center - (displayFillHeight / 2f)).coerceIn(
                        0f,
                        (trackHeight.value - displayFillHeight).coerceAtLeast(0f),
                    )
                }
            }
            val baselineGuideTop = (
                ((1f - baselineFraction) * trackHeight.value) - (baselineGuideHeight.value / 2f)
                ).coerceIn(
                0f,
                (trackHeight - baselineGuideHeight).value.coerceAtLeast(0f),
            )

            if (coercedBaseline != valueRange.start && coercedBaseline != valueRange.endInclusive) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .fillMaxWidth()
                        .height(baselineGuideHeight)
                        .align(Alignment.TopCenter)
                        .offset(y = baselineGuideTop.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.White.copy(alpha = 0.14f)),
                )
            }

            Box(
                modifier = Modifier
                    .padding(4.dp)
                    .fillMaxWidth()
                    .height(displayFillHeight.dp)
                    .align(Alignment.TopCenter)
                    .offset(y = displayFillTop.dp)
                    .clip(RoundedCornerShape((cornerRadius - 2.dp).coerceAtLeast(8.dp)))
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                activeColor.copy(alpha = 0.92f),
                                activeColor,
                            ),
                        ),
                    ),
            )
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
        ProjectionEqPreset.FLAT -> "Ровно"
        ProjectionEqPreset.ACOUSTIC -> "Акустика"
        ProjectionEqPreset.CLASSICAL -> "Классика"
        ProjectionEqPreset.DANCE -> "Танцы"
        ProjectionEqPreset.ELECTRONIC -> "Электро"
        ProjectionEqPreset.HIP_HOP -> "Хип-хоп"
        ProjectionEqPreset.JAZZ -> "Джаз"
        ProjectionEqPreset.POP -> "Поп"
        ProjectionEqPreset.ROCK -> "Рок"
        ProjectionEqPreset.LOUDNESS -> "Громко"
        ProjectionEqPreset.BASS -> "Больше баса"
        ProjectionEqPreset.VOCAL -> "Вокал"
        ProjectionEqPreset.SPOKEN_WORD -> "Речь"
        ProjectionEqPreset.TREBLE_BOOSTER -> "Больше высоких"
        ProjectionEqPreset.BRIGHT -> "Ярко"
        ProjectionEqPreset.CUSTOM -> "Свои"
    }
}

private fun playerLabel(player: ProjectionAudioPlayerType): String {
    return when (player) {
        ProjectionAudioPlayerType.MEDIA -> "Медиа"
        ProjectionAudioPlayerType.NAVI -> "Навигатор"
        ProjectionAudioPlayerType.SIRI -> "Siri"
        ProjectionAudioPlayerType.PHONE -> "Звонок"
        ProjectionAudioPlayerType.ALERT -> "Уведомления"
    }
}

private fun formatGain(value: Float): String = String.format("%.1fx", value)

private fun formatEq(value: Float): String = String.format("%+.0f dB", value)
