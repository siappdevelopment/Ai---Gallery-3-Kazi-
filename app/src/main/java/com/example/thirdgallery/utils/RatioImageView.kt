package com.example.thirdgallery.utils

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView

/** ImageView whose height follows its width: height = width / [ratio] (set by the adapter, 1 = square). */
class RatioImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatImageView(context, attrs, defStyleAttr) {

    var ratio: Float = 1f
        set(value) {
            val safe = if (value > 0f) value else 1f
            if (safe != field) {
                field = safe
                requestLayout()
            }
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
        val width = measuredWidth
        setMeasuredDimension(width, (width / ratio).toInt())
    }
}
