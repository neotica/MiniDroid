package id.neotica.minidroid

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.InputType
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/**
 * Displays the 320x480 Android 1.6 framebuffer with aspect-ratio preservation,
 * maps touch input 1:1 to Goldfish touchscreen coordinates, and forwards IME/keyboard input.
 */
class EmulatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var vncClient: VncClient? = null

    private var bitmap: Bitmap = Bitmap.createBitmap(320, 480, Bitmap.Config.ARGB_8888)
    private val bitmapLock = Any()
    private val srcRect = Rect(0, 0, 320, 480)
    private val dstRect = RectF()
    private val paint = Paint().apply {
        isFilterBitmap = true
        isAntiAlias = false
    }
    private val borderPaint = Paint().apply {
        color = Color.parseColor("#2E343E")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(Color.parseColor("#0D0F12"))
    }

    fun updateFrame(pixels: IntArray, w: Int, h: Int) {
        synchronized(bitmapLock) {
            if (bitmap.width != w || bitmap.height != h) {
                bitmap.recycle()
                bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                srcRect.set(0, 0, w, h)
                updateDstRect(width, height)
            }
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        }
        postInvalidateOnAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateDstRect(w, h)
    }

    private fun updateDstRect(viewW: Int, viewH: Int) {
        if (viewW <= 0 || viewH <= 0) return
        val fbW = bitmap.width.toFloat()
        val fbH = bitmap.height.toFloat()
        val scale = minOf(viewW / fbW, viewH / fbH)
        val drawW = fbW * scale
        val drawH = fbH * scale
        val left = (viewW - drawW) / 2f
        val top = (viewH - drawH) / 2f
        dstRect.set(left, top, left + drawW, top + drawH)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        synchronized(bitmapLock) {
            if (dstRect.isEmpty) updateDstRect(width, height)
            canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
            canvas.drawRect(dstRect, borderPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val client = vncClient ?: return true
        if (dstRect.width() <= 0f || dstRect.height() <= 0f) return true

        val gx = (((event.x - dstRect.left) / dstRect.width()) * client.fbWidth).toInt()
            .coerceIn(0, client.fbWidth - 1)
        val gy = (((event.y - dstRect.top) / dstRect.height()) * client.fbHeight).toInt()
            .coerceIn(0, client.fbHeight - 1)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestFocus()
                client.sendPointer(gx, gy, pressed = true)
            }
            MotionEvent.ACTION_MOVE -> {
                client.sendPointer(gx, gy, pressed = true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                client.sendPointer(gx, gy, pressed = false)
            }
        }
        return true
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val str = text?.toString() ?: return true
                for (ch in str) {
                    sendCharToGuest(ch)
                }
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength > 0) {
                    repeat(beforeLength) {
                        vncClient?.tapKey(0xFF08) // Backspace
                    }
                }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                handleAndroidKeyEvent(event)
                return true
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (handleAndroidKeyEvent(event)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (handleAndroidKeyEvent(event)) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun handleAndroidKeyEvent(event: KeyEvent): Boolean {
        val client = vncClient ?: return false
        val down = event.action == KeyEvent.ACTION_DOWN
        val keysym = when (event.keyCode) {
            KeyEvent.KEYCODE_DEL -> 0xFF08
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> 0xFF0D
            KeyEvent.KEYCODE_TAB -> 0xFF09
            KeyEvent.KEYCODE_ESCAPE -> 0xFF1B
            KeyEvent.KEYCODE_DPAD_UP -> 0xFF52
            KeyEvent.KEYCODE_DPAD_DOWN -> 0xFF54
            KeyEvent.KEYCODE_DPAD_LEFT -> 0xFF51
            KeyEvent.KEYCODE_DPAD_RIGHT -> 0xFF53
            KeyEvent.KEYCODE_DPAD_CENTER -> 0xFF8D
            else -> {
                val unicode = event.unicodeChar
                if (unicode in 0x20..0x7E) unicode else 0
            }
        }
        if (keysym == 0) return false
        client.sendKey(keysym, down)
        return true
    }

    private fun sendCharToGuest(ch: Char) {
        val client = vncClient ?: return
        when (ch) {
            '\n' -> client.tapKey(0xFF0D)
            '\b' -> client.tapKey(0xFF08)
            else -> {
                val code = ch.code
                if (code in 0x20..0x7E) {
                    client.tapKey(code)
                }
            }
        }
    }
}
