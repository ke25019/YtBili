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
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.graphics.luminance
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
 * Three things make this work at all:
 *
 *  - **The session cookies are injected** into `CookieManager`, otherwise the
 *    page shows a logged-out studio.
 *  - **A desktop user agent is used by default**, because Bilibili's studio is
 *    desktop-only and serves a broken layout to mobile UAs. Callers that embed a
 *    page which is mobile-friendly can opt out with [desktopUserAgent].
 *  - **Bilibili's own site chrome is stripped** by a CSS reskin, so the page
 *    reads as a screen of this app rather than a raw web page.
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
    /** Bilibili's upload studio is desktop-only and serves a broken layout to mobile UAs. */
    desktopUserAgent: Boolean = true,
    viewModel: InAppBrowserViewModel = hiltViewModel(),
) {
    val cookie by viewModel.cookie.collectAsStateWithLifecycle(initialValue = "")

    var webView by remember { mutableStateOf<WebView?>(null) }
    var fileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var progress by remember { mutableStateOf(0) }

    // The embedded page cannot read our theme, so the reskin has to be handed the
    // current background colour: dark also flips a light page into the dark shell.
    val dark = AppTheme.colors.background.luminance() < 0.5f

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

    // The app draws edge to edge, so this screen's own bar has to clear the
    // status bar itself.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.colors.background)
            .statusBarsPadding(),
    ) {
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

        // A hairline marks the seam between our bar and the embedded page. The
        // warning strip already provides that separation when it is shown.
        if (warning == null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(AppTheme.colors.divider),
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
                        // The studio is desktop-only; pages that ship a real
                        // mobile layout keep the WebView's own user agent.
                        settings.userAgentString =
                            if (desktopUserAgent) MEDIA_USER_AGENT else settings.userAgentString

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

                            override fun onPageFinished(view: WebView?, url: String?) {
                                // Re-apply the reskin on every finished load: the
                                // site replaces its own DOM on SPA navigations.
                                view?.evaluateJavascript(reskinScript(dark), null)
                            }
                        }

                        loadUrl(url)
                        webView = this
                    }
                },
                update = { view ->
                    webView = view
                    // Injected on every update rather than only at progress 100:
                    // the script replaces its own node, so repeated calls are
                    // harmless, and this way a theme flip re-applies the right
                    // colours even on an already-loaded page.
                    view.evaluateJavascript(reskinScript(dark), null)
                },
            )

            if (url.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = YtColors.Red)
                }
            }
        }
    }
}

/** CSS that strips Bilibili's own site chrome so the page sits inside our shell. */
private fun reskinScript(dark: Boolean): String {
    val background = if (dark) "#0F0F0F" else "#FFFFFF"

    val chromeCss = """
        .bili-header, .bili-header__bar, .international-header, .mini-header, #biliMainHeader,
        .bili-header__banner, .nav-mask, .link-navbar, .nav-bar, .m-navbar, .tab-bar,
        .fixed-header, .message-navbar, .bili-footer, .international-footer, .footer,
        .fixed-openapp, .openapp-btn, .ad-report, .pop-live-small, .lt-row, .header-bili,
        .nav-link, .bili-dyn-header { display: none !important; }
        html, body { background: $background !important; }
        body { padding-top: 0 !important; margin-top: 0 !important; }
        * { -webkit-tap-highlight-color: transparent !important; }
        ::-webkit-scrollbar { width: 0 !important; height: 0 !important; }
    """.trimIndent()

    // A light page is inverted so it matches the dark shell; images and video are
    // inverted back so they keep their real colours.
    val darkCss = if (dark) {
        """
        html { filter: invert(1) hue-rotate(180deg); }
        img, video, canvas, svg, [style*="background-image"] { filter: invert(1) hue-rotate(180deg); }
        """.trimIndent()
    } else {
        ""
    }

    // The stylesheet travels inside a JS string literal, so it is flattened to one
    // line and quotes are escaped.
    val css = listOf(chromeCss, darkCss)
        .filter { it.isNotEmpty() }
        .joinToString("\n")
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\n", " ")

    // Idempotent: any previous node is dropped before the new one is inserted, so
    // this is safe to run on every load and every recomposition.
    return """
        (function () {
            try {
                var previous = document.getElementById('ytbili-reskin');
                if (previous && previous.parentNode) { previous.parentNode.removeChild(previous); }
                var style = document.createElement('style');
                style.id = 'ytbili-reskin';
                style.type = 'text/css';
                style.appendChild(document.createTextNode('$css'));
                (document.head || document.documentElement).appendChild(style);
                document.documentElement.style.background = '$background';
                if (document.body) { document.body.style.background = '$background'; }
            } catch (e) {}
        })();
    """.trimIndent()
}
