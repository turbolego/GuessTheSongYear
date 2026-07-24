package com.turbolego.songguesser

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Local test to verify NewPipe Extractor works on this JVM.
 * This mimics exactly what VideoPlayerFragment does at runtime.
 */
class NewPipeRuntimeTest {

    @Before
    fun setUp() {
        org.schabi.newpipe.extractor.NewPipe.init(
            object : org.schabi.newpipe.extractor.downloader.Downloader() {
                override fun execute(request: org.schabi.newpipe.extractor.downloader.Request)
                        : org.schabi.newpipe.extractor.downloader.Response {
                    val url = java.net.URL(request.url())
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 15000
                    conn.readTimeout = 15000
                    conn.requestMethod = request.httpMethod()
                    for ((key, values) in request.headers().entries) {
                        conn.setRequestProperty(key, values.joinToString(", "))
                    }
                    conn.instanceFollowRedirects = true
                    conn.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0")

                    // Send request body for POST
                    val requestBody = request.dataToSend()
                    if (requestBody != null && requestBody.isNotEmpty()) {
                        conn.doOutput = true
                        conn.setRequestProperty("Content-Length", requestBody.size.toString())
                        conn.outputStream.use { it.write(requestBody) }
                    }

                    val code = conn.responseCode
                    val msg = conn.responseMessage ?: ""
                    val headers = mutableMapOf<String, MutableList<String>>()
                    conn.headerFields?.forEach { (k, v) -> if (k != null) headers[k] = v.toMutableList() }
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val finalUrl = conn.url.toString()
                    conn.disconnect()
                    return org.schabi.newpipe.extractor.downloader.Response(
                        code, msg, headers, body, finalUrl)
                }
            }
        )
    }

    @Test
    fun `NewPipe can extract stream URL from dQw4w9WgXcQ (Rick Astley)`() {
        val service = org.schabi.newpipe.extractor.NewPipe.getService("YouTube")
        assertNotNull("YouTube service should be available", service)

        val info = org.schabi.newpipe.extractor.stream.StreamInfo.getInfo(
            service, "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        )

        println("Video name: ${info.name}")
        println("Uploader: ${info.uploaderName}")
        println("Progressive streams: ${info.videoStreams.size}")
        println("Video-only streams: ${info.videoOnlyStreams.size}")
        println("Audio streams: ${info.audioStreams.size}")

        val progressive = info.videoStreams.filter { !it.isVideoOnly }
        assertTrue("At least one progressive stream should exist",
            progressive.isNotEmpty())

        val url = progressive.first().url!!
        println("Stream URL: ${url.take(80)}...")
        assertTrue("URL should start with https", url.startsWith("https"))
    }
}