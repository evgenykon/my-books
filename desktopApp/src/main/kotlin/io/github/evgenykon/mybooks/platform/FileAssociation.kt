package io.github.evgenykon.mybooks.platform

import io.github.evgenykon.mybooks.util.AppLog
import java.io.File

/**
 * Binds `.fb2` to My Books for the current user only (`HKCU\Software\Classes`), so no
 * administrator rights are required and the change is reversible from the menu.
 *
 * Windows 10+ protects the *default* handler through `UserChoice`, which no installer can
 * write: the first double click may ask which application should open the file, and picking
 * My Books there stores it. Everything after that opens the book directly.
 */
object FileAssociation {

    private const val EXT = ".fb2"
    private const val PROG_ID = "MyBooks.Fb2"
    private const val CLASSES = "HKCU\\Software\\Classes"

    val supported: Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("win")

    /** Path of the running launcher: the jpackage exe when installed, else the java binary. */
    fun executable(): File? {
        val packaged = System.getProperty("jpackage.app-path")
        if (!packaged.isNullOrBlank()) return File(packaged).absoluteFile
        val command = ProcessHandle.current().info().command().orElse(null) ?: return null
        return File(command).absoluteFile
    }

    fun isRegistered(): Boolean {
        val exe = executable() ?: return false
        val (code, out) = reg("query", "$CLASSES\\$PROG_ID\\shell\\open\\command", "/ve")
        return code == 0 && out.contains(exe.absolutePath, ignoreCase = true)
    }

    /** Idempotent: writes the association only when it points somewhere else. */
    fun ensureRegistered(): Boolean {
        if (!supported) return false
        if (isRegistered()) return true
        return register()
    }

    fun register(): Boolean {
        if (!supported) return false
        val exe = executable() ?: return false
        val reg = File.createTempFile("mybooks-fb2-", ".reg")
        return try {
            // A .reg file is used instead of "reg add": the command value contains quotes and
            // %1, which the command line mangles.
            // reg import wants UTF-16LE with a BOM.
            val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
            reg.writeBytes(bom + regFile(exe).toByteArray(Charsets.UTF_16LE))
            val (code, out) = run(listOf("reg", "import", reg.absolutePath))
            if (code != 0) AppLog.info("reg import failed (exit $code): ${out.trim()}")
            code == 0
        } finally {
            reg.delete()
        }
    }

    private fun regFile(exe: File): String {
        val path = exe.absolutePath.replace("\\", "\\\\")
        return buildString {
            append("Windows Registry Editor Version 5.00\r\n\r\n")
            append("[HKEY_CURRENT_USER\\Software\\Classes\\$EXT]\r\n")
            append("@=\"$PROG_ID\"\r\n")
            append("\"Content Type\"=\"application/fb2+xml\"\r\n\r\n")
            append("[HKEY_CURRENT_USER\\Software\\Classes\\$PROG_ID]\r\n")
            append("@=\"FB2 book\"\r\n\r\n")
            append("[HKEY_CURRENT_USER\\Software\\Classes\\$PROG_ID\\DefaultIcon]\r\n")
            append("@=\"\\\"$path\\\",0\"\r\n\r\n")
            append("[HKEY_CURRENT_USER\\Software\\Classes\\$PROG_ID\\shell\\open\\command]\r\n")
            append("@=\"\\\"$path\\\" \\\"%1\\\"\"\r\n")
        }
    }

    fun unregister(): Boolean {
        if (!supported) return false
        val a = reg("delete", "$CLASSES\\$EXT", "/f").first == 0
        val b = reg("delete", "$CLASSES\\$PROG_ID", "/f").first == 0
        // "reg delete" on a missing key fails, which is fine: nothing to remove.
        return a || b || !isRegistered()
    }

    private fun reg(action: String, vararg args: String): Pair<Int, String> =
        run(listOf("reg", action) + args)

    private fun run(command: List<String>): Pair<Int, String> = runCatching {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        process.outputStream.close() // never block on an interactive prompt
        val out = process.inputStream.bufferedReader().readText()
        process.waitFor() to out
    }.getOrElse { -1 to (it.message ?: "") }
}
