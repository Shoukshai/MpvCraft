package moe.rakka.mpvcraft.hud

import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Native-ish file picker without AWT/Swing.
 *
 * Minecraft starts Java in headless mode on several launchers, so AWT
 * FileDialog/JFileChooser throws HeadlessException even though a desktop is
 * clearly present.  Use the OS dialog helpers instead.  The call is blocking
 * and must therefore be made from a worker thread.
 */
object NativeFilePicker {

    fun chooseMedia(initialDirectory: File?): File? {
        val os = System.getProperty("os.name", "").lowercase(Locale.ROOT)
        return when {
            os.contains("win") -> chooseWindows(initialDirectory)
            os.contains("mac") || os.contains("darwin") -> chooseMac()
            else -> chooseLinux(initialDirectory)
        }
    }

    private fun chooseWindows(initialDirectory: File?): File? {
        val script = """
            [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new(${ '$' }false)
            Add-Type -AssemblyName System.Windows.Forms
            ${ '$' }dialog = New-Object System.Windows.Forms.OpenFileDialog
            ${ '$' }dialog.Title = 'Open media'
            ${ '$' }dialog.Multiselect = ${ '$' }false
            ${ '$' }dialog.RestoreDirectory = ${ '$' }true
            ${ '$' }dialog.CheckFileExists = ${ '$' }true
            ${ '$' }dialog.Filter = 'Media files|*.mkv;*.mp4;*.webm;*.avi;*.mov;*.m4v;*.ts;*.m2ts;*.flv;*.wmv;*.mp3;*.flac;*.ogg;*.wav;*.m4a|All files|*.*'
            ${ '$' }start = ${ '$' }env:MPVCRAFT_PICKER_DIR
            if (${ '$' }start -and [System.IO.Directory]::Exists(${ '$' }start)) {
                ${ '$' }dialog.InitialDirectory = ${ '$' }start
            }
            if (${ '$' }dialog.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) {
                [Console]::Write(${ '$' }dialog.FileName)
            }
            ${ '$' }dialog.Dispose()
        """.trimIndent()

        var lastError: IOException? = null
        for (exe in listOf("powershell.exe", "pwsh.exe")) {
            try {
                val pb = ProcessBuilder(
                    exe,
                    "-NoLogo",
                    "-NoProfile",
                    "-NonInteractive",
                    "-STA",
                    "-WindowStyle",
                    "Hidden",
                    "-Command",
                    script,
                )
                initialDirectory?.takeIf { it.isDirectory }?.let {
                    pb.environment()["MPVCRAFT_PICKER_DIR"] = it.absolutePath
                }
                return runPickerProcess(pb, cancelExitCodes = setOf(0))
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw IOException("PowerShell is unavailable", lastError)
    }

    private fun chooseMac(): File? {
        val pb = ProcessBuilder(
            "osascript",
            "-e",
            "POSIX path of (choose file with prompt \"Open media\")",
        )
        val process = try {
            pb.start()
        } catch (e: IOException) {
            throw IOException("osascript is unavailable", e)
        }
        val stdout = process.inputStream.bufferedReader(Charsets.UTF_8).readText().trim()
        val stderr = process.errorStream.bufferedReader(Charsets.UTF_8).readText().trim()
        val exit = process.waitFor()
        if (exit == 0) return stdout.takeIf { it.isNotBlank() }?.let(::File)
        if (stderr.contains("User canceled", ignoreCase = true) || stderr.contains("-128")) return null
        throw IOException(stderr.ifBlank { "macOS file picker exited with code $exit" })
    }

    private fun chooseLinux(initialDirectory: File?): File? {
        val start = initialDirectory?.takeIf { it.isDirectory }?.absolutePath
        val candidates = buildList {
            add(
                ProcessBuilder(
                    "zenity",
                    "--file-selection",
                    "--title=Open media",
                    *(if (start != null) arrayOf("--filename=${start}${File.separator}") else emptyArray()),
                )
            )
            add(
                ProcessBuilder(
                    "kdialog",
                    "--getopenfilename",
                    start ?: System.getProperty("user.home", "."),
                    "Media files (*.mkv *.mp4 *.webm *.avi *.mov *.m4v *.ts *.m2ts *.flv *.wmv *.mp3 *.flac *.ogg *.wav *.m4a);;All files (*)",
                )
            )
        }

        var lastError: IOException? = null
        for (pb in candidates) {
            try {
                return runPickerProcess(pb, cancelExitCodes = setOf(1))
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw IOException("No file picker found (install zenity or kdialog)", lastError)
    }

    private fun runPickerProcess(pb: ProcessBuilder, cancelExitCodes: Set<Int>): File? {
        val process = pb.start()
        val stdout = process.inputStream.bufferedReader(Charsets.UTF_8).readText().trim()
        val stderr = process.errorStream.bufferedReader(Charsets.UTF_8).readText().trim()
        val exit = process.waitFor()

        if (exit == 0) return stdout.takeIf { it.isNotBlank() }?.let(::File)
        if (exit in cancelExitCodes && stdout.isBlank()) return null
        throw IOException(stderr.ifBlank { "File picker exited with code $exit" })
    }
}
