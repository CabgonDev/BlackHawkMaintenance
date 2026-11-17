package com.cabgon.blackhawk.ui.preflight

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.createBitmap

class SignatureView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeWidth = 5f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        isDither = true
    }

    init {
        // fondo totalmente transparente
        setBackgroundColor(Color.TRANSPARENT)
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun clear() {
        path.reset()
        invalidate()
    }

    fun hasSignature(): Boolean = !path.isEmpty

    fun exportBitmap(
        targetW: Int = width.coerceAtLeast(1),
        targetH: Int = height.coerceAtLeast(1),
    ): Bitmap {
        val bmp = createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        // 👇 deja transparente el fondo (sin rellenar)
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        // dibuja la firma con trazo suave y opacidad ligera
        paint.alpha = 230 // 0–255 (ligero realismo)
        c.drawPath(path, paint)
        return bmp
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawPath(path, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.action) {
            MotionEvent.ACTION_DOWN -> path.moveTo(x, y)
            MotionEvent.ACTION_MOVE -> path.lineTo(x, y)
        }
        invalidate()
        return true
    }
}
