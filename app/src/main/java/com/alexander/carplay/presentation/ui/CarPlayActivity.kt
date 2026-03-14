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
import com.alexander.carplay.presentation.viewmodel.CarPlayViewModel
import com.alexander.carplay.presentation.viewmodel.CarPlayViewModelFactory

class CarPlayActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_REPLAY_CAPTURE_PATH = "replay_capture_path"
    }

    private val viewModel: CarPlayViewModel by viewModels {
        CarPlayViewModelFactory((application as CarPlayApp).appContainer)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
        requestNotificationsIfNeeded()

        setContent {
            CarPlayComposeTheme {
                CarPlayRoute(viewModel = viewModel)
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeStartReplayFromIntent(intent, initial = false)
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
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
        launchIntent: Intent?,
        initial: Boolean,
    ) {
        val capturePath = launchIntent?.getStringExtra(EXTRA_REPLAY_CAPTURE_PATH) ?: return
        viewModel.onReplayClicked(capturePath)
        if (!initial) {
            Toast.makeText(this, "Replay started: $capturePath", Toast.LENGTH_SHORT).show()
        }
    }
}
