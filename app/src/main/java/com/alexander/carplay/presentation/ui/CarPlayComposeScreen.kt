package com.alexander.carplay.presentation.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alexander.carplay.R
import com.alexander.carplay.domain.model.ProjectionAudioPlayerType
import com.alexander.carplay.domain.model.ProjectionAudioRoute
import com.alexander.carplay.domain.model.ProjectionDeviceSettings
import com.alexander.carplay.domain.model.ProjectionDebugHeadUnitMode
import com.alexander.carplay.domain.model.ProjectionEqPreset
import com.alexander.carplay.domain.model.ProjectionMicRoute
import com.alexander.carplay.domain.model.ProjectionPlayerAudioSettings
import com.alexander.carplay.domain.model.buildProjectionSeatAutoDecayStages
import com.alexander.carplay.domain.model.ProjectionSeatAutoComfortSettings
import com.alexander.carplay.domain.model.ProjectionSeatAutoModeSettings
import com.alexander.carplay.domain.model.ProjectionUiEvent
import com.alexander.carplay.presentation.climate.ClimateBarScreen
import com.alexander.carplay.presentation.climate.rememberClimateBarState
import com.alexander.carplay.presentation.viewmodel.CarPlayDeviceUiState
import com.alexander.carplay.presentation.viewmodel.CarPlayUiState
import com.alexander.carplay.presentation.viewmodel.CarPlayViewModel
import kotlinx.coroutines.delay
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

private val AppTypography = Typography(
    displayLarge = iosTextStyle(
        fontSize = 34.sp,
        lineHeight = 38.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.8).sp,
    ),
    headlineMedium = iosTextStyle(
        fontSize = 28.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.5).sp,
    ),
    headlineSmall = iosTextStyle(
        fontSize = 24.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.4).sp,
    ),
    titleLarge = iosTextStyle(
        fontSize = 20.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.3).sp,
    ),
    titleMedium = iosTextStyle(
        fontSize = 17.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = (-0.2).sp,
    ),
    titleSmall = iosTextStyle(
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = (-0.1).sp,
    ),
    bodyLarge = iosTextStyle(
        fontSize = 17.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = (-0.2).sp,
    ),
    bodyMedium = iosTextStyle(
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = (-0.1).sp,
    ),
    bodySmall = iosTextStyle(
        fontSize = 13.sp,
        lineHeight = 17.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp,
    ),
    labelLarge = iosTextStyle(
        fontSize = 15.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.1).sp,
    ),
    labelMedium = iosTextStyle(
        fontSize = 13.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp,
    ),
    labelSmall = iosTextStyle(
        fontSize = 12.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp,
    ),
)

private val AutoSeatHeatThresholdOptions = listOf(4, 6, 8, 10, 12, 15)
private val AutoSeatVentThresholdOptions = listOf(22, 24, 26, 28, 30, 32)
private val AutoSeatStartLevelOptions = listOf(1, 2, 3)
private val AutoSeatDurationOptions = listOf(5, 10, 15, 20, 30)

private fun iosTextStyle(
    fontSize: androidx.compose.ui.unit.TextUnit,
    lineHeight: androidx.compose.ui.unit.TextUnit,
    fontWeight: FontWeight,
    letterSpacing: androidx.compose.ui.unit.TextUnit,
): TextStyle = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontSize = fontSize,
    lineHeight = lineHeight,
    fontWeight = fontWeight,
    letterSpacing = letterSpacing,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

@Composable
fun CarPlayComposeTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = AppColorScheme,
        typography = AppTypography,
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
            compareByDescending<CarPlayDeviceUiState> { it.isAvailable }
                .thenByDescending { it.isActive }
                .thenByDescending { it.isConnecting }
                .thenByDescending { it.isSelected }
                .thenBy { it.title.lowercase() },
        )
    }

    var selectedDeviceId by rememberSaveable { mutableStateOf<String?>(null) }
    var isSelectorExpanded by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var climatePanelPreferenceEnabled by rememberSaveable { mutableStateOf(viewModel.loadClimatePanelEnabled()) }

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
    val sessionIsActive = sessionSnapshot.state == com.alexander.carplay.domain.model.ProjectionConnectionState.STREAMING ||
        sessionSnapshot.protocolPhase == com.alexander.carplay.domain.model.ProjectionProtocolPhase.CARPLAY_SESSION_SETUP ||
        sessionSnapshot.protocolPhase == com.alexander.carplay.domain.model.ProjectionProtocolPhase.AIRPLAY_NEGOTIATING ||
        sessionSnapshot.protocolPhase == com.alexander.carplay.domain.model.ProjectionProtocolPhase.STREAMING_ACTIVE
    val hasResumeSnapshot = sessionIsActive &&
        ProjectionFrameSnapshotStore.peekFresh(
            targetWidth = uiState.videoWidth,
            targetHeight = uiState.videoHeight,
        ) != null
    val effectiveShowConnectionOverlay = uiState.showConnectionOverlay && !hasResumeSnapshot
    val shouldShowStop = sessionIsActive || selectedDevice?.isConnecting == true || selectedDevice?.isActive == true
    val connectActionEnabled = shouldShowStop || selectedDevice != null
    val appliedClimatePanelEnabled = sessionSnapshot.appliedClimatePanelEnabled
    val climateBarState = rememberClimateBarState(enabled = appliedClimatePanelEnabled)
    val context = LocalContext.current
    val hostDensity = LocalDensity.current
    val debugHeadUnitSpec = remember(context) { ProjectionDebugHeadUnitMode.resolve(context) }
    val containerScale = debugHeadUnitSpec?.containerScale ?: 1f
    val overlayUiScale = 1f
    val climateBarHeightDp = sessionSnapshot.appliedClimateBarHeightDp
    val shellDensity = remember(debugHeadUnitSpec, hostDensity, context) {
        if (debugHeadUnitSpec == null) {
            hostDensity
        } else {
            val dm = context.resources.displayMetrics
            val hostLandscapeWidthPx = maxOf(dm.widthPixels, dm.heightPixels)
            val containerWidthPx = hostLandscapeWidthPx * debugHeadUnitSpec.containerScale
            Density(
                density = containerWidthPx / debugHeadUnitSpec.totalWidth,
                fontScale = hostDensity.fontScale,
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        SimulatedHeadUnitContainer(
            modifier = Modifier.fillMaxSize(),
            scale = containerScale,
        ) {
            CompositionLocalProvider(LocalDensity provides shellDensity) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                Column(
                    modifier = if (debugHeadUnitSpec != null) {
                        Modifier
                            .fillMaxWidth()
                            .height(debugHeadUnitSpec.totalHeight.dp)
                    } else {
                        Modifier.fillMaxSize()
                    },
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clipToBounds(),
                        contentAlignment = Alignment.Center,
                    ) {
                        ProjectionViewportFrame(
                            modifier = Modifier.fillMaxSize(),
                            viewportWidth = null,
                            viewportHeight = null,
                        ) {
                            val clipTop = sessionSnapshot.appliedVideoClipTopPx
                            val clipBottom = sessionSnapshot.appliedVideoClipBottomPx
                            val totalClip = clipTop + clipBottom
                            val vH = uiState.videoHeight

                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .videoCrop(
                                        videoHeight = vH ?: 0,
                                        clipTopPx = clipTop,
                                        clipBottomPx = clipBottom,
                                    ),
                            ) {
                                ProjectionTextureSurface(
                                    viewModel = viewModel,
                                    videoWidth = uiState.videoWidth,
                                    videoHeight = uiState.videoHeight,
                                    sessionActive = sessionIsActive,
                                    touchEnabled = !effectiveShowConnectionOverlay,
                                )
                            }

                            androidx.compose.animation.AnimatedVisibility(
                                visible = effectiveShowConnectionOverlay,
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
                                    onDeviceSelected = { device: CarPlayDeviceUiState ->
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
                                    onSettingsClick = { showSettings = true },
                                    uiScale = overlayUiScale,
                                )
                            }

                            if (showSettings) {
                                ProjectionSettingsScreen(
                                    deviceId = sessionSnapshot.currentDeviceId,
                                    deviceName = sessionSnapshot.currentDeviceName,
                                    diagnosticsText = uiState.diagnosticsText,
                                    climatePanelEnabled = climatePanelPreferenceEnabled,
                                    viewModel = viewModel,
                                    onDismiss = { showSettings = false },
                                    onClimatePanelSaved = { climatePanelPreferenceEnabled = it },
                                    uiScale = overlayUiScale,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .zIndex(4f),
                                )
                            }
                        }
                    }

                    if (appliedClimatePanelEnabled) {
                        ClimateBarScreen(
                            state = climateBarState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(climateBarHeightDp.dp),
                        )
                    }
                }
                }
            }
        }

    }
}

/**
 * Layout modifier that physically enlarges the child beyond the parent bounds
 * so that [clipTopPx]+[clipBottomPx] video pixels are hidden by the parent's
 * [clipToBounds].  The TextureView inside receives a taller layout, mapping
 * [videoHeight] stream pixels into (parentH + extra) screen pixels — giving
 * reduced compression compared to fitting the full stream into parentH.
 */
private fun Modifier.videoCrop(
    videoHeight: Int,
    clipTopPx: Int,
    clipBottomPx: Int,
): Modifier {
    val totalClip = clipTopPx + clipBottomPx
    if (totalClip <= 0 || videoHeight <= totalClip) return this

    return this.layout { measurable, constraints ->
        val parentH = constraints.maxHeight
        // How many extra screen-pixels the child needs so the SurfaceTexture
        // maps (videoHeight) into (parentH + extra) instead of parentH.
        // visible content = videoHeight − totalClip displayed in parentH
        // full content    = videoHeight          displayed in parentH + extra
        // ⇒ extra = parentH × totalClip / (videoHeight − totalClip)
        val visibleContent = videoHeight - totalClip
        val extraPx = (parentH.toLong() * totalClip / visibleContent).toInt()
        val childH = parentH + extraPx

        val childConstraints = constraints.copy(
            minHeight = childH,
            maxHeight = childH,
        )
        val placeable = measurable.measure(childConstraints)
        // Shift child up so that clipTopPx share of the extra falls above the parent.
        val offsetY = -(extraPx.toLong() * clipTopPx / totalClip).toInt()
        layout(constraints.maxWidth, parentH) {
            placeable.placeRelative(0, offsetY)
        }
    }
}

@Composable
private fun SimulatedHeadUnitContainer(
    modifier: Modifier = Modifier,
    scale: Float,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        val displayWidth = if (scale < 1f) maxWidth * scale else maxWidth
        val displayHeight = if (scale < 1f) maxHeight * scale else maxHeight

        Box(
            modifier = Modifier
                .width(displayWidth)
                .height(displayHeight)
                .clipToBounds(),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                content()
            }
        }
    }
}

@Composable
private fun ProjectionViewportFrame(
    viewportWidth: Int?,
    viewportHeight: Int?,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    if (viewportWidth == null || viewportHeight == null || viewportWidth <= 0 || viewportHeight <= 0) {
        Box(
            modifier = modifier.fillMaxSize(),
            content = content,
        )
        return
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        val viewportAspect = viewportWidth.toFloat() / viewportHeight.toFloat()
        val availableAspect = if (maxHeight > 0.dp) {
            maxWidth.value / maxHeight.value
        } else {
            viewportAspect
        }
        val viewportModifier = if (availableAspect > viewportAspect) {
            Modifier
                .fillMaxHeight()
                .aspectRatio(viewportAspect)
        } else {
            Modifier
                .fillMaxWidth()
                .aspectRatio(viewportAspect)
        }
        Box(
            modifier = viewportModifier.clipToBounds(),
            content = content,
        )
    }
}

@Composable
private fun ScaledHeadUnitContent(
    modifier: Modifier = Modifier,
    scale: Float,
    content: @Composable () -> Unit,
) {
    SimulatedHeadUnitContainer(
        modifier = modifier,
        scale = scale,
        content = content,
    )
}

@Composable
private fun ProjectionTextureSurface(
    viewModel: CarPlayViewModel,
    videoWidth: Int?,
    videoHeight: Int?,
    sessionActive: Boolean,
    touchEnabled: Boolean,
) {
    val latestViewModel by rememberUpdatedState(viewModel)
    val latestVideoWidth by rememberUpdatedState(videoWidth)
    val latestVideoHeight by rememberUpdatedState(videoHeight)
    val latestSessionActive by rememberUpdatedState(sessionActive)
    val lifecycleOwner = LocalLifecycleOwner.current
    var textureSurface by remember { mutableStateOf<AndroidSurface?>(null) }
    var textureViewRef by remember { mutableStateOf<TextureView?>(null) }
    var surfaceBoundToSession by remember { mutableStateOf(false) }
    var lifecycleSurfaceEnabled by remember { mutableStateOf(false) }
    var lastAppliedBufferSize by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var resumeSnapshot by remember(sessionActive, videoWidth, videoHeight) {
        mutableStateOf(
            if (sessionActive) {
                ProjectionFrameSnapshotStore.peekFresh(
                    targetWidth = videoWidth,
                    targetHeight = videoHeight,
                )
            } else {
                null
            },
        )
    }
    var fadeSnapshotOut by remember { mutableStateOf(false) }
    var videoFrameObserved by remember { mutableStateOf(false) }
    val snapshotAlpha by animateFloatAsState(
        targetValue = when {
            resumeSnapshot == null -> 0f
            fadeSnapshotOut -> 0f
            else -> 1f
        },
        animationSpec = tween(durationMillis = if (fadeSnapshotOut) 340 else 0),
        label = "resumeSnapshotAlpha",
    )

    fun captureCurrentFrame(textureView: TextureView?) {
        if (!latestSessionActive) return
        val view = textureView ?: return
        if (!view.isAvailable || view.width <= 1 || view.height <= 1) return
        runCatching {
            view.bitmap?.let { bitmap ->
                ProjectionFrameSnapshotStore.save(
                    bitmap.copy(Bitmap.Config.ARGB_8888, false),
                )
            }
        }
    }

    fun bindSurfaceIfPossible(textureView: TextureView?) {
        if (!lifecycleSurfaceEnabled) return
        val view = textureView ?: return
        if (!view.isAvailable) return
        val surfaceTexture = view.surfaceTexture ?: return
        val width = latestVideoWidth
        val height = latestVideoHeight
        if (width != null && height != null) {
            val nextSize = width to height
            if (lastAppliedBufferSize != nextSize) {
                surfaceTexture.setDefaultBufferSize(width, height)
                view.post {
                    view.requestLayout()
                    view.invalidate()
                }
                lastAppliedBufferSize = nextSize
            }
        }
        view.isOpaque = true
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
            captureCurrentFrame(textureViewRef)
            unbindSurfaceFromSession()
            textureSurface?.release()
            textureSurface = null
            textureViewRef = null
            lastAppliedBufferSize = null
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START,
                Lifecycle.Event.ON_RESUME,
                -> {
                    lifecycleSurfaceEnabled = true
                    bindSurfaceIfPossible(textureViewRef)
                }

                Lifecycle.Event.ON_STOP -> {
                    captureCurrentFrame(textureViewRef)
                    lifecycleSurfaceEnabled = false
                    unbindSurfaceFromSession()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(lifecycleSurfaceEnabled, sessionActive, videoWidth, videoHeight) {
        if (!lifecycleSurfaceEnabled || !sessionActive) return@LaunchedEffect
        if (resumeSnapshot == null) {
            resumeSnapshot = ProjectionFrameSnapshotStore.peekFresh(
                targetWidth = videoWidth,
                targetHeight = videoHeight,
            )
        }
        fadeSnapshotOut = false
        videoFrameObserved = false
    }

    LaunchedEffect(videoFrameObserved, resumeSnapshot) {
        if (!videoFrameObserved || resumeSnapshot == null) return@LaunchedEffect
        delay(120)
        fadeSnapshotOut = true
    }

    LaunchedEffect(fadeSnapshotOut, resumeSnapshot) {
        if (!fadeSnapshotOut || resumeSnapshot == null) return@LaunchedEffect
        delay(420)
        resumeSnapshot = null
        fadeSnapshotOut = false
        videoFrameObserved = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds(),
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize(),
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
                            if (lifecycleSurfaceEnabled) {
                                attachSurface()
                            }
                        }

                        override fun onSurfaceTextureSizeChanged(
                            surface: SurfaceTexture,
                            width: Int,
                            height: Int,
                        ) {
                            val videoW = latestVideoWidth
                            val videoH = latestVideoHeight
                            if (videoW != null && videoH != null) {
                                val nextSize = videoW to videoH
                                if (lastAppliedBufferSize != nextSize) {
                                    surface.setDefaultBufferSize(videoW, videoH)
                                    post {
                                        requestLayout()
                                        invalidate()
                                    }
                                    lastAppliedBufferSize = nextSize
                                }
                            }
                        }

                        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                            captureCurrentFrame(this@apply)
                            unbindSurfaceFromSession()
                            textureSurface?.release()
                            textureSurface = null
                            textureViewRef = null
                            lastAppliedBufferSize = null
                            return true
                        }

                        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                            if (resumeSnapshot != null && !videoFrameObserved) {
                                videoFrameObserved = true
                            }
                        }
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
                    val nextSize = width to height
                    if (lastAppliedBufferSize != nextSize) {
                        textureView.surfaceTexture?.setDefaultBufferSize(width, height)
                        textureView.post {
                            textureView.requestLayout()
                            textureView.invalidate()
                        }
                        lastAppliedBufferSize = nextSize
                    }
                }
                if (lifecycleSurfaceEnabled && textureView.isAvailable) {
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

        val snapshot = resumeSnapshot
        if (snapshot != null && snapshotAlpha > 0f) {
            Image(
                bitmap = snapshot.bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(snapshotAlpha)
                    .zIndex(1f),
            )
        }
    }
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
    onSettingsClick: () -> Unit,
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
                            fontWeight = FontWeight.Normal,
                            fontSize = MaterialTheme.typography.displayLarge.fontSize * 4,
                            lineHeight = 180.sp,
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
                            modifier = Modifier.widthIn(min = 285.dp, max = 446.dp),
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

                OverlaySettingsButton(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 28.dp, bottom = overlayBottomOffset + 6.dp),
                    onClick = onSettingsClick,
                )
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
                color = Color.White.copy(alpha = 0.42f),
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Normal,
                    fontSize = MaterialTheme.typography.bodyLarge.fontSize * 1.24f,
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
    val density = LocalDensity.current

    Box(
        modifier = modifier
            .height(96.dp),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
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
                ActiveStateDot(isActive = selectedDevice?.isAvailable == true || selectedDevice?.isActive == true)

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(if (showCompactStatusOnly) 0.dp else 4.dp),
                ) {
                    if (showCompactStatusOnly) {
                        Text(
                            text = fallbackStatus,
                            color = Color.White.copy(alpha = 0.76f),
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Normal,
                                fontSize = MaterialTheme.typography.titleLarge.fontSize * 1.22f,
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
                            color = Color.White.copy(alpha = 0.58f),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Normal,
                                fontSize = MaterialTheme.typography.titleMedium.fontSize * 1.24f,
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

        if (isExpanded && devices.isNotEmpty()) {
            Popup(
                alignment = Alignment.BottomStart,
                offset = with(density) { IntOffset(0, (-12).dp.roundToPx()) },
                onDismissRequest = onDismiss,
                properties = PopupProperties(
                    focusable = true,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true,
                ),
            ) {
                Surface(
                    modifier = Modifier
                        .widthIn(min = 325.dp, max = 546.dp),
                    color = Color(0xF2141B28),
                    contentColor = Color.White,
                    shape = RoundedCornerShape(30.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
                    shadowElevation = 14.dp,
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp),
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        items(devices.size) { index ->
                            val device = devices[index]
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onSelect(device)
                                        onDismiss()
                                    }
                                    .padding(horizontal = 24.dp, vertical = 16.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    ActiveStateDot(isActive = device.isAvailable || device.isActive)
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = device.title,
                                            color = Color.White,
                                            style = MaterialTheme.typography.titleLarge.copy(
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = MaterialTheme.typography.titleLarge.fontSize * 1.32f,
                                            ),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Spacer(modifier = Modifier.height(3.dp))
                                        Text(
                                            text = device.subtitle,
                                            color = Color.White.copy(alpha = 0.58f),
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontWeight = FontWeight.Normal,
                                                fontSize = MaterialTheme.typography.titleMedium.fontSize * 1.14f,
                                            ),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    if (device.isConnecting) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(22.dp),
                                            color = Color.White,
                                            strokeWidth = 2.dp,
                                        )
                                    }
                                }
                                if (index < devices.lastIndex) {
                                    Spacer(
                                        modifier = Modifier
                                            .padding(top = 14.dp)
                                            .fillMaxWidth()
                                            .height(1.dp)
                                            .background(Color.White.copy(alpha = 0.08f)),
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
private fun OverlaySettingsButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .requiredSize(40.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.055f), CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.08f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_overlay_settings),
            contentDescription = stringResource(id = R.string.overlay_open_settings),
            modifier = Modifier.size(20.dp),
            tint = Color.White.copy(alpha = 0.64f),
        )
    }
}

@Composable
private fun ProjectionSettingsScreen(
    deviceId: String?,
    deviceName: String?,
    diagnosticsText: String,
    climatePanelEnabled: Boolean,
    viewModel: CarPlayViewModel,
    onDismiss: () -> Unit,
    onClimatePanelSaved: (Boolean) -> Unit,
    uiScale: Float,
    modifier: Modifier = Modifier,
) {
    val loadedSettings = remember(deviceId, deviceName) {
        viewModel.loadDeviceSettings(deviceId)
    }
    val loadedAdapterName = remember { viewModel.loadAdapterName() }
    val loadedAutoConnectEnabled = remember { viewModel.loadAutoConnectEnabled() }
    var savedSettings by remember(deviceId, deviceName) { mutableStateOf(loadedSettings) }
    var workingSettings by remember(deviceId, deviceName) { mutableStateOf(loadedSettings) }
    var savedAdapterName by remember { mutableStateOf(loadedAdapterName) }
    var workingAdapterName by remember { mutableStateOf(loadedAdapterName) }
    var savedAutoConnectEnabled by remember { mutableStateOf(loadedAutoConnectEnabled) }
    var workingAutoConnectEnabled by remember { mutableStateOf(loadedAutoConnectEnabled) }
    var savedClimatePanelEnabled by remember { mutableStateOf(climatePanelEnabled) }
    var workingClimatePanelEnabled by remember { mutableStateOf(climatePanelEnabled) }
    var showDiagnostics by rememberSaveable(deviceId, deviceName) { mutableStateOf(false) }
    var pendingRealtimePreview by remember(deviceId, deviceName) {
        mutableStateOf<ProjectionDeviceSettings?>(null)
    }

    val reconnectRequired = remember(
        workingSettings,
        savedSettings,
        workingAdapterName,
        savedAdapterName,
        workingClimatePanelEnabled,
        savedClimatePanelEnabled,
    ) {
        workingSettings.audioRoute != savedSettings.audioRoute ||
            workingSettings.micRoute != savedSettings.micRoute ||
            workingAdapterName != savedAdapterName ||
            workingClimatePanelEnabled != savedClimatePanelEnabled
    }
    val hasUnsavedChanges = workingSettings != savedSettings ||
        workingAdapterName != savedAdapterName ||
        workingAutoConnectEnabled != savedAutoConnectEnabled ||
        workingClimatePanelEnabled != savedClimatePanelEnabled

    LaunchedEffect(deviceId, deviceName) {
        val adapterName = viewModel.loadAdapterName()
        savedAdapterName = adapterName
        workingAdapterName = adapterName
        val autoConnectEnabled = viewModel.loadAutoConnectEnabled()
        savedAutoConnectEnabled = autoConnectEnabled
        workingAutoConnectEnabled = autoConnectEnabled
        savedClimatePanelEnabled = climatePanelEnabled
        workingClimatePanelEnabled = climatePanelEnabled
    }

    LaunchedEffect(pendingRealtimePreview, showDiagnostics) {
        val previewSettings = pendingRealtimePreview ?: return@LaunchedEffect
        if (showDiagnostics) return@LaunchedEffect
        delay(140)
        viewModel.previewDeviceSettings(previewSettings)
    }

    fun dismissSettings() {
        if (workingSettings != savedSettings) {
            viewModel.restoreSavedRuntimeSettings()
        }
        pendingRealtimePreview = null
        onDismiss()
    }

    BackHandler(onBack = ::dismissSettings)

    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
    ) {
        val climateCardWidth = (maxWidth * 0.18f).coerceIn(320.dp, 400.dp)
        val seatAutomationCardWidth = (maxWidth * 0.24f).coerceIn(420.dp, 540.dp)
        val adapterCardWidth = (maxWidth * 0.19f).coerceIn(340.dp, 460.dp)
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

        fun updateRealtimePlayerSettings(
            transform: (ProjectionPlayerAudioSettings) -> ProjectionPlayerAudioSettings,
        ) {
            val latestPlayerSettings =
                workingSettings.playerSettings[workingSettings.selectedPlayer] ?: ProjectionPlayerAudioSettings()
            val updatedPlayerSettings = transform(latestPlayerSettings)
            val updatedWorking = workingSettings.copy(
                playerSettings = workingSettings.playerSettings.toMutableMap().apply {
                    put(workingSettings.selectedPlayer, updatedPlayerSettings)
                },
            )
            workingSettings = updatedWorking
            if (!reconnectRequired) {
                pendingRealtimePreview = updatedWorking
            }
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
                    val settingsSheetTitle = stringResource(id = R.string.settings_sheet_title)
                    val settingsSheetDefaultDevice = stringResource(id = R.string.settings_sheet_default_device)
                    SettingsHeader(
                        title = if (showDiagnostics) {
                            stringResource(id = R.string.logs_title)
                        } else {
                            buildString {
                                append(settingsSheetTitle)
                                append(" (")
                                append(deviceName?.takeIf { it.isNotBlank() } ?: settingsSheetDefaultDevice)
                                append(")")
                            }
                        },
                        saveLabel = stringResource(
                            id = if (reconnectRequired) {
                                R.string.settings_save_reconnect
                            } else {
                                R.string.settings_save
                            },
                        ),
                        saveEnabled = hasUnsavedChanges && !showDiagnostics,
                        onBack = {
                            if (showDiagnostics) {
                                showDiagnostics = false
                            } else {
                                dismissSettings()
                            }
                        },
                        onSave = {
                            pendingRealtimePreview = null
                            viewModel.saveAdapterName(workingAdapterName)
                            viewModel.saveAutoConnectEnabled(workingAutoConnectEnabled)
                            viewModel.saveClimatePanelEnabled(workingClimatePanelEnabled)
                            savedAdapterName = workingAdapterName
                            savedAutoConnectEnabled = workingAutoConnectEnabled
                            savedClimatePanelEnabled = workingClimatePanelEnabled
                            onClimatePanelSaved(workingClimatePanelEnabled)
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
                            ClimatePanelSection(
                                modifier = Modifier
                                    .width(climateCardWidth)
                                    .fillMaxHeight(),
                                enabled = workingClimatePanelEnabled,
                                onEnabledChanged = { workingClimatePanelEnabled = it },
                            )

                            SeatAutoComfortSection(
                                modifier = Modifier
                                    .width(seatAutomationCardWidth)
                                    .fillMaxHeight(),
                                driverSettings = workingSettings.driverSeatAutoComfort,
                                passengerSettings = workingSettings.passengerSeatAutoComfort,
                                onDriverSettingsChanged = { updated ->
                                    workingSettings = workingSettings.copy(driverSeatAutoComfort = updated)
                                },
                                onPassengerSettingsChanged = { updated ->
                                    workingSettings = workingSettings.copy(passengerSeatAutoComfort = updated)
                                },
                            )

                            AdapterIdentitySection(
                                modifier = Modifier
                                    .width(adapterCardWidth)
                                    .fillMaxHeight(),
                                adapterName = workingAdapterName,
                                onAdapterNameChange = { workingAdapterName = sanitizeAdapterNameInput(it) },
                                autoConnectEnabled = workingAutoConnectEnabled,
                                onAutoConnectChanged = { workingAutoConnectEnabled = it },
                                onDisconnect = {
                                    workingAutoConnectEnabled = false
                                    savedAutoConnectEnabled = false
                                    viewModel.disconnectAndDisableAutoConnect()
                                    onDismiss()
                                },
                            )

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
                                    val updated = workingSettings.copy(
                                        micSettings = workingSettings.micSettings.copy(gainMultiplier = it),
                                    )
                                    workingSettings = updated
                                    if (!reconnectRequired) {
                                        pendingRealtimePreview = updated
                                    }
                                },
                            )

                            AudioSettingsGroup(
                                modifier = Modifier
                                    .width(audioGroupWidth)
                                    .fillMaxHeight(),
                                enabled = workingSettings.audioRoute == ProjectionAudioRoute.ADAPTER,
                                onEnabledChange = { enabled ->
                                    workingSettings = workingSettings.copy(
                                        audioRoute = if (enabled) {
                                            ProjectionAudioRoute.ADAPTER
                                        } else {
                                            ProjectionAudioRoute.CAR_BLUETOOTH
                                        },
                                    )
                                },
                            ) {
                                AudioProfilesSection(
                                    modifier = Modifier
                                        .width(audioCardWidth)
                                        .fillMaxHeight(),
                                    playerSettings = workingSettings.playerSettings,
                                    isEnabled = workingSettings.audioRoute == ProjectionAudioRoute.ADAPTER,
                                    selectedPlayer = workingSettings.selectedPlayer,
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
                                        onGainChanged = { value ->
                                            updateRealtimePlayerSettings { latest ->
                                                latest.copy(gainMultiplier = value)
                                            }
                                        },
                                        onLoudnessChanged = { value ->
                                            updateRealtimePlayerSettings { latest ->
                                                latest.copy(loudnessBoostPercent = value)
                                            }
                                        },
                                        onBassChanged = { value ->
                                            updateRealtimePlayerSettings { latest ->
                                                latest.copy(bassBoostPercent = value)
                                            }
                                        },
                                    )

                                    EqualizerSection(
                                        modifier = Modifier
                                            .width(eqCardWidth)
                                            .fillMaxHeight(),
                                        selectedPlayer = workingSettings.selectedPlayer,
                                        bandsDb = selectedPlayerSettings.eqBandsDb,
                                        preset = selectedPlayerSettings.eqPreset,
                                        onBandChange = { index, value ->
                                            updateRealtimePlayerSettings { latest ->
                                                val newBands = latest.eqBandsDb.toMutableList().apply {
                                                    this[index] = value
                                                }
                                                latest.copy(
                                                    eqPreset = ProjectionEqPreset.detect(newBands),
                                                    eqBandsDb = newBands,
                                                )
                                            }
                                        },
                                        onPresetSelect = { preset ->
                                            updateRealtimePlayerSettings { latest ->
                                                if (preset == ProjectionEqPreset.CUSTOM) {
                                                    latest.copy(eqPreset = ProjectionEqPreset.CUSTOM)
                                                } else {
                                                    latest.copy(
                                                        eqPreset = preset,
                                                        eqBandsDb = preset.bandsDb,
                                                    )
                                                }
                                            }
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
    fullWidth: Boolean = false,
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
        modifier = if (fullWidth) Modifier.fillMaxWidth() else Modifier.wrapContentWidth(),
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
                .then(if (fullWidth) Modifier.fillMaxWidth() else Modifier.wrapContentWidth())
                .clickable(enabled = enabled, onClick = onClick)
                .padding(
                    horizontal = if (large) 28.dp else 18.dp,
                    vertical = if (large) 18.dp else 13.dp,
                ),
            textAlign = if (fullWidth) TextAlign.Center else TextAlign.Start,
        )
    }
}

@Composable
private fun AudioSettingsGroup(
    modifier: Modifier = Modifier,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AdapterToggleSwitch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                )
                Text(
                    text = stringResource(id = R.string.settings_audio_group_title),
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
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
private fun ClimatePanelSection(
    enabled: Boolean,
    onEnabledChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsSectionCard(
        modifier = modifier,
        title = stringResource(id = R.string.settings_climate_panel_title),
        subtitle = stringResource(id = R.string.settings_climate_panel_subtitle),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            AdapterToggleRow(
                title = stringResource(id = R.string.settings_climate_panel_toggle),
                checked = enabled,
                onCheckedChange = onEnabledChanged,
            )
        }
    }
}

@Composable
private fun SeatAutoComfortSection(
    driverSettings: ProjectionSeatAutoComfortSettings,
    passengerSettings: ProjectionSeatAutoComfortSettings,
    onDriverSettingsChanged: (ProjectionSeatAutoComfortSettings) -> Unit,
    onPassengerSettingsChanged: (ProjectionSeatAutoComfortSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsSectionCard(
        modifier = modifier,
        title = stringResource(id = R.string.settings_seat_auto_title),
        subtitle = stringResource(id = R.string.settings_seat_auto_subtitle),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 4.dp),
        ) {
            item {
                SeatAutoComfortCard(
                    title = stringResource(id = R.string.settings_seat_left_title),
                    settings = driverSettings,
                    onSettingsChange = onDriverSettingsChanged,
                )
            }
            item {
                SeatAutoComfortCard(
                    title = stringResource(id = R.string.settings_seat_right_title),
                    settings = passengerSettings,
                    onSettingsChange = onPassengerSettingsChanged,
                )
            }
        }
    }
}

@Composable
private fun SeatAutoComfortCard(
    title: String,
    settings: ProjectionSeatAutoComfortSettings,
    onSettingsChange: (ProjectionSeatAutoComfortSettings) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        color = Color.White.copy(alpha = 0.045f),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            )

            SeatAutoModeBlock(
                title = stringResource(id = R.string.settings_seat_auto_heat_title),
                thresholdLabel = stringResource(id = R.string.settings_seat_auto_threshold_until_label),
                settings = settings.heat,
                thresholdOptions = AutoSeatHeatThresholdOptions,
                onSettingsChange = { updated -> onSettingsChange(settings.copy(heat = updated)) },
            )

            SeatAutoModeBlock(
                title = stringResource(id = R.string.settings_seat_auto_vent_title),
                thresholdLabel = stringResource(id = R.string.settings_seat_auto_threshold_from_label),
                settings = settings.vent,
                thresholdOptions = AutoSeatVentThresholdOptions,
                onSettingsChange = { updated -> onSettingsChange(settings.copy(vent = updated)) },
            )
        }
    }
}

@Composable
private fun SeatAutoModeBlock(
    title: String,
    thresholdLabel: String,
    settings: ProjectionSeatAutoModeSettings,
    thresholdOptions: List<Int>,
    onSettingsChange: (ProjectionSeatAutoModeSettings) -> Unit,
) {
    val decaySummary = remember(settings.startLevel, settings.durationMinutes) {
        buildProjectionSeatAutoDecayStages(
            startLevel = settings.startLevel,
            totalMinutes = settings.durationMinutes,
        )
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = Color.White.copy(alpha = 0.04f),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.weight(1f),
                )
                AdapterToggleSwitch(
                    checked = settings.enabled,
                    onCheckedChange = { enabled -> onSettingsChange(settings.copy(enabled = enabled)) },
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (settings.enabled) 1f else 0.7f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SeatAutoValueChip(
                    modifier = Modifier.weight(1f),
                    label = thresholdLabel,
                    value = "${settings.thresholdC}°C",
                    onClick = {
                        onSettingsChange(
                            settings.copy(
                                thresholdC = cycleOption(thresholdOptions, settings.thresholdC),
                            ),
                        )
                    },
                )
                SeatAutoValueChip(
                    modifier = Modifier.weight(1f),
                    label = stringResource(id = R.string.settings_seat_auto_level_label),
                    value = stringResource(
                        id = R.string.settings_seat_auto_level_value,
                        settings.startLevel,
                    ),
                    onClick = {
                        onSettingsChange(
                            settings.copy(
                                startLevel = cycleOption(AutoSeatStartLevelOptions, settings.startLevel),
                            ),
                        )
                    },
                )
                SeatAutoValueChip(
                    modifier = Modifier.weight(1f),
                    label = stringResource(id = R.string.settings_seat_auto_duration_label),
                    value = stringResource(
                        id = R.string.settings_seat_auto_duration_value,
                        settings.durationMinutes,
                    ),
                    onClick = {
                        onSettingsChange(
                            settings.copy(
                                durationMinutes = cycleOption(AutoSeatDurationOptions, settings.durationMinutes),
                            ),
                        )
                    },
                )
            }

            val decayLabel = stringResource(id = R.string.settings_seat_auto_decay_label)
            val decayStages = decaySummary.map { stage ->
                stringResource(
                    id = R.string.settings_seat_auto_decay_stage_value,
                    stage.minutes,
                    stage.level,
                )
            }

            Text(
                text = buildString {
                    append(decayLabel)
                    append(": ")
                    append(decayStages.joinToString(separator = " \u2192 "))
                },
                color = Color.White.copy(alpha = if (settings.enabled) 0.58f else 0.38f),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = FontWeight.Medium,
                    lineHeight = 17.sp,
                ),
            )
        }
    }
}

@Composable
private fun SeatAutoValueChip(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = Color.White.copy(alpha = 0.05f),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = label,
                color = Color.White.copy(alpha = 0.54f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
            )
            Text(
                text = value,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun AdapterIdentitySection(
    adapterName: String,
    onAdapterNameChange: (String) -> Unit,
    autoConnectEnabled: Boolean,
    onAutoConnectChanged: (Boolean) -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val backgroundExempt = context.isIgnoringBatteryOptimizationsCompat()

    SettingsSectionCard(
        modifier = modifier,
        title = "Адаптер",
        subtitle = "Имя для box, Wi-Fi и Bluetooth",
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = Color.White.copy(alpha = 0.05f),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.07f)),
        ) {
            BasicTextField(
                value = adapterName,
                onValueChange = onAdapterNameChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.headlineSmall.copy(
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                ),
                cursorBrush = SolidColor(Color.White),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                decorationBox = { innerTextField ->
                    if (adapterName.isBlank()) {
                        Text(
                            text = "Carlink-1234",
                            color = Color.White.copy(alpha = 0.32f),
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                        )
                    }
                    innerTextField()
                },
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        AdapterToggleRow(
            title = stringResource(id = R.string.settings_auto_connect),
            checked = autoConnectEnabled,
            onCheckedChange = onAutoConnectChanged,
        )

        Spacer(modifier = Modifier.height(18.dp))

        HeaderButton(
            label = stringResource(id = R.string.settings_disconnect),
            onClick = onDisconnect,
            fullWidth = true,
        )

        Spacer(modifier = Modifier.height(18.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = Color.White.copy(alpha = 0.045f),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(id = R.string.settings_background_title),
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                )
                Text(
                    text = stringResource(id = R.string.settings_background_subtitle),
                    color = Color.White.copy(alpha = 0.58f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                HeaderButton(
                    label = stringResource(
                        id = if (backgroundExempt) {
                            R.string.settings_background_button_manage
                        } else {
                            R.string.settings_background_button_request
                        },
                    ),
                    onClick = { context.openBatteryOptimizationSettings() },
                    subtle = true,
                    fullWidth = true,
                )
            }
        }
    }
}

@Composable
private fun SettingsSectionCard(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String? = null,
    headerLeading: (@Composable () -> Unit)? = null,
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                headerLeading?.invoke()
                Text(
                    text = title,
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.weight(1f),
                )
            }
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(16.dp))
            } else {
                Spacer(modifier = Modifier.height(12.dp))
            }
            content()
        }
    }
}

@Composable
private fun AudioProfilesSection(
    modifier: Modifier = Modifier,
    playerSettings: Map<ProjectionAudioPlayerType, ProjectionPlayerAudioSettings>,
    isEnabled: Boolean,
    selectedPlayer: ProjectionAudioPlayerType,
    onSelectPlayer: (ProjectionAudioPlayerType) -> Unit,
) {
    SettingsSectionCard(
        modifier = modifier,
        title = stringResource(id = R.string.settings_players_title),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(ProjectionAudioPlayerType.entries.size) { index ->
                val player = ProjectionAudioPlayerType.entries[index]
                val isSelected = selectedPlayer == player
                val volumeLabel = formatVolumePercent(playerSettings[player]?.gainMultiplier ?: 1f)
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
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = playerLabel(player),
                                color = if (isSelected) Color(0xFF09111E) else Color.White,
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                            )
                            Text(
                                text = stringResource(id = R.string.settings_player_volume_template, volumeLabel),
                                color = if (isSelected) {
                                    if (isEnabled) Color(0xFF09111E).copy(alpha = 0.68f) else Color.White.copy(alpha = 0.82f)
                                } else {
                                    Color.White.copy(alpha = 0.56f)
                                },
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
                            )
                        }
                        if (isSelected) {
                            Text(
                                text = stringResource(id = R.string.settings_player_selected),
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
    onGainChanged: (Float) -> Unit,
    onLoudnessChanged: (Int) -> Unit,
    onBassChanged: (Int) -> Unit,
) {
    SettingsSectionCard(
        modifier = modifier,
        title = stringResource(id = R.string.settings_player_enhancement_title, playerLabel(selectedPlayer)),
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
                valueLabel = formatVolumePercent(playerSettings.gainMultiplier),
                value = playerSettings.gainMultiplier,
                valueRange = 0f..3f,
                onValueChange = onGainChanged,
            )
            VerticalControlSlider(
                modifier = Modifier.weight(1f),
                title = stringResource(id = R.string.settings_loudness),
                valueLabel = "${playerSettings.loudnessBoostPercent}%",
                value = playerSettings.loudnessBoostPercent.toFloat(),
                valueRange = 0f..100f,
                onValueChange = { onLoudnessChanged(it.roundToInt()) },
            )
            VerticalControlSlider(
                modifier = Modifier.weight(1f),
                title = stringResource(id = R.string.settings_bass_boost),
                valueLabel = "${playerSettings.bassBoostPercent}%",
                value = playerSettings.bassBoostPercent.toFloat(),
                valueRange = 0f..100f,
                onValueChange = { onBassChanged(it.roundToInt()) },
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
    val bandGap = 6.dp

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
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                val bandWidth = ((maxWidth - bandGap * (labels.size - 1)) / labels.size)
                    .coerceAtMost(72.dp)

                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(bandGap),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    labels.forEachIndexed { index, label ->
                        VerticalEqBand(
                            width = bandWidth,
                            label = label,
                            value = bandsDb.getOrElse(index) { 0f },
                            onValueChange = { onBandChange(index, it) },
                        )
                    }
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
        headerLeading = {
            AdapterToggleSwitch(
                checked = isEnabled,
                onCheckedChange = onToggleEnabled,
            )
        },
    ) {
        if (isEnabled) {
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
    subtitle: String? = null,
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
                    text = subtitle ?: if (checked) "On" else "Off",
                    color = Color.White.copy(alpha = 0.56f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            AdapterToggleSwitch(
                checked = checked,
                onCheckedChange = onCheckedChange,
            )
        }
    }
}

@Composable
private fun AdapterToggleSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
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

@Composable
private fun AdapterOptionChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = if (selected) {
            Color.White.copy(alpha = 0.92f)
        } else {
            Color.White.copy(alpha = 0.06f)
        },
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            Color.White.copy(alpha = if (selected) 0.08f else 0.06f),
        ),
    ) {
        Text(
            text = label,
            color = if (selected) Color(0xFF09111E) else Color.White.copy(alpha = 0.84f),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 14.dp),
        )
    }
}

@Composable
private fun StepperButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.requiredWidth(62.dp),
        shape = RoundedCornerShape(22.dp),
        color = if (enabled) {
            Color.White.copy(alpha = 0.08f)
        } else {
            Color.White.copy(alpha = 0.035f)
        },
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            Color.White.copy(alpha = if (enabled) 0.08f else 0.05f),
        ),
    ) {
        Text(
            text = label,
            color = if (enabled) Color.White else Color.White.copy(alpha = 0.36f),
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onClick)
                .padding(vertical = 16.dp),
        )
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
        ProjectionAudioPlayerType.PHONE -> "Разговор"
        ProjectionAudioPlayerType.ALERT -> "Рингтон"
    }
}

private fun cycleOption(
    options: List<Int>,
    current: Int,
): Int {
    if (options.isEmpty()) return current
    val currentIndex = options.indexOf(current)
    return if (currentIndex == -1 || currentIndex == options.lastIndex) {
        options.first()
    } else {
        options[currentIndex + 1]
    }
}

private fun Context.isIgnoringBatteryOptimizationsCompat(): Boolean {
    val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        powerManager.isIgnoringBatteryOptimizations(packageName)
    } else {
        true
    }
}

private fun Context.openBatteryOptimizationSettings() {
    val intents = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !isIgnoringBatteryOptimizationsCompat()) {
            add(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }
        add(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        add(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }

    intents.forEach { intent ->
        try {
            startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
            // Try the next most-generic battery/settings screen.
        }
    }
}

private fun sanitizeAdapterNameInput(input: String): String {
    return input
        .filter { it.isLetterOrDigit() || it == '_' || it == '-' || it == ' ' }
        .take(16)
}

private fun formatGain(value: Float): String = String.format("%.1fx", value)

private fun formatVolumePercent(value: Float): String = "${(value * 100f).roundToInt()}%"

private fun formatEq(value: Float): String = String.format("%+.0f dB", value)
