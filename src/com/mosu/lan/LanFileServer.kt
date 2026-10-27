package com.mosu.lan

import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread
import org.json.JSONObject

/**
 * Sends beatmap sets to players of a LAN room who don't have the room's beatmap.
 *
 * Protocol: the client sends one frame `{"md5": "<beatmap md5>"}`; the server answers with one frame
 * `{"ok": true, "name": "<set name>", "size": <bytes>}` followed by the `.osz` archive, or `{"ok": false, "error": "..."}`.
 *
 * @param findBeatmapSet Returns the directory of the beatmap set containing the beatmap with the given MD5, or null.
 * @param cacheDirectory Where archives are built before being sent.
 */
class LanFileServer(
    private val findBeatmapSet: (md5: String) -> File?,
    private val cacheDirectory: File,
    port: Int = LanProtocol.FILE_PORT,
) {

    private val serverSocket = ServerSocket().apply {
        reuseAddress = true

        try {
            bind(InetSocketAddress(port))
        } catch (_: IOException) {
            bind(InetSocketAddress(0))
        }
    }

    val port: Int get() = serverSocket.localPort

    @Volatile
    private var isRunning = false

    // Archives are built one at a time so that several players requesting the same beatmap share it.
    private val archiveLock = Any()


    fun start() {
        isRunning = true

        thread(name = "LanFileServer", isDaemon = true) {
            while (isRunning) {
                val socket = try {
                    serverSocket.accept()
                } catch (_: SocketException) {
                    break
                } catch (e: IOException) {
                    Log.w(TAG, "Failed to accept a connection", e)
                    continue
                }

                thread(name = "LanFileServer-client", isDaemon = true) { socket.use(::serve) }
            }
        }
    }

    fun stop() {
        isRunning = false
        runCatching { serverSocket.close() }
    }


    private fun serve(socket: Socket) {
        try {
            socket.soTimeout = LanProtocol.TIMEOUT_MS

            val request = LanProtocol.readFrame(BufferedInputStream(socket.getInputStream())) ?: return
            val md5 = JSONObject(request).optString("md5").lowercase()
            val output = socket.getOutputStream()

            fun answer(json: JSONObject) {
                output.write((json.toString() + "\n").toByteArray(Charsets.UTF_8))
                output.flush()
            }

            if (!MD5_REGEX.matches(md5)) {
                answer(JSONObject().put("ok", false).put("error", "Invalid request"))
                return
            }

            val directory = findBeatmapSet(md5)?.takeIf { it.isDirectory }

            if (directory == null) {
                answer(JSONObject().put("ok", false).put("error", "The room's device doesn't have this beatmap"))
                return
            }

            val archive = synchronized(archiveLock) { buildArchive(directory, md5) }

            answer(JSONObject().put("ok", true).put("name", directory.name).put("size", archive.length()))
            archive.inputStream().use { it.copyTo(output, BUFFER_SIZE) }
            output.flush()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send a beatmap", e)
        }
    }

    /**
     * Packs [directory] into an `.osz` archive, skipping videos to keep transfers short.
     */
    private fun buildArchive(directory: File, md5: String): File {
        cacheDirectory.mkdirs()

        val archive = File(cacheDirectory, "lan-$md5.osz")

        if (archive.isFile && archive.lastModified() >= directory.lastModified()) {
            return archive
        }

        val temporary = File(cacheDirectory, "lan-$md5.osz.tmp")
        val root = directory.canonicalFile

        ZipOutputStream(temporary.outputStream().buffered(BUFFER_SIZE)).use { zip ->
            root.walkTopDown().filter { it.isFile && it.extension.lowercase() !in VIDEO_EXTENSIONS }.forEach { file ->
                zip.putNextEntry(ZipEntry(file.relativeTo(root).invariantSeparatorsPath))
                file.inputStream().use { it.copyTo(zip, BUFFER_SIZE) }
                zip.closeEntry()
            }
        }

        archive.delete()

        if (!temporary.renameTo(archive)) {
            throw IOException("Couldn't prepare the beatmap archive")
        }

        return archive
    }


    private companion object {
        const val TAG = "LanFileServer"
        const val BUFFER_SIZE = 64 * 1024

        val MD5_REGEX = Regex("^[0-9a-f]{32}$")
        val VIDEO_EXTENSIONS = setOf("mp4", "avi", "flv", "webm", "mkv", "mov", "wmv", "m4v", "mpg", "mpeg")
    }
}
