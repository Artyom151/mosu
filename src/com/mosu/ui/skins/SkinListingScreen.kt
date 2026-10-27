package com.mosu.ui.skins

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mosu.skins.OsuSkinsClient
import com.mosu.skins.SkinListItem
import com.mosu.skins.SkinSort
import com.mosu.ui.components.DownloadCard
import com.mosu.ui.components.DownloadUiState

/**
 * Everything the skin catalog screen displays. Modified from the main thread by [SkinListing].
 */
class SkinListingState {
    var query by mutableStateOf("")
    var sort by mutableStateOf(SkinSort.Popular)
    var loading by mutableStateOf(false)
    var failed by mutableStateOf(false)
    var selected by mutableStateOf<SkinListItem?>(null)
    var web by mutableStateOf<SkinListItem?>(null)
    var download by mutableStateOf<DownloadUiState?>(null)

    /** Whether the captcha widget is displayed in the verification WebView. */
    var webReady by mutableStateOf(false)
    val items = mutableStateListOf<SkinListItem>()
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, FlowPreview::class)
@Composable
fun SkinListingScreen(
    state: SkinListingState,
    onClose: () -> Unit,
    onSearch: () -> Unit,
    onLoadMore: () -> Unit,
    onImportFile: () -> Unit,
    onOpenWeb: (SkinListItem) -> Unit,
    onWebDownload: (SkinListItem, url: String, userAgent: String) -> Unit,
    onCancelDownload: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {

        Column(Modifier.fillMaxSize()) {

            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                Text("Skins", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onImportFile) { Icon(Icons.Rounded.FileOpen, contentDescription = "Import .osk file") }
            }

            OutlinedTextField(
                value = state.query,
                onValueChange = { state.query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                singleLine = true,
                placeholder = { Text("Search skins") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = { IconButton(onClick = onSearch) { Icon(Icons.Rounded.Search, contentDescription = "Search") } },
                shape = MaterialTheme.shapes.extraLarge,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            )

            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (sort in SkinSort.entries) {
                    FilterChip(
                        selected = state.sort == sort,
                        onClick = { state.sort = sort; onSearch() },
                        label = { Text(sort.name) },
                    )
                }
            }

            // Search while typing, shortly after the user stops.
            LaunchedEffect(Unit) {
                snapshotFlow { state.query }.drop(1).debounce(500).collect { onSearch() }
            }

            val gridState = rememberLazyGridState()

            val nearEnd by remember {
                derivedStateOf {
                    val info = gridState.layoutInfo
                    (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 6
                }
            }

            LaunchedEffect(nearEnd, state.items.size) {
                if (nearEnd && state.items.isNotEmpty()) onLoadMore()
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {

                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(180.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.items, key = { it.id }) { item -> SkinCard(item) { state.selected = item } }
                }

                if (state.loading) {
                    LoadingIndicator(Modifier.align(Alignment.BottomCenter).padding(16.dp).size(48.dp))
                }

                if (!state.failed && state.items.isEmpty() && !state.loading) {
                    Text("No skins found", style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.Center))
                }

                if (state.failed && state.items.isEmpty() && !state.loading) {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Couldn't load skins", style = MaterialTheme.typography.titleMedium)
                        OutlinedButton(onClick = onSearch, modifier = Modifier.padding(top = 8.dp)) { Text("Retry") }
                    }
                }
            }
        }

        state.selected?.let { item ->
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable { state.selected = null },
                contentAlignment = Alignment.Center,
            ) {
                SkinPreview(item, onDownload = { state.selected = null; onOpenWeb(item) }, onClose = { state.selected = null })
            }
        }

        state.web?.let { item ->
            SkinVerifyDialog(item, state, onClose = { state.web = null }, onDownload = { url, ua -> onWebDownload(item, url, ua) })
        }

        state.download?.let { download ->
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(enabled = false) {},
                contentAlignment = Alignment.Center,
            ) {
                DownloadCard(download, onCancel = onCancelDownload)
            }
        }
    }
}

@Composable
private fun SkinCard(item: SkinListItem, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, shape = MaterialTheme.shapes.large) {
        val image by rememberRemoteImage(item.thumbnailUrl)

        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
            image?.let { Image(it, contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }

        Text(
            item.name,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SkinPreview(item: SkinListItem, onDownload: () -> Unit, onClose: () -> Unit) {
    // The game runs in landscape, so the preview is laid out side by side to keep the buttons on screen.
    Card(
        modifier = Modifier.padding(16.dp).fillMaxWidth(0.92f).clickable(enabled = false) {},
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {

            val image by rememberRemoteImage(item.previewUrl)

            Box(
                Modifier.weight(1.3f).aspectRatio(16f / 9f).clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                val bitmap = image

                if (bitmap != null) {
                    Image(bitmap, contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    LoadingIndicator(Modifier.size(48.dp))
                }
            }

            Column(
                Modifier.weight(1f).align(Alignment.CenterVertically),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(item.name, style = MaterialTheme.typography.titleLarge, maxLines = 4, overflow = TextOverflow.Ellipsis)

                Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Download", modifier = Modifier.padding(start = 8.dp))
                }

                OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Close") }
            }
        }
    }
}

/**
 * Hides everything on the download page except the captcha widget and the countdown message.
 */
private const val VERIFY_PAGE_JS = """
(function () {
  var s = document.createElement('style');
  s.textContent = 'html,body{background:#1c1b1f!important}body *{visibility:hidden!important}' +
    '.cf-turnstile,.cf-turnstile *,#waitSection,#waitSection *{visibility:visible!important}' +
    '.cf-turnstile{position:fixed!important;top:16px;left:8px;right:8px;margin:0!important}' +
    '#waitSection{position:fixed!important;top:100px;left:0;right:0;color:#fff;text-align:center;font-size:16px}';
  document.head.appendChild(s);
})();
"""

/**
 * Walks the site's download flow for the user: opens the skin page, submits its download form and shows only the
 * Cloudflare confirmation. Once the user confirms it, the site starts the download by itself (after a short
 * countdown) and the file is intercepted by the WebView's download listener.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SkinVerifyDialog(item: SkinListItem, state: SkinListingState, onClose: () -> Unit, onDownload: (url: String, userAgent: String) -> Unit) {

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(enabled = false) {},
        contentAlignment = Alignment.Center,
    ) {
        Card(modifier = Modifier.padding(16.dp).widthIn(max = 420.dp).fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

                Text("Confirm you're human", style = MaterialTheme.typography.titleLarge)
                Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)

                Box(
                    Modifier.fillMaxWidth().height(150.dp).clip(MaterialTheme.shapes.large)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    val submitted = remember(item.id) { booleanArrayOf(false) }

                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            WebView(context).apply {
                                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true

                                webViewClient = object : WebViewClient() {

                                    // Only the skin site and its captcha provider may be opened.
                                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                                        request.url.scheme != "https" || !OsuSkinsClient.isTrustedHost(request.url.host)

                                    override fun onPageFinished(view: WebView, url: String?) {
                                        val path = url?.let { Uri.parse(it).path }.orEmpty()

                                        when {
                                            path == "/skin/${item.id}" && !submitted[0] -> {
                                                submitted[0] = true
                                                view.evaluateJavascript("document.getElementById('downloadForm').submit();", null)
                                            }

                                            path == "/skin/${item.id}/download" -> {
                                                view.evaluateJavascript(VERIFY_PAGE_JS, null)
                                                state.webReady = true
                                            }
                                        }
                                    }
                                }

                                setDownloadListener { url, userAgent, _, _, _ -> onDownload(url, userAgent) }
                                loadUrl(item.pageUrl)
                            }
                        },
                        onRelease = { it.stopLoading(); it.destroy() },
                    )

                    if (!state.webReady) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                            LoadingIndicator(Modifier.size(48.dp))
                        }
                    }
                }

                Text(
                    "Tap the checkbox. The download starts automatically.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            }
        }
    }
}
