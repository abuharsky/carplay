package com.alexander.carplay.presentation.ui

import android.Manifest
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.alexander.carplay.CarPlayApp
import com.alexander.carplay.databinding.ActivityCarPlayBinding
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
    private var diagnosticsCollapsed = false
    private var outputSurface: Surface? = null
    private var videoWidth = 0
    private var videoHeight = 0
    private var lastDiagnosticsText = ""

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
            if (event.actionMasked == MotionEvent.ACTION_DOWN || binding.connectButton.visibility != android.view.View.VISIBLE) {
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
        binding.statusMessage.text = uiState.statusMessage
        binding.collapsedStatusMessage.text = uiState.statusMessage
        videoWidth = uiState.videoWidth ?: 0
        videoHeight = uiState.videoHeight ?: 0
        updateSurfaceBufferSize()
        updateDiagnosticsText(uiState.diagnosticsText)
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
