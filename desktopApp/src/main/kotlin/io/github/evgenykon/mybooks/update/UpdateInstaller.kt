package io.github.evgenykon.mybooks.update

import io.github.evgenykon.mybooks.util.AppLog
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files

/**
 * Downloads the new MSI and hands the swap to a tiny script: the installer cannot replace files
 * of a running app, so the script waits for this process to exit, installs quietly and starts
 * the app again.
 */
object UpdateInstaller {

    fun download(update: AppUpdate, onProgress: (Float) -> Unit): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "mybooks-update").apply { mkdirs() }
        val target = File(dir, update.msiName)
        target.delete()

        val connection = URL(update.msiUrl).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        connection.setRequestProperty("User-Agent", "my-books-desktop")
        try {
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: update.size
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        written += read
                        if (total > 0) onProgress((written.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        check(target.length() > 0) { "пустой файл обновления" }
        return target
    }

    /**
     * Writes `update.cmd` next to the MSI and starts it detached, then the caller is expected to
     * exit. The script waits for the given PID, installs quietly and relaunches the app.
     */
    fun launchInstaller(msi: File, pid: Long): Boolean = runCatching {
        val script = File(msi.parentFile, "update.cmd")
        script.writeText(
            """
            @echo off
            :wait
            tasklist /FI "PID eq $pid" 2>nul | find "$pid" >nul
            if not errorlevel 1 (
              timeout /t 1 /nobreak >nul
              goto wait
            )
            msiexec /i "$msi" /qb /norestart
            start "" "%LOCALAPPDATA%\MyBooks\MyBooks.exe"
            del "%~f0"
            """.trimIndent(),
            Charsets.US_ASCII,
        )
        ProcessBuilder("cmd.exe", "/c", "start", "/min", "", script.absolutePath).start()
        AppLog.info("update: installer script started for ${msi.name}")
        true
    }.onFailure { AppLog.error("update: cannot start the installer", it) }.getOrDefault(false)
}
