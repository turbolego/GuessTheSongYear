package com.turbolego.songguesser

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.turbolego.songguesser.databinding.FragmentSettingsBinding

/**
 * Settings screen where users choose between:
 * 1. "Use default list" — the curated asset file, refreshed via GitHub Actions
 * 2. "Use your own list" — paste YouTube URLs or video IDs
 *
 * Video IDs are extracted using regex that handles:
 *   - youtube.com/watch?v=VIDEO_ID
 *   - youtu.be/VIDEO_ID
 *   - youtube.com/embed/VIDEO_ID
 *   - youtube.com/shorts/VIDEO_ID
 *   - Bare 11-char video IDs
 *   - Comma, newline, or space separated
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Load current settings
        loadCurrentSettings()
        setupListeners()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    // ── State ──────────────────────────────────────────────────────────────

    private fun loadCurrentSettings() {
        val context = requireContext()
        val source = VideoProvider.getSource(context)
        binding.radioDefaultList.isChecked = source == VideoProvider.Source.DEFAULT
        binding.radioCustomList.isChecked = source == VideoProvider.Source.CUSTOM
        binding.editCustomList.setText(VideoProvider.getCustomListRaw(context))
        updateCustomListVisibility()

        // Validate current custom list on load
        val raw = binding.editCustomList.text.toString()
        if (raw.isNotBlank()) {
            updateValidation(raw)
        }
    }

    private fun setupListeners() {
        // Radio group toggle
        binding.radioGroupSource.setOnCheckedChangeListener { _, checkedId ->
            val isCustom = checkedId == R.id.radioCustomList
            binding.layoutCustomList.visibility = if (isCustom) View.VISIBLE else View.GONE
        }

        // Live validation as user types
        binding.editCustomList.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateValidation(s?.toString() ?: "")
            }
        })

        // Save button
        binding.buttonSave.setOnClickListener { saveSettings() }

        // Cancel button
        binding.buttonCancel.setOnClickListener { goBack() }
    }

    private fun updateCustomListVisibility() {
        val isCustom = binding.radioCustomList.isChecked
        binding.layoutCustomList.visibility = if (isCustom) View.VISIBLE else View.GONE
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
            // Save custom list
            VideoProvider.setCustomListRaw(context, raw)
            VideoProvider.setSource(context, VideoProvider.Source.CUSTOM)
            VideoProvider.loadFromCustomText(raw)

            Toast.makeText(context,
                getString(R.string.settings_saved_custom, ids.size), Toast.LENGTH_SHORT).show()
        } else {
            // Use default list
            VideoProvider.setSource(context, VideoProvider.Source.DEFAULT)
            VideoProvider.loadFromAssets(context)

            Toast.makeText(context, R.string.settings_saved_default, Toast.LENGTH_SHORT).show()
        }

        goBack()
    }

    private fun goBack() {
        parentFragmentManager.popBackStack()
    }
}
