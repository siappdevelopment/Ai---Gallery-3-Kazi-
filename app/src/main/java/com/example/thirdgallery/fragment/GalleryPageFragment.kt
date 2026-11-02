package com.example.thirdgallery.fragment

import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import com.example.thirdgallery.callend.CallEndPermission
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.example.thirdgallery.R
import com.example.thirdgallery.activity.SearchActivity
import com.example.thirdgallery.activity.SettingActivity
import com.example.thirdgallery.databinding.FragmentGalleryPageBinding
import com.example.thirdgallery.viewmodel.GalleryLayoutViewModel

/** What the tabs of the gallery page ask of the page that holds them. */
interface GalleryHost {
    /** Hides (selection mode) or restores the top bar and the tab bar. */
    fun setChromeVisible(visible: Boolean)

    /** Switches to the Albums tab (the Gallery's "All albums" link). */
    fun openAlbumsTab()
}

/**
 * The gallery: top bar and the For you / Gallery / Albums tabs. It is the left page of the launcher and also what the
 * stand-alone gallery screen shows.
 */
class GalleryPageFragment : Fragment(), GalleryHost {

    private var binding: FragmentGalleryPageBinding? = null
    private var selectedTab = TAB_FOR_YOU
    private val galleryLayout: GalleryLayoutViewModel by activityViewModels()
    private var systemBottomInset = 0
    private var chromeVisible = true

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val b = FragmentGalleryPageBinding.inflate(inflater, container, false)
        binding = b
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = binding ?: return
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
        b.btnSettings.setOnClickListener { startActivity(SettingActivity.intent(requireContext())) }
        b.btnSearch.setOnClickListener { startActivity(SearchActivity.intent(requireContext())) }

        selectedTab = savedInstanceState?.getInt(KEY_TAB, TAB_FOR_YOU) ?: TAB_FOR_YOU
        selectTab(selectedTab, force = true)
    }

    // Resumed only while this page is the one on screen (a swipe to it, or coming back to it).
    private val phoneLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { requestingPhone = false }
    private var requestingPhone = false

    override fun onResume() {
        super.onResume()
        askPhoneAccess()
    }

    /** Asks for the call permission when the page is entered and it is not granted yet; at most once per app start. */
    private fun askPhoneAccess() {
        val context = context ?: return
        if (requestingPhone || askedThisStart || CallEndPermission.isGranted(context)) return
        askedThisStart = true
        requestingPhone = true
        try {
            phoneLauncher.launch(CallEndPermission.PERMISSION)
        } catch (e: Exception) {
            requestingPhone = false
        }
    }

    override fun onStart() {
        super.onStart()
        // Settings may have changed the number of columns while the Gallery tab was behind it.
        galleryLayout.reload()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, selectedTab)
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    override fun setChromeVisible(visible: Boolean) {
        if (chromeVisible == visible) return
        chromeVisible = visible
        val b = binding ?: return
        val visibility = if (visible) View.VISIBLE else View.GONE
        b.topBar.visibility = visibility
        b.navBar.visibility = visibility
        b.root.setPadding(b.root.paddingLeft, b.root.paddingTop, b.root.paddingRight, if (visible) 0 else systemBottomInset)
    }

    override fun openAlbumsTab() = selectTab(TAB_ALBUMS)

    private fun showLayoutPicker() {
        val fm = childFragmentManager
        if (fm.isStateSaved || fm.findFragmentByTag(LayoutPickerBottomSheet.TAG) != null) return
        LayoutPickerBottomSheet().show(fm, LayoutPickerBottomSheet.TAG)
    }

    /** Shows the tab's fragment (created once, then hidden/shown so scroll positions survive). */
    private fun selectTab(tab: Int, force: Boolean = false) {
        if (!force && tab == selectedTab) return
        selectedTab = tab
        updateTabStyles(tab)

        val fm = childFragmentManager
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
        b.btnLayout.visibility = if (tab == TAB_GALLERY) View.VISIBLE else View.GONE
        val tabs = listOf(
            Triple(b.indicatorForYou, b.iconForYou, b.labelForYou),
            Triple(b.indicatorGallery, b.iconGallery, b.labelGallery),
            Triple(b.indicatorAlbums, b.iconAlbums, b.labelAlbums),
        )
        tabs.forEachIndexed { index, (indicator, icon, label) ->
            val selected = index == tab
            indicator.visibility = if (selected) View.VISIBLE else View.INVISIBLE
            icon.isSelected = selected
            label.isSelected = selected
            label.setTypeface(label.typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun tagFor(tab: Int) = "home_tab_$tab"

    private companion object {
        /** The call permission is asked on the gallery page once per app start (the system also stops showing it after repeated denials). */
        @Volatile
        var askedThisStart = false

        const val KEY_TAB = "selected_tab"
        const val TAB_FOR_YOU = 0
        const val TAB_GALLERY = 1
        const val TAB_ALBUMS = 2
        val TABS = intArrayOf(TAB_FOR_YOU, TAB_GALLERY, TAB_ALBUMS)
    }
}
