package com.alexander.carplay.presentation.ui

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.os.bundleOf
import androidx.lifecycle.ViewModelProvider
import com.alexander.carplay.CarPlayApp
import com.alexander.carplay.R
import com.alexander.carplay.databinding.BottomSheetProjectionSettingsBinding
import com.alexander.carplay.databinding.ItemEqBandSliderBinding
import com.alexander.carplay.domain.model.ProjectionAudioPlayerType
import com.alexander.carplay.domain.model.ProjectionAudioRoute
import com.alexander.carplay.domain.model.ProjectionDeviceSettings
import com.alexander.carplay.domain.model.ProjectionEqPreset
import com.alexander.carplay.domain.model.ProjectionMicRoute
import com.alexander.carplay.domain.model.ProjectionPlayerAudioSettings
import com.alexander.carplay.presentation.viewmodel.CarPlayViewModel
import com.alexander.carplay.presentation.viewmodel.CarPlayViewModelFactory
import androidx.fragment.app.DialogFragment
import kotlin.math.roundToInt

class ProjectionSettingsBottomSheetFragment : DialogFragment() {
    companion object {
        private const val ARG_DEVICE_ID = "device_id"
        private const val ARG_DEVICE_NAME = "device_name"
        private const val ARG_APPLIED_AUDIO_ROUTE = "applied_audio_route"
        private const val ARG_APPLIED_MIC_ROUTE = "applied_mic_route"
        private val EQ_LABELS = listOf("32", "64", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
        const val TAG = "projection_settings_sheet"

        fun newInstance(
            deviceId: String?,
            deviceName: String?,
            appliedAudioRoute: ProjectionAudioRoute,
            appliedMicRoute: ProjectionMicRoute,
        ): ProjectionSettingsBottomSheetFragment {
            return ProjectionSettingsBottomSheetFragment().apply {
                arguments = bundleOf(
                    ARG_DEVICE_ID to deviceId,
                    ARG_DEVICE_NAME to deviceName,
                    ARG_APPLIED_AUDIO_ROUTE to appliedAudioRoute.name,
                    ARG_APPLIED_MIC_ROUTE to appliedMicRoute.name,
                )
            }
        }
    }

    private val viewModel: CarPlayViewModel by lazy(LazyThreadSafetyMode.NONE) {
        ViewModelProvider(
            requireActivity(),
            CarPlayViewModelFactory((requireActivity().application as CarPlayApp).appContainer),
        )[CarPlayViewModel::class.java]
    }

    private var _binding: BottomSheetProjectionSettingsBinding? = null
    private val binding: BottomSheetProjectionSettingsBinding
        get() = requireNotNull(_binding)

    private val eqRows = mutableListOf<ItemEqBandSliderBinding>()
    private var isRendering = false
    private lateinit var initialSettings: ProjectionDeviceSettings
    private lateinit var workingSettings: ProjectionDeviceSettings
    private lateinit var appliedAudioRoute: ProjectionAudioRoute
    private lateinit var appliedMicRoute: ProjectionMicRoute

    private val deviceId: String?
        get() = arguments?.getString(ARG_DEVICE_ID)

    private val deviceName: String?
        get() = arguments?.getString(ARG_DEVICE_NAME)

    override fun getTheme(): Int = R.style.ThemeOverlay_CarPlay_SettingsDialog

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return Dialog(requireContext(), theme).apply {
            setCanceledOnTouchOutside(true)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = BottomSheetProjectionSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        appliedAudioRoute =
            ProjectionAudioRoute.valueOf(requireArguments().getString(ARG_APPLIED_AUDIO_ROUTE) ?: ProjectionAudioRoute.ADAPTER.name)
        appliedMicRoute =
            ProjectionMicRoute.valueOf(requireArguments().getString(ARG_APPLIED_MIC_ROUTE) ?: ProjectionMicRoute.ADAPTER.name)
        initialSettings = viewModel.loadDeviceSettings(deviceId)
        workingSettings = initialSettings

        setupEqRows()
        setupControls()
        render()
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setDimAmount(0.12f)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                setBackgroundBlurRadius(56)
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun setupEqRows() {
        if (eqRows.isNotEmpty()) return
        EQ_LABELS.forEachIndexed { index, label ->
            val rowBinding = ItemEqBandSliderBinding.inflate(layoutInflater, binding.eqBandsContainer, false)
            rowBinding.eqBandLabel.text = label
            rowBinding.eqBandSlider.addOnChangeListener { _, value, fromUser ->
                if (!fromUser || isRendering) return@addOnChangeListener
                updateSelectedPlayer { current ->
                    current.copy(eqBandsDb = current.eqBandsDb.toMutableList().apply {
                        this[index] = value
                    })
                }
                rowBinding.eqBandValue.text = formatEqValue(value)
                applyRealtimePlayerSettings()
                updateSaveState()
            }
            binding.eqBandsContainer.addView(rowBinding.root)
            eqRows += rowBinding
        }
    }

    private fun setupControls() {
        binding.audioRouteGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || isRendering) return@addOnButtonCheckedListener
            workingSettings = workingSettings.copy(
                audioRoute = when (checkedId) {
                    R.id.audioRouteCarButton -> ProjectionAudioRoute.CAR_BLUETOOTH
                    else -> ProjectionAudioRoute.ADAPTER
                },
            )
            render()
        }

        binding.micRouteGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || isRendering) return@addOnButtonCheckedListener
            workingSettings = workingSettings.copy(
                micRoute = when (checkedId) {
                    R.id.micRoutePhoneButton -> ProjectionMicRoute.PHONE
                    else -> ProjectionMicRoute.ADAPTER
                },
            )
            render()
        }

        binding.playerTypeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || isRendering) return@addOnButtonCheckedListener
            workingSettings = workingSettings.copy(
                selectedPlayer = when (checkedId) {
                    R.id.playerNaviButton -> ProjectionAudioPlayerType.NAVI
                    R.id.playerSiriButton -> ProjectionAudioPlayerType.SIRI
                    R.id.playerPhoneButton -> ProjectionAudioPlayerType.PHONE
                    R.id.playerAlertButton -> ProjectionAudioPlayerType.ALERT
                    else -> ProjectionAudioPlayerType.MEDIA
                },
            )
            renderPlayerSettings()
            updateSaveState()
        }

        binding.eqPresetGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || isRendering) return@addOnButtonCheckedListener
            val preset = when (checkedId) {
                R.id.eqPresetLoudnessButton -> ProjectionEqPreset.LOUDNESS
                R.id.eqPresetBassButton -> ProjectionEqPreset.BASS
                R.id.eqPresetVocalButton -> ProjectionEqPreset.VOCAL
                R.id.eqPresetBrightButton -> ProjectionEqPreset.BRIGHT
                R.id.eqPresetCustomButton -> ProjectionEqPreset.CUSTOM
                else -> ProjectionEqPreset.FLAT
            }
            applyEqPreset(preset)
            renderPlayerSettings()
            applyRealtimePlayerSettings()
            updateSaveState()
        }

        binding.playerGainSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser || isRendering) return@addOnChangeListener
            updateSelectedPlayer { it.copy(gainMultiplier = value) }
            binding.playerGainValue.text = formatGain(value)
            applyRealtimePlayerSettings()
            updateSaveState()
        }

        binding.playerLoudnessSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser || isRendering) return@addOnChangeListener
            updateSelectedPlayer { it.copy(loudnessBoostPercent = value.roundToInt()) }
            binding.playerLoudnessValue.text = formatPercent(value.roundToInt())
            applyRealtimePlayerSettings()
            updateSaveState()
        }

        binding.playerBassSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser || isRendering) return@addOnChangeListener
            updateSelectedPlayer { it.copy(bassBoostPercent = value.roundToInt()) }
            binding.playerBassValue.text = formatBass(value.roundToInt())
            applyRealtimePlayerSettings()
            updateSaveState()
        }

        binding.micGainSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser || isRendering) return@addOnChangeListener
            workingSettings = workingSettings.copy(
                micSettings = workingSettings.micSettings.copy(gainMultiplier = value),
            )
            binding.micGainValue.text = formatGain(value)
            updateSaveState()
        }

        binding.closeButton.setOnClickListener { dismissAllowingStateLoss() }
        binding.saveButton.setOnClickListener {
            val reconnectRequired = isReconnectRequired()
            viewModel.saveDeviceSettings(workingSettings, reconnectRequired)
            dismissAllowingStateLoss()
        }
    }

    private fun render() {
        isRendering = true
        try {
            binding.settingsSubtitle.text = deviceName?.takeIf { it.isNotBlank() }?.let {
                getString(R.string.settings_sheet_device_template, it)
            } ?: getString(R.string.settings_sheet_default_device)

            binding.audioRouteGroup.check(
                if (workingSettings.audioRoute == ProjectionAudioRoute.ADAPTER) {
                    R.id.audioRouteAdapterButton
                } else {
                    R.id.audioRouteCarButton
                },
            )
            binding.micRouteGroup.check(
                if (workingSettings.micRoute == ProjectionMicRoute.ADAPTER) {
                    R.id.micRouteAdapterButton
                } else {
                    R.id.micRoutePhoneButton
                },
            )

            binding.playerCard.visibility =
                if (workingSettings.audioRoute == ProjectionAudioRoute.ADAPTER) View.VISIBLE else View.GONE
            binding.micCard.visibility =
                if (workingSettings.micRoute == ProjectionMicRoute.ADAPTER) View.VISIBLE else View.GONE

            binding.playerTypeGroup.check(
                when (workingSettings.selectedPlayer) {
                    ProjectionAudioPlayerType.MEDIA -> R.id.playerMediaButton
                    ProjectionAudioPlayerType.NAVI -> R.id.playerNaviButton
                    ProjectionAudioPlayerType.SIRI -> R.id.playerSiriButton
                    ProjectionAudioPlayerType.PHONE -> R.id.playerPhoneButton
                    ProjectionAudioPlayerType.ALERT -> R.id.playerAlertButton
                },
            )

            binding.micGainSlider.value = workingSettings.micSettings.gainMultiplier
            binding.micGainValue.text = formatGain(workingSettings.micSettings.gainMultiplier)

            renderPlayerSettings()
            updateSaveState()
        } finally {
            isRendering = false
        }
    }

    private fun renderPlayerSettings() {
        val playerType = workingSettings.selectedPlayer
        val playerSettings = workingSettings.playerSettings[playerType] ?: ProjectionPlayerAudioSettings()
        binding.playerSectionTitle.text = playerType.title
        binding.eqPresetGroup.check(
            when (playerSettings.eqPreset) {
                ProjectionEqPreset.FLAT -> R.id.eqPresetFlatButton
                ProjectionEqPreset.LOUDNESS -> R.id.eqPresetLoudnessButton
                ProjectionEqPreset.BASS -> R.id.eqPresetBassButton
                ProjectionEqPreset.VOCAL -> R.id.eqPresetVocalButton
                ProjectionEqPreset.BRIGHT -> R.id.eqPresetBrightButton
                ProjectionEqPreset.CUSTOM -> R.id.eqPresetCustomButton
            },
        )
        binding.playerGainSlider.value = playerSettings.gainMultiplier
        binding.playerGainValue.text = formatGain(playerSettings.gainMultiplier)
        binding.playerLoudnessSlider.value = playerSettings.loudnessBoostPercent.toFloat()
        binding.playerLoudnessValue.text = formatPercent(playerSettings.loudnessBoostPercent)
        binding.playerBassSlider.value = playerSettings.bassBoostPercent.toFloat()
        binding.playerBassValue.text = formatBass(playerSettings.bassBoostPercent)

        eqRows.forEachIndexed { index, row ->
            val value = playerSettings.eqBandsDb.getOrElse(index) { 0f }
            row.eqBandSlider.value = value
            row.eqBandValue.text = formatEqValue(value)
        }
    }

    private fun applyEqPreset(preset: ProjectionEqPreset) {
        updateSelectedPlayer { current ->
            val bands = if (preset == ProjectionEqPreset.CUSTOM) {
                current.eqBandsDb
            } else {
                preset.bandsDb
            }
            current.copy(
                eqPreset = preset,
                eqBandsDb = bands,
            )
        }
    }

    private fun updateSelectedPlayer(
        transform: (ProjectionPlayerAudioSettings) -> ProjectionPlayerAudioSettings,
    ) {
        val playerType = workingSettings.selectedPlayer
        val currentSettings = workingSettings.playerSettings[playerType] ?: ProjectionPlayerAudioSettings()
        val updatedSettings = transform(currentSettings)
        val detectedPreset = ProjectionEqPreset.detect(updatedSettings.eqBandsDb)
        workingSettings = workingSettings.copy(
            playerSettings = workingSettings.playerSettings.toMutableMap().apply {
                put(
                    playerType,
                    updatedSettings.copy(
                        eqPreset = if (updatedSettings.eqPreset == ProjectionEqPreset.CUSTOM) {
                            ProjectionEqPreset.CUSTOM
                        } else {
                            detectedPreset
                        },
                    ),
                )
            },
        )
    }

    private fun applyRealtimePlayerSettings() {
        val realtimeSettings = initialSettings.copy(
            selectedPlayer = workingSettings.selectedPlayer,
            playerSettings = workingSettings.playerSettings,
        )
        initialSettings = realtimeSettings
        viewModel.saveDeviceSettings(
            settings = realtimeSettings,
            reconnectRequired = false,
        )
    }

    private fun updateSaveState() {
        val reconnectRequired = isReconnectRequired()
        binding.reconnectCard.visibility = if (reconnectRequired) View.VISIBLE else View.GONE
        binding.saveButton.text = if (reconnectRequired) {
            getString(R.string.settings_save_reconnect)
        } else {
            getString(R.string.settings_save)
        }
    }

    private fun isReconnectRequired(): Boolean {
        return workingSettings.audioRoute != initialSettings.audioRoute ||
            workingSettings.micRoute != initialSettings.micRoute
    }

    private fun formatGain(value: Float): String = String.format("%.1fx", value)

    private fun formatPercent(value: Int): String = "$value%"

    private fun formatBass(value: Int): String = "$value%"

    private fun formatEqValue(value: Float): String = String.format("%+.0f dB", value)
}
