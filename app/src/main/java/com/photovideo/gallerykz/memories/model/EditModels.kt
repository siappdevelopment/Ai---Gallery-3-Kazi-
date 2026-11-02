package com.photovideo.gallerykz.memories.model

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.photovideo.gallerykz.memories.R

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

/** The kept part of the picture as shares (0..1) of its width and height. */
data class CropBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    companion object {
        val FULL = CropBox(0f, 0f, 1f, 1f)
        const val MIN_SIDE = 0.1f
    }
}

/** Crop shapes: [value] is width / height of the cropped picture; null is free (the corners are dragged without a lock). */
enum class CropRatio(val value: Float?, @StringRes val label: Int) {
    FREE(null, R.string.edit_ratio_free),
    SQUARE(1f, R.string.edit_ratio_1_1),
    R4_3(4f / 3f, R.string.edit_ratio_4_3),
    R16_9(16f / 9f, R.string.edit_ratio_16_9),
}

/** One point of a brush stroke as shares (0..1) of the whole picture's width and height. */
data class StrokePoint(val x: Float, val y: Float)

/** A brush stroke: its colour, its thickness as a share of the whole picture's width, and its points. */
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
    val crop: CropBox = CropBox.FULL,
    val textX: Float = 0.5f,
    val textY: Float = 0.5f,
    val text: String = "",
    val color: Int = DEFAULT_COLOR,
    val brushSize: Int = DEFAULT_BRUSH,
    val strokes: List<Stroke> = emptyList(),
) {
    fun value(adjustment: Adjustment): Int = values[adjustment] ?: 0

    fun withValue(adjustment: Adjustment, value: Int): EditState =
        copy(values = if (value == 0) values - adjustment else values + (adjustment to value))

    /**
     * Turned a quarter clockwise as the user sees it. The crop, the strokes and the text turn with the picture; [width] and
     * [height] are the picture's size before the turn. (When mirrored the stored turn goes the other way round.)
     */
    fun rotatedRight(width: Int, height: Int): EditState {
        val k = width.toFloat() / height.coerceAtLeast(1)
        return copy(
            rotation = (rotation + if (flipped) 270 else 90) % 360,
            ratio = if (ratio == CropRatio.SQUARE) ratio else CropRatio.FREE,
            crop = CropBox(1f - crop.bottom, crop.left, 1f - crop.top, crop.right),
            textX = 1f - textY,
            textY = textX,
            strokes = strokes.map { s -> Stroke(s.color, s.size * k, s.points.map { StrokePoint(1f - it.y, it.x) }) },
        )
    }

    /** Mirrored left to right; the crop, the strokes and the text mirror with the picture. */
    fun mirrored(): EditState = copy(
        flipped = !flipped,
        crop = CropBox(1f - crop.right, crop.top, 1f - crop.left, crop.bottom),
        textX = 1f - textX,
        strokes = strokes.map { s -> Stroke(s.color, s.size, s.points.map { StrokePoint(1f - it.x, it.y) }) },
    )

    /** True when anything differs from the untouched picture (the colour and brush size alone do not count). */
    val isEdited: Boolean
        get() = values.isNotEmpty() || filter != EditFilter.ORIGINAL || rotation != 0 || flipped || crop != CropBox.FULL ||
            text.isNotBlank() || strokes.isNotEmpty()

    companion object {
        const val DEFAULT_COLOR = 0xFFFA457F.toInt()
        const val DEFAULT_BRUSH = 12
        const val MIN_BRUSH = 2
        const val MAX_BRUSH = 40
    }
}
