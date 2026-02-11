// app/src/main/java/com/cabgon/blackhawk/ui/pdf/PdfHighlightOverlay.kt
package com.cabgon.blackhawk.ui.pdf

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class PdfHighlightOverlay @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    private val rects = mutableListOf<RectF>()
    private val paint = Paint().apply {
        style = Paint.Style.FILL
        // Amarillo neón brillante, 35% opacidad
        color = 0x59FFFF00 // #59 = 35% alpha
        isAntiAlias = true
        // Halo suave para dar efecto de marcador
        setShadowLayer(4f, 0f, 0f, 0x80FFFF00.toInt())
    }




    fun setRects(screenRects: List<RectF>) {
        rects.clear()
        rects.addAll(screenRects)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        rects.forEach { canvas.drawRect(it, paint) }

    }
}
