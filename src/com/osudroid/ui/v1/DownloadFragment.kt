package com.osudroid.ui.v1

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.edlplan.ui.fragment.LoadingFragment
import com.mosu.ui.components.DownloadCard
import com.mosu.ui.components.DownloadUiState
import com.mosu.ui.theme.MosuTheme
import com.reco1l.framework.net.FileRequest
import app.mosu.R

/**
 * Overlay showing the progress of a download using Material 3 Expressive indicators.
 */
class DownloadFragment : LoadingFragment() {

    override val layoutID = R.layout.download_fragment


    private var downloader: FileRequest? = null

    private lateinit var onLoad: Runnable


    /**
     * The state displayed by the overlay. Must be modified from the main thread.
     */
    var state by mutableStateOf(DownloadUiState(title = ""))

    /**
     * Called when the user taps the cancel button.
     */
    var onCancel: () -> Unit = {}


    override fun onLoadView() {
        super.onLoadView()

        val compose = findViewById<ComposeView>(R.id.download_compose)!!
        compose.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        compose.setContent {
            MosuTheme(darkTheme = true) {
                DownloadCard(state, onCancel = { onCancel() })
            }
        }

        onLoad.run()
    }


    fun setDownloader(downloader: FileRequest, onLoad: Runnable) {
        this.onLoad = onLoad
        this.downloader = downloader
    }

    /**
     * For downloads not made through a [FileRequest]; [onCancel] is used to cancel them.
     */
    fun setOnLoad(onLoad: Runnable) {
        this.onLoad = onLoad
    }


    override fun callDismissOnBackPress() {
        val downloader = downloader

        if (downloader?.isDownloading == true) {
            downloader.cancel()
            return
        }

        if (downloader == null && state.cancellable) {
            onCancel()
            return
        }

        super.callDismissOnBackPress()
    }
}
