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
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
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
    private var searchJob: Job? = null
    private var generation = 0L

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
            // Reopening while a selection is pending cancels that session.
            dismiss()
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
        generation++
        searchJob?.cancel()
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
        fun expand(show: Boolean) {
            view.findViewById<View>(R.id.float_ball).visibility = if (show) View.GONE else View.VISIBLE
            view.findViewById<View>(R.id.float_panel).visibility = if (show) View.VISIBLE else View.GONE
        }
        view.findViewById<View>(R.id.float_ball).setOnClickListener {
            expand(true)
            search(tvResult)
        }
        view.findViewById<View>(R.id.float_ball).setOnLongClickListener { dismiss(); true }
        view.findViewById<Button>(R.id.btn_close).setOnClickListener { expand(false) }
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
        if (searchJob?.isActive == true) return
        val request = generation
        searchJob = AppScope.launch {
            var full: Bitmap? = null
            var cropped: Bitmap? = null
            try {
                val service = ScreenCaptureService.instance
                if (service == null || !service.isReady()) {
                    tvResult.text = "截屏会话已结束，请回首页重新授权"
                    return@launch
                }
                EasyFloat.hide(TAG)
                delay(300)
                val frame = service.captureBitmap()
                full = frame
                if (frame == null) {
                    tvResult.text = "未获取到屏幕图像，请重试或重新授权"
                    return@launch
                }
                val region = when (Config.regionMode) {
                    Config.REGION_MODE_SELECT -> selectRegion(frame)
                    Config.REGION_MODE_LAST -> Config.lastRegionRect?.let { saved ->
                        Rect((saved.left * frame.width).toInt(), (saved.top * frame.height).toInt(),
                            (saved.right * frame.width).toInt(), (saved.bottom * frame.height).toInt())
                    } ?: Rect(0, 0, frame.width, frame.height)
                    else -> Rect(0, 0, frame.width, frame.height)
                }
                if (region == null) { tvResult.text = "已取消识别"; return@launch }
                cropped = QuestionSolver.cropRegion(frame, region)
                EasyFloat.show(TAG)
                if (request == generation) runSearch(cropped!!, tvResult)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (request == generation) tvResult.text = "识别未完成：${error.message}"
            } finally {
                val crop = cropped
                if (crop != null && crop !== full && !crop.isRecycled) crop.recycle()
                full?.let { if (!it.isRecycled) it.recycle() }
                if (request == generation && EasyFloat.getFloatView(TAG)?.isAttachedToWindow == true) EasyFloat.show(TAG)
            }
        }
    }

    private suspend fun selectRegion(bitmap: Bitmap): Rect? = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { RegionSelectOverlay.forceDismiss() }
        RegionSelectOverlay.show(AppContext.get(), bitmap, Config.lastRegionRect,
            object : RegionSelectOverlay.Callback {
                override fun onConfirm(regionPx: Rect) {
                    if (continuation.isActive) {
                        Config.lastRegionRect = normalized(regionPx, bitmap)
                        continuation.resume(regionPx)
                    }
                }
                override fun onCancel() { if (continuation.isActive) continuation.resume(null) }
            })
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
        } catch (e: CancellationException) {
            throw e
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
