package com.mosu.lan

import android.os.Environment.DIRECTORY_DOWNLOADS
import android.util.Log
import com.mosu.ui.components.DownloadPhase
import com.mosu.ui.components.DownloadUiState
import com.osudroid.multiplayer.Multiplayer
import com.osudroid.multiplayer.api.data.RoomBeatmap
import com.osudroid.ui.v1.DownloadFragment
import com.osudroid.utils.mainThread
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import net.lingala.zip4j.ZipFile
import org.json.JSONObject
import ru.nsu.ccfit.zuev.osu.Config
import ru.nsu.ccfit.zuev.osu.GlobalManager
import ru.nsu.ccfit.zuev.osu.ToastLogger
import ru.nsu.ccfit.zuev.osu.helper.FileUtils

/**
 * Downloads the room's beatmap from the device hosting a LAN room (see [LanFileServer]) and imports it.
 */
object LanBeatmapTransfer {

    @Volatile
    private var isTransferring = false

    fun download(beatmap: RoomBeatmap) {
        if (isTransferring) return

        val port = LanSession.filePort

        if (port <= 0) {
            ToastLogger.showText("The room's device doesn't share beatmaps", true)
            return
        }

        isTransferring = true

        val title = listOfNotNull(beatmap.artist, beatmap.title).joinToString(" - ").ifEmpty { "Beatmap" }
        val fragment = DownloadFragment()

        val socket = AtomicReference<Socket?>(null)
        val isCancelled = AtomicBoolean(false)

        fragment.state = DownloadUiState(title = title, message = "Requesting from the host…", phase = DownloadPhase.Connecting)
        fragment.onCancel = {
            isCancelled.set(true)
            runCatching { socket.get()?.close() }
        }

        fragment.setOnLoad {
            thread(name = "LanBeatmapTransfer", isDaemon = true) {
                val context = GlobalManager.getInstance().mainActivity
                val directory = context.getExternalFilesDir(DIRECTORY_DOWNLOADS) ?: context.cacheDir
                var archive: File? = null

                try {
                    val connection = Socket().also { socket.set(it) }
                    connection.connect(InetSocketAddress(LanSession.address, port), 5000)
                    connection.soTimeout = LanProtocol.TIMEOUT_MS

                    connection.use {
                        connection.getOutputStream().apply {
                            write((JSONObject().put("md5", beatmap.md5).toString() + "\n").toByteArray(Charsets.UTF_8))
                            flush()
                        }

                        val input = BufferedInputStream(connection.getInputStream())
                        val header = JSONObject(LanProtocol.readFrame(input) ?: throw IOException("No answer from the host"))

                        if (!header.optBoolean("ok")) {
                            throw IOException(header.optString("error", "The host couldn't send the beatmap"))
                        }

                        val name = header.optString("name").replace("[\"*/:<>?\\\\|]".toRegex(), "_").ifEmpty { beatmap.md5 }
                        val total = header.optLong("size")
                        val file = File(directory, "$name.osz").also { archive = it }

                        receive(input, file, total, title, fragment) { isCancelled.get() }
                    }

                    mainThread {
                        fragment.state = fragment.state.copy(message = "Importing…", phase = DownloadPhase.Importing, speed = "", cancellable = false)
                    }

                    import(archive!!)
                } catch (e: Exception) {
                    archive?.delete()

                    if (!isCancelled.get()) {
                        Log.e("LanBeatmapTransfer", "Beatmap transfer failed", e)
                        ToastLogger.showText("Couldn't get the beatmap from the host: ${e.message}", true)
                    }
                } finally {
                    isTransferring = false
                    mainThread(fragment::dismiss)
                }
            }
        }

        mainThread(fragment::show)
    }

    private fun receive(input: BufferedInputStream, file: File, total: Long, title: String, fragment: DownloadFragment, isCancelled: () -> Boolean) {
        val started = System.nanoTime()
        var received = 0L
        var lastUpdate = 0L
        val buffer = ByteArray(64 * 1024)

        file.outputStream().use { output ->
            while (received < total) {
                if (isCancelled()) throw IOException("Cancelled")

                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), total - received).toInt())
                if (count < 0) throw IOException("The host closed the connection")

                output.write(buffer, 0, count)
                received += count

                val now = System.nanoTime()

                if (now - lastUpdate > 100_000_000L || received == total) {
                    lastUpdate = now

                    val speed = received / ((now - started) / 1e9).coerceAtLeast(0.001)
                    val secondsLeft = if (speed > 0) ((total - received) / speed).toInt() else 0
                    val megabytes = { bytes: Double -> "%.1f MB".format(bytes / 1024 / 1024) }

                    mainThread {
                        fragment.state = DownloadUiState(
                            title = title,
                            message = "${megabytes(received.toDouble())} / ${megabytes(total.toDouble())}",
                            phase = DownloadPhase.Downloading,
                            progress = if (total > 0) received.toFloat() / total else 0f,
                            speed = "${megabytes(speed)}/s • ~$secondsLeft s left",
                        )
                    }
                }
            }
        }
    }

    private fun import(archive: File) {
        ZipFile(archive).use {
            if (!it.isValidZipFile) throw IOException("The received beatmap is damaged")
        }

        if (!FileUtils.extractBeatmapset(archive.path, Config.getBeatmapPath())) {
            throw IOException("Couldn't extract the beatmap")
        }

        archive.delete()
        GlobalManager.getInstance().mainActivity.loadBeatmapLibrary()

        Multiplayer.room?.let { room -> Multiplayer.roomScene?.onRoomBeatmapChange(room.beatmap) }
    }
}
