package com.rokumio.host

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Locates and prepares the files the server needs at runtime.
 *
 * The APK bundles the runtime binaries (ffmpeg, ffprobe and the standalone Node
 * 26 executable) as bundled native executables — packaged via AGP's jniLibs
 * source-set mechanism and extracted to nativeLibraryDir at install — and the
 * user supplies server.js itself by picking a file (it's proprietary, so it's
 * never bundled). The Node executable — like ffmpeg/ffprobe — is a standalone
 * PIE executable that the server process spawns via child_process.spawn; this
 * class answers "where is everything?" and prepares the writable data dir +
 * preload env the server needs.
 */
class ServerLocator(private val context: Context) {

    private val filesDir: File = context.filesDir

    /** Where AGP extracted the bundled native executables at install time. They
     *  are named lib*.so only so AGP packages/extracts them (with the exec bit)
     *  here; they are spawned via exec(), which ignores the .so suffix. */
    private val nativeLibDir: File = File(context.applicationInfo.nativeLibraryDir)

    fun nodeBinary(): File = File(nativeLibDir, "libnode.so")

    fun filesDir(): File = filesDir

    fun serverJs(): File = File(filesDir, "server.js")

    fun hasServerJs(): Boolean = serverJs().isFile

    /**
     * Ensure the server's writable data dir and, crucially, a persisted
     * server-settings.json exist before the engine starts.
     *
     * Without a settings file the server falls back to cacheSize = 0 on Android
     * (see server-desktop.js the cacheSize default), which puts the torrent engine
     * into a tiny memory/circular-buffer mode that cannot keep up with the 4K
     * re-seeks of live transcoding — the "buffering forever / no video" symptom.
     * Seeding a real cacheSize makes the engine use the same disk cache as a
     * desktop/Termux run. We only create the file if it's absent so we don't
     * clobber anything the server (or a future settings UI) may write later.
     */
    fun prepareServerData() {
        val cacheDir = File(filesDir, "stremio-cache")
        cacheDir.mkdirs()

        val settings = File(filesDir, "server-settings.json")
        if (settings.isFile) return

        val data = buildString {
            append("{\n")
            append("  \"cacheSize\": 2147483648,\n")
            append("  \"cacheRoot\": \"${filesDir.absolutePath}\",\n")
            append("  \"transcodeHardwareAccel\": false\n")
            append("}\n")
        }
        settings.writeText(data)
    }

    /**
     * A tiny preload script that sets the env vars server.js reads to locate the
     * transcoders and its writable data dir. It's injected before the server runs
     * via `node -r <preload> server.js`.
     *
     * The server resolves its app/data dir from APP_PATH. On Android
     * process.platform === "android", so the desktop build skips its linux branch
     * and would otherwise fall back to os.tmpdir()/stremio-server (a non-writable
     * /tmp on Android). Pointing APP_PATH (and HOME, which some paths read
     * directly) at our private filesDir fixes that.
     *
     * ffmpeg and ffprobe are bundled native executables under nativeLibraryDir
     * (libffmpeg.so / libffprobe.so). Setting FFMPEG_BIN/FFPROBE_BIN makes the
     * server's child_process.spawn use them.
     */
    fun writePreload() {
        val dataDir = filesDir.absolutePath
        val ffmpegBin = File(nativeLibDir, "libffmpeg.so").absolutePath
        val ffprobeBin = File(nativeLibDir, "libffprobe.so").absolutePath
        val preload = File(filesDir, "preload.js")
        val content = "process.env.APP_PATH = ${dataDir.jsonQuote()};\n" +
            "process.env.HOME = ${dataDir.jsonQuote()};\n" +
            "process.env.FFMPEG_BIN = ${ffmpegBin.jsonQuote()};\n" +
            "process.env.FFPROBE_BIN = ${ffprobeBin.jsonQuote()};\n"
        preload.writeText(content)
    }

    private fun String.jsonQuote(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /**
     * Fallback path: copy the user's picked file (a content URI) into our folder.
     * Returns true on success, false on any failure.
     */
    fun importServerJs(uri: Uri, contentResolver: ContentResolver): Boolean {
        val target = serverJs()
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
                true
            } ?: false
        } catch (e: Exception) {
            false
        }
    }
}
