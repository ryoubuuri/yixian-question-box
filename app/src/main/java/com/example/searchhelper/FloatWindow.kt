package com.example.searchhelper

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.lzf.easyfloat.EasyFloat
import com.lzf.easyfloat.enums.ShowPattern
import com.lzf.easyfloat.enums.SidePattern
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The always-on-top answer window: capture -> (optional manual region pick) -> OCR ->
 * match -> render, all in place.
 */
object FloatWindow {

    private const val TAG = "search_window"

    /** Guard against double-taps on 【截屏搜题】 while a search is in flight. */
    @Volatile
    private var searching = false

    /**
     * Create the floating window, or bring an existing one back.
     *
     * EasyFloat keeps its own tag registry. A plain [dismiss] can leave that registry
     * dirty, so the guard must look at whether the view is *really* on screen rather
     * than whether the tag is still registered — otherwise show() silently does nothing
     * and the window only comes back after the process is killed.
     */
    fun show(activity: Activity) {
        val existing = EasyFloat.getFloatView(TAG)
        if (existing != null) {
            if (existing.isAttachedToWindow && existing.visibility == View.VISIBLE) return
            // Stale or hidden: drop it before building a fresh one.
            EasyFloat.dismiss(TAG)
        }
        EasyFloat.with(activity)
            .setLayout(R.layout.float_search_box) { view -> bindActions(view) }
            .setShowPattern(ShowPattern.ALL_TIME)
            .setSidePattern(SidePattern.RESULT_HORIZONTAL)
            .setDragEnable(true)
            .setGravity(Gravity.END or Gravity.CENTER_VERTICAL, 0, 200)
            .setTag(TAG)
            .show()
    }

    fun dismiss() {
        // If a region-pick overlay is open (float window hidden beneath it), remove it
        // first so the user is not stranded on a crop screen after the service stops.
        RegionSelectOverlay.forceDismiss()
        EasyFloat.dismiss(TAG)
    }

    /** Whether the window is really on screen (a hidden or stale one does not count). */
    fun isShowing(): Boolean {
        val view = EasyFloat.getFloatView(TAG) ?: return false
        return view.isAttachedToWindow && view.visibility == View.VISIBLE
    }

    private fun bindActions(view: View) {
        val tvResult = view.findViewById<TextView>(R.id.tv_result)
        applyModeLabel(view)
        view.findViewById<Button>(R.id.btn_capture).setOnClickListener { search(tvResult) }
        view.findViewById<Button>(R.id.btn_close).setOnClickListener { dismiss() }
        // Reach the AI settings without hunting for the launcher icon. The float window
        // lives outside any Activity, so the intent needs FLAG_ACTIVITY_NEW_TASK.
        view.findViewById<Button>(R.id.btn_settings).setOnClickListener {
            val context = view.context
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
    }

    /** Title shows the current region mode, e.g. "题库助手 · 框选识别". */
    private fun applyModeLabel(view: View) {
        view.findViewById<TextView>(R.id.tv_title).text =
            "题库助手 · " + when (Config.regionMode) {
                Config.REGION_MODE_SELECT -> "框选识别"
                Config.REGION_MODE_LAST -> "上次区域"
                else -> "全屏识别"
            }
    }

    /** Keep the label in sync when the mode is changed from the home screen. */
    fun refreshModeLabel() {
        val view = EasyFloat.getFloatView(TAG) ?: return
        if (!view.isAttachedToWindow) return
        applyModeLabel(view)
    }

    private fun search(tvResult: TextView) {
        if (searching) return
        searching = true
        AppScope.launch {
            try {
                val service = ScreenCaptureService.instance
                if (service == null) {
                    tvResult.text = "截屏服务未运行，请回到首页重新点击【启动悬浮窗】"
                    return@launch
                }

                // Hide first so the window itself is not captured, then wait for a new
                // frame. captureBitmap() also polls briefly, but the fixed delay gives
                // the producer time to publish the float-window-free frame.
                EasyFloat.hide(TAG)
                kotlinx.coroutines.delay(250)
                val bitmap = service.captureBitmap()
                if (bitmap == null) {
                    EasyFloat.show(TAG)
                    tvResult.text = "截屏失败，未获取到图像缓冲区数据。" +
                        "若反复出现，请回首页点【停止服务并关闭悬浮窗】后重新启动"
                    return@launch
                }

                when (Config.regionMode) {
                    Config.REGION_MODE_SELECT -> pickRegion(bitmap, tvResult)
                    Config.REGION_MODE_LAST -> reuseRegion(bitmap, tvResult)
                    else -> runSearch(bitmap, tvResult)
                }
            } finally {
                searching = false
            }
        }
    }

    /** REGION_MODE_SELECT: show the crop overlay; float window stays hidden meanwhile. */
    private fun pickRegion(bitmap: android.graphics.Bitmap, tvResult: TextView) {
        RegionSelectOverlay.show(
            AppContext.get(),
            bitmap,
            Config.lastRegionRect,
            object : RegionSelectOverlay.Callback {
                override fun onConfirm(regionPx: Rect) {
                    // Remember it for REGION_MODE_LAST and as the default box next time.
                    Config.lastRegionRect = normalized(regionPx, bitmap)
                    AppScope.launch {
                        EasyFloat.show(TAG)
                        runSearch(QuestionSolver.cropRegion(bitmap, regionPx), tvResult)
                    }
                }

                override fun onCancel() {
                    AppScope.launch {
                        EasyFloat.show(TAG)
                        tvResult.text = "已取消识别"
                    }
                }
            }
        )
    }

    /** REGION_MODE_LAST: reuse the remembered area; full screen if none was saved. */
    private suspend fun reuseRegion(bitmap: android.graphics.Bitmap, tvResult: TextView) {
        val saved = Config.lastRegionRect
        EasyFloat.show(TAG)
        if (saved == null) {
            tvResult.text = "还没有保存过框选区域，本次按全屏识别；\n可在首页切换到【每次框选】先框一次。"
            runSearch(bitmap, tvResult)
        } else {
            val px = Rect(
                (saved.left * bitmap.width).toInt(),
                (saved.top * bitmap.height).toInt(),
                (saved.right * bitmap.width).toInt(),
                (saved.bottom * bitmap.height).toInt()
            )
            runSearch(QuestionSolver.cropRegion(bitmap, px), tvResult)
        }
    }

    private fun normalized(regionPx: Rect, bitmap: Bitmap): RectF {
        return RectF(
            regionPx.left / bitmap.width.toFloat(),
            regionPx.top / bitmap.height.toFloat(),
            regionPx.right / bitmap.width.toFloat(),
            regionPx.bottom / bitmap.height.toFloat()
        )
    }

    /** Shared tail: OCR + match + render into the float window. */
    private suspend fun runSearch(bitmap: Bitmap, tvResult: TextView) {
        tvResult.text = "正在 OCR 识别题目..."
        val screen = try {
            QuestionSolver.analyze(bitmap)
        } catch (e: Exception) {
            tvResult.text = "OCR 失败：${e.message}"
            return
        }
        if (screen.stem.isBlank() && screen.rawText.isBlank()) {
            tvResult.text = "未识别到文字，请调整屏幕内容或框选区域后重试"
            return
        }

        val preview = screen.stem.replace('\n', ' ').take(100)
        tvResult.text = "题目已识别：\n$preview\n\n正在匹配答案..."
        tvResult.text = QuestionSolver.solve(screen)
    }
}
