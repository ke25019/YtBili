package com.ytstyle.bili.ui.login

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Renders Bilibili's Geetest challenge.
 *
 * Rather than reimplementing Geetest's protocol, this loads **Geetest's own
 * `gt4.js`** and drives it exactly as Bilibili's web client does. That means
 * whatever challenge Bilibili currently demands is handled by the vendor's
 * code, instead of being guessed at here.
 *
 * The resulting `validate`/`seccode` pair is what the login POST expects.
 *
 * Note on the payload shape: for Geetest v4 the success callback yields an
 * object (`lot_number`, `captcha_output`, `pass_token`, `gen_time`). Bilibili's
 * web client serialises that object as JSON into `validate` and appends the
 * literal `|jordan` suffix for `seccode`. That convention is reproduced here,
 * but it is the one part of the login flow that could not be verified without a
 * real account, so treat it as the most likely place for a login failure.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun GeetestCaptcha(
    gt: String,
    challenge: String,
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
                // Geetest's script is loaded from a CDN; without this the page
                // can be treated as an opaque origin and the script blocked.
                settings.allowFileAccess = false
                settings.mediaPlaybackRequiresUserGesture = false

                webViewClient = object : WebViewClient() {
                    override fun onReceivedError(
                        view: WebView?,
                        request: android.webkit.WebResourceRequest?,
                        error: android.webkit.WebResourceError?,
                    ) {
                        // Surfacing this is the whole point: previously a failed
                        // load left a blank box with no explanation.
                        val description = error?.description?.toString().orEmpty()
                        val url = request?.url?.toString().orEmpty()
                        mainHandler.post {
                            onError("页面加载失败${if (url.isNotEmpty()) "($url)" else ""}: $description")
                        }
                    }

                    override fun onReceivedHttpError(
                        view: WebView?,
                        request: android.webkit.WebResourceRequest?,
                        errorResponse: android.webkit.WebResourceResponse?,
                    ) {
                        val code = errorResponse?.statusCode ?: 0
                        // Only report the main document failing; sub-resource
                        // failures are common and not fatal.
                        if (request?.isForMainFrame == true && code >= 400) {
                            mainHandler.post { onError("验证码服务返回 HTTP $code") }
                        }
                    }
                }

                addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun onSuccess(validate: String, seccode: String) {
                            // JS bridge callbacks arrive off the main thread.
                            mainHandler.post { onSuccess(validate, seccode) }
                        }

                        @JavascriptInterface
                        fun onError(message: String) {
                            mainHandler.post { onError(message) }
                        }
                    },
                    "DroidBridge",
                )

                // The base URL must be Bilibili's so Geetest's script and the
                // challenge request are same-site and not blocked.
                loadDataWithBaseURL(
                    "https://www.bilibili.com",
                    html,
                    "text/html",
                    "utf-8",
                    null,
                )
            }
        },
    )
}

private fun buildCaptchaHtml(gt: String, challenge: String): String = """
<!DOCTYPE html>
<html>
<head>
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <style>
    html, body { margin: 0; padding: 0; background: #0F0F0F; }
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

    if (typeof initGeetest4 !== 'function') {
      reportError('gt4.js failed to load');
    } else {
      initGeetest4({
        gt: '$gt',
        challenge: '$challenge',
        product: 'bind',
        lang: 'zh-cn'
      }, function (captcha) {
        captcha.appendTo('#captcha');
        captcha.onSuccess(function () {
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
