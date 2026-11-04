package com.photovideo.gallerykz.memories.activity

import com.photovideo.gallerykz.memories.ads.AdAwareActivity

import com.photovideo.gallerykz.memories.ads.AdScreen

import com.photovideo.gallerykz.memories.ads.AdHooks

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.photovideo.gallerykz.memories.R
import com.photovideo.gallerykz.memories.databinding.ActivityVersionBinding

/**
 * About app (design 56): the app icon, its name, "Version 1.0.0 (100)" read from the installed package, and a line about
 * what the app does. Opened from the App version row of Settings.
 */
class VersionActivity : AdAwareActivity() {

    private var binding: ActivityVersionBinding? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val b = ActivityVersionBinding.inflate(layoutInflater)
        binding = b
        setContentView(b.root)
        AdHooks.install(this, AdScreen.ABOUT)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        b.btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        b.tvAppName.setText(R.string.app_name)
        b.tvVersion.text = versionText()
    }

    override fun onDestroy() {
        binding = null
        super.onDestroy()
    }

    /** "Version 1.0.0 (100)": the name and the build number of the installed app; a missing value is left out. */
    private fun versionText(): String {
        val info = try {
            packageManager.getPackageInfo(packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        } ?: return getString(R.string.about_version_unknown)
        val name = info.versionName.orEmpty()
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        return if (name.isEmpty()) getString(R.string.about_version_unknown) else getString(R.string.about_version, name, code)
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, VersionActivity::class.java)
    }
}
