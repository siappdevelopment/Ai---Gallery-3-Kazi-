package com.example.thirdgallery.model

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.example.thirdgallery.R

/** The tools of the photo editor's bottom bar (Stickers is not part of it: it has no editing behind it yet). */
enum class EditTool { ADJUST, FILTERS, CROP, DRAW, TEXT }

/** The seven sliders of the Adjust tool, each from -100 to 100 (0 = untouched). */
enum class Adjustment(@DrawableRes val icon: Int, @StringRes val label: Int) {
    BRIGHTNESS(R.drawable.ic_light_mode, R.string.edit_adj_brightness),
    CONTRAST(R.drawable.ic_contrast, R.string.edit_adj_contrast),
    SATURATION(R.drawable.ic_palette, R.string.edit_adj_saturation),
    EXPOSURE(R.drawable.ic_exposure, R.string.edit_adj_exposure),
    HIGHLIGHTS(R.drawable.ic_wb_sunny, R.string.edit_adj_highlights),
    SHADOWS(R.drawable.ic_dark_mode, R.string.edit_adj_shadows),
    SHARPNESS(R.drawable.ic_deblur, R.string.edit_adj_sharpness),
}

enum class EditFilter(@StringRes val label: Int) {
    ORIGINAL(R.string.edit_filter_original),
    VIVID(R.string.edit_filter_vivid),
    WARM(R.string.edit_filter_warm),
    COOL(R.string.edit_filter_cool),
    MONO(R.string.edit_filter_mono),
    FADE(R.string.edit_filter_fade),
}

/** Crop shapes: [value] is width / height, null keeps the picture's own shape. */
enum class CropRatio(val value: Float?, @StringRes val label: Int) {
    FREE(null, R.string.edit_ratio_free),
    SQUARE(1f, R.string.edit_ratio_1_1),
    R4_3(4f / 3f, R.string.edit_ratio_4_3),
    R16_9(16f / 9f, R.string.edit_ratio_16_9),
}

/** One point of a brush stroke as shares (0..1) of the picture's width and height. */
data class StrokePoint(val x: Float, val y: Float)

/** A brush stroke: its colour, its thickness as a share of the picture's width, and its points. */
data class Stroke(val color: Int, val size: Float, val points: List<StrokePoint>)

/**
 * Everything the user changed in the editor. It is immutable, so undo and redo are just earlier and later states.
 * [color] is shared by the brush and the text (like the design's swatches).
 */
data class EditState(
    val values: Map<Adjustment, Int> = emptyMap(),
    val filter: EditFilter = EditFilter.ORIGINAL,
    val rotation: Int = 0,
    val flipped: Boolean = false,
    val ratio: CropRatio = CropRatio.FREE,
    val text: String = "",
    val color: Int = DEFAULT_COLOR,
    val brushSize: Int = DEFAULT_BRUSH,
    val strokes: List<Stroke> = emptyList(),
) {
    fun value(adjustment: Adjustment): Int = values[adjustment] ?: 0

    fun withValue(adjustment: Adjustment, value: Int): EditState =
        copy(values = if (value == 0) values - adjustment else values + (adjustment to value))

    /** True when anything differs from the untouched picture (the colour and brush size alone do not count). */
    val isEdited: Boolean
        get() = values.isNotEmpty() || filter != EditFilter.ORIGINAL || rotation != 0 || flipped || ratio != CropRatio.FREE ||
            text.isNotBlank() || strokes.isNotEmpty()

    companion object {
        const val DEFAULT_COLOR = 0xFFFA457F.toInt()
        const val DEFAULT_BRUSH = 12
        const val MIN_BRUSH = 2
        const val MAX_BRUSH = 40
    }
}
