package com.osudroid.beatmaplisting

import android.os.Environment.DIRECTORY_DOWNLOADS
import com.mosu.ui.components.DownloadPhase
import com.mosu.ui.components.DownloadUiState
import com.osudroid.resources.R.*
import com.reco1l.framework.net.FileRequest
import com.reco1l.framework.net.IFileRequestObserver
import com.osudroid.utils.mainThread
import com.osudroid.multiplayer.Multiplayer
import com.osudroid.ui.v1.DownloadFragment
import com.osudroid.utils.async
import net.lingala.zip4j.ZipFile
import ru.nsu.ccfit.zuev.osu.Config
import ru.nsu.ccfit.zuev.osu.GlobalManager
import ru.nsu.ccfit.zuev.osu.ToastLogger
import ru.nsu.ccfit.zuev.osu.helper.FileUtils
import ru.nsu.ccfit.zuev.osu.helper.StringTable
import java.io.IOException

object BeatmapDownloader : IFileRequestObserver {


    private lateinit var fragment: DownloadFragment

    private lateinit var currentFilename: String


    private val context = GlobalManager.getInstance().mainActivity

    // TODO: We should adapt the system to allow multiple downloads at a time.
    private var isDownloading = false


    fun download(url: String, suggestedFilename: String) {

        if (isDownloading) {
            return
        }
        isDownloading = true

        // Remove illegal characters in FAT32 file system.
        // We may extract the beatmap to an EXT4 file system, but that's the least to worry about.
        currentFilename = suggestedFilename.replace("[\"*/:<>?\\\\|]".toRegex(), "_")

        val file = context.getExternalFilesDir(DIRECTORY_DOWNLOADS)!!.resolve("$currentFilename.osz")

        val downloader = FileRequest(file, url)
        downloader.buildRequest { header("User-Agent", "Chrome/Android") }

        fragment = DownloadFragment()
        fragment.setDownloader(downloader) {

            fragment.state = DownloadUiState(
                title = currentFilename,
                message = context.getString(string.beatmap_downloader_connecting),
                phase = DownloadPhase.Connecting,
                cancelLabel = context.getString(string.beatmap_downloader_cancel)
            )
            fragment.onCancel = { downloader.cancel() }

            downloader.observer = this@BeatmapDownloader

            async {
                downloader.execute()
            }
        }
        fragment.show()
    }


    override fun onDownloadEnd(downloader: FileRequest) {
        mainThread {
            fragment.state = fragment.state.copy(
                message = StringTable.format(string.beatmap_downloader_importing, currentFilename),
                phase = DownloadPhase.Importing,
                speed = "",
                cancellable = false
            )
        }

        val file = downloader.file

        try {
            ZipFile(file).use {
                if (!it.isValidZipFile) {
                    mainThread(fragment::dismiss)
                    ToastLogger.showText("Import failed, invalid ZIP file.", true)
                    return
                }

                if (!FileUtils.extractZip(file.path, Config.getBeatmapPath())) {
                    mainThread(fragment::dismiss)
                    ToastLogger.showText("Import failed, failed to extract ZIP file.", true)
                    return
                }

                GlobalManager.getInstance().mainActivity.loadBeatmapLibrary()
            }
        } catch (e: IOException) {
            ToastLogger.showText("Import failed:" + e.message, true)
        } finally {
            mainThread(fragment::dismiss)

            if (Multiplayer.isConnected) {
                Multiplayer.roomScene?.onRoomBeatmapChange(Multiplayer.room!!.beatmap)
            }

            isDownloading = false
        }
    }

    override fun onDownloadCancel(downloader: FileRequest) {
        ToastLogger.showText("Download canceled.", true)

        mainThread(fragment::dismiss)
        isDownloading = false
    }

    override fun onDownloadUpdate(downloader: FileRequest) {

        mainThread {
            fragment.state = fragment.state.copy(
                message = StringTable.format(string.beatmap_downloader_downloading, currentFilename),
                phase = DownloadPhase.Downloading,
                progress = (downloader.progress / 100.0).toFloat(),
                speed = "%.2f MB/s".format(downloader.speedKbps / 1024)
            )
        }
    }

    override fun onDownloadFail(downloader: FileRequest, exception: Exception) {
        ToastLogger.showText("Download failed. " + exception.message, true)

        mainThread(fragment::dismiss)
        isDownloading = false
    }
}
