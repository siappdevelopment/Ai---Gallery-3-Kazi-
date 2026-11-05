package com.photovideo.gallerykz.memories.activity

import com.photovideo.gallerykz.memories.ads.AdAwareActivity

import com.photovideo.gallerykz.memories.ads.AdScreen

import com.photovideo.gallerykz.memories.ads.AdHooks

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.photovideo.gallerykz.memories.R
import com.photovideo.gallerykz.memories.database.MediaEntity
import com.photovideo.gallerykz.memories.database.MediaTypes
import com.photovideo.gallerykz.memories.databinding.ActivityVideoPlayerBinding
import com.photovideo.gallerykz.memories.fragment.AddToAlbumBottomSheet
import com.photovideo.gallerykz.memories.fragment.ConfirmDialogFragment
import com.photovideo.gallerykz.memories.fragment.DetailsDialogFragment
import com.photovideo.gallerykz.memories.helper.MediaFormat
import com.photovideo.gallerykz.memories.helper.MediaUris
import com.photovideo.gallerykz.memories.helper.ShareHelper
import com.photovideo.gallerykz.memories.repo.MediaRepository
import com.photovideo.gallerykz.memories.viewmodel.FullScreenMediaViewModel

/**
 * The video player (design: black screen): the name and date on top with a details button, the video (tap to play or pause,
 * a big play button while paused), a seek bar with the times, mute, play / pause, speed (1x, 1.5x, 2x, 0.5x) and full screen,
 * and Share, Favorite, Album and Delete below. Playback starts as soon as the video is ready and follows the screen's
 * lifecycle: it pauses when the screen is left and continues where it was when it comes back.
 *
 * It reuses the photo viewer's view model for the media's details, favorite and removal, so those behave as in the viewer.
 */
class VideoPlayerActivity : AdAwareActivity() {

    private var binding: ActivityVideoPlayerBinding? = null
    private val viewModel: FullScreenMediaViewModel by viewModels()
    private val handler = Handler(Looper.getMainLooper())

    private var uid = 0L
    private var player: MediaPlayer? = null
    private var prepared = false
    private var failed = false
    private var playing = false
    private var wasPlayingBeforePause = false
    private var muted = false
    private var speedIndex = 0
    private var fullscreen = false
    private var resumeAtMs = 0
    private var dragging = false
    private var media: MediaEntity? = null

    /** What to do once the system confirmation (favorite / delete) was accepted. */
    private var pendingAction: (() -> Unit)? = null

    /**
     * A system confirmation (favorite / bin) is open over the player. Its dialog only covers the screen, so the video
     * keeps playing underneath: pausing here and seeking back on return made it stutter and jump back.
     */
    private var requestOpen = false

    private val requestLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        requestOpen = false
        val action = pendingAction
        pendingAction = null
        if (result.resultCode == RESULT_OK) {
            if (action != null) action() else MediaRepository(applicationContext).sync(true) { }
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            updateProgress()
            if (playing) handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        uid = intent.getLongExtra(FullScreenMediaViewModel.ARG_UID, 0L)
        // An id that is not a video, or missing, can not be played: say so and leave.
        if (uid <= 0L || MediaTypes.typeOf(uid) != MediaTypes.VIDEO) {
            Toast.makeText(applicationContext, R.string.video_error, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val b = ActivityVideoPlayerBinding.inflate(layoutInflater)
        binding = b
        setContentView(b.root)
        // The player is black in both themes, so its bottom ad always uses the dark theme.
        AdHooks.install(this, AdScreen.VIDEO_PLAYER, darkAd = true)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        if (savedInstanceState != null) {
            resumeAtMs = savedInstanceState.getInt(KEY_POSITION, 0)
            wasPlayingBeforePause = savedInstanceState.getBoolean(KEY_PLAYING, true)
            muted = savedInstanceState.getBoolean(KEY_MUTED, false)
            speedIndex = savedInstanceState.getInt(KEY_SPEED, 0).coerceIn(0, SPEEDS.lastIndex)
            fullscreen = savedInstanceState.getBoolean(KEY_FULL, false)
        } else {
            wasPlayingBeforePause = true
        }

        wireControls(b)
        observeMedia(b)
        viewModel.select(uid)
        startVideo(b)
        applyMuteIcon(b)
        applySpeedLabel(b)
        applyFullscreen(b, fromUser = false)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (fullscreen) toggleFullscreen() else finish()
            }
        })
    }

    private fun wireControls(b: ActivityVideoPlayerBinding) {
        b.btnBack.setOnClickListener { if (fullscreen) toggleFullscreen() else finish() }
        b.btnInfo.setOnClickListener { media?.let { viewModel.loadDetails(it) } }
        b.videoArea.setOnClickListener { togglePlay() }
        b.btnPlayPause.setOnClickListener { togglePlay() }
        b.btnMute.setOnClickListener {
            muted = !muted
            applyVolume()
            applyMuteIcon(b)
        }
        b.btnSpeed.setOnClickListener {
            speedIndex = (speedIndex + 1) % SPEEDS.size
            applySpeedLabel(b)
            if (playing) applySpeed()
        }
        b.btnFullscreen.setOnClickListener { toggleFullscreen() }
        b.seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) b.tvCurrent.text = MediaFormat.duration(progress.toLong())
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                dragging = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                dragging = false
                if (prepared) player?.seekTo(seekBar.progress)
                updateProgress()
            }
        })

        b.actionShare.setOnClickListener {
            ShareHelper.share(this, listOf(uid)) {
                Toast.makeText(applicationContext, R.string.photo_share_failed, Toast.LENGTH_SHORT).show()
            }
        }
        b.actionFavorite.setOnClickListener { toggleFavorite() }
        b.actionAlbum.setOnClickListener {
            val fm = supportFragmentManager
            if (fm.isStateSaved || fm.findFragmentByTag(AddToAlbumBottomSheet.TAG) != null) return@setOnClickListener
            AddToAlbumBottomSheet.newForSelection(listOf(uid), false, 0L, 0, false).show(fm, AddToAlbumBottomSheet.TAG)
        }
        b.actionDelete.setOnClickListener { confirmDelete() }

        supportFragmentManager.setFragmentResultListener(KEY_DELETE, this) { _, result ->
            if (result.getBoolean(ConfirmDialogFragment.RESULT_CONFIRMED)) moveToBin()
        }
        viewModel.details.observe(this) { event ->
            event.getContentIfNotHandled()?.let { DetailsDialogFragment.show(supportFragmentManager, it) }
        }
    }

    /** The file name and date on top and the favorite heart follow the media record. */
    private fun observeMedia(b: ActivityVideoPlayerBinding) {
        viewModel.current.observe(this) { item ->
            media = item
            if (item == null) return@observe
            b.tvDate.text = MediaFormat.dayTitle(this, item.dayKey)
            b.tvName.text = item.displayName.ifBlank { getString(R.string.photo_filter_video) }
            val on = item.isFavorite
            b.ivFavorite.setImageResource(if (on) R.drawable.ic_favorite else R.drawable.ic_favorite_border)
            b.ivFavorite.setColorFilter(ContextCompat.getColor(this, if (on) R.color.brand_pink else R.color.white))
        }
    }

    // ---- playback ----

    private fun startVideo(b: ActivityVideoPlayerBinding) {
        b.progress.visibility = View.VISIBLE
        b.videoView.setOnPreparedListener { mp ->
            player = mp
            prepared = true
            b.progress.visibility = View.GONE
            b.seek.max = mp.duration.coerceAtLeast(1)
            b.tvTotal.text = MediaFormat.duration(mp.duration.toLong())
            applyVolume()
            if (resumeAtMs > 0) mp.seekTo(resumeAtMs)
            if (wasPlayingBeforePause) play() else showPaused()
            updateProgress()
        }
        b.videoView.setOnCompletionListener {
            playing = false
            handler.removeCallbacks(ticker)
            showPaused()
            // The next play starts from the beginning.
            resumeAtMs = 0
            binding?.seek?.progress = binding?.seek?.max ?: 0
        }
        b.videoView.setOnErrorListener { _, _, _ ->
            onPlaybackError()
            true
        }
        try {
            b.videoView.setVideoURI(MediaUris.ofUid(uid))
        } catch (e: Exception) {
            onPlaybackError()
        }
    }

    private fun onPlaybackError() {
        if (failed) return
        failed = true
        Toast.makeText(applicationContext, R.string.video_error, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun togglePlay() {
        if (!prepared || failed) return
        if (playing) pause() else {
            // After the end, play from the start.
            val b = binding
            if (b != null && b.videoView.currentPosition >= b.videoView.duration - 200) b.videoView.seekTo(0)
            play()
        }
    }

    private fun play() {
        val b = binding ?: return
        if (!prepared || failed) return
        try {
            b.videoView.start()
            applySpeed()
        } catch (e: IllegalStateException) {
            onPlaybackError()
            return
        }
        playing = true
        b.ivBigPlay.visibility = View.GONE
        b.btnPlayPause.setImageResource(R.drawable.ic_pause)
        b.btnPlayPause.contentDescription = getString(R.string.video_pause)
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    private fun pause() {
        val b = binding ?: return
        try {
            if (prepared) b.videoView.pause()
        } catch (e: IllegalStateException) {
            // The player is gone; the state below still shows "paused".
        }
        playing = false
        handler.removeCallbacks(ticker)
        showPaused()
        updateProgress()
    }

    private fun showPaused() {
        val b = binding ?: return
        b.ivBigPlay.visibility = View.VISIBLE
        b.btnPlayPause.setImageResource(R.drawable.ic_play_arrow)
        b.btnPlayPause.contentDescription = getString(R.string.video_play)
    }

    private fun updateProgress() {
        val b = binding ?: return
        if (!prepared || dragging) return
        val position = try {
            b.videoView.currentPosition
        } catch (e: IllegalStateException) {
            0
        }
        b.seek.progress = position
        b.tvCurrent.text = MediaFormat.duration(position.toLong())
    }

    private fun applyVolume() {
        val volume = if (muted) 0f else 1f
        try {
            player?.setVolume(volume, volume)
        } catch (e: IllegalStateException) {
            // Applied the next time the player is prepared.
        }
    }

    /** Speed changes only while playing: on some phones setting it on a paused player starts it. */
    private fun applySpeed() {
        val mp = player ?: return
        try {
            mp.playbackParams = mp.playbackParams.setSpeed(SPEEDS[speedIndex])
        } catch (e: Exception) {
            // This phone cannot change the speed; the video keeps playing at normal speed.
        }
    }

    private fun applyMuteIcon(b: ActivityVideoPlayerBinding) {
        b.btnMute.setImageResource(if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up)
        b.btnMute.contentDescription = getString(if (muted) R.string.video_unmute else R.string.video_mute)
    }

    private fun applySpeedLabel(b: ActivityVideoPlayerBinding) {
        b.btnSpeed.text = SPEED_LABELS[speedIndex]
    }

    // ---- full screen ----

    private fun toggleFullscreen() {
        fullscreen = !fullscreen
        binding?.let { applyFullscreen(it, fromUser = true) }
    }

    /** Full screen: landscape, no system bars, only the video and its controls. */
    private fun applyFullscreen(b: ActivityVideoPlayerBinding, fromUser: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (fullscreen) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            if (fromUser) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        b.topBar.visibility = if (fullscreen) View.GONE else View.VISIBLE
        b.actionBar.visibility = if (fullscreen) View.GONE else View.VISIBLE
        b.btnFullscreen.setImageResource(if (fullscreen) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen)
        b.btnFullscreen.contentDescription = getString(if (fullscreen) R.string.video_exit_fullscreen else R.string.video_fullscreen)
    }

    // ---- actions ----

    private fun toggleFavorite() {
        val item = media ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Toast.makeText(applicationContext, R.string.viewer_unsupported, Toast.LENGTH_SHORT).show()
            return
        }
        val target = !item.isFavorite
        val uri = MediaUris.of(item.mediaId, item.mediaType)
        launchRequest(MediaStore.createFavoriteRequest(contentResolver, listOf(uri), target)) {
            viewModel.setFavorite(item.uid, target)
        }
    }

    private fun confirmDelete() {
        if (media == null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Toast.makeText(applicationContext, R.string.viewer_unsupported, Toast.LENGTH_SHORT).show()
            return
        }
        pause()
        ConfirmDialogFragment.show(
            supportFragmentManager,
            KEY_DELETE,
            R.drawable.ic_delete,
            getString(R.string.delete_video_title),
            getString(R.string.delete_body),
            getString(R.string.delete_move_to_bin),
        )
    }

    /** The user confirmed: ask the system to move the video to the Bin, then drop it from the gallery and leave. */
    private fun moveToBin() {
        val item = media ?: return
        val uri = MediaUris.of(item.mediaId, item.mediaType)
        launchRequest(MediaStore.createTrashRequest(contentResolver, listOf(uri), true)) {
            viewModel.remove(item.uid)
            Toast.makeText(applicationContext, R.string.bin_moved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun launchRequest(request: PendingIntent, onAccepted: () -> Unit) {
        pendingAction = onAccepted
        requestOpen = true
        try {
            requestLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
        } catch (e: Exception) {
            requestOpen = false
            pendingAction = null
            Toast.makeText(applicationContext, R.string.bin_failed, Toast.LENGTH_SHORT).show()
        }
    }

    // ---- lifecycle ----

    override fun onPause() {
        // Leaving the screen pauses the video; coming back continues it where it was.
        wasPlayingBeforePause = playing
        val b = binding
        if (b != null && prepared) {
            resumeAtMs = try {
                b.videoView.currentPosition
            } catch (e: IllegalStateException) {
                resumeAtMs
            }
            // Only a dialog is on top (favorite / bin confirmation): the video keeps playing. onStop pauses it if the
            // screen really goes away.
            if (!requestOpen) pause()
        }
        super.onPause()
    }

    override fun onStop() {
        // The screen is really gone (home, another app): now the video pauses, and onResume continues it.
        if (playing) {
            wasPlayingBeforePause = true
            pause()
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // Nothing to restore while the video never stopped (after a dialog); seeking then would jump it back.
        if (prepared && wasPlayingBeforePause && !failed && !playing) {
            binding?.videoView?.seekTo(resumeAtMs)
            play()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_POSITION, resumeAtMs)
        outState.putBoolean(KEY_PLAYING, wasPlayingBeforePause)
        outState.putBoolean(KEY_MUTED, muted)
        outState.putInt(KEY_SPEED, speedIndex)
        outState.putBoolean(KEY_FULL, fullscreen)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        binding?.videoView?.let {
            it.setOnPreparedListener(null)
            it.setOnCompletionListener(null)
            it.setOnErrorListener(null)
            try {
                it.stopPlayback()
            } catch (e: Exception) {
                // Already released.
            }
        }
        player = null
        binding = null
        super.onDestroy()
    }

    companion object {
        private const val KEY_POSITION = "position"
        private const val KEY_PLAYING = "playing"
        private const val KEY_MUTED = "muted"
        private const val KEY_SPEED = "speed"
        private const val KEY_FULL = "fullscreen"
        private const val KEY_DELETE = "video_delete"
        private const val TICK_MS = 250L

        /** The speed button cycles 1x, 1.5x, 2x, 0.5x (the design's order). */
        private val SPEEDS = floatArrayOf(1f, 1.5f, 2f, 0.5f)
        private val SPEED_LABELS = listOf("1x", "1.5x", "2x", "0.5x")

        /** Opens the player for the video [uid] (the media id the whole app uses; see MediaUris). */
        fun intent(context: Context, uid: Long): Intent =
            Intent(context, VideoPlayerActivity::class.java)
                .putExtra(FullScreenMediaViewModel.ARG_UID, uid)
                // A one-item list: the view model must not read the whole gallery for this screen.
                .putExtra(FullScreenMediaViewModel.ARG_UIDS, longArrayOf(uid))
    }
}
