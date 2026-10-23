package com.example.thirdgallery.activity

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.example.thirdgallery.R
import com.example.thirdgallery.databinding.ActivityHomeBinding
import com.example.thirdgallery.fragment.AlbumFragment
import com.example.thirdgallery.fragment.ForYouFragment
import com.example.thirdgallery.fragment.LayoutPickerBottomSheet
import com.example.thirdgallery.fragment.PhotoFragment

class HomeActivity : AppCompatActivity() {

    private var binding: ActivityHomeBinding? = null
    private var selectedTab = TAB_FOR_YOU
    private var systemBottomInset = 0
    private var chromeVisible = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val b = ActivityHomeBinding.inflate(layoutInflater)
        binding = b
        setContentView(b.root)

        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // The tab bar extends under the system navigation bar, so it takes the bottom inset.
            systemBottomInset = bars.bottom
            v.setPadding(bars.left, bars.top, bars.right, if (chromeVisible) 0 else bars.bottom)
            b.navBar.setPadding(0, 0, 0, bars.bottom)
            insets
        }

        b.tabForYou.setOnClickListener { selectTab(TAB_FOR_YOU) }
        b.tabGallery.setOnClickListener { selectTab(TAB_GALLERY) }
        b.tabAlbums.setOnClickListener { selectTab(TAB_ALBUMS) }
        b.btnLayout.setOnClickListener { showLayoutPicker() }

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

    /**
     * Hides (selection mode) or restores the normal top bar and the tab bar. Without the tab bar the
     * screen takes the system navigation bar inset itself, so nothing slides under it.
     */
    fun setChromeVisible(visible: Boolean) {
        if (chromeVisible == visible) return
        chromeVisible = visible
        val b = binding ?: return
        val visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE
        b.topBar.visibility = visibility
        b.navBar.visibility = visibility
        b.root.setPadding(b.root.paddingLeft, b.root.paddingTop, b.root.paddingRight, if (visible) 0 else systemBottomInset)
    }

    /** Switches to the Albums tab (used by the Gallery's "All albums" link). */
    fun openAlbumsTab() = selectTab(TAB_ALBUMS)

    private fun showLayoutPicker() {
        val fm = supportFragmentManager
        if (fm.isStateSaved || fm.findFragmentByTag(LayoutPickerBottomSheet.TAG) != null) return
        LayoutPickerBottomSheet().show(fm, LayoutPickerBottomSheet.TAG)
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
        TAB_GALLERY -> PhotoFragment()
        TAB_ALBUMS -> AlbumFragment()
        else -> ForYouFragment()
    }

    private fun updateTabStyles(tab: Int) {
        val b = binding ?: return
        // The layout picker only applies to the Gallery tab.
        b.btnLayout.visibility = if (tab == TAB_GALLERY) android.view.View.VISIBLE else android.view.View.GONE
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
