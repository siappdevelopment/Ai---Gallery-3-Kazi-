package com.example.thirdgallery.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.thirdgallery.R
import com.example.thirdgallery.adapter.BurstCardAdapter
import com.example.thirdgallery.adapter.BurstsHeaderAdapter
import com.example.thirdgallery.databinding.ActivityBurstsBinding
import com.example.thirdgallery.fragment.AddToAlbumBottomSheet
import com.example.thirdgallery.viewmodel.BurstsViewModel
import com.example.thirdgallery.viewmodel.PickEvent

/** Best from bursts (design): pick one shot from each burst of the gallery; the rest can go to the Bin. */
class BurstsActivity : AppCompatActivity() {

    private var binding: ActivityBurstsBinding? = null
    private val viewModel: BurstsViewModel by viewModels()

    private val requestLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        viewModel.onRequestResult(result.resultCode == RESULT_OK)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val b = ActivityBurstsBinding.inflate(layoutInflater)
        binding = b
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val cards = BurstCardAdapter(
            onSelect = { key, uid -> viewModel.select(key, uid) },
            onKeep = { viewModel.keep(it) },
            onFavorite = { viewModel.favorite(it) },
            onAddToAlbum = { addToAlbum(it) },
        )
        b.rvBursts.layoutManager = LinearLayoutManager(this)
        b.rvBursts.adapter = ConcatAdapter(BurstsHeaderAdapter { finish() }, cards)
        b.rvBursts.itemAnimator = null

        viewModel.cards.observe(this) { list ->
            cards.submitList(list)
            b.emptyGroup.visibility = if (viewModel.loaded.value == true && list.isEmpty()) View.VISIBLE else View.GONE
        }
        viewModel.loaded.observe(this) { loaded ->
            b.emptyGroup.visibility = if (loaded == true && viewModel.cards.value.orEmpty().isEmpty()) View.VISIBLE else View.GONE
        }
        viewModel.events.observe(this) { event ->
            when (val content = event.getContentIfNotHandled()) {
                is PickEvent.Launch -> requestLauncher.launch(IntentSenderRequest.Builder(content.intent.intentSender).build())
                is PickEvent.Message -> {
                    val text = if (content.count > 0) getString(content.res, content.count) else getString(content.res)
                    Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()
                }
                is PickEvent.Done, null -> Unit
            }
        }
    }

    /** The album button of a card: the existing album picker, for the shot that is selected. */
    private fun addToAlbum(key: Long) {
        val uid = viewModel.selectedOf(key) ?: return
        val fm = supportFragmentManager
        if (fm.isStateSaved || fm.findFragmentByTag(AddToAlbumBottomSheet.TAG) != null) return
        AddToAlbumBottomSheet.newForSelection(listOf(uid), false, 0L).show(fm, AddToAlbumBottomSheet.TAG)
    }

    override fun onDestroy() {
        binding?.rvBursts?.adapter = null
        binding = null
        super.onDestroy()
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, BurstsActivity::class.java)
    }
}
