package com.turbolego.songguesser

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.turbolego.songguesser.databinding.FragmentSettingsBinding

/**
 * Configures the curated YouTube source as well as the portable game mechanics
 * shared with the web version. Spotify-account-only settings deliberately remain
 * out of this screen because the Android edition uses a local video catalog.
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private var workingWeights: Map<Int, Int> = emptyMap()
    private val weightLabels = mutableMapOf<Int, TextView>()
    private val weightSliders = mutableMapOf<Int, SeekBar>()
    private var updatingWeightControls = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadCurrentSettings()
        setupListeners()
    }

    override fun onDestroyView() {
        weightLabels.clear()
        weightSliders.clear()
        _binding = null
        super.onDestroyView()
    }

    private fun loadCurrentSettings() {
        val context = requireContext()
        when (LocaleHelper.getLanguage(context)) {
            "en" -> binding.radioLanguageEnglish.isChecked = true
            else -> binding.radioLanguageNorwegian.isChecked = true
        }

        val source = VideoProvider.getSource(context)
        binding.radioDefaultList.isChecked = source == VideoProvider.Source.DEFAULT
        binding.radioCustomList.isChecked = source == VideoProvider.Source.CUSTOM
        binding.editCustomList.setText(VideoProvider.getCustomListRaw(context))
        updateCustomListVisibility()

        when (GamePreferences.gameMode(context)) {
            GameMode.CLASSIC -> binding.radioClassic.isChecked = true
            GameMode.ARCADE -> binding.radioArcade.isChecked = true
        }

        when (GamePreferences.randomizationMode(context)) {
            RandomizationMode.PURE_RANDOM -> binding.radioPureRandom.isChecked = true
            RandomizationMode.PRIORITIZE_MODERN -> binding.radioPrioritizeModern.isChecked = true
            RandomizationMode.CUSTOM -> binding.radioCustomWeights.isChecked = true
        }
        workingWeights = GamePreferences.decadeWeights(context)
        renderDecadeWeightControls()
        updateRandomizationVisibility()
        updateHistoryStatus()

        val raw = binding.editCustomList.text.toString()
        if (raw.isNotBlank()) updateValidation(raw)
    }

    private fun setupListeners() {
        binding.radioGroupSource.setOnCheckedChangeListener { _, _ -> updateCustomListVisibility() }
        binding.radioGroupRandomization.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.radioPureRandom) {
                workingWeights = GamePreferences.defaultWeights(RandomizationMode.PURE_RANDOM)
                updateDecadeWeightControls()
            } else if (checkedId == R.id.radioPrioritizeModern) {
                workingWeights = GamePreferences.defaultWeights(RandomizationMode.PRIORITIZE_MODERN)
                updateDecadeWeightControls()
            }
            updateRandomizationVisibility()
        }

        binding.editCustomList.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = updateValidation(s?.toString().orEmpty())
        })

        binding.buttonClearHistory.setOnClickListener { confirmClearHistory() }
        binding.buttonSave.setOnClickListener { saveSettings() }
        binding.buttonCancel.setOnClickListener { goBack() }
    }

    private fun updateCustomListVisibility() {
        binding.layoutCustomList.visibility = if (binding.radioCustomList.isChecked) View.VISIBLE else View.GONE
    }

    private fun updateRandomizationVisibility() {
        binding.layoutDecadeWeights.visibility = if (binding.radioCustomWeights.isChecked) View.VISIBLE else View.GONE
    }

    private fun updateValidation(raw: String) {
        if (raw.isBlank()) {
            binding.textValidationResult.visibility = View.GONE
            return
        }
        val ids = VideoProvider.parseVideoIds(raw)
        binding.textValidationResult.text = if (ids.isEmpty()) {
            getString(R.string.settings_no_ids_found)
        } else {
            getString(R.string.settings_ids_found, ids.size)
        }
        binding.textValidationResult.visibility = View.VISIBLE
    }

    private fun renderDecadeWeightControls() {
        val container = binding.layoutDecadeWeights
        container.removeAllViews()
        weightLabels.clear()
        weightSliders.clear()
        for (decade in GamePreferences.decadeStarts()) {
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                minimumHeight = 48.dp
            }
            val decadeLabel = TextView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(72.dp, LinearLayout.LayoutParams.WRAP_CONTENT)
                setTextColor(requireContext().getColor(R.color.body_text))
                text = getString(R.string.settings_decade_label, decade)
            }
            val slider = SeekBar(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                max = 100
                progress = workingWeights[decade] ?: 0
                contentDescription = getString(R.string.settings_decade_label, decade)
            }
            val valueLabel = TextView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(52.dp, LinearLayout.LayoutParams.WRAP_CONTENT)
                gravity = android.view.Gravity.END
                setTextColor(requireContext().getColor(R.color.amber_accent))
            }
            slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser || updatingWeightControls) return
                    workingWeights = GamePreferences.redistributeWeights(workingWeights, decade, progress)
                    binding.radioCustomWeights.isChecked = true
                    updateDecadeWeightControls()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
            })
            row.addView(decadeLabel)
            row.addView(slider)
            row.addView(valueLabel)
            container.addView(row)
            weightLabels[decade] = valueLabel
            weightSliders[decade] = slider
        }
        updateDecadeWeightControls()
    }

    private fun updateDecadeWeightControls() {
        if (!isAdded) return
        updatingWeightControls = true
        GamePreferences.decadeStarts().forEach { decade ->
            val value = workingWeights[decade] ?: 0
            weightSliders[decade]?.progress = value
            weightLabels[decade]?.text = getString(R.string.settings_percent, value)
        }
        updatingWeightControls = false
    }

    private fun saveSettings() {
        val context = requireContext()
        val useCustom = binding.radioCustomList.isChecked
        if (useCustom) {
            val raw = binding.editCustomList.text.toString().trim()
            if (raw.isBlank()) {
                Toast.makeText(context, R.string.settings_error_empty_list, Toast.LENGTH_SHORT).show()
                return
            }
            val ids = VideoProvider.parseVideoIds(raw)
            if (ids.isEmpty()) {
                Toast.makeText(context, R.string.settings_error_no_ids, Toast.LENGTH_SHORT).show()
                return
            }
            VideoProvider.setCustomListRaw(context, raw)
            VideoProvider.setSource(context, VideoProvider.Source.CUSTOM)
            VideoProvider.loadFromCustomText(raw)
        } else {
            VideoProvider.setSource(context, VideoProvider.Source.DEFAULT)
            VideoProvider.loadFromAssets(context)
        }

        val gameMode = if (binding.radioArcade.isChecked) GameMode.ARCADE else GameMode.CLASSIC
        GamePreferences.setGameMode(context, gameMode)

        val randomization = when {
            binding.radioPureRandom.isChecked -> RandomizationMode.PURE_RANDOM
            binding.radioCustomWeights.isChecked -> RandomizationMode.CUSTOM
            else -> RandomizationMode.PRIORITIZE_MODERN
        }
        if (randomization == RandomizationMode.CUSTOM && workingWeights.values.sum() <= 0) {
            Toast.makeText(context, R.string.settings_error_no_decades, Toast.LENGTH_SHORT).show()
            return
        }
        GamePreferences.setRandomizationMode(context, randomization)
        GamePreferences.saveDecadeWeights(context, workingWeights)

        val selectedLanguage = if (binding.radioLanguageEnglish.isChecked) "en" else "nb"
        if (LocaleHelper.getLanguage(context) != selectedLanguage) {
            LocaleHelper.setLanguage(requireActivity() as AppCompatActivity, selectedLanguage)
            return
        }

        val message = if (useCustom) {
            getString(R.string.settings_saved_custom, VideoProvider.parseVideoIds(binding.editCustomList.text.toString()).size)
        } else {
            getString(R.string.settings_saved_default)
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        goBack()
    }

    private fun confirmClearHistory() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_clear_history)
            .setMessage(R.string.settings_clear_history_message)
            .setPositiveButton(R.string.reset) { _, _ ->
                PlayHistory.clear(requireContext())
                updateHistoryStatus()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun updateHistoryStatus() {
        binding.textHistoryStatus.text = getString(
            R.string.settings_history_status,
            PlayHistory.historyCount(requireContext()),
            PlayHistory.duplicateCount(requireContext()),
        )
    }

    private fun goBack() {
        parentFragmentManager.popBackStack()
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()
}
