package com.felipe.endoscopeviewer

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Centers the camera inside the available area without stretching its 4:3 image.
 */
class AspectRatioFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = View.MeasureSpec.getSize(widthMeasureSpec)
        val availableHeight = View.MeasureSpec.getSize(heightMeasureSpec)
        if (availableWidth == 0 || availableHeight == 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        val targetWidth = min(
            availableWidth,
            (availableHeight * CAMERA_ASPECT_RATIO).roundToInt()
        )
        val targetHeight = (targetWidth / CAMERA_ASPECT_RATIO).roundToInt()
        super.onMeasure(
            View.MeasureSpec.makeMeasureSpec(targetWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(targetHeight, View.MeasureSpec.EXACTLY)
        )
    }

    companion object {
        private const val CAMERA_ASPECT_RATIO = 4f / 3f
    }
}
