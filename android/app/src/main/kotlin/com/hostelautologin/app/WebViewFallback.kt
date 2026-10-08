package com.hostelautologin.app

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.hostelautologin.core.AppLog
import com.hostelautologin.core.HostPin
import com.hostelautologin.core.HostPinException
import com.hostelautologin.core.LoginFallback
import com.hostelautologin.core.LoginResult
import com.hostelautologin.core.Outcome
import com.hostelautologin.core.PortalConfig
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Browser-based login for JS/cookie-heavy portals (portal.json: fallback.type = "browser" + fallback.webview).
 * Safety: every request the WebView makes is checked against the pinned portal host; anything else is blocked,
 * and credentials are injected only while the page is on the pinned host. Blocking call: run off the main thread.
 */
class WebViewFallback(private val context: Context, private val wifi: WifiTracker) : LoginFallback {
    private val main = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    override fun login(config: PortalConfig, username: String, password: String): LoginResult {
        val spec = config.webView ?: return LoginResult(Outcome.CONFIG_ERROR, "fallback.webview is not configured in portal.json")
        val startUrl = try {
            HostPin.check("${config.scheme}://${config.portalHost}${spec.path}", config.portalHost, config.scheme).toString()
        } catch (e: HostPinException) { return LoginResult(Outcome.CONFIG_ERROR, "refused: ${e.message}") }

        val done = CountDownLatch(1)
        val result = AtomicReference(LoginResult(Outcome.TRANSIENT, "WebView login timed out"))
        var web: WebView? = null

        fun pinned(url: String) = try { HostPin.check(url, config.portalHost, config.scheme); true } catch (e: HostPinException) { false }

        main.post {
            wifi.bindProcess(true)
            val w = WebView(context.applicationContext)
            web = w
            w.settings.javaScriptEnabled = true
            w.settings.domStorageEnabled = true
            var submitted = false
            w.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = !pinned(request.url.toString())
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    if (pinned(request.url.toString())) null
                    else WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))

                override fun onPageFinished(view: WebView, url: String) {
                    if (!pinned(url)) return
                    if (!submitted) {
                        submitted = true
                        view.evaluateJavascript(fillScript(spec.usernameSelector, spec.passwordSelector, spec.submitSelector, username, password), null)
                        return
                    }
                    val urlOk = spec.successUrlContains?.let { it in url }
                    view.evaluateJavascript("document.body ? document.body.innerText : ''") { text ->
                        val textOk = spec.successTextContains?.let { text != null && it.lowercase() in text.lowercase() }
                        if (urlOk == true || textOk == true || (urlOk == null && textOk == null)) {
                            result.set(LoginResult(Outcome.SUCCESS, "WebView login finished"))
                            done.countDown()
                        }
                    }
                }
            }
            w.loadUrl(startUrl)
        }

        val finished = done.await(45, TimeUnit.SECONDS)
        main.post {
            web?.stopLoading(); web?.destroy(); web = null
            wifi.bindProcess(false)
        }
        if (!finished) AppLog.w("WebView fallback timed out")
        return result.get()
    }

    private fun fillScript(userSel: String, passSel: String, submitSel: String, user: String, pw: String): String {
        val q = { s: String -> JSONObject.quote(s) }      // safe JS string literal
        return """(function(){
            var u=document.querySelector(${q(userSel)}), p=document.querySelector(${q(passSel)}), b=document.querySelector(${q(submitSel)});
            if(!u||!p||!b){return;}
            u.value=${q(user)}; p.value=${q(pw)};
            u.dispatchEvent(new Event('input',{bubbles:true})); p.dispatchEvent(new Event('input',{bubbles:true}));
            b.click();
        })();"""
    }
}
