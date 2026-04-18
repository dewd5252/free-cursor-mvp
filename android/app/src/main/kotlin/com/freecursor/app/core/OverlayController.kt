package com.freecursor.app.core

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView

class OverlayController(
    private val appContext: Context,
) {
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var cursorView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    fun show() {
        if (!Settings.canDrawOverlays(appContext)) {
            return
        }

        if (cursorView != null) {
            return
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 96
            y = 280
        }

        val view = TextView(appContext).apply {
            text = "◎"
            textSize = 34f
            typeface = Typeface.DEFAULT_BOLD
            setBackgroundColor(0x2234D399)
            setPadding(16, 10, 16, 10)
            setOnTouchListener(DragTouchListener())
        }

        windowManager.addView(view, lp)
        cursorView = view
        layoutParams = lp
    }

    fun hide() {
        cursorView?.let { view ->
            windowManager.removeView(view)
        }
        cursorView = null
        layoutParams = null
    }

    fun isVisible(): Boolean = cursorView != null

    private inner class DragTouchListener : View.OnTouchListener {
        private var initialX = 0
        private var initialY = 0
        private var initialTouchX = 0f
        private var initialTouchY = 0f

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val params = layoutParams ?: return false

            return when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(view, params)
                    true
                }

                else -> false
            }
        }
    }
}
