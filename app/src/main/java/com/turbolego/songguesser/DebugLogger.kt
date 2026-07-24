package com.turbolego.songguesser

/**
 * Simple in-memory log collector for debugging video playback issues.
 * Logs are timestamped and stored in a rotating buffer (max 200 entries).
 */
object DebugLogger {
    private val buffer = mutableListOf<LogEntry>()
    private const val MAX_ENTRIES = 200

    data class LogEntry(
        val timestamp: Long = System.currentTimeMillis(),
        val level: String,
        val tag: String,
        val message: String
    )

    fun clear() { buffer.clear() }

    fun d(tag: String, msg: String) = add("D", tag, msg)
    fun i(tag: String, msg: String) = add("I", tag, msg)
    fun w(tag: String, msg: String) = add("W", tag, msg)
    fun e(tag: String, msg: String) = add("E", tag, msg)

    private fun add(level: String, tag: String, msg: String) {
        synchronized(buffer) {
            if (buffer.size >= MAX_ENTRIES) buffer.removeAt(0)
            buffer.add(LogEntry(level = level, tag = tag, message = msg))
        }
        // Also log to Android logcat
        android.util.Log.println(
            when (level) { "E" -> android.util.Log.ERROR; "W" -> android.util.Log.WARN;
                "I" -> android.util.Log.INFO; else -> android.util.Log.DEBUG },
            "Debug|$tag", msg
        )
    }

    fun snapshot(): List<LogEntry> = synchronized(buffer) { buffer.toList() }

    fun render(): String = synchronized(buffer) {
        buffer.joinToString("\n") { entry ->
            val time = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                .format(java.util.Date(entry.timestamp))
            "[${time}] [${entry.level}] [${entry.tag}] ${entry.message}"
        }
    }
}
