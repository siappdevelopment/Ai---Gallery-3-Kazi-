package com.example.thirdgallery.activity

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.example.thirdgallery.R
import com.example.thirdgallery.databinding.ActivityHomeBinding
import com.example.thirdgallery.fragment.ForYouFragment
import com.example.thirdgallery.fragment.PlaceholderFragment

class HomeActivity : AppCompatActivity() {

    private var binding: ActivityHomeBinding? = null
    private var selectedTab = TAB_FOR_YOU

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val b = ActivityHomeBinding.inflate(layoutInflater)
        binding = b
        setContentView(b.root)

        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // The tab bar extends under the system navigation bar, so it takes the bottom inset.
            v.setPadding(bars.left, bars.top, bars.right, 0)
            b.navBar.setPadding(0, 0, 0, bars.bottom)
            insets
        }

        b.tabForYou.setOnClickListener { selectTab(TAB_FOR_YOU) }
        b.tabGallery.setOnClickListener { selectTab(TAB_GALLERY) }
        b.tabAlbums.setOnClickListener { selectTab(TAB_ALBUMS) }

        selectedTab = savedInstanceState?.getInt(KEY_TAB, TAB_FOR_YOU) ?: TAB_FOR_YOU
        selectTab(selectedTab, force = true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, selectedTab)
    }

    override fun onDestroy() {
        binding = null
        super.onDestroy()
    }

    /** Shows the tab's fragment (created once, then hidden/shown so scroll positions survive). */
    private fun selectTab(tab: Int, force: Boolean = false) {
        if (!force && tab == selectedTab) return
        selectedTab = tab
        updateTabStyles(tab)

        val fm = supportFragmentManager
        if (fm.isStateSaved) return
        val tx = fm.beginTransaction().setReorderingAllowed(true)
        TABS.forEach { other ->
            if (other != tab) fm.findFragmentByTag(tagFor(other))?.let { tx.hide(it) }
        }
        val existing = fm.findFragmentByTag(tagFor(tab))
        if (existing != null) {
            tx.show(existing)
        } else {
            tx.add(R.id.fragmentContainer, createFragment(tab), tagFor(tab))
        }
        tx.commitAllowingStateLoss()
    }

    private fun createFragment(tab: Int): Fragment = when (tab) {
        TAB_GALLERY -> PlaceholderFragment.newInstance(R.string.home_nav_gallery)
        TAB_ALBUMS -> PlaceholderFragment.newInstance(R.string.home_nav_albums)
        else -> ForYouFragment()
    }

    private fun updateTabStyles(tab: Int) {
        val b = binding ?: return
        val tabs = listOf(
            Triple(b.indicatorForYou, b.iconForYou, b.labelForYou),
            Triple(b.indicatorGallery, b.iconGallery, b.labelGallery),
            Triple(b.indicatorAlbums, b.iconAlbums, b.labelAlbums),
        )
        tabs.forEachIndexed { index, (indicator, icon, label) ->
            val selected = index == tab
            indicator.visibility = if (selected) android.view.View.VISIBLE else android.view.View.INVISIBLE
            icon.isSelected = selected
            label.isSelected = selected
            label.setTypeface(label.typeface, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
    }

    private fun tagFor(tab: Int) = "home_tab_$tab"

    private companion object {
        const val KEY_TAB = "selected_tab"
        const val TAB_FOR_YOU = 0
        const val TAB_GALLERY = 1
        const val TAB_ALBUMS = 2
        val TABS = intArrayOf(TAB_FOR_YOU, TAB_GALLERY, TAB_ALBUMS)
    }
}
