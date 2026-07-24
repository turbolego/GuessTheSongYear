package com.turbolego.songguesser

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.turbolego.songguesser.databinding.FragmentDebugBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "DebugFragment"

class DebugFragment : Fragment() {

    private var _binding: FragmentDebugBinding? = null
    private val binding get() = _binding!!

    private var youtubeService: StreamingService? = null
    private var testInProgress = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDebugBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        DebugLogger.clear()
        appendLog("DebugFragment klar")
        appendLog("NewPipe init...")

        try {
            NewPipe.init(DebugDownloader())
            youtubeService = NewPipe.getService("YouTube")
            appendLog("✅ NewPipe initialisert: ${youtubeService?.serviceInfo?.name}")
        } catch (e: Exception) {
            appendLog("❌ NewPipe init FEILET: ${e.javaClass.simpleName}: ${e.message}")
            logStackTrace(e)
        }

        binding.buttonTestPlay.setOnClickListener { runExtractionTest() }
        binding.buttonClearLog.setOnClickListener {
            DebugLogger.clear()
            binding.textLog.text = "Logger tømt"
        }
    }

    private fun runExtractionTest() {
        if (testInProgress) {
            appendLog("⚠️ Test allerede i gang — vent")
            return
        }
        testInProgress = true
        binding.textStatus.visibility = View.VISIBLE

        val videoId = binding.editVideoId.text.toString().trim()
        if (videoId.length != 11 || !videoId.matches(Regex("[A-Za-z0-9_-]{11}"))) {
            appendLog("❌ Ugyldig video ID: '$videoId' — må være 11 tegn")
            testInProgress = false
            return
        }

        appendLog("═".repeat(50))
        appendLog("🧪 TEST STARTER: $videoId")
        appendLog("═".repeat(50))

        binding.textStatus.text = "Starter ekstraksjon for $videoId..."
        binding.textStatus.setTextColor(resources.getColor(R.color.amber_accent, null))

        lifecycleScope.launch {
            runExtraction(videoId)
            testInProgress = false
            binding.textStatus.text = testInProgressResult
            binding.textStatus.setTextColor(resources.getColor(
                if (testInProgressResult.startsWith("✅")) R.color.green_correct
                else R.color.error_red, null
            ))
        }
    }

    private var testInProgressResult = "Ferdig"

    private suspend fun runExtraction(videoId: String) {
        withContext(Dispatchers.IO) {
            try {
                // Step 1: Initialization
                val service = youtubeService
                if (service == null) {
                    appendLog("❌ YouTube service ikke tilgjengelig — NewPipe init feilet")
                    testInProgressResult = "❌ FEILET — NewPipe ikke init"
                    return@withContext
                }
                appendLog("✅ YouTube service: ${service.serviceInfo.name}")

                // Step 2: Get StreamInfo
                val url = "https://www.youtube.com/watch?v=$videoId"
                appendLog("📡 Henter StreamInfo for: $url")
                appendLog("⏳ Dette kan ta 3-10 sekunder...")

                val startTime = System.currentTimeMillis()
                val streamInfo = StreamInfo.getInfo(service, url)
                val elapsed = System.currentTimeMillis() - startTime
                appendLog("✅ StreamInfo hentet på ${elapsed}ms")

                // Step 3: Metadata
                appendLog("📝 Tittel: ${streamInfo.name ?: "(ingen)"}")
                appendLog("📝 Artist: ${streamInfo.uploaderName ?: "(ingen)"}")
                appendLog("📝 Varighet: ${streamInfo.duration ?: "?"} sekunder")
                appendLog("📝 Kanal: ${streamInfo.uploaderUrl ?: "(ukjent)"}")

                // Step 4: Stream counts
                appendLog("")
                appendLog("📊 Strømmer funnet:")
                appendLog("   • Video (progressive): ${streamInfo.videoStreams.size}")
                appendLog("   • Video-only (DASH):  ${streamInfo.videoOnlyStreams.size}")
                appendLog("   • Audio-only:         ${streamInfo.audioStreams.size}")

                // Step 5: Progressive streams detail
                if (streamInfo.videoStreams.isNotEmpty()) {
                    appendLog("")
                    appendLog("📊 Progressive streams:")
                    for ((i, s) in streamInfo.videoStreams.withIndex()) {
                        val res = s.resolution ?: "?"
                        val fmt = s.getFormat()?.name ?: "?"
                        val hasAudio = !s.isVideoOnly
                        appendLog("   [$i] ${res} (${fmt}) audio=$hasAudio url=${s.url?.take(60)}...")
                    }
                }

                // Step 6: Pick best stream
                val bestProgressive = streamInfo.videoStreams
                    .filter { !it.isVideoOnly }
                    .maxByOrNull { parseRes(it.resolution) }

                if (bestProgressive != null) {
                    appendLog("")
                    appendLog("✅ BESTE STREAM: ${bestProgressive.resolution}")
                    appendLog("   URL: ${bestProgressive.url?.take(80)}...")
                    appendLog("   Format: ${bestProgressive.getFormat()?.name}")

                    // Step 7: Verify URL is reachable
                    appendLog("")
                    appendLog("🔍 Verifiserer stream URL...")
                    val verifyOk = verifyUrl(bestProgressive.url ?: "")
                    if (verifyOk) {
                        appendLog("✅ Stream URL er tilgjengelig!")
                        testInProgressResult = "✅ SUCCESS — Stream klar for ExoPlayer"
                    } else {
                        appendLog("⚠️ Stream URL ga ikke 200 — kan være utløpt")
                        testInProgressResult = "⚠️ DELVIS — URL funnet, men ikke verifisert"
                    }
                } else {
                    // Fallback to video-only or audio
                    appendLog("⚠️ Ingen progressive streams funnet!")
                    if (streamInfo.videoOnlyStreams.isNotEmpty()) {
                        val best = streamInfo.videoOnlyStreams.first()
                        appendLog("   Fallback til video-only: ${best.resolution}")
                        testInProgressResult = "⚠️ DELVIS — Kun video-only stream"
                    } else if (streamInfo.audioStreams.isNotEmpty()) {
                        appendLog("   Fallback til audio-only")
                        testInProgressResult = "⚠️ DELVIS — Kun audio stream"
                    } else {
                        appendLog("❌ INGEN spillebare strømmer funnet!")
                        testInProgressResult = "❌ FEILET — Ingen spillebare strømmer"
                    }
                }

            } catch (e: Exception) {
                appendLog("")
                appendLog("❌ EKSTRAKSJON FEILET:")
                appendLog("   Type: ${e.javaClass.name}")
                appendLog("   Melding: ${e.message ?: "(tom)"}")
                appendLog("")
                appendLog("📋 Stack trace:")
                logStackTrace(e)

                // Find root cause
                var cause: Throwable? = e
                while (cause?.cause != null) {
                    cause = cause.cause
                    if (cause != null) {
                        appendLog("   Caused by: ${cause.javaClass.simpleName}: ${cause.message}")
                    }
                }

                testInProgressResult = "❌ FEILET — $e"
            }
        }
    }

    private suspend fun verifyUrl(url: String): Boolean {
        if (url.isBlank()) return false
        return withContext(Dispatchers.IO) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 5000
                conn.instanceFollowRedirects = true
                val code = conn.responseCode
                conn.disconnect()
                appendLog("   HTTP $code for stream URL")
                code in 200..299
            } catch (e: Exception) {
                appendLog("   ⚠️ Kunne ikke verifisere URL: ${e.message}")
                false
            }
        }
    }

    private fun parseRes(resolution: String?): Int {
        if (resolution == null) return 0
        return resolution.filter { it.isDigit() }.toIntOrNull() ?: 0
    }

    private fun logStackTrace(e: Throwable) {
        try {
            val baos = ByteArrayOutputStream()
            val ps = PrintStream(baos)
            e.printStackTrace(ps)
            ps.flush()
            val trace = baos.toString()
            val lines = trace.lines().toList()
            // Show first 15 lines of stack trace
            for (line in lines.take(15)) {
                appendLog("   $line")
            }
            if (lines.size > 15) {
                appendLog("   ... og ${lines.size - 15} flere linjer")
            }
        } catch (_: Exception) {
            appendLog("   (stack trace ikke tilgjengelig)")
        }
    }

    private fun appendLog(msg: String) {
        DebugLogger.i(TAG, msg)
        requireActivity().runOnUiThread {
            binding.textLog.append("\n$msg")
            binding.textLog.post {
                binding.scrollLog.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    // ═══════════════════════════════════════════════════════════════
    // Debug Downloader — same as SimpleDownloader but with full logging
    // ═══════════════════════════════════════════════════════════════

    class DebugDownloader : Downloader() {
        override fun execute(request: Request): Response {
            val url = URL(request.url())
            val method = request.httpMethod()
            DebugLogger.i(TAG, "🌐 $method ${request.url().take(80)}")

            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 30000
            conn.readTimeout = 30000
            conn.requestMethod = method
            for ((key, value) in request.headers().entries) {
                conn.setRequestProperty(key, value.joinToString(", "))
            }
            conn.instanceFollowRedirects = true

            // Send request body for POST
            val dataToSend = request.dataToSend()
            if (dataToSend != null && dataToSend.isNotEmpty()) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Length", dataToSend.size.toString())
                conn.outputStream.use { it.write(dataToSend) }
                DebugLogger.i(TAG, "📦 POST body: ${dataToSend.size} bytes")
            }

            val responseCode = conn.responseCode
            val responseMessage = conn.responseMessage ?: ""
            val headers = mutableMapOf<String, MutableList<String>>()
            conn.headerFields?.forEach { (key, values) ->
                if (key != null) headers[key] = values.toMutableList()
            }

            val body = if (responseCode in 200..299) {
                try {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } catch (ex: Exception) {
                    DebugLogger.i(TAG, "⚠️ Feil ved lesing av body: ${ex.message}")
                    ""
                }
            } else {
                try {
                    conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                } catch (ex: Exception) {
                    DebugLogger.i(TAG, "⚠️ Feil ved lesing av error stream: ${ex.message}")
                    ""
                }
            }

            val latestUrl = conn.url.toString()
            DebugLogger.i(TAG, "   ← HTTP $responseCode (${responseCodeToLabel(responseCode)})")
            if (responseCode !in 200..299) {
                DebugLogger.i(TAG, "   ⚠️ Svar: $responseMessage")
                val bodyPreview = body.take(200)
                if (bodyPreview.isNotBlank()) DebugLogger.i(TAG, "   Body: $bodyPreview...")
            }
            conn.disconnect()

            return Response(responseCode, responseMessage, headers, body, latestUrl)
        }

        private fun responseCodeToLabel(code: Int): String = when (code) {
            200 -> "OK"
            301 -> "Moved Permanently"
            302 -> "Found (redirect)"
            303 -> "See Other"
            307 -> "Temporary Redirect"
            308 -> "Permanent Redirect"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            411 -> "Length Required"
            429 -> "Too Many Requests"
            500 -> "Internal Server Error"
            502 -> "Bad Gateway"
            503 -> "Service Unavailable"
            else -> ""
        }
    }
}
