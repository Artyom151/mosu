package com.mosu.skins

import android.net.Uri
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
                    onCancelDownload = ::cancelDownload,
                )
            }
        }

        if (state.items.isEmpty()) {
            search(reset = true)
        }
    }


    private fun search(reset: Boolean) {

        if (reset) {
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
                endReached = added.isEmpty()
                page++
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                state.failed = true
            } finally {
                state.loading = false
            }
        }
    }


    private fun startDownload(item: SkinListItem, url: String, userAgent: String) {

        if (downloadJob?.isActive == true) return

        state.web = null

        val file = File(requireContext().cacheDir, "${SkinInstaller.sanitizeName(item.name)}.osk")

        state.download = DownloadUiState(title = item.name, message = "Connecting…", phase = DownloadPhase.Connecting)

        downloadJob = scope.launch {
            try {
                SkinDownloader.download(url, userAgent, item.pageUrl, file) { progress, speed ->
                    state.download = DownloadUiState(
                        title = item.name,
                        phase = if (progress >= 0) DownloadPhase.Downloading else DownloadPhase.Connecting,
                        progress = progress.coerceAtLeast(0f),
                        speed = "%.2f MB/s".format(speed / 1024f / 1024f),
                    )
                }

                state.download = DownloadUiState(title = item.name, message = "Installing…", phase = DownloadPhase.Importing, cancellable = false)

                val installed = withContext(Dispatchers.IO) { SkinInstaller.install(file, item.name) }
                report(installed)
            } catch (e: CancellationException) {
                file.delete()
                throw e
            } catch (e: Exception) {
                file.delete()
                ToastLogger.showText("Skin download failed: ${e.message}", true)
            } finally {
                state.download = null
            }
        }
    }

    private fun cancelDownload() {
        downloadJob?.cancel()
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
