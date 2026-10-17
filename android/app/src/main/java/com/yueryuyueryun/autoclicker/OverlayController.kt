// AutoClicker Android 版 · 作者 cui-huajin / yueryuyueryun · MIT License
package com.yueryuyueryun.autoclicker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

// 全部悬浮窗（控制条/设置面板/存档面板/选点层/录制层）的构建与触摸逻辑，纯代码布局避免额外 res 文件
class OverlayController(
    private val service: ClickerService,
    private val wm: WindowManager
) {

    // Android 8 以下没有 TYPE_APPLICATION_OVERLAY，退回 TYPE_PHONE
    private val overlayType =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private var barView: View? = null
    private var barParams: WindowManager.LayoutParams? = null
    private var barX = 0
    private var barY = 300

    private var settingsView: View? = null
    private var macroPanelView: View? = null
    private var pickView: View? = null
    private var recordView: View? = null

    private var btnMode: Button? = null
    private var btnRecord: Button? = null
    private var btnStart: Button? = null

    fun showBar() {
        hideBar()
        val bar = buildBar()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            // 控制条不需要输入焦点，避免挡住下层应用的按键事件
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
        refreshState()
    }

    fun hideBar() {
        barView?.let { removeViewQuietly(it) }
        barView = null
        barParams = null
    }

    fun destroyAll() {
        hideBar()
        hideSettingsPanel()
        hideMacroPanel()
        hidePickLayer()
        hideRecordLayer()
    }

    fun refreshState() {
        btnMode?.text =
            if (service.mode == ClickerService.Mode.CONTINUOUS) "模式:连点" else "模式:宏"
        btnRecord?.text = if (service.isRecording) "停止录制" else "录制"
    }

    fun toast(msg: String) {
        Toast.makeText(service, msg, Toast.LENGTH_SHORT).show()
    }

    // ---------------- 控制条 ----------------

    private fun buildBar(): View {
        val ctx = service
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundBackground(Color.argb(225, 33, 33, 33), 28f)
            setPadding(dp(6f), dp(4f), dp(6f), dp(4f))
        }

        // 拖动手柄：只有它响应拖动，避免和按钮点击冲突
        val drag = TextView(ctx).apply {
            text = "⠿"
            setTextColor(Color.WHITE)
            textSize = 20f
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
        }
        drag.setOnTouchListener(BarDragListener())
        root.addView(drag)

        val scroll = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }

        btnMode = barButton(ctx, "模式:连点") {
            service.toggleMode()
            refreshState()
        }
        val btnSettings = barButton(ctx, "设置") { showSettingsPanel() }
        val btnPick = barButton(ctx, "选点") { showPickLayer() }
        btnRecord = barButton(ctx, "录制") {
            if (service.isRecording) service.stopRecording() else service.startRecording()
            refreshState()
        }
        val btnMacro = barButton(ctx, "存档") { showMacroPanel() }
        btnStart = barButton(ctx, "开始") { service.start() }
        val btnStop = barButton(ctx, "停止") { service.stop() }

        for (b in listOf(btnMode!!, btnSettings, btnPick, btnRecord!!, btnMacro, btnStart!!, btnStop)) {
            row.addView(b)
        }
        scroll.addView(row)
        root.addView(scroll)
        return root
    }

    private fun barButton(ctx: Context, text: String, onClick: () -> Unit): Button {
        return Button(ctx).apply {
            this.text = text
            textSize = 12f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = roundBackground(Color.argb(255, 66, 66, 66), 16f)
            setPadding(dp(10f), dp(2f), dp(10f), dp(2f))
            minimumWidth = 0
            minWidth = 0
            minimumHeight = 0
            minHeight = 0
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(4f) }
            layoutParams = lp
            setOnClickListener { onClick() }
        }
    }

    // 拖动时直接改 LayoutParams 的 x/y，比 translation 更稳（不受父窗口裁剪影响）
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

    // 录制层会盖住先添加的控制条，重新 add 一次把控制条抬回最上层，保证[停止录制]可按
    private fun bringBarToFront() {
        val view = barView ?: return
        val params = barParams ?: return
        removeViewQuietly(view)
        wm.addView(view, params)
    }

    // ---------------- 设置面板 ----------------

    fun showSettingsPanel() {
        hideSettingsPanel()
        val ctx = service
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = roundBackground(Color.argb(245, 250, 250, 250), 16f)
            setPadding(dp(20f), dp(16f), dp(20f), dp(16f))
        }
        panel.addView(panelTitle(ctx, "设置"))

        val etInterval = numberInput(ctx, "点击间隔(毫秒)", service.intervalMs.toString())
        val etLoops = numberInput(ctx, "循环次数(0=无限)", service.loops.toString())
        val etGap = numberInput(ctx, "宏循环间隔(毫秒)", service.gapMs.toString())
        panel.addView(etInterval)
        panel.addView(etLoops)
        panel.addView(etGap)

        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(panelButton(ctx, "取消") { hideSettingsPanel() })
        row.addView(panelButton(ctx, "确定") {
            // 间隔设下限，防止 0ms 把手势队列打爆
            etInterval.text.toString().toLongOrNull()?.let { if (it >= 10L) service.intervalMs = it }
            etLoops.text.toString().toIntOrNull()?.let { if (it >= 0) service.loops = it }
            etGap.text.toString().toLongOrNull()?.let { if (it >= 0L) service.gapMs = it }
            hideSettingsPanel()
            toast("设置已保存")
        })
        panel.addView(row)

        settingsView = panel
        wm.addView(panel, panelParams())
    }

    fun hideSettingsPanel() {
        settingsView?.let { removeViewQuietly(it) }
        settingsView = null
    }

    // ---------------- 存档面板 ----------------

    fun showMacroPanel() {
        hideMacroPanel()
        val ctx = service
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = roundBackground(Color.argb(245, 250, 250, 250), 16f)
            setPadding(dp(20f), dp(16f), dp(20f), dp(16f))
        }
        panel.addView(panelTitle(ctx, "宏存档"))

        val tvSelected = TextView(ctx).apply {
            textSize = 13f
            setPadding(0, dp(4f), 0, dp(8f))
        }
        panel.addView(tvSelected)

        val listContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(ctx).apply {
            addView(listContainer)
        }
        panel.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(200f)
            )
        )

        val etName = EditText(ctx).apply {
            hint = "存档名称"
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT
        }
        panel.addView(etName)

        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(panelButton(ctx, "保存当前录制") {
            val name = etName.text.toString().trim()
                .ifEmpty { service.selectedMacro?.name ?: "" }
            if (name.isEmpty()) {
                toast("请输入存档名称")
                return@panelButton
            }
            if (service.saveCurrentRecording(name)) {
                toast("已保存存档[$name]")
                fillMacroList(ctx, listContainer, tvSelected, etName)
            } else {
                toast("没有已录制的步骤，请先录制")
            }
        })
        row.addView(panelButton(ctx, "删除选中") {
            if (service.deleteSelectedMacro()) {
                toast("已删除")
                fillMacroList(ctx, listContainer, tvSelected, etName)
            } else {
                toast("请先选择一个存档")
            }
        })
        row.addView(panelButton(ctx, "关闭") { hideMacroPanel() })
        panel.addView(row)

        fillMacroList(ctx, listContainer, tvSelected, etName)

        macroPanelView = panel
        wm.addView(panel, panelParams())
    }

    private fun fillMacroList(
        ctx: Context,
        container: LinearLayout,
        tvSelected: TextView,
        etName: EditText
    ) {
        container.removeAllViews()
        val names = service.macroNames()
        tvSelected.text = "当前选中: ${service.selectedMacro?.name ?: "无"}"
        etName.setText(service.selectedMacro?.name ?: "")
        if (names.isEmpty()) {
            container.addView(TextView(ctx).apply {
                text = "暂无存档，录制后点[保存当前录制]"
                textSize = 13f
            })
            return
        }
        for (name in names) {
            val macro = MacroStore(ctx).find(name)
            val label = "$name (${macro?.steps?.size ?: 0}步)"
            container.addView(panelButton(ctx, label) {
                service.selectMacro(name)
                toast("已选择存档[$name]")
                fillMacroList(ctx, container, tvSelected, etName)
            })
        }
    }

    fun hideMacroPanel() {
        macroPanelView?.let { removeViewQuietly(it) }
        macroPanelView = null
    }

    // ---------------- 选点层（准星） ----------------

    fun showPickLayer() {
        hidePickLayer()
        val view = PickView(service) { x, y ->
            service.setTarget(x, y)
            hidePickLayer()
            refreshState()
            toast("已选点 ($x, $y)")
        }
        pickView = view
        wm.addView(view, fullScreenParams())
    }

    fun hidePickLayer() {
        pickView?.let { removeViewQuietly(it) }
        pickView = null
    }

    // 准星跟随手指移动，抬手确认坐标；用 rawX/rawY 保证与 dispatchGesture 的屏幕坐标系一致
    private inner class PickView(
        ctx: Context,
        private val onPicked: (Int, Int) -> Unit
    ) : View(ctx) {

        private val paint = Paint().apply {
            color = Color.RED
            strokeWidth = dp(2f).toFloat()
            style = Paint.Style.STROKE
        }
        private var crossX = -1f
        private var crossY = -1f

        init {
            setBackgroundColor(Color.argb(25, 255, 0, 0))
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
            val arm = dp(28f).toFloat()
            canvas.drawLine(cx - arm, cy, cx + arm, cy, paint)
            canvas.drawLine(cx, cy - arm, cx, cy + arm, paint)
            canvas.drawCircle(cx, cy, arm * 0.6f, paint)
        }
    }

    // ---------------- 录制层 ----------------

    fun showRecordLayer() {
        hideRecordLayer()
        val ctx = service
        val root = FrameLayout(ctx)
        // 半透明蓝色提示正在录制，同时尽量不干扰用户看清下层界面
        root.setBackgroundColor(Color.argb(36, 0, 120, 255))
        val hint = TextView(ctx).apply {
            text = "录制中：点击屏幕记录触点，按控制条[停止录制]结束"
            setTextColor(Color.WHITE)
            textSize = 15f
        }
        root.addView(
            hint,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply { topMargin = dp(72f) }
        )
        root.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_DOWN) {
                // 录制坐标同样取 rawX/rawY，与注入手势坐标系一致
                service.onRecordTouch(ev.rawX.toInt(), ev.rawY.toInt())
                true
            } else {
                false
            }
        }
        recordView = root
        wm.addView(root, fullScreenParams())
        bringBarToFront()
        refreshState()
    }

    fun hideRecordLayer() {
        recordView?.let { removeViewQuietly(it) }
        recordView = null
        refreshState()
    }

    // ---------------- 通用工具 ----------------

    private fun panelTitle(ctx: Context, text: String): TextView {
        return TextView(ctx).apply {
            this.text = text
            textSize = 16f
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, dp(8f))
        }
    }

    private fun numberInput(ctx: Context, hint: String, value: String): EditText {
        return EditText(ctx).apply {
            this.hint = hint
            setText(value)
            textSize = 14f
            inputType = InputType.TYPE_CLASS_NUMBER
        }
    }

    private fun panelButton(ctx: Context, text: String, onClick: () -> Unit): Button {
        return Button(ctx).apply {
            this.text = text
            textSize = 13f
            isAllCaps = false
            setOnClickListener { onClick() }
        }
    }

    // 面板需要接收输入焦点才能弹出软键盘；NOT_TOUCH_MODAL 让面板外的触摸照常传给下层应用
    private fun panelParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            dp(300f),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }
    }

    // 全屏层：铺满整块屏幕（含状态栏区域），这样 rawX/rawY 与视图坐标一致
    private fun fullScreenParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
    }

    private fun roundBackground(color: Int, radiusDp: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }
    }

    private fun dp(v: Float): Int = (v * service.resources.displayMetrics.density + 0.5f).toInt()

    // 视图可能已被系统移除，重复 remove 会抛异常，这里统一吞掉
    private fun removeViewQuietly(view: View) {
        try {
            wm.removeView(view)
        } catch (e: IllegalArgumentException) {
            // 忽略：视图本来就不在窗口上
        }
    }
}
