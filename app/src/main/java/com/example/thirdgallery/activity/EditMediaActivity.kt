package com.example.thirdgallery.activity

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.thirdgallery.R
import com.example.thirdgallery.databinding.ActivityEditMediaBinding
import com.example.thirdgallery.databinding.ItemEditAdjustBinding
import com.example.thirdgallery.databinding.ItemEditFilterBinding
import com.example.thirdgallery.fragment.ConfirmDialogFragment
import com.example.thirdgallery.helper.EditRenderer
import com.example.thirdgallery.model.Adjustment
import com.example.thirdgallery.model.CropRatio
import com.example.thirdgallery.model.EditFilter
import com.example.thirdgallery.model.EditState
import com.example.thirdgallery.model.EditTool
import com.example.thirdgallery.viewmodel.EditMediaViewModel
import com.example.thirdgallery.viewmodel.EditResult

/**
 * Photo editor (design 36): close, undo / redo, Reset and Save on top; the photo in a frame; the panel of the chosen tool;
 * and the tool bar (Adjust, Filters, Crop, Draw, Text, Stickers). Every change shows live on the photo; Save writes the
 * edited picture as a new photo and the original stays as it is.
 */
class EditMediaActivity : AppCompatActivity() {

    private var binding: ActivityEditMediaBinding? = null
    private val viewModel: EditMediaViewModel by viewModels()

    private val adjustChips = LinkedHashMap<Adjustment, ItemEditAdjustBinding>()
    private val filterItems = LinkedHashMap<EditFilter, ItemEditFilterBinding>()
    private val ratioButtons = LinkedHashMap<CropRatio, TextView>()
    private val toolViews = LinkedHashMap<EditTool, View>()
    private var swatchViews: List<View> = emptyList()

    private var turned: Bitmap? = null
    private var turnedKey: Pair<Int, Boolean>? = null
    private var sliderSync = false
    private var textSync = false

    private val pink by lazy { ContextCompat.getColor(this, R.color.brand_pink) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val b = ActivityEditMediaBinding.inflate(layoutInflater)
        binding = b
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        buildAdjustChips(b)
        buildToolBar(b)
        buildCropButtons(b)
        buildSwatches(b)
        wireControls(b)
        supportFragmentManager.setFragmentResultListener(KEY_DISCARD, this) { _, result ->
            if (result.getBoolean(ConfirmDialogFragment.RESULT_CONFIRMED)) finish()
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = close()
        })

        viewModel.source.observe(this) { source ->
            if (source != null) onSource(b, source)
        }
        viewModel.loadFailed.observe(this) { failed ->
            if (failed == true) {
                // The photo is gone or cannot be read: nothing to edit.
                Toast.makeText(applicationContext, R.string.edit_open_failed, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
        viewModel.edit.observe(this) { render(b, it) }
        viewModel.tool.observe(this) { showTool(b, it) }
        viewModel.adjustment.observe(this) { renderAdjustment(b, viewModel.edit.value ?: EditState(), it) }
        viewModel.canUndo.observe(this) { b.btnUndo.alpha = if (it == true) 1f else 0.35f }
        viewModel.canRedo.observe(this) { b.btnRedo.alpha = if (it == true) 1f else 0.35f }
        viewModel.saving.observe(this) { saving ->
            b.btnSave.isEnabled = saving != true
            b.btnSave.alpha = if (saving == true) 0.6f else 1f
        }
        viewModel.result.observe(this) { event ->
            when (val result = event.getContentIfNotHandled()) {
                is EditResult.Saved -> {
                    Toast.makeText(applicationContext, R.string.edit_saved, Toast.LENGTH_SHORT).show()
                    finish()
                }
                is EditResult.Failed -> Toast.makeText(applicationContext, result.message, Toast.LENGTH_SHORT).show()
                null -> Unit
            }
        }
    }

    override fun onDestroy() {
        binding = null
        turned = null
        super.onDestroy()
    }

    // ---- building the panels ----

    private fun buildAdjustChips(b: ActivityEditMediaBinding) {
        val inflater = LayoutInflater.from(this)
        Adjustment.entries.forEach { adjustment ->
            val chip = ItemEditAdjustBinding.inflate(inflater, b.adjustChips, false)
            chip.ivIcon.setImageResource(adjustment.icon)
            chip.tvLabel.setText(adjustment.label)
            chip.root.setOnClickListener { viewModel.selectAdjustment(adjustment) }
            b.adjustChips.addView(chip.root)
            adjustChips[adjustment] = chip
        }
    }

    private fun buildToolBar(b: ActivityEditMediaBinding) {
        toolViews[EditTool.ADJUST] = b.toolAdjust
        toolViews[EditTool.FILTERS] = b.toolFilters
        toolViews[EditTool.CROP] = b.toolCrop
        toolViews[EditTool.DRAW] = b.toolDraw
        toolViews[EditTool.TEXT] = b.toolText
        toolViews.forEach { (tool, view) -> view.setOnClickListener { viewModel.selectTool(tool) } }
        // Stickers has nothing behind it yet.
        b.toolStickers.setOnClickListener {
            Toast.makeText(applicationContext, R.string.albums_coming_soon, Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildCropButtons(b: ActivityEditMediaBinding) {
        ratioButtons[CropRatio.FREE] = b.ratioFree
        ratioButtons[CropRatio.SQUARE] = b.ratio11
        ratioButtons[CropRatio.R4_3] = b.ratio43
        ratioButtons[CropRatio.R16_9] = b.ratio169
        ratioButtons.forEach { (ratio, view) -> view.setOnClickListener { viewModel.update { it.copy(ratio = ratio) } } }
        b.btnRotate.setOnClickListener { viewModel.update { it.copy(rotation = (it.rotation + 90) % 360) } }
        b.btnFlip.setOnClickListener { viewModel.update { it.copy(flipped = !it.flipped) } }
    }

    private fun buildSwatches(b: ActivityEditMediaBinding) {
        val density = resources.displayMetrics.density
        val views = ArrayList<View>()
        fun fill(row: LinearLayout, sizeDp: Int) {
            COLORS.forEachIndexed { index, color ->
                val dot = View(this).apply {
                    background = ContextCompat.getDrawable(this@EditMediaActivity, R.drawable.bg_edit_swatch)?.mutate()
                    backgroundTintList = android.content.res.ColorStateList.valueOf(color)
                    layoutParams = LinearLayout.LayoutParams((sizeDp * density).toInt(), (sizeDp * density).toInt()).apply {
                        if (index > 0) marginStart = (12 * density).toInt()
                    }
                    setOnClickListener { viewModel.update { it.copy(color = color) } }
                }
                row.addView(dot)
                views.add(dot)
            }
        }
        fill(b.swatchesDraw, 28)
        fill(b.swatchesText, 26)
        swatchViews = views
    }

    private fun wireControls(b: ActivityEditMediaBinding) {
        b.btnClose.setOnClickListener { close() }
        b.btnUndo.setOnClickListener { viewModel.undo() }
        b.btnRedo.setOnClickListener { viewModel.redo() }
        b.btnReset.setOnClickListener { viewModel.reset() }
        b.btnSave.setOnClickListener { viewModel.save() }
        b.canvas.onStroke = { viewModel.addStroke(it) }

        b.seekAdjust.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser || sliderSync) return
                val adjustment = viewModel.adjustment.value ?: return
                viewModel.updateLive { it.withValue(adjustment, progress - 100) }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = viewModel.commitLive()
        })
        b.seekBrush.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser || sliderSync) return
                viewModel.updateLive { it.copy(brushSize = progress + EditState.MIN_BRUSH) }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = viewModel.commitLive()
        })
        b.etText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (textSync) return
                viewModel.updateLive { it.copy(text = s?.toString().orEmpty()) }
            }
        })
        b.etText.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) viewModel.commitLive() }
    }

    // ---- showing the state ----

    /** The photo arrived: the preview and the filter thumbnails use it. */
    private fun onSource(b: ActivityEditMediaBinding, source: Bitmap) {
        val inflater = LayoutInflater.from(this)
        if (filterItems.isEmpty()) {
            EditFilter.entries.forEach { filter ->
                val item = ItemEditFilterBinding.inflate(inflater, b.filterRow, false)
                item.tvLabel.setText(filter.label)
                item.root.setOnClickListener { viewModel.update { it.copy(filter = filter) } }
                b.filterRow.addView(item.root)
                filterItems[filter] = item
            }
        }
        filterItems.forEach { (filter, item) ->
            item.ivThumb.setImageBitmap(source)
            item.ivThumb.colorFilter = EditRenderer.filterMatrix(filter)?.let { ColorMatrixColorFilter(it) }
        }
        render(b, viewModel.edit.value ?: EditState())
    }

    private fun render(b: ActivityEditMediaBinding, state: EditState) {
        val source = viewModel.source.value
        if (source != null) {
            // The turned copy is only rebuilt when the rotation or the mirroring changed.
            val key = state.rotation to state.flipped
            if (turned == null || turnedKey != key) {
                turned = EditRenderer.transform(source, state.rotation, state.flipped)
                turnedKey = key
                b.canvas.setBitmap(turned)
            }
            val bitmap = turned ?: source
            b.canvas.setAspect(EditRenderer.frameAspect(state, bitmap.width, bitmap.height))
        }
        b.canvas.setColorMatrix(EditRenderer.colorMatrix(state))
        b.canvas.setStrokes(state.strokes)
        b.canvas.setText(state.text, state.color)
        b.canvas.setBrush(viewModel.tool.value == EditTool.DRAW, state.color, state.brushSize / BRUSH_DIVIDER)

        // Chips: a pink dot marks an adjustment that is changed.
        Adjustment.entries.forEach { adjustment ->
            adjustChips[adjustment]?.ivDot?.visibility = if (state.value(adjustment) != 0) View.VISIBLE else View.INVISIBLE
        }
        renderAdjustment(b, state, viewModel.adjustment.value ?: Adjustment.BRIGHTNESS)
        filterItems.forEach { (filter, item) ->
            val on = state.filter == filter
            item.root.alpha = 1f
            item.ivThumb.alpha = if (on) 1f else 0.55f
            item.tvLabel.setTextColor(ContextCompat.getColor(this, if (on) R.color.white else R.color.edit_muted))
        }
        ratioButtons.forEach { (ratio, view) -> styleChip(view, state.ratio == ratio) }
        swatchViews.forEachIndexed { index, view ->
            val selected = COLORS[index % COLORS.size] == state.color
            val scale = if (selected) 1.25f else 0.85f
            view.animate().scaleX(scale).scaleY(scale).setDuration(150).start()
        }
        sliderSync = true
        b.seekBrush.progress = state.brushSize - EditState.MIN_BRUSH
        sliderSync = false
        b.tvBrushSize.text = state.brushSize.toString()
        if (b.etText.text?.toString() != state.text) {
            textSync = true
            b.etText.setText(state.text)
            b.etText.setSelection(state.text.length)
            textSync = false
        }
    }

    private fun renderAdjustment(b: ActivityEditMediaBinding, state: EditState, adjustment: Adjustment) {
        val value = state.value(adjustment)
        b.tvAdjustName.setText(adjustment.label)
        b.tvAdjustValue.text = if (value > 0) "+$value" else value.toString()
        if (b.seekAdjust.progress != value + 100) {
            sliderSync = true
            b.seekAdjust.progress = value + 100
            sliderSync = false
        }
        adjustChips.forEach { (a, chip) -> styleChip(chip.root, a == adjustment, chip.tvLabel, chip.ivIcon) }
    }

    /** White chip with dark text when selected, a faint white one with white text otherwise (design). */
    private fun styleChip(view: View, selected: Boolean, label: TextView? = null, icon: android.widget.ImageView? = null) {
        view.setBackgroundResource(if (selected) R.drawable.bg_edit_chip_on else R.drawable.bg_edit_chip_off)
        val color = ContextCompat.getColor(this, if (selected) R.color.edit_chip_text_on else R.color.white)
        (label ?: view as? TextView)?.setTextColor(color)
        icon?.setColorFilter(color)
    }

    private fun showTool(b: ActivityEditMediaBinding, tool: EditTool) {
        b.panelAdjust.visibility = if (tool == EditTool.ADJUST) View.VISIBLE else View.GONE
        b.panelFilters.visibility = if (tool == EditTool.FILTERS) View.VISIBLE else View.GONE
        b.panelCrop.visibility = if (tool == EditTool.CROP) View.VISIBLE else View.GONE
        b.panelDraw.visibility = if (tool == EditTool.DRAW) View.VISIBLE else View.GONE
        b.panelText.visibility = if (tool == EditTool.TEXT) View.VISIBLE else View.GONE
        toolViews.forEach { (t, view) -> tintTool(view, t == tool) }
        b.canvas.setGridVisible(tool == EditTool.CROP)
        val state = viewModel.edit.value ?: EditState()
        b.canvas.setBrush(tool == EditTool.DRAW, state.color, state.brushSize / BRUSH_DIVIDER)
    }

    /** The tool bar item of the current tool is pink, the others grey (design). */
    private fun tintTool(item: View, selected: Boolean) {
        val color = if (selected) pink else ContextCompat.getColor(this, R.color.edit_muted)
        (item as? ViewGroup)?.let { group ->
            for (i in 0 until group.childCount) {
                when (val child = group.getChildAt(i)) {
                    is TextView -> child.setTextColor(color)
                    is android.widget.ImageView -> child.setColorFilter(color)
                }
            }
        }
    }

    // ---- leaving ----

    private fun close() {
        if (!viewModel.hasChanges) {
            finish()
            return
        }
        ConfirmDialogFragment.show(
            supportFragmentManager,
            KEY_DISCARD,
            R.drawable.ic_close,
            getString(R.string.edit_discard_title),
            getString(R.string.edit_discard_body),
            getString(R.string.edit_discard),
        )
    }

    companion object {
        private const val KEY_DISCARD = "edit_discard"
        private const val BRUSH_DIVIDER = 290f

        /** The swatches of the Draw and Text tools (pink, white, black, yellow, blue, green). */
        private val COLORS = listOf(
            EditState.DEFAULT_COLOR, 0xFFFFFFFF.toInt(), 0xFF161616.toInt(), 0xFFE5B100.toInt(), 0xFF2E8FD8.toInt(), 0xFF2DAA6B.toInt(),
        )

        /** Opens the editor for the photo [uid]; videos and unknown media are refused by the editor itself. */
        fun intent(context: Context, uid: Long): Intent =
            Intent(context, EditMediaActivity::class.java).putExtra(EditMediaViewModel.ARG_UID, uid)
    }
}
