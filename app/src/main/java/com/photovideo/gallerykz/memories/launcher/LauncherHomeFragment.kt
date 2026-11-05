package com.photovideo.gallerykz.memories.launcher

import com.photovideo.gallerykz.memories.ads.SlotSpec

import com.photovideo.gallerykz.memories.ads.LauncherAds

import com.photovideo.gallerykz.memories.ads.AdSlot

import com.photovideo.gallerykz.memories.ads.AdScreen

import com.photovideo.gallerykz.memories.ads.AdRemoteConfig

import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import com.bumptech.glide.Glide
import com.photovideo.gallerykz.memories.R
import com.photovideo.gallerykz.memories.activity.HighlightsDetailActivity
import com.photovideo.gallerykz.memories.activity.VaultMediaActivity
import com.photovideo.gallerykz.memories.activity.VaultSetupActivity
import com.photovideo.gallerykz.memories.databinding.DialogLauncherFolderBinding
import com.photovideo.gallerykz.memories.databinding.FragmentLauncherHomeBinding
import com.photovideo.gallerykz.memories.helper.HighlightLabels
import com.photovideo.gallerykz.memories.helper.MediaUris
import com.photovideo.gallerykz.memories.helper.VaultSession
import com.photovideo.gallerykz.memories.helper.clearImage
import com.photovideo.gallerykz.memories.model.HighlightCard
import com.photovideo.gallerykz.memories.repo.VaultRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The home page of the launcher, over the wallpaper (the launcher_set design): Google search bar with voice search, the
 * clock with the Memories card, the gallery and Photo vault tabs on the screen edges, the "Make us your default" card
 * while this app is not the Home app, the Google and Tools folders with the Calendar and Launcher shortcuts, the swipe-up
 * arrow, and the dock (contacts, phone, messages, browser) resolved from the apps the phone really has.
 */
class LauncherHomeFragment : Fragment(), LauncherSettings.Listener, LauncherApps.Listener {

    private class DockSlot(val view: ImageView, val find: (Context) -> ShortcutTarget?) {
        var target: ShortcutTarget? = null
    }

    private var _binding: FragmentLauncherHomeBinding? = null
    private val binding get() = _binding!!
    private val host get() = activity as? LauncherHost
    private val memoryViewModel: LauncherMemoryViewModel by viewModels()

    private var dock: List<DockSlot> = emptyList()
    private var calendar: ShortcutTarget? = null
    private var googleApps: List<LauncherApp> = emptyList()
    private var toolsApps: List<LauncherApp> = emptyList()
    private var folderDialog: Dialog? = null
    private var lastShortcutAt = 0L
    private var memory: HighlightCard? = null
    private var clockRegistered = false

    private val clockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = updateDateTime()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLauncherHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = binding
        // This page is over the wallpaper, so it keeps clear of the system bars itself.
        ViewCompat.setOnApplyWindowInsetsListener(b.homeRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        // The clock is filled at once, so the first frame is never empty.
        updateDateTime()

        dock = listOf(
            DockSlot(b.ivAppIconContacts, LauncherShortcuts::contacts),
            DockSlot(b.ivAppIcon1, LauncherShortcuts::dialer),
            DockSlot(b.ivAppIcon2, LauncherShortcuts::sms),
            DockSlot(b.ivAppIcon3, LauncherShortcuts::browser),
        )
        dock.forEach { slot -> slot.view.setOnClickListener { slot.target?.let(::start) } }

        b.llGoogleSearch.setOnClickListener { openGoogle() }
        b.ivGoogleVoiceSearch.setOnClickListener { voiceSearch() }
        b.promoDefault.btnSetDefault.setOnClickListener { host?.requestDefaultLauncher() }
        b.rlQuickAction.setOnClickListener { host?.openGallery() }
        b.rlVaultAction.setOnClickListener { openVault() }
        b.llGoogleFolder.setOnClickListener { showFolder(getString(R.string.launcher_folder_google), googleApps) }
        b.llToolsFolder.setOnClickListener { showFolder(getString(R.string.launcher_folder_tools), toolsApps) }
        b.llTopApp1.setOnClickListener { calendar?.let(::start) }
        b.llTopApp2.setOnClickListener { startActivity(LauncherSettingsActivity.intent(requireContext())) }
        b.ivSwipeUp.setOnClickListener { host?.openDrawer() }
        b.cardMemories.setOnClickListener { openMemory() }

        b.promoDefault.lavSetAsDefaultTap.setFailureListener {
            // Only the pointing finger is lost; the card stays usable.
            _binding?.promoDefault?.lavSetAsDefaultTap?.visibility = View.GONE
        }
        memoryViewModel.card.observe(viewLifecycleOwner) { showMemory(it) }

        LauncherSettings.register(this)
        LauncherApps.register(this)
    }

    override fun onResume() {
        super.onResume()
        startClock()
        // The default phone, messages or browser app may have changed while another app was in front.
        loadShortcuts()
        loadFolders()
        applySettings()
        memoryViewModel.refresh()
        refreshDefaultPromo()
    }

    override fun onPause() {
        stopClock()
        folderDialog?.dismiss()
        _binding?.promoDefault?.lavSetAsDefaultTap?.pauseAnimation()
        super.onPause()
    }

    override fun onDestroyView() {
        LauncherSettings.unregister(this)
        LauncherApps.unregister(this)
        folderDialog?.dismiss()
        folderDialog = null
        _binding?.let {
            it.promoDefault.lavSetAsDefaultTap.cancelAnimation()
            clearImage(it.ivMemoryCover)
        }
        _binding = null
        super.onDestroyView()
    }

    override fun onLauncherSettingsChanged() {
        if (_binding != null && isAdded) applySettings()
    }

    override fun onAppsChanged(apps: List<LauncherApp>) {
        if (_binding != null && isAdded) {
            loadFolders()
            applySettings()
        }
    }

    /** Closes a folder pop-up that is open (the Home key, a new intent). */
    fun dismissTransientUi() {
        folderDialog?.dismiss()
    }

    fun refreshDefaultPromo() {
        val b = _binding ?: return
        val promo = b.promoDefault
        val show = !LauncherNav.isDefaultHome(requireContext())
        promo.root.visibility = if (show) View.VISIBLE else View.GONE
        // The pointing finger plays while the card is there and rests otherwise.
        val finger = promo.lavSetAsDefaultTap
        if (show) {
            if (!finger.isAnimating) finger.playAnimation()
        } else {
            finger.cancelAnimation()
        }
    }

    // ---- clock ----

    private fun startClock() {
        if (clockRegistered) return
        val context = context ?: return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        }
        ContextCompat.registerReceiver(context, clockReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        clockRegistered = true
        updateDateTime()
    }

    private fun stopClock() {
        if (!clockRegistered) return
        clockRegistered = false
        try {
            context?.unregisterReceiver(clockReceiver)
        } catch (e: IllegalArgumentException) {
            // Already gone.
        }
    }

    /** "2:15 PM" (the AM/PM part small), the day and the date, in English like the design. */
    private fun updateDateTime() {
        val b = _binding ?: return
        val now = Date()
        val time = SimpleDateFormat("h:mm a", Locale.ENGLISH).format(now).uppercase(Locale.ENGLISH)
        val spaced = time.lastIndexOf(' ')
        b.tvTime.text = if (spaced > 0) {
            SpannableStringBuilder(time).apply {
                setSpan(RelativeSizeSpan(0.45f), spaced, time.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } else {
            time
        }
        b.tvDay.text = SimpleDateFormat("EEEE", Locale.ENGLISH).format(now)
        b.tvDate.text = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH).format(now)
    }

    // ---- memories ----

    /** Today's memory: its cover, name and number of photos; a quiet line when there is none. */
    private fun showMemory(card: HighlightCard?) {
        val b = _binding ?: return
        memory = card
        val context = context ?: return
        if (card == null) {
            clearImage(b.ivMemoryCover)
            b.ivMemoryCover.setImageDrawable(null)
            b.tvMemoryTitle.setText(R.string.launcher_memories)
            b.tvMemoryCount.setText(R.string.launcher_memories_empty)
            return
        }
        b.tvMemoryTitle.text = HighlightLabels.title(context, card)
        b.tvMemoryCount.text = resources.getQuantityString(R.plurals.intro2_photos, card.count, card.count)
        val cover = card.uids.firstOrNull()
        if (cover == null) {
            clearImage(b.ivMemoryCover)
            b.ivMemoryCover.setImageDrawable(null)
        } else {
            Glide.with(b.ivMemoryCover).load(MediaUris.ofUid(cover)).override(COVER_PX).centerCrop().dontAnimate().into(b.ivMemoryCover)
        }
    }

    private fun openMemory() {
        if (!canLaunchShortcut()) return
        val card = memory
        if (card == null) host?.openGallery() else startActivity(HighlightsDetailActivity.intent(requireContext(), card))
    }

    /** The vault opens straight away while it is unlocked (auto-lock), otherwise through the PIN screen. */
    private fun openVault() {
        if (!canLaunchShortcut()) return
        val context = requireContext()
        val unlocked = VaultSession.isOpen(context) && VaultRepository(context).isSetUp
        startActivity(if (unlocked) VaultMediaActivity.intent(context) else VaultSetupActivity.intent(context))
    }

    // ---- data ----

    private fun loadShortcuts() {
        val context = requireContext()
        // On some phones one app is both the Contacts app and the default Phone dialer (for example Xiaomi's), so two slots would
        // show the same icon. An app already in the dock is not added a second time: its first slot stays, the later one is hidden.
        // Xiaomi / Redmi phones make this go wrong more than others: their default dialer and their Contacts app can be two different
        // packages (or one package with two entries) that carry the same "Contacts" name and the same picture. So an app counts as
        // already in the dock when its package, its name or its picture equals one of the earlier slots.
        val found = dock.map { it.find(context) }
        val shown = DockDedupe.shown(found.map { target -> target?.let { DockCandidate(it.packageName, it.label, pictureKey(it.icon)) } })
        dock.forEachIndexed { index, slot ->
            val target = found[index]
            android.util.Log.d("LauncherDock", "slot $index: ${target?.packageName} '${target?.label}'" + if (target != null && !shown[index]) " (duplicate, hidden)" else "")
            slot.target = if (shown[index]) target else null
        }
        calendar = LauncherShortcuts.calendar(context)
    }

    /** A fingerprint of how an icon looks (its picture at 16 px), so two apps with the same icon can be told apart from different ones. */
    private fun pictureKey(icon: android.graphics.drawable.Drawable): Int? = try {
        val copy = icon.constantState?.newDrawable()?.mutate() ?: icon.mutate()
        val size = 16
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        copy.setBounds(0, 0, size, size)
        copy.draw(android.graphics.Canvas(bitmap))
        val pixels = IntArray(size * size)
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
        bitmap.recycle()
        pixels.contentHashCode()
    } catch (e: Exception) {
        null
    }

    private fun loadFolders() {
        // findNow: the folders must not wait for the list of every installed app (right after the onboarding it is still
        // loading, which made the folders appear a second late).
        val context = context ?: return
        googleApps = LauncherShortcuts.GOOGLE_PACKAGES.mapNotNull { LauncherApps.findNow(context, it) }
        toolsApps = LauncherShortcuts.TOOLS_GROUPS.mapNotNull { group -> group.firstNotNullOfOrNull { LauncherApps.findNow(context, it) } }
    }

    // ---- sizes and look ----

    /** Applies icon size, name size and name visibility to everything on the page. */
    private fun applySettings() {
        val b = _binding ?: return
        val context = requireContext()
        val size = LauncherSettings.iconPx(context)
        val labelSp = LauncherSettings.getLabelSize(context).toFloat()
        val labels = LauncherSettings.isLabelVisible(context)
        val corner = size * LauncherIcons.CORNER_RATIO

        fun sizeOf(view: View, px: Int) {
            view.layoutParams = view.layoutParams.apply {
                width = px
                height = px
            }
        }

        // Folders: a card with a 3x3 preview; a folder without apps is hidden.
        bindFolder(b.cvGoogleFolder, b.gvGoogleApps, googleApps, size, corner)
        bindFolder(b.cvToolsFolder, b.gvToolsApps, toolsApps, size, corner)
        b.llGoogleFolder.visibility = if (googleApps.isEmpty()) View.GONE else View.VISIBLE
        b.llToolsFolder.visibility = if (toolsApps.isEmpty()) View.GONE else View.VISIBLE

        // Calendar: the system app; its slot stays (empty) when the phone has none, so the row keeps its places.
        val cal = calendar
        b.llTopApp1.visibility = if (cal == null) View.INVISIBLE else View.VISIBLE
        if (cal != null) {
            sizeOf(b.ivTopAppIcon1, size)
            LauncherIcons.bind(b.ivTopAppIcon1, cal.icon, size)
            b.tvTopAppName1.text = cal.label
        }

        // Launcher settings shortcut.
        sizeOf(b.ivTopAppIcon2, size)
        b.tvTopAppName2.text = "Launcher" // always English, never translated

        listOf(b.tvGoogle, b.tvTools, b.tvTopAppName1, b.tvTopAppName2).forEach {
            it.setTextSize(TypedValue.COMPLEX_UNIT_SP, labelSp)
            it.visibility = if (labels) View.VISIBLE else View.GONE
        }

        // Dock: a slot without an app is hidden.
        dock.forEach { slot ->
            val target = slot.target
            val slotView = slot.view.parent as? View
            if (target == null) {
                slotView?.visibility = View.GONE
            } else {
                slotView?.visibility = View.VISIBLE
                sizeOf(slot.view, size)
                LauncherIcons.bind(slot.view, target.icon, size)
            }
        }
    }

    private fun bindFolder(card: androidx.cardview.widget.CardView, grid: GridLayout, apps: List<LauncherApp>, size: Int, corner: Float) {
        card.layoutParams = card.layoutParams.apply {
            width = size
            height = size
        }
        card.radius = corner
        grid.removeAllViews()
        val density = resources.displayMetrics.density
        val pad = maxOf((2 * density).toInt(), size * 4 / 50)
        val margin = density.toInt().coerceAtLeast(1)
        val icon = ((size - 2 * pad - 6 * margin) / FOLDER_COLUMNS).coerceAtLeast((6 * density).toInt())
        grid.setPadding(pad, pad, pad, pad)
        apps.take(FOLDER_MAX_ICONS).forEach { app ->
            val image = ImageView(requireContext())
            image.layoutParams = GridLayout.LayoutParams().apply {
                width = icon
                height = icon
                setMargins(margin, margin, margin, margin)
            }
            LauncherIcons.bind(image, app.icon, icon)
            grid.addView(image)
        }
    }

    // ---- actions ----

    /** Starts a shortcut and remembers it for the Recent page. */
    /** An app of the home page is tapped: the tap ad (every Nth tap) may come first. */
    private fun start(target: ShortcutTarget) {
        val activity = activity ?: return
        LauncherAds.onAppTap(activity) { launchTarget(target) }
    }

    private fun launchTarget(target: ShortcutTarget) {
        val context = context ?: return
        try {
            startActivity(Intent(target.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            RecentAppsStore.record(context, target.packageName)
        } catch (e: Exception) {
            toast(R.string.launcher_cannot_open)
        }
    }

    /** Debounces taps that open another app, so a double tap cannot start it twice. */
    private fun canLaunchShortcut(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastShortcutAt < SHORTCUT_DEBOUNCE_MS) return false
        lastShortcutAt = now
        return true
    }

    private fun openGoogle() {
        if (!canLaunchShortcut()) return
        val context = requireContext()
        val launch = context.packageManager.getLaunchIntentForPackage(LauncherShortcuts.GOOGLE_APP)
        val intent = launch ?: Intent(android.speech.RecognizerIntent.ACTION_WEB_SEARCH)
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            toast(R.string.launcher_cannot_open)
        }
    }

    private fun voiceSearch() {
        if (!canLaunchShortcut()) return
        val context = requireContext()
        for (intent in LauncherShortcuts.voiceSearchIntents()) {
            if (intent.resolveActivity(context.packageManager) == null) continue
            try {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) {
                // Try the next one.
            }
        }
        toast(R.string.launcher_cannot_open)
    }

    private fun showFolder(title: String, apps: List<LauncherApp>) {
        if (apps.isEmpty() || folderDialog?.isShowing == true) return
        val context = requireContext()
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val d = DialogLauncherFolderBinding.inflate(layoutInflater)
        d.tvTitle.text = title
        val adapter = LauncherAppsAdapter(onClick = { app ->
            dialog.dismiss()
            val host = activity
            if (host == null) {
                LauncherApps.launch(context, app)
            } else {
                LauncherAds.onAppTap(host) { if (!LauncherApps.launch(context, app)) toast(R.string.launcher_cannot_open) }
            }
        })
        d.rvApps.layoutManager = GridLayoutManager(context, FOLDER_COLUMNS)
        d.rvApps.adapter = adapter
        adapter.applySettings(context)
        adapter.submitList(apps)
        dialog.setContentView(d.root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            decorView.setPadding((16 * resources.displayMetrics.density).toInt(), 0, (16 * resources.displayMetrics.density).toInt(), 0)
        }
        dialog.setOnDismissListener { if (folderDialog === dialog) folderDialog = null }
        folderDialog = dialog
        dialog.show()
    }

    private fun toast(res: Int) {
        context?.applicationContext?.let { Toast.makeText(it, res, Toast.LENGTH_SHORT).show() }
    }

    private companion object {
        const val FOLDER_COLUMNS = 3
        const val FOLDER_MAX_ICONS = 9
        const val SHORTCUT_DEBOUNCE_MS = 800L
        const val COVER_PX = 400
    }
}
