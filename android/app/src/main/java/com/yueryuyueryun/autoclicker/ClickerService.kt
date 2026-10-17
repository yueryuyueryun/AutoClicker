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

// 无障碍服务：持有悬浮控制条，负责手势注入与点击/回放的调度
class ClickerService : AccessibilityService() {

    companion object {
        // 单次点击手势时长，60ms 足够被系统识别为点击且不影响高频连点
        private const val CLICK_DURATION_MS = 60L

        // MainActivity 通过静态引用控制控制条显隐，服务断开时置空
        var instance: ClickerService? = null
            private set
    }

    enum class Mode { CONTINUOUS, MACRO }

    var mode = Mode.CONTINUOUS
        private set
    var intervalMs = 100L
    var loops = 0            // 0 表示无限循环，直到手动停止
    var gapMs = 500L         // 宏回放的循环间隔
    var targetX = 0
    var targetY = 0
    var hasTarget = false
    var isRecording = false
        private set
    var selectedMacro: MacroStore.Macro? = null
        private set

    private var running = false
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
        overlay.showBar()
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

    fun toggleMode() {
        // 切模式前先停，避免旧调度任务在新模式下继续跑
        stopRunning()
        mode = if (mode == Mode.CONTINUOUS) Mode.MACRO else Mode.CONTINUOUS
    }

    fun setTarget(x: Int, y: Int) {
        targetX = x
        targetY = y
        hasTarget = true
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
        if (running) return
        when (mode) {
            Mode.CONTINUOUS -> {
                if (!hasTarget) {
                    overlay.toast("请先用[选点]确定点击位置")
                    return
                }
                running = true
                clickCount = 0
                handler.postDelayed(continuousTick, intervalMs)
            }
            Mode.MACRO -> {
                val macro = selectedMacro
                if (macro == null || macro.steps.isEmpty()) {
                    overlay.toast("请先在[存档]中选择宏")
                    return
                }
                running = true
                playLoopIndex = 0
                playStepIndex = 0
                handler.postDelayed(macroTick, macro.steps[0].interval.coerceAtLeast(1L))
            }
        }
        overlay.refreshState()
    }

    fun stop() {
        stopRunning()
    }

    private fun stopRunning() {
        running = false
        handler.removeCallbacks(continuousTick)
        handler.removeCallbacks(macroTick)
        if (::overlay.isInitialized) overlay.refreshState()
    }

    // 连续点击：固定点按 intervalMs 间隔重复，loops=0 表示无限
    private val continuousTick = object : Runnable {
        override fun run() {
            if (!running) return
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
            if (!running) return
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
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, CLICK_DURATION_MS)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, handler)
    }

    // 录制：捕获层把按下坐标转给这里，interval 记录与上一步的时间差
    fun startRecording() {
        recordedSteps.clear()
        lastRecordTime = SystemClock.uptimeMillis()
        isRecording = true
        overlay.showRecordLayer()
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
        overlay.hideRecordLayer()
        overlay.toast("已录制 ${recordedSteps.size} 步，可在[存档]中保存")
    }
}
