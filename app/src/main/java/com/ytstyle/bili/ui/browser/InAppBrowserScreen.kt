package com.ytstyle.bili.ui.browser

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ytstyle.bili.ui.theme.AppTheme
import com.ytstyle.bili.ui.theme.YtColors
import com.ytstyle.bili.ui.player.MEDIA_USER_AGENT

/**
 * A WebView used for the few flows that are genuinely web-only: 投稿 (the upload
 * studio) and the message centre.
 *
 * Two things make this work at all:
 *
 *  - **The session cookies are injected** into `CookieManager`, otherwise the
 *    page shows a logged-out studio.
 *  - **A desktop user agent is used**, because Bilibili's studio is desktop-only
 *    and serves a broken layout to mobile UAs.
 *
 * Known limits, surfaced in the banner so nobody discovers them the hard way:
 * an upload can be interrupted by backgrounding the app and has no resume, and
 * anything submitted goes to the real account.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun InAppBrowserScreen(
    url: String,
    title: String,
    warning: String?,
    onBack: () -> Unit,
    viewModel: InAppBrowserViewModel = hiltViewModel(),
) {
    val cookie by viewModel.cookie.collectAsStateWithLifecycle(initialValue = "")

    var webView by remember { mutableStateOf<WebView?>(null) }
    var fileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var progress by remember { mutableStateOf(0) }

    // The studio's file picker has to be driven through a real activity result.
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        fileCallback?.onReceiveValue(uris)
        fileCallback = null
    }

    BackHandler {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else onBack()
    }

    Column(modifier = Modifier.fillMaxSize().background(AppTheme.colors.background)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = AppTheme.colors.onBackground,
                )
            }
            Text(
                text = title,
                color = AppTheme.colors.onBackground,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
            )
        }

        if (warning != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(YtColors.CoinColor.copy(alpha = 0.15f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    text = warning,
                    color = AppTheme.colors.onBackground,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
        }

        if (progress in 1..99) {
            androidx.compose.material3.LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = YtColors.Red,
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.setSupportZoom(true)
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        // The studio is desktop-only.
                        settings.userAgentString = MEDIA_USER_AGENT

                        // Seed the session so the studio is authenticated.
                        cookie.takeIf { it.isNotEmpty() }?.let { cookie ->
                            val manager = CookieManager.getInstance()
                            manager.setAcceptCookie(true)
                            listOf(
                                "https://www.bilibili.com",
                                "https://member.bilibili.com",
                                "https://message.bilibili.com",
                                "https://api.bilibili.com",
                            ).forEach { manager.setCookie(it, cookie) }
                            manager.flush()
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress
                            }

                            override fun onShowFileChooser(
                                view: WebView?,
                                callback: ValueCallback<Array<Uri>>?,
                                params: FileChooserParams?,
                            ): Boolean {
                                fileCallback?.onReceiveValue(null)
                                fileCallback = callback
                                val intent = params?.createIntent() ?: return false
                                return runCatching {
                                    filePicker.launch(intent)
                                    true
                                }.getOrDefault(false)
                            }
                        }

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean {
                                // Keep navigation inside the WebView, but hand
                                // non-web schemes (bilibili://, mailto:) to the
                                // system.
                                val url = request?.url?.toString().orEmpty()
                                val isWeb = url.startsWith("http://") || url.startsWith("https://")
                                return !isWeb
                            }
                        }

                        loadUrl(url)
                        webView = this
                    }
                },
                update = { webView = it },
            )

            if (url.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = YtColors.Red)
                }
            }
        }
    }
}
