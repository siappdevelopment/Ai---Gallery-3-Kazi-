package com.photovideo.gallerykz.memories.ads

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
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

    @SuppressLint("SetJavaScriptEnabled")
    fun show(activity: Activity, done: (Boolean) -> Unit) {
        val link = QuizAds.pickLink(activity)
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
            val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            val view = LayoutInflater.from(activity).inflate(R.layout.dialog_quiz_browser, null)
            dialog.setContentView(view)
            dialog.setCancelable(false)
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.WHITE))
            val web = view.findViewById<WebView>(R.id.quizWebView)
            val progress = view.findViewById<ProgressBar>(R.id.quizWebProgress)
            web.settings.javaScriptEnabled = true
            web.settings.domStorageEnabled = true
            web.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                    val scheme = request.url.scheme.orEmpty()
                    return scheme != "http" && scheme != "https"
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
            fun close() {
                try {
                    dialog.dismiss()
                } catch (e: Exception) {
                    // Already gone.
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
