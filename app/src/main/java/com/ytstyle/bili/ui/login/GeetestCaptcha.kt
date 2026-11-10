package com.ytstyle.bili.ui.login

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ytstyle.bili.data.log.AppLog

/**
 * Renders Bilibili's Geetest challenge.
 *
 * Geetest's own `gt4.js` is loaded and driven the way Bilibili's web client
 * drives it, so whatever challenge Bilibili currently demands is handled by the
 * vendor's code rather than guessed at here. The resulting `validate`/`seccode`
 * pair is what the login POST expects.
 *
 * ## Why a load failure is no longer fatal
 *
 * The captcha page pulls resources from several hosts, and Geetest itself
 * **tries more than one domain** (`gcaptcha4.geevisit.com`, then
 * `gcaptcha4.gsensebot.com`). Chromium blocks some of those probes with
 * `ERR_BLOCKED_BY_ORB` — opaque-response blocking, which fires on a cross-origin
 * response that does not look like the script it was requested as.
 *
 * Treating every `onReceivedError` as fatal meant the *first* such probe aborted
 * the login, before Geetest's own failover had a chance to reach a domain that
 * answers properly. Only a **main-frame** failure is fatal now; sub-resource
 * failures are logged and otherwise ignored.
 *
 * The vendor's `bind` product also opens sub-frames and, on some devices, a
 * window, so those are enabled explicitly.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun GeetestCaptcha(
    gt: String,
    challenge: String,
    onReady: () -> Unit,
    onSuccess: (validate: String, seccode: String) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    // Only rebuild the page when the challenge changes; a recomposition must
    // not discard a half-solved captcha.
    val html = remember(gt, challenge) { buildCaptchaHtml(gt, challenge) }

    AndroidView(
        modifier = modifier.fillMaxWidth().height(320.dp),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.allowFileAccess = false
                // Geetest's `bind` product may open a window for its own
                // challenge; without this the request is silently dropped.
                settings.javaScriptCanOpenWindowsAutomatically = true
                settings.setSupportMultipleWindows(true)
                // Some Geetest hosts still serve parts of the challenge over
                // plain HTTP.
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                // A real Chrome UA. The WebView default advertises itself as an
                // unknown client, and CDNs that do not recognise it can answer
                // with a payload the browser then refuses.
                settings.userAgentString = CAPTCHA_USER_AGENT

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        AppLog.d("captcha", "page finished: ${url.orEmpty()}")
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: android.webkit.WebResourceRequest?,
                        error: android.webkit.WebResourceError?,
                    ) {
                        val description = error?.description?.toString().orEmpty()
                        val url = request?.url?.toString().orEmpty()
                        val mainFrame = request?.isForMainFrame == true

                        // Always logged: this is the trail that explains a
                        // captcha that never appears.
                        AppLog.w(
                            "captcha",
                            "${if (mainFrame) "main frame" else "sub-resource"} failed " +
                                "($description) $url",
                        )

                        if (mainFrame) {
                            mainHandler.post { onError("页面加载失败：$description") }
                        }
                    }

                    override fun onReceivedHttpError(
                        view: WebView?,
                        request: android.webkit.WebResourceRequest?,
                        errorResponse: android.webkit.WebResourceResponse?,
                    ) {
                        val code = errorResponse?.statusCode ?: 0
                        val url = request?.url?.toString().orEmpty()
                        AppLog.w("captcha", "HTTP $code ${if (request?.isForMainFrame == true) "main frame" else "sub-resource"} $url")
                        // Only the main document failing means the challenge
                        // cannot be presented at all.
                        if (request?.isForMainFrame == true && code >= 400) {
                            mainHandler.post { onError("验证码服务返回 HTTP $code") }
                        }
                    }
                }

                addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun onReady() {
                            AppLog.d("captcha", "challenge ready")
                            mainHandler.post { onReady() }
                        }

                        @JavascriptInterface
                        fun onSuccess(validate: String, seccode: String) {
                            // JS bridge callbacks arrive off the main thread.
                            AppLog.d("captcha", "solved")
                            mainHandler.post { onSuccess(validate, seccode) }
                        }

                        @JavascriptInterface
                        fun onError(message: String) {
                            AppLog.w("captcha", "vendor error: $message")
                            mainHandler.post { onError(message) }
                        }
                    },
                    "DroidBridge",
                )

                // The base URL must be Bilibili's so Geetest sees the Referer it
                // expects for this `gt` and the challenge request is not treated
                // as coming from nowhere.
                loadDataWithBaseURL(
                    "https://www.bilibili.com/",
                    html,
                    "text/html",
                    "utf-8",
                    null,
                )
            }
        },
    )
}

/** A standard mobile Chrome UA; `client_type=h5` is what Geetest serves for it. */
private const val CAPTCHA_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

private fun buildCaptchaHtml(gt: String, challenge: String): String = """
<!DOCTYPE html>
<html>
<head>
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <style>
    html, body { margin: 0; padding: 0; background: #FFFFFF; }
    #captcha { padding: 8px; }
  </style>
  <script src="https://static.geetest.com/v4/gt4.js"></script>
</head>
<body>
  <div id="captcha"></div>
  <script>
    function reportError(message) {
      try { window.DroidBridge.onError(String(message)); } catch (e) {}
    }
    window.onerror = function (m) { reportError(m); };

    var captchaReady = false;

    // A white box with no explanation is the worst outcome: if nothing has been
    // rendered after six seconds, say so instead of leaving the user waiting.
    setTimeout(function () {
      if (captchaReady) return;
      var box = document.getElementById('captcha');
      if (!box || box.children.length === 0) {
        reportError('验证码加载超时，可改用扫码或 Cookie 登录');
      }
    }, 6000);

    if (typeof initGeetest4 !== 'function') {
      reportError('gt4.js 未能加载');
    } else {
      initGeetest4({
        // v4's parameter is `captchaId`; passing v3's `gt` made the SDK request
        // /load without a captcha_id, which the service answers with HTTP 403 —
        // the blank challenge in the logs.
        captchaId: '$gt',
        challenge: '$challenge',
        // `float` renders the challenge inline. `bind` waits for a separate
        // trigger element that this page never provides, so it reported "ready"
        // while drawing nothing.
        product: 'float',
        lang: 'zh-cn'
      }, function (captcha) {
        // onReady fires once the challenge is actually on screen, which is what
        // tells the app the wait is over.
        if (captcha.onReady) {
          captcha.onReady(function () {
            captchaReady = true;
            try { window.DroidBridge.onReady(); } catch (e) {}
          });
        }
        captcha.appendTo('#captcha');
        captcha.onSuccess(function () {
          captchaReady = true;
          var result = captcha.getValidate() || {};
          var validate = JSON.stringify(result);
          window.DroidBridge.onSuccess(validate, validate + '|jordan');
        });
        captcha.onError(function (err) {
          reportError((err && err.message) ? err.message : 'captcha error');
        });
      });
    }
  </script>
</body>
</html>
""".trimIndent()
