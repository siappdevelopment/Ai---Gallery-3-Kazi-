package com.example.thirdgallery.activity

import android.app.PendingIntent
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import android.content.res.ColorStateList
import com.example.thirdgallery.R
import com.example.thirdgallery.adapter.ViewerPagerAdapter
import com.example.thirdgallery.database.MediaEntity
import com.example.thirdgallery.database.MediaTypes
import com.example.thirdgallery.fragment.AddToAlbumBottomSheet
import com.example.thirdgallery.fragment.ConfirmDialogFragment
import com.example.thirdgallery.fragment.MoreOptionsBottomSheet
import com.example.thirdgallery.fragment.RenameBottomSheet
import com.example.thirdgallery.viewmodel.RenameEvent
import com.example.thirdgallery.fragment.DetailsDialogFragment
import com.example.thirdgallery.repo.MediaRepository
import com.example.thirdgallery.databinding.ActivityFullScreenMediaBinding
import com.example.thirdgallery.helper.MediaFormat
import com.example.thirdgallery.helper.MediaUris
import com.example.thirdgallery.helper.ShareHelper
import com.example.thirdgallery.model.AlbumKind
import com.example.thirdgallery.model.MediaFilter
import com.example.thirdgallery.viewmodel.FullScreenMediaViewModel
import java.util.Date

/**
 * Photo viewer (design 34): swipe through the items of the album or gallery the tapped item came
 * from, with a top bar (date, position), previous / next buttons and Share / Edit / Favorite /
 * Delete / More at the bottom.
 */
class FullScreenMediaActivity : AppCompatActivity() {

    private var binding: ActivityFullScreenMediaBinding? = null
    private val viewModel: FullScreenMediaViewModel by viewModels()

    private var pagerAdapter: ViewerPagerAdapter? = null
    private var pageManager: PagerLayoutManager? = null
    private var scrollListener: RecyclerView.OnScrollListener? = null
    private var uids: List<Long> = emptyList()
    private var position = 0
    private var chromeVisible = true

    /** What to do once the system confirmation (favorite / delete) was accepted. */
    private var pendingAction: (() -> Unit)? = null

    private val requestLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val action = pendingAction
            pendingAction = null
            if (result.resultCode == RESULT_OK) {
                // After a recreation the follow-up is gone; re-reading the index brings it back in line.
                if (action != null) action() else MediaRepository(applicationContext).sync(true) { }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )

        val b = ActivityFullScreenMediaBinding.inflate(layoutInflater)
        binding = b
        setContentView(b.root)
        chromeVisible = savedInstanceState?.getBoolean(KEY_CHROME, true) ?: true
        applyChrome()

        // The bars sit under the system bars, so they take the insets as extra padding.
        val topBase = b.topBar.paddingTop
        val bottomBase = b.bottomBar.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            b.topBar.setPadding(b.topBar.paddingLeft, topBase + bars.top, b.topBar.paddingRight, b.topBar.paddingBottom)
            b.bottomBar.setPadding(b.bottomBar.paddingLeft, b.bottomBar.paddingTop, b.bottomBar.paddingRight, bottomBase + bars.bottom)
            insets
        }

        val adapter = ViewerPagerAdapter(
            onToggleChrome = { toggleChrome() },
            onZoomChanged = { zoomed -> pageManager?.swipeEnabled = !zoomed },
            onPlay = { playVideo(it) },
        )
        pagerAdapter = adapter
        val manager = PagerLayoutManager(this)
        pageManager = manager
        b.pager.layoutManager = manager
        b.pager.adapter = adapter
        b.pager.itemAnimator = null
        b.pager.setItemViewCacheSize(1)
        val snap = PagerSnapHelper()
        snap.attachToRecyclerView(b.pager)
        val listener = object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return
                val page = snap.findSnapView(manager) ?: return
                val index = manager.getPosition(page)
                if (index != position) showPage(index)
            }
        }
        scrollListener = listener
        b.pager.addOnScrollListener(listener)

        b.btnBack.setOnClickListener { finish() }
        b.btnPrev.setOnClickListener { b.pager.smoothScrollToPosition((position - 1).coerceAtLeast(0)) }
        b.btnNext.setOnClickListener { b.pager.smoothScrollToPosition((position + 1).coerceAtMost(uids.size - 1)) }
        val comingSoon = View.OnClickListener {
            Toast.makeText(applicationContext, R.string.albums_coming_soon, Toast.LENGTH_SHORT).show()
        }
        b.btnMoreTop.setOnClickListener { MoreOptionsBottomSheet.show(supportFragmentManager) }
        b.actionEdit.setOnClickListener(comingSoon)
        b.actionDetails.setOnClickListener { showDetails() }
        b.actionShare.setOnClickListener { share() }
        b.actionFavorite.setOnClickListener { toggleFavorite() }
        b.actionDelete.setOnClickListener { delete() }

        supportFragmentManager.setFragmentResultListener(MoreOptionsBottomSheet.RESULT_KEY, this) { _, result ->
            onMoreOption(result.getString(MoreOptionsBottomSheet.RESULT_ACTION).orEmpty())
        }
        supportFragmentManager.setFragmentResultListener(RenameBottomSheet.RESULT_KEY, this) { _, result ->
            result.getString(RenameBottomSheet.RESULT_NAME)?.let { startRename(it) }
        }
        supportFragmentManager.setFragmentResultListener(AddToAlbumBottomSheet.RESULT_KEY, this) { _, result ->
            // The item left the album or folder being viewed: it no longer belongs to this swipe list.
            val leftAlbum = result.getBoolean(AddToAlbumBottomSheet.RESULT_LEFT_ALBUM) && viewModel.sourceAlbumId > 0
            val leftFolder = result.getBoolean(AddToAlbumBottomSheet.RESULT_LEFT_FOLDER) && viewModel.sourceFolderId > 0
            if (leftAlbum || leftFolder) {
                uids.getOrNull(position)?.let { viewModel.forget(it) }
            }
        }
        viewModel.renameName.observe(this) { event ->
            event.getContentIfNotHandled()?.let { RenameBottomSheet.show(supportFragmentManager, it) }
        }
        viewModel.rename.observe(this) { event -> event.getContentIfNotHandled()?.let { onRenameResult(it) } }
        supportFragmentManager.setFragmentResultListener(KEY_DELETE, this) { _, result ->
            if (result.getBoolean(ConfirmDialogFragment.RESULT_CONFIRMED)) moveToBin()
        }

        viewModel.uids.observe(this) { list -> onList(list.orEmpty()) }
        viewModel.current.observe(this) { render(it) }
        viewModel.details.observe(this) { event ->
            event.getContentIfNotHandled()?.let { DetailsDialogFragment.show(supportFragmentManager, it) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_CHROME, chromeVisible)
    }

    override fun onDestroy() {
        scrollListener?.let { binding?.pager?.removeOnScrollListener(it) }
        scrollListener = null
        pageManager = null
        pagerAdapter = null
        binding = null
        super.onDestroy()
    }

    private fun onList(list: List<Long>) {
        val b = binding ?: return
        if (list.isEmpty()) {
            finish()
            return
        }
        val previous = viewModel.currentUid
        uids = list
        pagerAdapter?.submit(list)
        // Stay on the same item; when it is gone (deleted) take the one that moved into its place.
        val target = list.indexOf(previous).takeIf { it >= 0 } ?: position.coerceIn(0, list.size - 1)
        b.pager.scrollToPosition(target)
        showPage(target)
    }

    private fun showPage(index: Int) {
        val uid = uids.getOrNull(index) ?: return
        position = index
        viewModel.select(uid)
        updateNavigation()
        render(viewModel.current.value?.takeIf { it.uid == uid })
    }

    private fun updateNavigation() {
        val b = binding ?: return
        b.btnPrev.visibility = if (chromeVisible && position > 0) View.VISIBLE else View.GONE
        b.btnNext.visibility = if (chromeVisible && position < uids.size - 1) View.VISIBLE else View.GONE
    }

    private fun render(media: MediaEntity?) {
        val b = binding ?: return
        if (media == null || media.uid != uids.getOrNull(position)) return
        b.tvDate.text = MediaFormat.dayTitle(this, media.dayKey)
        val time = android.text.format.DateFormat.getTimeFormat(this).format(Date(media.dateMs))
        b.tvMeta.text = getString(R.string.viewer_meta, time, getString(R.string.viewer_position, position + 1, uids.size))
        b.ivFavorite.setImageResource(if (media.isFavorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border)
        val tint = if (media.isFavorite) R.color.brand_pink else R.color.white
        ImageViewCompat.setImageTintList(b.ivFavorite, ColorStateList.valueOf(ContextCompat.getColor(this, tint)))
    }

    private fun toggleChrome() {
        chromeVisible = !chromeVisible
        applyChrome()
    }

    private fun applyChrome() {
        val b = binding ?: return
        val visibility = if (chromeVisible) View.VISIBLE else View.GONE
        b.topBar.visibility = visibility
        b.bottomBar.visibility = visibility
        updateNavigation()
    }

    private fun current(): MediaEntity? =
        viewModel.current.value?.takeIf { it.uid == uids.getOrNull(position) }

    /** Details of the item that is on screen right now (read in the background, then shown). */
    private fun showDetails() {
        current()?.let { viewModel.loadDetails(it) }
    }

    private fun onMoreOption(action: String) {
        val media = current() ?: return
        when (action) {
            MoreOptionsBottomSheet.ACTION_ADD -> showAlbumSheet(media, move = false, copy = false, titleRes = 0)
            MoreOptionsBottomSheet.ACTION_MOVE -> showAlbumSheet(media, move = true, copy = false, titleRes = R.string.viewer_move_to_album)
            MoreOptionsBottomSheet.ACTION_COPY -> showAlbumSheet(media, move = false, copy = true, titleRes = R.string.viewer_copy_to_album)
            MoreOptionsBottomSheet.ACTION_RENAME -> viewModel.requestRename(media)
            MoreOptionsBottomSheet.ACTION_WALLPAPER -> setWallpaper(media)
            MoreOptionsBottomSheet.ACTION_VAULT ->
                Toast.makeText(applicationContext, R.string.albums_coming_soon, Toast.LENGTH_SHORT).show()
        }
    }

    /** The shared album picker: Add / Move / Copy only differ in title and in what happens on pick. */
    private fun showAlbumSheet(media: MediaEntity, move: Boolean, copy: Boolean, titleRes: Int) {
        val fm = supportFragmentManager
        if (fm.isStateSaved || fm.findFragmentByTag(AddToAlbumBottomSheet.TAG) != null) return
        AddToAlbumBottomSheet.newForSelection(listOf(media.uid), move, viewModel.sourceAlbumId, titleRes, copy)
            .show(fm, AddToAlbumBottomSheet.TAG)
    }

    /** Android 11+ asks for write access first; Android 10 asks (if needed) after the first try; older: directly. */
    private fun startRename(newName: String) {
        val media = current() ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val uri = MediaUris.of(media.mediaId, media.mediaType)
            launchRequest(MediaStore.createWriteRequest(contentResolver, listOf(uri))) {
                viewModel.rename(media.uid, newName)
            }
        } else {
            viewModel.rename(media.uid, newName)
        }
    }

    private fun onRenameResult(result: RenameEvent) {
        when (result) {
            RenameEvent.Done ->
                Toast.makeText(applicationContext, R.string.viewer_renamed, Toast.LENGTH_SHORT).show()
            is RenameEvent.Consent -> {
                pendingAction = { viewModel.retryRename() }
                requestLauncher.launch(IntentSenderRequest.Builder(result.sender).build())
            }
            is RenameEvent.Failed ->
                Toast.makeText(applicationContext, result.message, Toast.LENGTH_SHORT).show()
        }
    }

    /** Photos open the system "crop and set wallpaper" screen; videos are not supported. */
    private fun setWallpaper(media: MediaEntity) {
        if (media.mediaType != MediaTypes.IMAGE) {
            Toast.makeText(applicationContext, R.string.viewer_wallpaper_photos_only, Toast.LENGTH_SHORT).show()
            return
        }
        val uri = MediaUris.of(media.mediaId, media.mediaType)
        val crop = try {
            WallpaperManager.getInstance(this).getCropAndSetWallpaperIntent(uri)
        } catch (e: IllegalArgumentException) {
            null
        }
        val intent = crop ?: Intent.createChooser(
            Intent(Intent.ACTION_ATTACH_DATA).setDataAndType(uri, "image/*").putExtra("mimeType", "image/*"),
            getString(R.string.viewer_wallpaper),
        )
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(applicationContext, R.string.viewer_unsupported, Toast.LENGTH_SHORT).show()
        }
    }

    private fun share() {
        val uid = uids.getOrNull(position) ?: return
        ShareHelper.share(this, listOf(uid)) {
            Toast.makeText(applicationContext, R.string.photo_share_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleFavorite() {
        val media = current() ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Toast.makeText(applicationContext, R.string.viewer_unsupported, Toast.LENGTH_SHORT).show()
            return
        }
        val target = !media.isFavorite
        val uri = MediaUris.of(media.mediaId, media.mediaType)
        launchRequest(MediaStore.createFavoriteRequest(contentResolver, listOf(uri), target)) {
            viewModel.setFavorite(media.uid, target)
        }
    }

    /** Delete photo dialog (design 85); the system trash is Android 11+. */
    private fun delete() {
        val media = current() ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Toast.makeText(applicationContext, R.string.viewer_unsupported, Toast.LENGTH_SHORT).show()
            return
        }
        val isVideo = media.mediaType == MediaTypes.VIDEO
        ConfirmDialogFragment.show(
            supportFragmentManager,
            KEY_DELETE,
            R.drawable.ic_delete,
            getString(if (isVideo) R.string.delete_video_title else R.string.delete_photo_title),
            getString(R.string.delete_body),
            getString(R.string.delete_move_to_bin),
        )
    }

    /** The user confirmed: ask the system to move the item to the Bin, then drop it from the gallery. */
    private fun moveToBin() {
        val media = current() ?: return
        val uri = MediaUris.of(media.mediaId, media.mediaType)
        launchRequest(MediaStore.createTrashRequest(contentResolver, listOf(uri), true)) {
            viewModel.remove(media.uid)
            Toast.makeText(applicationContext, R.string.bin_moved, Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchRequest(request: PendingIntent, onAccepted: () -> Unit) {
        pendingAction = onAccepted
        requestLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
    }

    private fun playVideo(uid: Long) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(MediaUris.ofUid(uid), "video/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(applicationContext, R.string.viewer_unsupported, Toast.LENGTH_SHORT).show()
        }
    }

    /** Horizontal pager whose swiping can be switched off while a page is zoomed. */
    private class PagerLayoutManager(context: Context) : LinearLayoutManager(context, HORIZONTAL, false) {
        var swipeEnabled = true
        override fun canScrollHorizontally() = swipeEnabled && super.canScrollHorizontally()
    }

    companion object {
        private const val KEY_CHROME = "chrome_visible"
        private const val KEY_DELETE = "delete_media"

        /**
         * Opens [uid] inside the list it was tapped in: a device folder / smart collection / app album
         * ([kind] + [id]), or the whole gallery ([AlbumKind.DEVICE] with id 0) under [filter].
         */
        fun intent(
            context: Context,
            uid: Long,
            kind: AlbumKind,
            id: Long,
            filter: MediaFilter = MediaFilter.ALL,
            query: String = "",
        ): Intent =
            Intent(context, FullScreenMediaActivity::class.java)
                .putExtra(FullScreenMediaViewModel.ARG_UID, uid)
                .putExtra(FullScreenMediaViewModel.ARG_KIND, kind.key)
                .putExtra(FullScreenMediaViewModel.ARG_ID, id)
                .putExtra(FullScreenMediaViewModel.ARG_FILTER, filter.ordinal)
                .putExtra(FullScreenMediaViewModel.ARG_QUERY, query)
    }
}
