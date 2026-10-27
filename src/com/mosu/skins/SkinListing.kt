package com.mosu.skins

import android.net.Uri
import android.util.Log
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.edlplan.ui.fragment.BaseFragment
import com.mosu.ui.components.DownloadPhase
import com.mosu.ui.components.DownloadUiState
import com.mosu.ui.skins.SkinListingScreen
import com.mosu.ui.skins.SkinListingState
import com.mosu.ui.theme.MosuTheme
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.nsu.ccfit.zuev.osu.ToastLogger
import ru.nsu.ccfit.zuev.osuplus.R

/**
 * Skin catalog (osuskins.net) with previews and a wavy-progress download flow.
 */
class SkinListing : BaseFragment() {

    override val layoutID = R.layout.skin_listing_fragment

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val state = SkinListingState()

    private var page = 1

    private var endReached = false

    private var searchJob: Job? = null

    private var lastSearchKey: String? = null

    private var downloadJob: Job? = null

    private val filePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) importFile(uri)
    }


    override fun onLoadView() {
        val compose = findViewById<ComposeView>(R.id.skin_listing_compose)!!
        compose.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        compose.setContent {
            MosuTheme(darkTheme = true) {
                SkinListingScreen(
                    state = state,
                    onClose = ::dismiss,
                    onSearch = { search(reset = true) },
                    onLoadMore = { search(reset = false) },
                    onImportFile = { filePicker.launch("*/*") },
                    onOpenWeb = { state.webReady = false; state.web = it },
                    onWebDownload = ::startDownload,
                    onVerified = ::onVerified,
                    onCancelDownload = ::cancelDownload,
                )
            }
        }

        if (state.items.isEmpty()) {
            search(reset = true)
        }
    }


    private fun search(reset: Boolean) {

        Log.i("SkinListing", "search(reset=$reset, query='${state.query}', sort=${state.sort}, page=$page, loading=${state.loading}, end=$endReached)")

        // Typing triggers a delayed search and the keyboard's search key triggers another one for the same query.
        val key = "${state.query.trim()}|${state.sort}"
        if (reset && key == lastSearchKey && !state.failed) {
            return
        }

        if (reset) {
            lastSearchKey = key
            searchJob?.cancel()
            page = 1
            endReached = false
        } else if (state.loading || endReached) {
            return
        }

        searchJob = scope.launch {
            state.loading = true
            state.failed = false

            try {
                val result = OsuSkinsClient.search(state.query, state.sort, page)

                if (reset) state.items.clear()

                val known = state.items.mapTo(HashSet()) { it.id }
                val added = result.filter { known.add(it.id) }

                state.items.addAll(added)
                Log.i("SkinListing", "Search returned ${result.size} items, ${added.size} new")
                endReached = added.isEmpty()
                page++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("SkinListing", "Skin search failed (query='${state.query}', page=$page)", e)
                ToastLogger.showText("Skin search failed: ${e.message}", true)
                state.failed = true
            } finally {
                state.loading = false
            }
        }
    }


    /**
     * The captcha was solved: the site waits a few seconds before starting the download, show progress right away.
     */
    private fun onVerified(item: SkinListItem, expectedBytes: Long) {
        if (state.download == null) {
            state.download = DownloadUiState(
                title = item.name,
                message = "Starting download…" + if (expectedBytes > 0) " (${formatMegabytes(expectedBytes)})" else "",
                phase = DownloadPhase.Connecting,
            )
        }
    }

    private fun startDownload(item: SkinListItem, url: String, userAgent: String, expectedLength: Long) {

        if (downloadJob?.isActive == true) return

        state.web = null

        val file = File(requireContext().cacheDir, "${SkinInstaller.sanitizeName(item.name)}.osk")

        state.download = DownloadUiState(title = item.name, message = "Connecting…", phase = DownloadPhase.Connecting)

        downloadJob = scope.launch {
            try {
                SkinDownloader.download(url, userAgent, item.pageUrl, file, expectedLength) { read, total, speed ->
                    state.download = progressState(item, read, total, speed)
                }

                state.download = DownloadUiState(title = item.name, message = "Installing…", phase = DownloadPhase.Importing, cancellable = false)

                val installed = withContext(Dispatchers.IO) { SkinInstaller.install(file, item.name) }
                report(installed)
            } catch (e: CancellationException) {
                file.delete()
                throw e
            } catch (e: Exception) {
                file.delete()
                Log.e("SkinListing", "Skin download failed: $url", e)
                ToastLogger.showText("Skin download failed: ${e.message}", true)
            } finally {
                state.download = null
            }
        }
    }

    private fun progressState(item: SkinListItem, read: Long, total: Long, speed: Long): DownloadUiState {

        val known = total > 0

        val eta = if (known && speed > 0) {
            val seconds = ((total - read).coerceAtLeast(0) / speed).toInt()
            if (seconds >= 60) " • ~${seconds / 60} min ${seconds % 60} s left" else " • ~$seconds s left"
        } else ""

        return DownloadUiState(
            title = item.name,
            message = if (known) "${formatMegabytes(read)} / ${formatMegabytes(total)}" else formatMegabytes(read),
            phase = if (known) DownloadPhase.Downloading else DownloadPhase.Connecting,
            progress = if (known) read.toFloat() / total else 0f,
            speed = formatMegabytes(speed) + "/s" + eta,
        )
    }

    private fun formatMegabytes(bytes: Long) = "%.1f MB".format(bytes / 1024f / 1024f)

    private fun cancelDownload() {
        if (downloadJob?.isActive == true) {
            downloadJob?.cancel()
        } else {
            // Still waiting for the site to start the download.
            state.download = null
            state.web = null
        }
    }


    private fun importFile(uri: Uri) {
        val context = requireContext()

        state.download = DownloadUiState(title = "Importing", phase = DownloadPhase.Importing, cancellable = false)

        downloadJob = scope.launch {
            try {
                val installed = withContext(Dispatchers.IO) {
                    val name = context.contentResolver.query(uri, null, null, null, null)?.use {
                        val column = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (column >= 0 && it.moveToFirst()) it.getString(column) else null
                    } ?: "skin"

                    val file = File(context.cacheDir, "import_${System.currentTimeMillis()}.osk")

                    context.contentResolver.openInputStream(uri)!!.use { input ->
                        file.outputStream().use { input.copyTo(it) }
                    }

                    SkinInstaller.install(file, name.removeSuffix(".osk"))
                }
                report(installed)
            } catch (e: Exception) {
                ToastLogger.showText("Skin import failed: ${e.message}", true)
            } finally {
                state.download = null
            }
        }
    }

    private fun report(installedName: String?) {
        Log.i("SkinListing", "Skin install result: $installedName")
        if (installedName != null) {
            state.web = null
            ToastLogger.showText("Skin installed: $installedName", true)
        } else {
            ToastLogger.showText("Failed to install the skin.", true)
        }
    }


    override fun callDismissOnBackPress() {
        when {
            state.download != null -> if (state.download?.cancellable == true) cancelDownload()
            state.web != null -> state.web = null
            state.selected != null -> state.selected = null
            else -> dismiss()
        }
    }

    override fun dismiss() {
        scope.cancel()
        super.dismiss()
    }
}
