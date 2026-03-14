package com.alexander.carplay.presentation.ui

import android.Manifest
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.alexander.carplay.CarPlayApp
import com.alexander.carplay.databinding.ActivityCarPlayBinding
import com.alexander.carplay.databinding.ItemProjectionDeviceBinding
import com.alexander.carplay.presentation.viewmodel.CarPlayDeviceUiState
import com.alexander.carplay.presentation.viewmodel.CarPlayUiState
import com.alexander.carplay.presentation.viewmodel.CarPlayViewModel
import com.alexander.carplay.presentation.viewmodel.CarPlayViewModelFactory
import kotlinx.coroutines.launch

class CarPlayActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_REPLAY_CAPTURE_PATH = "replay_capture_path"
        private const val STATE_DIAGNOSTICS_COLLAPSED = "diagnostics_collapsed"
    }

    private lateinit var binding: ActivityCarPlayBinding
    private lateinit var connectionOverlay: View
    private lateinit var freezeFrameView: ImageView
    private lateinit var overlayStateIndicator: TextView
    private lateinit var overlayStatusText: TextView
    private lateinit var deviceScrollView: HorizontalScrollView
    private lateinit var deviceListContainer: LinearLayout
    private var diagnosticsCollapsed = false
    private var outputSurface: Surface? = null
    private var videoWidth = 0
    private var videoHeight = 0
    private var lastDiagnosticsText = ""
    private var lastRenderedDevices: List<CarPlayDeviceUiState> = emptyList()
    private var overlayVisible = true
    private var lastStreaming = false
    private var frozenFrameBitmap: Bitmap? = null

    private val viewModel: CarPlayViewModel by viewModels {
        CarPlayViewModelFactory((application as CarPlayApp).appContainer)
    }

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(
            surface: SurfaceTexture,
            width: Int,
            height: Int,
        ) {
            bindSurfaceTexture(surface)
        }

        override fun onSurfaceTextureSizeChanged(
            surface: SurfaceTexture,
            width: Int,
            height: Int,
        ) {
            updateSurfaceBufferSize(surface)
            applyVideoTransform()
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            viewModel.onSurfaceDestroyed()
            outputSurface?.release()
            outputSurface = null
            return true
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCarPlayBinding.inflate(layoutInflater)
        setContentView(binding.root)
        connectionOverlay = findViewById(com.alexander.carplay.R.id.connectionOverlay)
        freezeFrameView = findViewById(com.alexander.carplay.R.id.freezeFrameView)
        overlayStateIndicator = findViewById(com.alexander.carplay.R.id.overlayStateIndicator)
        overlayStatusText = findViewById(com.alexander.carplay.R.id.overlayStatusText)
        deviceScrollView = findViewById(com.alexander.carplay.R.id.deviceScrollView)
        deviceListContainer = findViewById(com.alexander.carplay.R.id.deviceListContainer)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
        requestNotificationsIfNeeded()
        diagnosticsCollapsed = savedInstanceState?.getBoolean(STATE_DIAGNOSTICS_COLLAPSED, false) ?: false

        binding.projectionSurface.surfaceTextureListener = surfaceTextureListener
        if (binding.projectionSurface.isAvailable) {
            binding.projectionSurface.surfaceTexture?.let(::bindSurfaceTexture)
        }
        binding.connectButton.setOnClickListener { viewModel.onConnectClicked() }
        binding.replayButton.setOnClickListener { viewModel.onReplayClicked() }
        binding.collapseButton.setOnClickListener { setDiagnosticsCollapsed(true) }
        binding.expandButton.setOnClickListener { setDiagnosticsCollapsed(false) }
        binding.collapsedOverlayPanel.setOnClickListener { setDiagnosticsCollapsed(false) }
        binding.settingsButton.setOnClickListener {
            Toast.makeText(this, "Настройки добавим следующим шагом", Toast.LENGTH_SHORT).show()
        }
        binding.touchCapture.setOnTouchListener { _, event ->
            if (!overlayVisible) {
                viewModel.onTouchEvent(
                    event,
                    binding.touchCapture.width,
                    binding.touchCapture.height,
                )
            }
            true
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect(::render)
                }
            }
        }

        maybeStartReplayFromIntent(intent, initial = true)
    }

    override fun onStart() {
        super.onStart()
        viewModel.onStart()
        viewModel.onBindUi()
    }

    override fun onStop() {
        super.onStop()
        viewModel.onUnbindUi()
    }

    override fun onDestroy() {
        binding.projectionSurface.surfaceTextureListener = null
        outputSurface?.release()
        outputSurface = null
        frozenFrameBitmap?.recycle()
        frozenFrameBitmap = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeStartReplayFromIntent(intent, initial = false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_DIAGNOSTICS_COLLAPSED, diagnosticsCollapsed)
        super.onSaveInstanceState(outState)
    }

    private fun render(uiState: CarPlayUiState) {
        binding.stateIndicator.text = uiState.stateLabel
        binding.stateIndicator.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, uiState.overlayColorRes))
        binding.collapsedStateIndicator.text = uiState.stateLabel
        binding.collapsedStateIndicator.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, uiState.overlayColorRes))
        overlayStateIndicator.text = uiState.stateLabel
        val subduedOverlayStateColor =
            ColorUtils.setAlphaComponent(ContextCompat.getColor(this, uiState.overlayColorRes), 72)
        overlayStateIndicator.backgroundTintList =
            ColorStateList.valueOf(subduedOverlayStateColor)
        binding.statusMessage.text = uiState.statusMessage
        binding.collapsedStatusMessage.text = uiState.statusMessage
        overlayStatusText.text = uiState.statusMessage
        videoWidth = uiState.videoWidth ?: 0
        videoHeight = uiState.videoHeight ?: 0
        updateSurfaceBufferSize()
        updateDiagnosticsText(uiState.diagnosticsText)
        renderDevices(uiState.devices)
        updateOverlayState(uiState.showConnectionOverlay, uiState.isStreaming)
        binding.connectButton.visibility = if (uiState.showConnectButton) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
        binding.replayButton.visibility = if (uiState.showConnectButton) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
        applyDiagnosticsPanelState()
        applyVideoTransform()
    }

    private fun updateDiagnosticsText(text: String) {
        if (text == lastDiagnosticsText) return

        val shouldStickToTop = isLogNearTop()
        val scrollView = binding.logScrollView
        val textView = binding.logText
        val previousScrollY = scrollView.scrollY
        val previousContentHeight = scrollView.getChildAt(0)?.height ?: 0

        if (lastDiagnosticsText.isNotEmpty() && text.startsWith(lastDiagnosticsText)) {
            prependDiagnosticsDelta(text.substring(lastDiagnosticsText.length))
        } else {
            textView.text = formatDiagnosticsForDisplay(text)
        }
        lastDiagnosticsText = text

        if (shouldStickToTop) {
            scrollView.post {
                scrollView.scrollTo(0, 0)
            }
        } else {
            scrollView.post {
                val content = scrollView.getChildAt(0) ?: return@post
                val maxScrollY = (content.height - scrollView.height).coerceAtLeast(0)
                val contentDelta = content.height - previousContentHeight
                scrollView.scrollTo(0, (previousScrollY + contentDelta).coerceIn(0, maxScrollY))
            }
        }
    }

    private fun prependDiagnosticsDelta(delta: String) {
        val formattedDelta = formatDiagnosticsForDisplay(delta)
        if (formattedDelta.isEmpty()) return

        val existing = binding.logText.text?.toString().orEmpty()
        binding.logText.text = if (existing.isEmpty()) {
            formattedDelta
        } else {
            "$formattedDelta\n$existing"
        }
    }

    private fun formatDiagnosticsForDisplay(rawText: String): String {
        val trimmed = rawText.trimEnd('\n')
        if (trimmed.isEmpty()) return ""
        return trimmed
            .split('\n')
            .asReversed()
            .joinToString("\n")
    }

    private fun isLogNearTop(): Boolean {
        return binding.logScrollView.scrollY <= 48
    }

    private fun bindSurfaceTexture(surfaceTexture: SurfaceTexture) {
        updateSurfaceBufferSize(surfaceTexture)
        outputSurface?.release()
        outputSurface = Surface(surfaceTexture)
        viewModel.onSurfaceAvailable(outputSurface!!)
        applyVideoTransform()
    }

    private fun renderDevices(devices: List<CarPlayDeviceUiState>) {
        if (devices == lastRenderedDevices) return
        lastRenderedDevices = devices

        deviceListContainer.removeAllViews()
        deviceScrollView.visibility = if (devices.isEmpty()) View.GONE else View.VISIBLE
        devices.forEach { device ->
            val itemBinding = ItemProjectionDeviceBinding.inflate(layoutInflater, deviceListContainer, false)
            itemBinding.deviceTitle.text = device.title
            itemBinding.deviceSubtitle.text = device.subtitle
            itemBinding.deviceProgress.visibility = if (device.isConnecting) View.VISIBLE else View.GONE
            itemBinding.deviceCancelButton.visibility = if (device.isConnecting) View.VISIBLE else View.GONE
            itemBinding.deviceCancelButton.setOnClickListener { viewModel.onCancelDeviceConnection() }
            itemBinding.deviceCard.setOnClickListener { viewModel.onDeviceSelected(device.id) }

            val backgroundColor = when {
                device.isActive -> com.alexander.carplay.R.color.device_chip_active_bg
                device.isSelected || device.isConnecting -> com.alexander.carplay.R.color.device_chip_pending_bg
                else -> com.alexander.carplay.R.color.device_chip_idle_bg
            }
            val strokeColor = when {
                device.isActive -> com.alexander.carplay.R.color.device_chip_active_stroke
                device.isSelected || device.isConnecting -> com.alexander.carplay.R.color.device_chip_pending_stroke
                else -> com.alexander.carplay.R.color.device_chip_idle_stroke
            }
            itemBinding.deviceCard.setCardBackgroundColor(ContextCompat.getColor(this, backgroundColor))
            itemBinding.deviceCard.strokeColor = ContextCompat.getColor(this, strokeColor)
            deviceListContainer.addView(itemBinding.root)
        }
    }

    private fun updateOverlayState(
        shouldShowOverlay: Boolean,
        isStreaming: Boolean,
    ) {
        if (lastStreaming && !isStreaming) {
            captureFreezeFrame()
        }
        if (isStreaming) {
            clearFreezeFrame()
        }
        lastStreaming = isStreaming

        if (shouldShowOverlay == overlayVisible) return
        overlayVisible = shouldShowOverlay

        if (shouldShowOverlay) {
            connectionOverlay.visibility = View.VISIBLE
            connectionOverlay.alpha = 0f
            connectionOverlay.animate().alpha(1f).setDuration(220L).start()
            if (freezeFrameView.drawable != null) {
                freezeFrameView.visibility = View.VISIBLE
                freezeFrameView.alpha = 0f
                freezeFrameView.animate().alpha(1f).setDuration(180L).start()
            }
        } else {
            connectionOverlay.animate()
                .alpha(0f)
                .setDuration(220L)
                .withEndAction {
                    connectionOverlay.visibility = View.GONE
                }
                .start()
            freezeFrameView.animate()
                .alpha(0f)
                .setDuration(180L)
                .withEndAction {
                    freezeFrameView.visibility = View.GONE
                }
                .start()
        }
    }

    private fun captureFreezeFrame() {
        val bitmap = binding.projectionSurface.bitmap ?: return
        frozenFrameBitmap?.recycle()
        frozenFrameBitmap = bitmap
        freezeFrameView.setImageBitmap(bitmap)
        freezeFrameView.alpha = 1f
        freezeFrameView.visibility = View.VISIBLE
    }

    private fun clearFreezeFrame() {
        freezeFrameView.setImageDrawable(null)
        freezeFrameView.visibility = View.GONE
        freezeFrameView.alpha = 0f
        frozenFrameBitmap?.recycle()
        frozenFrameBitmap = null
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, binding.root).let { controller ->
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return
        }
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
    }

    private fun maybeStartReplayFromIntent(
        launchIntent: android.content.Intent?,
        initial: Boolean,
    ) {
        val capturePath = launchIntent?.getStringExtra(EXTRA_REPLAY_CAPTURE_PATH) ?: return
        viewModel.onReplayClicked(capturePath)
        if (!initial) {
            Toast.makeText(this, "Replay started: $capturePath", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setDiagnosticsCollapsed(collapsed: Boolean) {
        diagnosticsCollapsed = collapsed
        applyDiagnosticsPanelState()
    }

    private fun applyDiagnosticsPanelState() {
        binding.overlayPanel.visibility = if (diagnosticsCollapsed) {
            android.view.View.GONE
        } else {
            android.view.View.VISIBLE
        }
        binding.collapsedOverlayPanel.visibility = if (diagnosticsCollapsed) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
    }

    private fun applyVideoTransform() {
        binding.projectionSurface.setTransform(null)
    }

    private fun updateSurfaceBufferSize(surfaceTexture: SurfaceTexture? = binding.projectionSurface.surfaceTexture) {
        if (videoWidth <= 0 || videoHeight <= 0) return
        surfaceTexture?.setDefaultBufferSize(videoWidth, videoHeight)
    }
}
