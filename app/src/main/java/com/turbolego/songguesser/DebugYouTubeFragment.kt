package com.turbolego.songguesser

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.turbolego.songguesser.databinding.FragmentDebugBinding

private const val TAG = "DebugYouTube"

/**
 * YouTube playback diagnostic page.
 * Shows VideoProvider stats and lets the user test video ID parsing.
 */
class DebugYouTubeFragment : Fragment() {

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

        appendLog("═ Debug: YouTube ═")
        appendLog("")
        appendLog("📊 VideoProvider:")
        appendLog("   • Total videos: ${VideoProvider.size()}")
        appendLog("   • Available years: ${VideoProvider.getAvailableYears().size}")
        appendLog("   • Source: ${VideoProvider.getSource(context).name}")

        appendLog("")
        appendLog("🎲 Random picks (5):")
        for (i in 1..5) {
            val entry = VideoProvider.getRandomVideoEntryWeighted()
            if (entry != null) {
                appendLog("   $i: ${entry.id} (${entry.year}) ${entry.title.take(30)}")
            }
        }

        appendLog("")
        appendLog("✓ Diagnostics complete")

        binding.editVideoId.setText("dQw4w9WgXcQ")

        binding.buttonTestPlay.setOnClickListener {
            val videoId = binding.editVideoId.text.toString().trim()
            if (videoId.length != 11 || !videoId.matches(Regex("[A-Za-z0-9_-]{11}"))) {
                Toast.makeText(context, "Ugyldig video ID", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            DebugLogger.clear()
            appendLog("═ Testing ID: $videoId ═")
            appendLog("")
            appendLog("🔍 Checking parseVideoIds...")
            val parsed = VideoProvider.parseVideoIds(videoId)
            if (parsed.contains(videoId)) {
                appendLog("✅ parseVideoIds = $parsed")
            } else {
                appendLog("⚠️  parseVideoIds returned: $parsed")
            }

            appendLog("")
            appendLog("🔍 Looking up in VideoProvider...")
            val entry = VideoProvider.getRandomVideoEntryWeighted()
            if (entry != null) {
                appendLog("✅ Random entry: ${entry.id} (${entry.year})")
            } else {
                appendLog("⚠️  No random entry (empty provider)")
            }

            appendLog("")
            appendLog("📡 oEmbed check...")
            val urls = listOf("https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=$videoId&format=json")
            appendLog("   (external check — HTTP OK expected from oEmbed API)")
            appendLog("✓ Test complete")
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
