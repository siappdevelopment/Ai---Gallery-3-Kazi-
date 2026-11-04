package com.photovideo.gallerykz.memories.activity

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.bumptech.glide.Glide
import com.photovideo.gallerykz.memories.R
import com.photovideo.gallerykz.memories.database.MediaTypes
import com.photovideo.gallerykz.memories.helper.MediaUris

/**
 * Full-screen preview of one item in the Bin. Trashed items are not in the gallery database, so the
 * regular viewer cannot show them; this screen reads the file straight from MediaStore and offers no
 * edit or delete actions (restore / delete for good stay on the Bin screen).
 */
class BinPreviewActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val uid = intent.getLongExtra(EXTRA_UID, 0L)
        val isVideo = intent.getIntExtra(EXTRA_TYPE, MediaTypes.IMAGE) != MediaTypes.IMAGE
        val uri = MediaUris.ofUid(uid)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val match = ViewGroup.LayoutParams.MATCH_PARENT

        if (isVideo) {
            val video = VideoView(this)
            val controller = MediaController(this)
            controller.setAnchorView(video)
            video.setMediaController(controller)
            video.setVideoURI(uri)
            video.setOnPreparedListener { video.start() }
            root.addView(video, FrameLayout.LayoutParams(match, match, Gravity.CENTER))
        } else {
            val image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            root.addView(image, FrameLayout.LayoutParams(match, match))
            Glide.with(this).load(uri).fitCenter().into(image)
        }

        val density = resources.displayMetrics.density
        val size = (48 * density).toInt()
        val margin = (8 * density).toInt()
        val back = ImageButton(this).apply {
            setImageResource(R.drawable.ic_arrow_back)
            setColorFilter(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = getString(android.R.string.cancel)
            setOnClickListener { finish() }
        }
        root.addView(back, FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.START))

        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            (back.layoutParams as FrameLayout.LayoutParams).setMargins(
                bars.left + margin, bars.top + margin, 0, 0,
            )
            back.requestLayout()
            insets
        }
    }

    companion object {
        private const val EXTRA_UID = "uid"
        private const val EXTRA_TYPE = "media_type"

        fun intent(context: Context, uid: Long, mediaType: Int): Intent =
            Intent(context, BinPreviewActivity::class.java)
                .putExtra(EXTRA_UID, uid)
                .putExtra(EXTRA_TYPE, mediaType)
    }
}
