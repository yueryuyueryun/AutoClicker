// AutoClicker Android 版 · 作者 cui-huajin / yueryuyueryun · MIT License
package com.yueryuyueryun.autoclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

// 无障碍服务：持有悬浮胶囊条，负责手势注入与点击/回放的调度
class ClickerService : AccessibilityService() {

    companion object {
        // 单次点击手势时长，60ms 足够被系统识别为点击且不影响高频连点
        private const val CLICK_DURATION_MS = 60L
        // 双击时第二击相对第一击的延迟，模拟真实双击节奏
        private const val DOUBLE_TAP_GAP_MS = 30L

        // MainActivity 通过静态引用控制胶囊条显隐，服务断开时置空
        var instance: ClickerService? = null
            private set

        // 运行/录制/选点/设置变化回调，供主界面刷新按钮与字段状态
        var onRunningChanged: (() -> Unit)? = null
        var onRecordingChanged: (() -> Unit)? = null
        var onSettingsChanged: (() -> Unit)? = null
        var onTargetPicked: ((Int, Int) -> Unit)? = null
        var onSlotsChanged: (() -> Unit)? = null
    }

    enum class Mode { CONTINUOUS, MACRO }

    var mode = Mode.CONTINUOUS
        private set
    var intervalMs = 100L
    var loops = 0            // 0 表示无限循环，直到手动停止
        set(v) {
            field = if (v < 0) 0 else v
            onSettingsChanged?.invoke()
        }
    var gapMs = 500L         // 宏回放的循环间隔
    var clicksPerPress = 1   // 1=单击 2=双击
    var targetX = 0
    var targetY = 0
    var hasTarget = false
    var isRecording = false
        private set
    var isRunning = false
        private set
    var selectedMacro: MacroStore.Macro? = null
        private set

    private var clickCount = 0
    private var playLoopIndex = 0
    private var playStepIndex = 0

    private val handler = Handler(Looper.getMainLooper())
    private val recordedSteps = ArrayList<MacroStore.Step>()
    private var lastRecordTime = 0L

    private lateinit var overlay: OverlayController
    private lateinit var store: MacroStore

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        store = MacroStore(this)
        overlay = OverlayController(this, getSystemService(WINDOW_SERVICE) as WindowManager)
        // 服务可能被系统重建，坐标从偏好恢复，避免界面显示有坐标而服务认为没选点
        val p = getSharedPreferences("autoclicker", MODE_PRIVATE)
        if (p.getBoolean("thas", false)) {
            targetX = p.getInt("tx", 0)
            targetY = p.getInt("ty", 0)
            hasTarget = true
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 事件类型已最小化，本服务不消费任何事件
    }

    override fun onInterrupt() {
        // 被打断时立即停止，避免继续注入意外点击
        stopRunning()
    }

    override fun onDestroy() {
        stopRunning()
        if (::overlay.isInitialized) overlay.destroyAll()
        handler.removeCallbacksAndMessages(null)
        instance = null
        super.onDestroy()
    }

    fun showBar() {
        if (::overlay.isInitialized) overlay.showBar()
    }

    fun hideBar() {
        if (::overlay.isInitialized) overlay.hideBar()
    }

    // 主界面切换分页时同步运行模式
    fun setMode(macro: Boolean) {
        stopRunning()
        mode = if (macro) Mode.MACRO else Mode.CONTINUOUS
    }

    fun overlayPick() {
        if (::overlay.isInitialized) overlay.showPickLayer()
    }

    fun setTarget(x: Int, y: Int) {
        targetX = x
        targetY = y
        hasTarget = true
        onTargetPicked?.invoke(x, y)
    }

    fun selectMacro(name: String) {
        stopRunning()
        selectedMacro = store.find(name)
    }

    fun saveCurrentRecording(name: String): Boolean {
        if (recordedSteps.isEmpty()) return false
        // 存档携带当前设置的循环次数与循环间隔，与 Windows 版字段一致
        store.save(MacroStore.Macro(name, recordedSteps.toList(), gapMs, loops))
        selectedMacro = store.find(name)
        return true
    }

    fun deleteSelectedMacro(): Boolean {
        val name = selectedMacro?.name ?: return false
        store.delete(name)
        selectedMacro = null
        return true
    }

    fun recordedStepCount(): Int = recordedSteps.size

    fun macroNames(): List<String> = store.names()

    fun start() {
        if (isRunning) return
        when (mode) {
            Mode.CONTINUOUS -> {
                if (!hasTarget) {
                    if (::overlay.isInitialized) overlay.toast("请先用「点击位置」确定坐标")
                    return
                }
                isRunning = true
                clickCount = 0
                handler.postDelayed(continuousTick, intervalMs)
            }
            Mode.MACRO -> {
                val macro = selectedMacro
                if (macro == null || macro.steps.isEmpty()) {
                    if (::overlay.isInitialized) overlay.toast("请先在「回放存档」中选择宏")
                    return
                }
                isRunning = true
                playLoopIndex = 0
                playStepIndex = 0
                handler.postDelayed(macroTick, macro.steps[0].interval.coerceAtLeast(1L))
            }
        }
        onRunningChanged?.invoke()
        if (::overlay.isInitialized) overlay.refreshState()
    }

    fun stop() {
        stopRunning()
    }

    private fun stopRunning() {
        if (!isRunning) return
        isRunning = false
        handler.removeCallbacks(continuousTick)
        handler.removeCallbacks(macroTick)
        onRunningChanged?.invoke()
        if (::overlay.isInitialized) overlay.refreshState()
    }

    // 连续点击：固定点按 intervalMs 间隔重复，loops=0 表示无限
    private val continuousTick = object : Runnable {
        override fun run() {
            if (!isRunning) return
            dispatchClick(targetX, targetY)
            clickCount++
            if (loops > 0 && clickCount >= loops) {
                stopRunning()
                return
            }
            handler.postDelayed(this, intervalMs)
        }
    }

    // 宏回放：按每步 interval 依次点击，一轮结束后等待 gap 再进入下一轮
    private val macroTick = object : Runnable {
        override fun run() {
            if (!isRunning) return
            val macro = selectedMacro ?: run { stopRunning(); return }
            if (macro.steps.isEmpty()) {
                stopRunning()
                return
            }
            val step = macro.steps[playStepIndex]
            dispatchClick(step.x, step.y)
            playStepIndex++
            if (playStepIndex < macro.steps.size) {
                handler.postDelayed(this, macro.steps[playStepIndex].interval.coerceAtLeast(1L))
            } else {
                playStepIndex = 0
                playLoopIndex++
                if (macro.loops > 0 && playLoopIndex >= macro.loops) {
                    stopRunning()
                    return
                }
                handler.postDelayed(this, macro.gap.coerceAtLeast(1L))
            }
        }
    }

    // 核心原理：无障碍服务可向系统注入任意手势，无需 root
    private fun dispatchClick(x: Int, y: Int) {
        val builder = GestureDescription.Builder()
        val p1 = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        builder.addStroke(GestureDescription.StrokeDescription(p1, 0L, CLICK_DURATION_MS))
        if (clicksPerPress == 2) {
            val p2 = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            builder.addStroke(
                GestureDescription.StrokeDescription(p2, CLICK_DURATION_MS + DOUBLE_TAP_GAP_MS, CLICK_DURATION_MS)
            )
        }
        dispatchGesture(builder.build(), null, handler)
    }

    // 录制：捕获层把按下坐标转给这里，interval 记录与上一步的时间差
    fun startRecording() {
        stopRunning()
        recordedSteps.clear()
        lastRecordTime = SystemClock.uptimeMillis()
        isRecording = true
        overlay.showRecordLayer()
        onRecordingChanged?.invoke()
        if (::overlay.isInitialized) overlay.refreshState()
    }

    fun onRecordTouch(x: Int, y: Int) {
        if (!isRecording) return
        val now = SystemClock.uptimeMillis()
        // 第一步 interval 记 0，回放时会 coerce 到至少 1ms
        val interval = if (recordedSteps.isEmpty()) 0L else now - lastRecordTime
        lastRecordTime = now
        recordedSteps.add(MacroStore.Step(x, y, interval))
    }

    fun stopRecording() {
        isRecording = false
        val target = selectedMacro
        if (::overlay.isInitialized) {
            overlay.hideRecordLayer()
            if (target != null && recordedSteps.isNotEmpty()) {
                // 与电脑版一致：已选中存档时录制结果直接写回该存档
                store.save(MacroStore.Macro(target.name, recordedSteps.toList(), target.gap, target.loops))
                selectedMacro = store.find(target.name)
                overlay.toast("已录制 ${recordedSteps.size} 步，写入存档「${target.name}」")
                onSlotsChanged?.invoke()
            } else {
                overlay.toast("已录制 ${recordedSteps.size} 步，可在「新建存档」中保存")
            }
        }
        onRecordingChanged?.invoke()
        if (::overlay.isInitialized) overlay.refreshState()
        // 与电脑版 _exit_compact 一致：停止录制后主窗口自动回到前台
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(it)
        }
    }

    // 录制穿透：把用户这次点击原样注入给下层应用，让被点页面产生真实反馈
    fun injectTap(x: Int, y: Int, onDone: () -> Unit) {
        val builder = GestureDescription.Builder()
        val p = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        builder.addStroke(GestureDescription.StrokeDescription(p, 0L, CLICK_DURATION_MS))
        dispatchGesture(builder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { onDone() }
            override fun onCancelled(gestureDescription: GestureDescription?) { onDone() }
        }, handler)
    }
}
