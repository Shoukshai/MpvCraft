package moe.rakka.mpvcraft.hud

import java.io.File
import java.io.IOException
import java.util.Locale

object NativeFilePicker {

    fun chooseMediaFile(initialDirectory: File?): File? {
        val os = osName()
        return when {
            os.startsWith("windows") -> chooseWindowsFile(
                initialDirectory,
                "Open media",
                "Media files|*.mkv;*.mp4;*.webm;*.avi;*.mov;*.m4v;*.ts;*.m2ts;*.flv;*.wmv;*.mp3;*.flac;*.ogg;*.wav;*.m4a;*.aac;*.opus|All files|*.*",
            )
            os.contains("mac") || os.contains("darwin") -> chooseMacFile("Open media")
            else -> chooseLinuxFile(
                initialDirectory,
                "Open media",
                "Media files (*.mkv *.mp4 *.webm *.avi *.mov *.m4v *.ts *.m2ts *.flv *.wmv *.mp3 *.flac *.ogg *.wav *.m4a *.aac *.opus);;All files (*)",
            )
        }
    }

    fun chooseMediaFolder(initialDirectory: File?): File? {
        val os = osName()
        return when {
            os.startsWith("windows") -> chooseWindowsFolder(initialDirectory)
            os.contains("mac") || os.contains("darwin") -> chooseMacFolder()
            else -> chooseLinuxFolder(initialDirectory)
        }
    }

    fun chooseSubtitle(initialDirectory: File?): File? {
        val os = osName()
        return when {
            os.startsWith("windows") -> chooseWindowsFile(
                initialDirectory,
                "Add subtitle",
                "Subtitle files|*.srt;*.ass;*.ssa;*.vtt;*.sub;*.sup|All files|*.*",
            )
            os.contains("mac") || os.contains("darwin") -> chooseMacFile("Add subtitle")
            else -> chooseLinuxFile(
                initialDirectory,
                "Add subtitle",
                "Subtitle files (*.srt *.ass *.ssa *.vtt *.sub *.sup);;All files (*)",
            )
        }
    }

    fun chooseMedia(initialDirectory: File?): File? = chooseMediaFile(initialDirectory)

    private fun osName(): String = System.getProperty("os.name", "").lowercase(Locale.ROOT)

    private fun chooseWindowsFile(initialDirectory: File?, title: String, filter: String): File? {
        val script = """
            [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new(${ '$' }false)
            Add-Type -AssemblyName System.Windows.Forms
            ${ '$' }dialog = New-Object System.Windows.Forms.OpenFileDialog
            ${ '$' }dialog.Title = ${psQuote(title)}
            ${ '$' }dialog.Multiselect = ${ '$' }false
            ${ '$' }dialog.RestoreDirectory = ${ '$' }true
            ${ '$' }dialog.CheckFileExists = ${ '$' }true
            ${ '$' }dialog.Filter = ${psQuote(filter)}
            ${ '$' }start = ${ '$' }env:MPVCRAFT_PICKER_DIR
            if (${ '$' }start -and [System.IO.Directory]::Exists(${ '$' }start)) {
                ${ '$' }dialog.InitialDirectory = ${ '$' }start
            }
            if (${ '$' }dialog.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) {
                [Console]::Write(${ '$' }dialog.FileName)
            }
            ${ '$' }dialog.Dispose()
        """.trimIndent()
        return runWindows(script, initialDirectory)
    }

    private fun chooseWindowsFolder(initialDirectory: File?): File? {
        val script = """
            [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new(${ '$' }false)
            Add-Type -AssemblyName System.Windows.Forms
            ${ '$' }dialog = New-Object System.Windows.Forms.FolderBrowserDialog
            ${ '$' }dialog.Description = 'Open media folder'
            ${ '$' }dialog.ShowNewFolderButton = ${ '$' }false
            ${ '$' }start = ${ '$' }env:MPVCRAFT_PICKER_DIR
            if (${ '$' }start -and [System.IO.Directory]::Exists(${ '$' }start)) {
                ${ '$' }dialog.SelectedPath = ${ '$' }start
            }
            if (${ '$' }dialog.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) {
                [Console]::Write(${ '$' }dialog.SelectedPath)
            }
            ${ '$' }dialog.Dispose()
        """.trimIndent()
        return runWindows(script, initialDirectory)
    }

    private fun runWindows(script: String, initialDirectory: File?): File? {
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

    private fun chooseMacFile(title: String): File? {
        val pb = ProcessBuilder(
            "osascript",
            "-e",
            "POSIX path of (choose file with prompt \"${title.replace("\"", "\\\"")}\")",
        )
        return runMacPicker(pb)
    }

    private fun chooseMacFolder(): File? {
        val pb = ProcessBuilder(
            "osascript",
            "-e",
            "POSIX path of (choose folder with prompt \"Open media folder\")",
        )
        return runMacPicker(pb)
    }

    private fun runMacPicker(pb: ProcessBuilder): File? {
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
        throw IOException(stderr.ifBlank { "macOS picker exited with code $exit" })
    }

    private fun chooseLinuxFile(initialDirectory: File?, title: String, kdialogFilter: String): File? {
        val start = initialDirectory?.takeIf { it.isDirectory }?.absolutePath
        val candidates = buildList {
            add(
                ProcessBuilder(
                    "zenity",
                    "--file-selection",
                    "--title=$title",
                    *(if (start != null) arrayOf("--filename=${start}${File.separator}") else emptyArray()),
                )
            )
            add(
                ProcessBuilder(
                    "kdialog",
                    "--getopenfilename",
                    start ?: System.getProperty("user.home", "."),
                    kdialogFilter,
                )
            )
        }
        return runLinuxCandidates(candidates)
    }

    private fun chooseLinuxFolder(initialDirectory: File?): File? {
        val start = initialDirectory?.takeIf { it.isDirectory }?.absolutePath
            ?: System.getProperty("user.home", ".")
        val candidates = listOf(
            ProcessBuilder(
                "zenity",
                "--file-selection",
                "--directory",
                "--title=Open media folder",
                "--filename=${start}${File.separator}",
            ),
            ProcessBuilder("kdialog", "--getexistingdirectory", start),
        )
        return runLinuxCandidates(candidates)
    }

    private fun runLinuxCandidates(candidates: List<ProcessBuilder>): File? {
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

    private fun psQuote(value: String): String = "'${value.replace("'", "''")}'"
}
