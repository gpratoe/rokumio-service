package com.rokumio.host

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Finds the files the server needs at runtime.
 *
 * The APK bundles some binaries (the Node runtime, ffmpeg, ffprobe) but we do
 * NOT bundle server.js itself — it's proprietary, so the user provides it by
 * picking a file. This class answers "where is everything?" so the spawn logic
 * can build the command.
 */
class ServerLocator(private val context: Context) {

    private val filesDir: File = context.filesDir

    /** Directory the bundled binaries were extracted to on first run. */
    private val binDir: File = File(filesDir, "bin")

    private val bundledBinaries = listOf("stremio-runtime", "ffmpeg", "ffprobe")

    /**
     * Copy the bundled binaries out of the APK's assets into our private folder.
     * Assets cannot be executed in place, so they must land in the filesystem.
     */
    fun extractBundledBinaries() {
        binDir.mkdirs()
        var success = true
        for (name in bundledBinaries) {
            if (File(binDir, name).isFile) continue
            success = copyAsset(name, File(binDir, name)) && success
        }
        if (!success) throw IllegalStateException("Failed to extract bundled binaries")
    }

    private fun copyAsset(name: String, target: File): Boolean {
        return try {
            context.assets.open(name).use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun nodeBinary(): File = File(binDir, "stremio-runtime")

    fun ffmpegBinary(): File = File(binDir, "ffmpeg")

    fun ffprobeBinary(): File = File(binDir, "ffprobe")

    fun serverJs(): File = File(filesDir, "server.js")

    fun hasServerJs(): Boolean = serverJs().isFile

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
