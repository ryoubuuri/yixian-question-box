package com.example.searchhelper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button

/**
 * Full-screen crop overlay for manual region selection ("每次框选" mode).
 *
 * Shows the freshly captured frame, lets the user drag a rectangle, then reports the
 * chosen region (in bitmap pixels) through [Callback.onConfirm] or nothing through
 * [Callback.onCancel]. The caller is responsible for hiding the floating window
 * beforehand; this overlay is a separate system window so it never pollutes the frame.
 */
object RegionSelectOverlay {

    interface Callback {
        /** [regionPx] is in bitmap pixel coordinates — safe for cropRegion(). */
        fun onConfirm(regionPx: Rect)

        /** User cancelled; nothing should be recognised. */
        fun onCancel()
    }

    private var rootView: View? = null
    private var windowManager: WindowManager? = null

    /** Whether the selection UI is currently on screen (blocks a second search). */
    fun isShowing(): Boolean = rootView != null

    /**
     * @param initialNormalized optional pre-selection (0..1), e.g. the remembered box.
     */
    fun show(context: Context, bitmap: Bitmap, initialNormalized: RectF?, callback: Callback) {
        if (isShowing()) return

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val root = LayoutInflater.from(context)
            .inflate(R.layout.overlay_region_selector, null)
        val canvas = root.findViewById<RegionCanvasView>(R.id.canvas_region)

        // Pre-fill with the remembered area when available, so repeated searches are
        // one tap ("确定识别") instead of redrawing the box every time.
        canvas.setBitmap(bitmap, initialNormalized)

        root.findViewById<Button>(R.id.btn_ok_region).setOnClickListener {
            val rect = canvas.bitmapRect()
            dismiss()
            callback.onConfirm(rect)
        }
        root.findViewById<Button>(R.id.btn_full_region).setOnClickListener {
            dismiss()
            callback.onConfirm(Rect(0, 0, bitmap.width, bitmap.height))
        }
        root.findViewById<Button>(R.id.btn_cancel_region).setOnClickListener {
            dismiss()
            callback.onCancel()
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP

        try {
            wm.addView(root, params)
            rootView = root
            windowManager = wm
        } catch (e: Exception) {
            // No overlay permission or already gone — surface through the callback
            // as a cancel so the caller can restore its own window.
            rootView = null
            callback.onCancel()
        }
    }

    /** Remove the overlay (idempotent). Safe to call from any thread via handler-less post. */
    private fun dismiss() {
        val root = rootView ?: return
        rootView = null
        val wm = windowManager ?: return
        windowManager = null
        try {
            wm.removeView(root)
        } catch (ignored: IllegalArgumentException) {
            // View already detached.
        }
    }

    /**
     * Force-close without invoking the callback. Used when the app is stopping
     * (service teardown) — no point running OCR afterwards.
     */
    fun forceDismiss() {
        dismiss()
    }
}
