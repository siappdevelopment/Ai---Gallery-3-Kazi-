package com.photovideo.gallerykz.memories.fragment

import com.photovideo.gallerykz.memories.ads.SlotSpec

import com.photovideo.gallerykz.memories.ads.ScreenInterAds
import com.photovideo.gallerykz.memories.ads.SwipeAds

import com.photovideo.gallerykz.memories.ads.AdSlot

import com.photovideo.gallerykz.memories.ads.AdScreen

import com.photovideo.gallerykz.memories.ads.AdHooks
import com.photovideo.gallerykz.memories.ads.AdRemoteConfig

import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import com.photovideo.gallerykz.memories.callend.CallEndPermission
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.fragment.app.activityViewModels
import com.photovideo.gallerykz.memories.R
import com.photovideo.gallerykz.memories.activity.SearchActivity
import com.photovideo.gallerykz.memories.activity.SettingActivity
import com.photovideo.gallerykz.memories.databinding.FragmentGalleryPageBinding
import com.photovideo.gallerykz.memories.viewmodel.GalleryLayoutViewModel

/** What the tabs of the gallery page ask of the page that holds them. */
interface GalleryHost {
    /** Hides (selection mode) or restores the top bar and the tab bar. */
    fun setChromeVisible(visible: Boolean)

    /** Height of the top bar while it is shown: the grid keeps this space under the selection bar so it does not jump when the bar goes. */
    fun topChromeHeight(): Int = 0

    /** Switches to the Albums tab (the Gallery's "All albums" link). */
    fun openAlbumsTab()
}

/**
 * The gallery: top bar and the For you / Gallery / Albums tabs (it opens on the middle one, Gallery). It is the left page of the launcher and also what the
 * stand-alone gallery screen shows.
 */
class GalleryPageFragment : Fragment(), GalleryHost {

    private var binding: FragmentGalleryPageBinding? = null
    private var selectedTab = DEFAULT_TAB
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
            applyBottomInsets()
            insets
        }

        // System Back: back to Home is the way back of the right swipe, so it runs the right-swipe ad (Right_Swipe_* of the Remote Config:
        // its counter, sequence and rules) first; then the Back goes on.
        AdHooks.fragmentBack(requireActivity(), viewLifecycleOwner, { activity, next -> SwipeAds.onReturn(activity, right = true, next) })

        // A tab switch counts for the bottom-navigation interstitial (every Nth switch); then the tab changes.
        b.tabForYou.setOnClickListener { switchTab(TAB_FOR_YOU) }
        b.tabGallery.setOnClickListener { switchTab(TAB_GALLERY) }
        b.tabAlbums.setOnClickListener { switchTab(TAB_ALBUMS) }
        // Each tab shows its own ad inside its content (For you, Gallery and Albums); only the Main ad sits under the tab bar.
        // The Main screen's bottom ad (`Main_*`) sits under the tab bar of this page; it follows the config while the page is open.
        mainSlot = AdSlot(
            requireActivity(), b.adMainSlot, viewLifecycleOwner, mainSpec(),
            onVisibility = { visible ->
                // While the tab bar is hidden (selection mode) the ad stays hidden too.
                if (visible && !chromeVisible) binding?.adMainSlot?.visibility = View.GONE
                applyBottomInsets()
            },
            liveSpec = { mainSpec() },
        )
        // The page is created next to Home (a pager neighbour): the ad is requested when the page is really entered (onResume), not before.
        val activityLifecycle = requireActivity().lifecycle
        val pauseCounter = object : DefaultLifecycleObserver {
            override fun onPause(owner: LifecycleOwner) {
                activityPauses++
            }
        }
        activityLifecycle.addObserver(pauseCounter)
        viewLifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) = activityLifecycle.removeObserver(pauseCounter)
        })
        b.btnLayout.setOnClickListener { showLayoutPicker() }
        b.btnSettings.setOnClickListener { startActivity(SettingActivity.intent(requireContext())) }
        b.btnSearch.setOnClickListener { startActivity(SearchActivity.intent(requireContext())) }

        selectedTab = savedInstanceState?.getInt(KEY_TAB, DEFAULT_TAB) ?: DEFAULT_TAB
        selectTab(selectedTab, force = true)
    }

    // Resumed only while this page is the one on screen (a swipe to it, or coming back to it).
    private val phoneLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { requestingPhone = false }
    private var requestingPhone = false

    override fun onResume() {
        super.onResume()
        askPhoneAccess()
        enterMainAd()
    }

    /**
     * First entry: the Main ad is requested at once, in the foreground. Coming back from Home (the activity stayed in front the whole
     * time) a place without auto refresh (`Main_Bottom_Ad_Auto_Refresh` false) loads a new ad; leaving the app or opening another
     * screen does not count, and with auto refresh on the timer of the slot does the work.
     */
    private fun enterMainAd() {
        val slot = mainSlot ?: return
        if (!mainStarted) {
            mainStarted = true
            slot.start()
        } else if (activityPauses == pausesAtLastResume && mainSpec().refreshSeconds == 0) {
            slot.refreshNow()
        }
        pausesAtLastResume = activityPauses
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
        mainStarted = false
        mainSlot = null
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
        // The ad steps aside with the tab bar and comes back as it was (no reload).
        if (!visible) {
            if (b.adMainSlot.visibility == View.VISIBLE) {
                adHiddenByChrome = true
                b.adMainSlot.visibility = View.GONE
            }
        } else if (adHiddenByChrome) {
            adHiddenByChrome = false
            b.adMainSlot.visibility = View.VISIBLE
        }
        applyBottomInsets()
        b.root.setPadding(b.root.paddingLeft, b.root.paddingTop, b.root.paddingRight, if (visible) 0 else systemBottomInset)
    }

    override fun topChromeHeight(): Int = if (chromeVisible) binding?.topBar?.height ?: 0 else 0

    override fun openAlbumsTab() = selectTab(TAB_ALBUMS)

    private fun showLayoutPicker() {
        val fm = childFragmentManager
        if (fm.isStateSaved || fm.findFragmentByTag(LayoutPickerBottomSheet.TAG) != null) return
        LayoutPickerBottomSheet().show(fm, LayoutPickerBottomSheet.TAG)
    }

    /** Shows the tab's fragment (created once, then hidden/shown so scroll positions survive). */
    private var adHiddenByChrome = false
    private var mainSlot: AdSlot? = null
    private var navElevation = -1f
    private var mainStarted = false
    private var activityPauses = 0
    private var pausesAtLastResume = 0

    private fun mainSpec(): SlotSpec = SlotSpec.of(AdRemoteConfig.config.screen(AdScreen.MAIN), "slot_main")

    /** The system navigation bar inset goes to the last thing on the page: the ad under the tab bar while it shows, else the tab bar. */
    private fun applyBottomInsets() {
        val b = binding ?: return
        val adShown = chromeVisible && b.adMainSlot.visibility == View.VISIBLE
        if (navElevation < 0f) navElevation = b.navBar.elevation
        b.navBar.setPadding(0, 0, 0, if (adShown) 0 else systemBottomInset)
        // The tab bar's shadow would fall on the ad under it; the ad is shown clean, without any shadow.
        b.navBar.elevation = if (adShown) 0f else navElevation
        b.adMainSlot.setPadding(0, 0, 0, if (adShown) systemBottomInset else 0)
    }

    private fun switchTab(tab: Int) {
        if (tab == selectedTab) return
        val activity = activity ?: return selectTab(tab)
        ScreenInterAds.onBottomNav(activity) { if (isAdded) selectTab(tab) }
    }

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

        /** The middle tab (the Gallery, `PhotoFragment`) is the one the page opens with. */
        const val DEFAULT_TAB = TAB_GALLERY
        val TABS = intArrayOf(TAB_FOR_YOU, TAB_GALLERY, TAB_ALBUMS)
    }
}
