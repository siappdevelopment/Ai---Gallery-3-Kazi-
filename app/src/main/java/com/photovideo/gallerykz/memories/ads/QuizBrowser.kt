package com.photovideo.gallerykz.memories.ads

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.webkit.WebChromeClient
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.photovideo.gallerykz.memories.R
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `Google_ad_Fail_type = Browser`: the Quiz link opens straight in a full-screen web view (dialog_quiz_browser.xml) inside the
 * screen that wanted the ad; no Quiz ad layout is shown. [show] calls `done` exactly once, when the web view is closed
 * (true) or at once when nothing could be opened (false).
 */
object QuizBrowser {

    private const val TAG = "KaziAds"
    private val active = AtomicBoolean(false)

    /** An http(s) address (a missing scheme becomes https); empty for anything else, so a bad link never reaches the web view. */
    private fun normalize(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) return ""
        val withScheme = if (text.contains("://")) text else "https://$text"
        val uri = try {
            android.net.Uri.parse(withScheme)
        } catch (e: Exception) {
            return ""
        }
        return if ((uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrEmpty()) withScheme else ""
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun show(activity: Activity, done: (Boolean) -> Unit) {
        val link = normalize(QuizAds.pickLink(activity))
        if (link.isEmpty() || activity.isFinishing || activity.isDestroyed || !active.compareAndSet(false, true)) {
            done(false)
            return
        }
        val once = AtomicBoolean(false)
        fun finish(shown: Boolean) {
            if (once.compareAndSet(false, true)) {
                active.set(false)
                done(shown)
            }
        }
        try {
            // A normal (not full-screen) window: the status bar stays visible and gets the colour of the app's theme.
            val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            val view = LayoutInflater.from(activity).inflate(R.layout.dialog_quiz_browser, null)
            dialog.setContentView(view)
            dialog.setCancelable(false)
            val themeColor = ContextCompat.getColor(activity, R.color.ad_background)
            dialog.window?.let { window ->
                window.setBackgroundDrawable(ColorDrawable(themeColor))
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                WindowCompat.setDecorFitsSystemWindows(window, false)
                val night = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !night
                    isAppearanceLightNavigationBars = !night
                }
            }
            // The status bar area, a cut-out and the navigation bar are kept clear of the toolbar and the page.
            val status = view.findViewById<View>(R.id.quizBrowserStatus)
            val root = view.findViewById<View>(R.id.quizBrowserRoot)
            ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
                status.layoutParams = status.layoutParams.apply { height = bars.top }
                v.setPadding(bars.left, 0, bars.right, maxOf(bars.bottom, ime.bottom))
                insets
            }
            val web = view.findViewById<WebView>(R.id.quizWebView)
            val progress = view.findViewById<ProgressBar>(R.id.quizWebProgress)
            fun close() {
                try {
                    dialog.dismiss()
                } catch (e: Exception) {
                    // Already gone.
                }
            }
            web.settings.javaScriptEnabled = true
            web.settings.domStorageEnabled = true
            web.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                    val scheme = request.url.scheme.orEmpty()
                    return scheme != "http" && scheme != "https"
                }

                // The page itself could not be loaded (no network, bad address): say so and close; nothing else is affected.
                override fun onReceivedError(v: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) {
                        Toast.makeText(activity, R.string.policy_link_unavailable, Toast.LENGTH_SHORT).show()
                        close()
                    }
                }

                // A crashed web view process is closed instead of taking the app with it.
                override fun onRenderProcessGone(v: WebView, detail: RenderProcessGoneDetail): Boolean {
                    close()
                    return true
                }

                override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                    progress.visibility = View.VISIBLE
                }

                override fun onPageFinished(v: WebView, url: String?) {
                    progress.visibility = View.GONE
                }
            }
            web.webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(v: WebView, newProgress: Int) {
                    progress.progress = newProgress
                }
            }
            view.findViewById<View>(R.id.quizBrowserClose).setOnClickListener { close() }
            dialog.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    if (web.canGoBack()) web.goBack() else close()
                    true
                } else {
                    keyCode == KeyEvent.KEYCODE_BACK
                }
            }
            dialog.setOnDismissListener {
                try {
                    (web.parent as? ViewGroup)?.removeView(web)
                    web.stopLoading()
                    web.destroy()
                } catch (e: Exception) {
                    Log.d(TAG, "web view cleanup: $e")
                }
                finish(true)
            }
            web.loadUrl(link)
            dialog.show()
        } catch (e: Exception) {
            Log.d(TAG, "quiz web view failed: $e")
            finish(false)
        }
    }
}
