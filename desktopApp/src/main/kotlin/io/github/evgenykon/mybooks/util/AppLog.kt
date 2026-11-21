package io.github.evgenykon.mybooks.util

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * File log of the Windows client.
 *
 * Everything that goes wrong is written to `%APPDATA%\MyBooks\logs\app.log` with a stack
 * trace, so a bug report never depends on retyping an error dialog. The file is rotated once
 * it grows past [MAX_BYTES]; only the newest part is kept.
 */
object AppLog {

    private const val MAX_BYTES = 2L * 1024 * 1024
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    private val lock = Any()
    private var file: File? = null

    /** Directory with the log; also what the UI shows to the user. */
    var directory: File? = null
        private set

    val logFile: File? get() = file

    fun init(baseDir: File) {
        val dir = File(baseDir, "logs").apply { mkdirs() }
        directory = dir
        file = File(dir, "app.log")
        rotateIfNeeded()
        info("=== My Books ${version()} started ===")
    }

    fun info(message: String) = write("INFO", message, null)

    fun warn(message: String, error: Throwable? = null) = write("WARN", message, error)

    fun error(message: String, error: Throwable? = null) = write("ERROR", message, error)

    /** Installs a handler so crashes of any thread end up in the log instead of nowhere. */
    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            error("Uncaught exception in ${thread.name}", error)
            previous?.uncaughtException(thread, error)
        }
    }

    private fun write(level: String, message: String, error: Throwable?) {
        val target = file ?: return
        val line = buildString {
            append(LocalDateTime.now().format(stamp))
            append(' ')
            append(level)
            append(' ')
            append(message)
            if (error != null) {
                append('\n')
                append(error.stackTraceToString())
            }
            append('\n')
        }
        synchronized(lock) {
            runCatching {
                target.appendText(line)
                if (target.length() > MAX_BYTES) rotate()
            }
        }
    }

    private fun rotateIfNeeded() {
        val target = file ?: return
        if (target.exists() && target.length() > MAX_BYTES) rotate()
    }

    private fun rotate() {
        val target = file ?: return
        runCatching {
            val backup = File(target.parentFile, "app.previous.log")
            backup.delete()
            target.copyTo(backup, overwrite = true)
            target.writeText("")
        }
    }

    private fun version(): String =
        runCatching { System.getProperty("jpackage.app-version") }.getOrNull() ?: "dev"
}

/** Stack trace of a throwable, also used when the UI shows an error to the user. */
fun Throwable.describe(): String =
    StringWriter().also { printStackTrace(PrintWriter(it)) }.toString()
