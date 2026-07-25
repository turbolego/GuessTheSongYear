package com.turbolego.songguesser

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.turbolego.songguesser.databinding.FragmentDebugBinding

private const val TAG = "DebugFragment"

/**
 * Minimal diagnostic fragment.
 * Previously used for NewPipe extraction testing — now shows VideoProvider stats.
 */
class DebugFragment : Fragment() {

    private var _binding: FragmentDebugBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDebugBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        DebugLogger.clear()

        val context = requireContext()
        VideoProvider.load(context)

        appendLog("═ GuessTheSongYear Debug ═")
        appendLog("")
        appendLog("📊 VideoProvider:")
        appendLog("   • Total videos: ${VideoProvider.size()}")
        appendLog("   • Available years: ${VideoProvider.getAvailableYears().size}")
        appendLog("   • Source: ${VideoProvider.getSource(context).name}")

        // Test random picks
        appendLog("")
        appendLog("🎲 Random picks:")
        for (i in 1..5) {
            val entry = VideoProvider.getRandomVideoEntryWeighted()
            if (entry != null) {
                appendLog("   $i: ${entry.id} (${entry.year})")
            }
        }

        appendLog("")
        appendLog("✓ Diagnostics complete")

        binding.buttonTestPlay.setOnClickListener {
            DebugLogger.clear()
            appendLog("(diagnostics only — NewPipe removed)")
        }
        binding.buttonClearLog.setOnClickListener {
            DebugLogger.clear()
            binding.textLog.text = "Logger cleared"
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun appendLog(msg: String) {
        DebugLogger.i(TAG, msg)
        binding.textLog.text = DebugLogger.render()
        binding.scrollLog.post { binding.scrollLog.fullScroll(View.FOCUS_DOWN) }
    }
}
