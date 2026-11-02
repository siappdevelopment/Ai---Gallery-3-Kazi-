package com.photovideo.gallerykz.memories.activity

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentContainerView
import com.photovideo.gallerykz.memories.R
import com.photovideo.gallerykz.memories.fragment.GalleryPageFragment

/**
 * The stand-alone gallery screen. The gallery itself is [GalleryPageFragment], which is also the left page of the
 * launcher; this activity just holds it for the places that cannot go through the launcher.
 */
class HomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = FragmentContainerView(this).apply { id = R.id.galleryPageContainer }
        setContentView(container)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .setReorderingAllowed(true)
                .add(R.id.galleryPageContainer, GalleryPageFragment(), TAG)
                .commit()
        }
    }

    private companion object {
        const val TAG = "gallery_page"
    }
}
