package com.dem.telemetry

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AppLogEntry(
    val timestampMs: Long,
    val level: String,
    val tag: String,
    val message: String,
)

object AppLogStore {
    private const val maxEntries = 200
    private val fileLock = Any()
    private val entriesState = MutableStateFlow<List<AppLogEntry>>(emptyList())
    private val autoLogPathState = MutableStateFlow<String?>(null)
    private var appContext: Context? = null
    private var activeLogFile: File? = null

    val entries: StateFlow<List<AppLogEntry>> = entriesState.asStateFlow()
    val autoLogPath: StateFlow<String?> = autoLogPathState.asStateFlow()

    fun initialize(context: Context) {
        synchronized(fileLock) {
            if (appContext != null) {
                return
            }
            appContext = context.applicationContext
            activeLogFile = createLogFile(appContext!!)
            autoLogPathState.value = activeLogFile?.absolutePath
        }
        info("AppLogStore", "Automatic log file enabled")
    }

    fun info(tag: String, message: String) {
        Log.i(tag, message)
        append("INFO", tag, message)
    }

    fun warn(tag: String, message: String) {
        Log.w(tag, message)
        append("WARN", tag, message)
    }

    fun error(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(tag, message, throwable)
            append("ERROR", tag, "$message | ${throwable.javaClass.simpleName}: ${throwable.message ?: "no message"}")
        } else {
            Log.e(tag, message)
            append("ERROR", tag, message)
        }
    }

    fun clear() {
        entriesState.value = emptyList()
    }

    private fun append(level: String, tag: String, message: String) {
        val entry = AppLogEntry(
            timestampMs = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message,
        )
        val next = entriesState.value.toMutableList()
        next.add(entry)
        if (next.size > maxEntries) {
            next.subList(0, next.size - maxEntries).clear()
        }
        entriesState.value = next
        appendToFile(entry)
    }

    private fun appendToFile(entry: AppLogEntry) {
        val context = appContext ?: return
        synchronized(fileLock) {
            val file = activeLogFile ?: createLogFile(context)?.also {
                activeLogFile = it
                autoLogPathState.value = it.absolutePath
            } ?: return

            val line = "${formatLogTime(entry.timestampMs)} ${entry.level} ${entry.tag}: ${entry.message}\n"
            runCatching {
                file.appendText(line)
            }.onFailure {
                Log.e("AppLogStore", "Failed to append auto log", it)
            }
        }
    }

    private fun createLogFile(context: Context): File? {
        return runCatching {
            val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
            val logDir = File(baseDir, "logs")
            if (!logDir.exists()) {
                logDir.mkdirs()
            }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            File(logDir, "dem-telemetry-$stamp.log")
        }.getOrNull()
    }

    private fun formatLogTime(timestampMs: Long): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return formatter.format(Date(timestampMs))
    }
}
