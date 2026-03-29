package com.alexander.carplay.presentation.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.alexander.carplay.CarPlayApp
import com.alexander.carplay.data.logging.DiagnosticLogStore
import com.alexander.carplay.data.logging.ProcessDiagnostics
import com.alexander.carplay.presentation.viewmodel.CarPlayViewModel
import com.alexander.carplay.presentation.viewmodel.CarPlayViewModelFactory

class CarPlayActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_REPLAY_CAPTURE_PATH = "replay_capture_path"
        private const val LOG_SOURCE = "Activity"
    }

    private val viewModel: CarPlayViewModel by viewModels {
        CarPlayViewModelFactory((application as CarPlayApp).appContainer)
    }
    private val logStore: DiagnosticLogStore by lazy {
        (application as CarPlayApp).appContainer.logStore
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logStore.info(
            LOG_SOURCE,
            "onCreate taskId=$taskId changingConfig=$isChangingConfigurations instance=${System.identityHashCode(this)} intent=${describeIntent(intent)} | ${ProcessDiagnostics.describeCurrentProcess()}",
        )
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
        requestRuntimePermissionsIfNeeded()

        setContent {
            CarPlayComposeTheme {
                CarPlayRoute(viewModel = viewModel)
            }
        }

        maybeStartReplayFromIntent(intent, initial = true)
    }

    override fun onStart() {
        super.onStart()
        logStore.info(LOG_SOURCE, "onStart taskId=$taskId instance=${System.identityHashCode(this)}")
        hideSystemBars()
        viewModel.onStart()
        viewModel.onActivityVisibilityChanged(true)
        viewModel.onBindUi()
    }

    override fun onResume() {
        super.onResume()
        logStore.info(
            LOG_SOURCE,
            "onResume taskId=$taskId instance=${System.identityHashCode(this)} hasFocus=${window.decorView.hasWindowFocus()}",
        )
        hideSystemBars()
    }

    override fun onStop() {
        super.onStop()
        logStore.info(
            LOG_SOURCE,
            "onStop taskId=$taskId instance=${System.identityHashCode(this)} changingConfig=$isChangingConfigurations finishing=$isFinishing",
        )
        viewModel.onActivityVisibilityChanged(false)
        viewModel.onUnbindUi()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        logStore.info(
            LOG_SOURCE,
            "onWindowFocusChanged hasFocus=$hasFocus taskId=$taskId instance=${System.identityHashCode(this)}",
        )
        if (hasFocus) {
            hideSystemBars()
            viewModel.onWindowFocusGained()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        logStore.info(LOG_SOURCE, "onNewIntent ${describeIntent(intent)} instance=${System.identityHashCode(this)}")
        setIntent(intent)
        maybeStartReplayFromIntent(intent, initial = false)
    }

    override fun onDestroy() {
        super.onDestroy()
        logStore.info(
            LOG_SOURCE,
            "onDestroy taskId=$taskId instance=${System.identityHashCode(this)} changingConfig=$isChangingConfigurations finishing=$isFinishing",
        )
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun requestRuntimePermissionsIfNeeded() {
        val missingPermissions = buildList {
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.RECORD_AUDIO)
            }
        }
        if (missingPermissions.isNotEmpty()) {
            requestPermissions(missingPermissions.toTypedArray(), 100)
        }
    }

    private fun maybeStartReplayFromIntent(
        launchIntent: Intent?,
        initial: Boolean,
    ) {
        val capturePath = launchIntent?.getStringExtra(EXTRA_REPLAY_CAPTURE_PATH) ?: return
        viewModel.onReplayClicked(capturePath)
        if (!initial) {
            Toast.makeText(this, "Replay started: $capturePath", Toast.LENGTH_SHORT).show()
        }
    }

    private fun describeIntent(intent: Intent?): String {
        if (intent == null) return "null"
        val extras = intent.extras?.keySet()?.sorted()?.joinToString(prefix = "[", postfix = "]") ?: "[]"
        return "action=${intent.action ?: "-"} extras=$extras flags=0x${intent.flags.toString(16)}"
    }
}
