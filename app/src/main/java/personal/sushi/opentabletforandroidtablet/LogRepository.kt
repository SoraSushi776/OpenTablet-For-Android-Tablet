package personal.sushi.opentabletforandroidtablet

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-app log collector — captures log messages from all layers (UI, Root scripts,
 * native) so the user can view them without needing ADB.
 *
 * Uses a ring buffer of 500 lines. Thread-safe.
 */
object LogRepository {

    private const val TAG = "LogRepository"
    private const val MAX_LINES = 500

    private val logLines = mutableListOf<String>()
    private var listener: ((String) -> Unit)? = null

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun log(tag: String, level: String, msg: String) {
        val ts = timeFormat.format(Date())
        val line = "$ts $level/$tag: $msg"
        if (logLines.size >= MAX_LINES) {
            logLines.removeAt(0)
        }
        logLines.add(line)

        // Also write to logcat so it's still captured via ADB if available.
        when (level) {
            "E" -> Log.e(tag, msg)
            "W" -> Log.w(tag, msg)
            "I" -> Log.i(tag, msg)
            "D" -> Log.d(tag, msg)
            else -> Log.d(tag, msg)
        }

        listener?.invoke(line)
    }

    fun i(tag: String, msg: String) = log(tag, "I", msg)
    fun e(tag: String, msg: String) = log(tag, "E", msg)
    fun d(tag: String, msg: String) = log(tag, "D", msg)
    fun w(tag: String, msg: String) = log(tag, "W", msg)

    @Synchronized
    fun getAllText(): String = logLines.joinToString("\n")

    @Synchronized
    fun getLines(): List<String> = logLines.toList()

    @Synchronized
    fun clear() {
        logLines.clear()
    }

    fun setListener(l: ((String) -> Unit)?) {
        listener = l
    }

    /** Append raw multi-line text (e.g. script stdout/stderr). */
    fun logRaw(tag: String, text: String) {
        text.lines().forEach { line ->
            if (line.isNotBlank()) {
                log(tag, "D", line.trim())
            }
        }
    }
}
