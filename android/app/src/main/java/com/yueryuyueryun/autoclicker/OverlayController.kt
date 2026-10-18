// AutoClicker Android 版 · 作者 cui-huajin / yueryuyueryun · MIT License
package com.yueryuyueryun.autoclicker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

// 悬浮层：执行胶囊条 / 录制胶囊条 / 准星选点层 / 录制捕获层，样式与电脑版卡片同源
class OverlayController(
    private val service: ClickerService,
    private val wm: WindowManager
) {

    private val overlayType =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private var barView: View? = null
    private var barParams: WindowManager.LayoutParams? = null
    private var barX = 40
    private var barY = 300

    private var pickView: View? = null
    private var recordView: View? = null

    private var loopEdit: TextView? = null
    private var loopUnit: TextView? = null

    fun showBar() {
        hideBar()
        val bar = if (service.isRecording) buildRecordPill() else buildExecPill()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = barX
            y = barY
        }
        barView = bar
        barParams = params
        wm.addView(bar, params)
    }

    fun hideBar() {
        barView?.let { removeViewQuietly(it) }
        barView = null
        barParams = null
    }

    fun refreshState() {
        // 胶囊条内容随录制/执行状态整体重建，保证按钮语义与电脑版一致
        if (service.isRecording || service.isRunning) showBar() else hideBar()
    }

    fun toast(msg: String) {
        Toast.makeText(service, msg, Toast.LENGTH_SHORT).show()
    }

    fun destroyAll() {
        hideBar()
        hidePickLayer()
        hideRecordLayer()
    }

    // ---------------- 执行胶囊条（电脑版紧凑胶囊同源） ----------------

    private fun buildExecPill(): View {
        val ctx = service
        val root = pillRoot(ctx)
        root.addView(pillButton(ctx, "终止执行", danger = true) { service.stop() })
        root.addView(ghostButton(ctx, "返回应用") {
            hideBar()
            openApp()
        }.apply { margin() })
        root.addView(pillButton(ctx, "再执行一次") { service.start() }.apply { margin() })
        root.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))

        val frame = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = solid(ctx, Theme.pal.field, 9f, Theme.pal.fieldBorder)
            setPadding(dp(ctx, 10f), 0, dp(ctx, 10f), 0)
        }
        // 悬浮胶囊条为 NOT_FOCUSABLE，无法弹出输入法，故此处只读展示次数；
        // 次数的编辑入口在主界面「循环次数」行，两处数值经回调保持一致
        loopEdit = TextView(ctx).apply {
            text = service.loops.toString()
            textSize = 13f
            setTextColor(Theme.pal.fieldText)
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            background = solid(ctx, Theme.pal.listBg, 7f, Theme.pal.fieldBorder)
            setPadding(dp(ctx, 6f), dp(ctx, 4f), dp(ctx, 6f), dp(ctx, 4f))
        }
        frame.addView(loopEdit, dp(ctx, 52f), dp(ctx, 26f))
        loopUnit = TextView(ctx).apply {
            text = if (service.loops == 0) "无限" else "次"
            textSize = 12f
            setTextColor(Theme.pal.dim)
            setPadding(dp(ctx, 5f), 0, 0, 0)
        }
        frame.addView(loopUnit)
        root.addView(frame)
        return root
    }

    private fun buildRecordPill(): View {
        val ctx = service
        val root = pillRoot(ctx)
        root.addView(pillButton(ctx, "停止录制", danger = true) { service.stopRecording() })
        return root
    }

    private fun pillRoot(ctx: Context): LinearLayout {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = solid(ctx, Theme.pal.card, 16f, Theme.pal.cardBorder)
            elevation = dp(ctx, 8f).toFloat()
            setPadding(dp(ctx, 8f), dp(ctx, 6f), dp(ctx, 10f), dp(ctx, 6f))
        }
        // 空白处按下即拖动整条，按钮各自消费点击
        root.setOnTouchListener(BarDragListener())
        return root
    }

    private fun View.margin() {
        (layoutParams as? LinearLayout.LayoutParams)?.leftMargin = dp(service, 6f)
        if (layoutParams == null) {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(service, 6f) }
        } else {
            (layoutParams as LinearLayout.LayoutParams).leftMargin = dp(service, 6f)
        }
    }

    private fun openApp() {
        val intent = service.packageManager.getLaunchIntentForPackage(service.packageName)
        intent?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        intent?.let { service.startActivity(it) }
    }

    private inner class BarDragListener : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var originX = 0
        private var originY = 0

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    originX = barX
                    originY = barY
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    barX = originX + (event.rawX - downRawX).toInt()
                    barY = originY + (event.rawY - downRawY).toInt()
                    val view = barView ?: return false
                    val params = barParams ?: return false
                    params.x = barX
                    params.y = barY
                    wm.updateViewLayout(view, params)
                    return true
                }
            }
            return false
        }
    }

    // ---------------- 准星选点层 ----------------

    fun showPickLayer() {
        hidePickLayer()
        val view = PickView(service) { x, y ->
            service.setTarget(x, y)
            hidePickLayer()
            toast("已选点 ($x, $y)")
        }
        pickView = view
        wm.addView(view, fullScreenParams())
    }

    fun hidePickLayer() {
        pickView?.let { removeViewQuietly(it) }
        pickView = null
    }

    // 准星跟随手指，抬手确认；rawX/rawY 与 dispatchGesture 屏幕坐标系一致
    private inner class PickView(
        ctx: Context,
        private val onPicked: (Int, Int) -> Unit
    ) : View(ctx) {

        private val paint = Paint().apply {
            color = Theme.gradStart
            strokeWidth = dp(ctx, 2f).toFloat()
            style = Paint.Style.STROKE
            isAntiAlias = true
        }
        private var crossX = -1f
        private var crossY = -1f

        init {
            setBackgroundColor(Color.argb(22, 91, 124, 250))
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    crossX = event.rawX
                    crossY = event.rawY
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    onPicked(event.rawX.toInt(), event.rawY.toInt())
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = if (crossX >= 0f) crossX else width / 2f
            val cy = if (crossY >= 0f) crossY else height / 2f
            val arm = dp(context, 28f).toFloat()
            canvas.drawLine(cx - arm, cy, cx + arm, cy, paint)
            canvas.drawLine(cx, cy - arm, cx, cy + arm, paint)
            canvas.drawCircle(cx, cy, arm * 0.6f, paint)
        }
    }

    // ---------------- 录制捕获层 ----------------

    fun showRecordLayer() {
        hideRecordLayer()
        val ctx = service
        val root = FrameLayout(ctx)
        root.setBackgroundColor(Color.argb(28, 91, 124, 250))
        val hint = TextView(ctx).apply {
            text = "录制中 · 点击屏幕记录触点"
            textSize = 13f
            setTextColor(Theme.pal.text)
            background = solid(ctx, Theme.pal.card, 12f, Theme.pal.cardBorder)
            setPadding(dp(ctx, 16f), dp(ctx, 8f), dp(ctx, 16f), dp(ctx, 8f))
        }
        root.addView(
            hint,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply { topMargin = dp(ctx, 72f) }
        )
        root.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_DOWN) {
                service.onRecordTouch(ev.rawX.toInt(), ev.rawY.toInt())
                true
            } else {
                false
            }
        }
        recordView = root
        wm.addView(root, fullScreenParams())
        // 录制层盖住胶囊条，重挂一次把[停止录制]抬回最上层
        barView?.let { bar ->
            barParams?.let { p ->
                removeViewQuietly(bar)
                wm.addView(bar, p)
            }
        }
    }

    fun hideRecordLayer() {
        recordView?.let { removeViewQuietly(it) }
        recordView = null
    }

    // ---------------- 工具 ----------------

    private fun fullScreenParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun dp(ctx: Context, v: Float): Int =
        (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    private fun removeViewQuietly(view: View) {
        try {
            wm.removeView(view)
        } catch (e: IllegalArgumentException) {
            // 视图可能已被系统移除
        }
    }
}
