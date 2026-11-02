package com.photovideo.gallerykz.memories.launcher

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnPreDraw
import androidx.recyclerview.widget.GridLayoutManager
import com.photovideo.gallerykz.memories.R
import com.photovideo.gallerykz.memories.databinding.ActivityRecentBinding

/**
 * The launcher's Recent page, opened by the Recents key while this app is the Home app. It lists the apps that were
 * opened from the launcher (newest first), opens one with a tap, and clears the list (and the apps' background
 * processes) with Clear all. It slides in from the left.
 */
class RecentActivity : AppCompatActivity(), LauncherApps.Listener {

    private var binding: ActivityRecentBinding? = null
    private val adapter = LauncherAppsAdapter(onClick = ::open)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Without the Home role there is nothing to replace: the page closes at once.
        if (!AppRecentsKey.canOpenRecent(this)) {
            finish()
            return
        }
        enableEdgeToEdge()
        val b = ActivityRecentBinding.inflate(layoutInflater)
        binding = b
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.recentRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        b.rvApps.layoutManager = GridLayoutManager(this, COLUMNS)
        b.rvApps.adapter = adapter
        b.rvApps.itemAnimator = null
        adapter.applySettings(this)

        b.ivClose.setOnClickListener { close() }
        b.tvClear.setOnClickListener { clearAll() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = close()
        })

        LauncherApps.register(this)
        if (LauncherApps.apps.isEmpty()) LauncherApps.reload(this)
        render()

        // Slide in from the left once the first frame is measured.
        b.recentRoot.doOnPreDraw {
            b.recentRoot.translationX = -b.recentRoot.width.toFloat()
            b.recentRoot.animate().translationX(0f).setDuration(SLIDE_MS).start()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        render()
    }

    override fun onDestroy() {
        LauncherApps.unregister(this)
        binding = null
        super.onDestroy()
    }

    override fun onAppsChanged(apps: List<LauncherApp>) = render()

    /** The recent packages that are still installed and launchable, newest first. */
    private fun recentApps(): List<LauncherApp> =
        RecentAppsStore.packages(this).mapNotNull { LauncherApps.find(it) }.take(MAX_SHOWN)

    private fun render() {
        val b = binding ?: return
        val list = recentApps()
        adapter.submitList(list)
        // Apps are still loading: show nothing rather than "No recent apps".
        val loading = LauncherApps.apps.isEmpty()
        b.tvEmpty.visibility = if (list.isEmpty() && !loading) View.VISIBLE else View.GONE
        b.tvClear.visibility = if (list.isEmpty()) View.INVISIBLE else View.VISIBLE
    }

    private fun open(app: LauncherApp) {
        if (LauncherApps.launch(this, app)) {
            finish()
            overridePendingTransition(0, 0)
        } else {
            Toast.makeText(applicationContext, R.string.launcher_cannot_open, Toast.LENGTH_SHORT).show()
            RecentAppsStore.remove(this, app.packageName)
            render()
        }
    }

    private fun clearAll() {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        RecentAppsStore.packages(this).forEach { pkg ->
            try {
                manager?.killBackgroundProcesses(pkg)
            } catch (e: Exception) {
                // Not allowed for this package: the list is cleared anyway.
            }
        }
        RecentAppsStore.clear(this)
        Toast.makeText(applicationContext, R.string.launcher_recent_cleared, Toast.LENGTH_SHORT).show()
        render()
    }

    private fun close() {
        val root = binding?.recentRoot
        if (root == null) {
            finish()
            return
        }
        root.animate().translationX(-root.width.toFloat()).setDuration(SLIDE_MS).withEndAction {
            finish()
            overridePendingTransition(0, 0)
        }.start()
    }

    companion object {
        private const val COLUMNS = 4
        private const val MAX_SHOWN = 12
        private const val SLIDE_MS = 220L
    }
}
