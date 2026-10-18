// AutoClicker Android 版 · 作者 cui-huajin / yueryuyueryun · MIT License
package com.yueryuyueryun.autoclicker

import android.accessibilityservice.AccessibilityServiceInfo
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

// 主界面：与电脑版 windows/app.py 的卡片布局、配色、页转与弹窗逐件对齐
class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var card: LinearLayout
    private lateinit var pageHost: FrameLayout
    private lateinit var panelCont: LinearLayout
    private lateinit var panelMacro: LinearLayout
    private lateinit var modeSeg: SegView
    private lateinit var unitSeg: SegView
    private lateinit var typeSeg: SegView
    private lateinit var freqEdit: EditText
    private lateinit var pickBtn: TextView
    private lateinit var recordBtn: TextView
    private lateinit var slotBox: LinearLayout
    private lateinit var gapEdit: EditText
    private lateinit var loopEdit: EditText
    private lateinit var loopUnit: TextView
    private lateinit var startBtn: TextView
    private lateinit var permRow: LinearLayout
    private lateinit var themeBtn: TextView

    private var mode = 0
    private var pageAnim: ValueAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("autoclicker", Context.MODE_PRIVATE)
        Theme.dark = prefs.getBoolean("theme_dark", false)
        mode = prefs.getInt("mode", 0)
        buildUi()
        ClickerService.instance?.let { pushSettings(it) }
    }

    override fun onResume() {
        super.onResume()
        ClickerService.onRunningChanged = { runOnUiThread { syncStart() } }
        ClickerService.onRecordingChanged = { runOnUiThread { syncRecord() } }
        ClickerService.onTargetPicked = { x, y ->
            prefs.edit().putInt("tx", x).putInt("ty", y).putBoolean("thas", true).apply()
            runOnUiThread { syncPick() }
        }
        ClickerService.onSettingsChanged = { runOnUiThread { syncLoopField() } }
        syncPermRow()
        syncStart()
        syncRecord()
        syncPick()
        refreshSlots()
        ClickerService.instance?.let { pushSettings(it) }
    }

    override fun onDestroy() {
        ClickerService.onRunningChanged = null
        ClickerService.onRecordingChanged = null
        ClickerService.onTargetPicked = null
        ClickerService.onSettingsChanged = null
        super.onDestroy()
    }

    // ---------------- 界面 ----------------

    private fun buildUi() {
        val p = Theme.pal
        window.statusBarColor = p.pageBg
        if (!Theme.dark) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }

        val root = FrameLayout(this).apply { setBackgroundColor(p.pageBg) }
        card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = solid(this@MainActivity, p.card, 18f, p.cardBorder)
            elevation = dp(this@MainActivity, 10f).toFloat()
            setPadding(dp(this@MainActivity, 22f), dp(this@MainActivity, 16f),
                dp(this@MainActivity, 22f), dp(this@MainActivity, 20f))
        }
        root.addView(
            card,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                leftMargin = dp(this@MainActivity, 12f); rightMargin = dp(this@MainActivity, 12f)
                topMargin = dp(this@MainActivity, 12f); bottomMargin = dp(this@MainActivity, 12f)
            }
        )
        setContentView(root)

        buildTitleBar()
        buildPermRow()
        addView(card, modeSegView(), height = dp(this, 36f), bottomMargin = dp(this, 12f))

        pageHost = FrameLayout(this).apply { clipChildren = true }
        panelCont = buildContinuousPanel()
        panelMacro = buildMacroPanel()
        pageHost.addView(panelCont, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        pageHost.addView(panelMacro, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        panelMacro.visibility = if (mode == 1) View.VISIBLE else View.GONE
        panelCont.visibility = if (mode == 1) View.GONE else View.VISIBLE
        addView(card, pageHost, weight = 1f, bottomMargin = dp(this, 14f))

        startBtn = pillButton(this, "开始连点") { toggleStart() }
        startBtn.textSize = 15f
        addView(card, startBtn, height = dp(this, 48f))
        syncStart()
    }

    private fun buildTitleBar() {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = grad(this@MainActivity, 6f)
            setPadding(dp(this@MainActivity, 3f), dp(this@MainActivity, 3f),
                dp(this@MainActivity, 3f), dp(this@MainActivity, 3f))
            setOnClickListener { showAbout() }
        }
        bar.addView(logo, dp(this, 24f), dp(this, 24f))
        val title = TextView(this).apply {
            text = "AutoClicker 连点器"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Theme.pal.text)
            setPadding(dp(this@MainActivity, 10f), 0, 0, 0)
        }
        bar.addView(title)
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        themeBtn = winButton(if (Theme.dark) "☀" else "☾") {
            Theme.dark = !Theme.dark
            prefs.edit().putBoolean("theme_dark", Theme.dark).apply()
            recreate()
        }
        bar.addView(themeBtn)
        bar.addView(winButton("─") {
            ClickerService.instance?.showBar()
            moveTaskToBack(true)
        })
        bar.addView(winButton("✕") {
            ClickerService.instance?.showBar()
            finish()
        })
        addView(card, bar, bottomMargin = dp(this, 14f))
    }

    private fun winButton(glyph: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = glyph
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Theme.pal.dim)
            background = solid(this@MainActivity, Theme.pal.card, 8f)
            setOnClickListener { onClick() }
            setOnTouchListener(PressDim(this))
        }.also { it.setPadding(0, 0, 0, 0) }
            .apply {
                layoutParams = LinearLayout.LayoutParams(dp(this@MainActivity, 28f), dp(this@MainActivity, 28f))
                    .apply { leftMargin = dp(this@MainActivity, 6f) }
            }

    private fun buildPermRow() {
        permRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val a11y = ghostButton(this, "开启无障碍服务") {
            toast("请在列表中找到 AutoClicker 并开启")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        val overlay = ghostButton(this, "开启悬浮窗权限") {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
        permRow.addView(a11y, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginEnd = dp(this@MainActivity, 8f) })
        permRow.addView(overlay, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(card, permRow, bottomMargin = dp(this, 12f))
        syncPermRow()
    }

    private fun syncPermRow() {
        val need = !isAccessibilityEnabled() || !Settings.canDrawOverlays(this)
        permRow.visibility = if (need) View.VISIBLE else View.GONE
    }

    private fun modeSegView(): View {
        modeSeg = SegView(this, listOf("连续点击", "宏回放"), mode) { i -> switchMode(i) }
        return modeSeg.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(this@MainActivity, 36f)
            )
        }
    }

    private fun buildContinuousPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        panel.addView(sectionLabel(this, "点击频率"))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        freqEdit = fieldInput(this, number = true).apply {
            setText(prefs.getString("freq", "100"))
        }
        row.addView(freqEdit, LinearLayout.LayoutParams(0, dp(this, 38f), 1f)
            .apply { marginEnd = dp(this@MainActivity, 8f) })
        unitSeg = SegView(this, listOf("毫秒 / 次", "次 / 秒"), prefs.getInt("unit", 0)) { applyFreq() }
        row.addView(unitSeg, LinearLayout.LayoutParams(dp(this, 138f), dp(this, 36f)))
        panel.addView(row, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        panel.addView(space(dp(this, 14f)))

        panel.addView(sectionLabel(this, "点击方式"))
        typeSeg = SegView(this, listOf("单击", "双击"), prefs.getInt("ctype", 0)) { i ->
            prefs.edit().putInt("ctype", i).apply()
            ClickerService.instance?.clicksPerPress = if (i == 1) 2 else 1
        }
        panel.addView(typeSeg, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(this, 36f)))
        panel.addView(space(dp(this, 14f)))

        panel.addView(sectionLabel(this, "点击位置"))
        pickBtn = ghostButton(this, "点击位置：未选择") {
            val svc = ClickerService.instance
            if (svc == null) toast("请先开启无障碍服务") else svc.overlayPick()
        }
        panel.addView(pickBtn, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        panel.addView(space(0, 1f))
        freqEdit.addTextChangedListener(afterChanged = { applyFreq() })
        return panel
    }

    private fun buildMacroPanel(): LinearLayout {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        recordBtn = pillButton(this, "开始录制", record = true) { toggleRecord() }
        val newBtn = ghostButton(this, "新建存档") { newSlot() }
        val delBtn = ghostButton(this, "删除") { deleteSlot() }
        for (v in listOf(recordBtn, newBtn, delBtn)) {
            row.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = dp(this@MainActivity, 8f) })
        }
        (delBtn.layoutParams as LinearLayout.LayoutParams).marginEnd = 0
        panel.addView(row)
        panel.addView(space(dp(this, 12f)))

        panel.addView(sectionLabel(this, "回放存档（点选加载）"))
        val scroll = ScrollView(this).apply {
            background = solid(this@MainActivity, Theme.pal.listBg, 12f, Theme.pal.fieldBorder)
            isVerticalScrollBarEnabled = false
        }
        slotBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(this@MainActivity, 5f), dp(this@MainActivity, 5f),
                dp(this@MainActivity, 5f), dp(this@MainActivity, 5f))
        }
        scroll.addView(slotBox)
        panel.addView(scroll, ViewGroup.LayoutParams.MATCH_PARENT, dp(this, 190f))
        panel.addView(space(dp(this, 12f)))

        panel.addView(spinRow("循环间隔", true, prefs.getString("gap", "0.5") ?: "0.5", "秒") { v, edit ->
            gapEdit = edit
            val secs = v.toDoubleOrNull() ?: return@spinRow
            prefs.edit().putString("gap", edit.text.toString()).apply()
            ClickerService.instance?.gapMs = (secs * 1000).toLong()
        })
        panel.addView(space(dp(this, 8f)))
        panel.addView(spinRow("循环次数", false, prefs.getString("loops", "1") ?: "1", "次") { v, edit ->
            loopEdit = edit
            val n = v.toIntOrNull() ?: return@spinRow
            prefs.edit().putString("loops", edit.text.toString()).apply()
            ClickerService.instance?.loops = n
            loopUnit.text = if (n == 0) "无限" else "次"
        })
        panel.addView(space(0, 1f))
        return panel
    }

    // 电脑版 spinFrame 行：左标题 + 右内嵌输入 + 单位
    private fun spinRow(
        title: String,
        decimal: Boolean,
        initial: String,
        unit: String,
        onChange: (String, EditText) -> Unit
    ): View {
        val frame = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = solid(this@MainActivity, Theme.pal.field, 9f, Theme.pal.fieldBorder)
            setPadding(dp(this@MainActivity, 14f), 0, dp(this@MainActivity, 12f), 0)
        }
        frame.addView(TextView(this).apply {
            text = title
            textSize = 13f
            setTextColor(Theme.pal.text)
        })
        frame.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val edit = fieldInput(this, number = true, decimal = decimal).apply {
            setText(initial)
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            background = solid(this@MainActivity, Theme.pal.listBg, 7f, Theme.pal.fieldBorder)
        }
        frame.addView(edit, dp(this, 72f), dp(this, 28f))
        val unitLab = TextView(this).apply {
            text = unit
            textSize = 13f
            setTextColor(Theme.pal.dim)
            setPadding(dp(this@MainActivity, 6f), 0, 0, 0)
        }
        frame.addView(unitLab)
        if (title == "循环次数") {
            loopUnit = unitLab
            loopEdit = edit
        }
        if (title == "循环间隔") gapEdit = edit
        edit.addTextChangedListener(afterChanged = { onChange(edit.text.toString(), edit) })
        return frame.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(this@MainActivity, 40f))
        }
    }

    // ---------------- 页转 / 模式 ----------------

    private fun switchMode(i: Int) {
        if (i == mode) return
        ClickerService.instance?.setMode(i == 1)
        mode = i
        prefs.edit().putInt("mode", i).apply()
        val old = if (i == 1) panelCont else panelMacro
        val new = if (i == 1) panelMacro else panelCont
        slidePanels(old, new, if (i == 1) 1 else -1)
        syncStart()
    }

    // 与电脑版 _slide_panels 同款：纯水平平移、InOutCubic、260ms
    private fun slidePanels(old: View, new: View, dir: Int) {
        pageAnim?.cancel()
        val w = pageHost.width.toFloat()
        if (w <= 0f) {
            old.visibility = View.GONE
            new.visibility = View.VISIBLE
            return
        }
        new.translationX = dir * w
        new.visibility = View.VISIBLE
        val va = ValueAnimator.ofFloat(0f, 1f)
        va.duration = 260
        va.interpolator = inOutCubic
        va.addUpdateListener { a ->
            val p = a.animatedFraction
            old.translationX = -dir * w * p
            new.translationX = dir * w * (1f - p)
        }
        va.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                old.visibility = View.GONE
                old.translationX = 0f
                pageAnim = null
            }
        })
        pageAnim = va
        va.start()
    }

    // ---------------- 状态同步 ----------------

    private fun syncStart() {
        val running = ClickerService.instance?.isRunning == true
        startBtn.text = when {
            running && mode == 1 -> "停止回放"
            running -> "停止连点"
            mode == 1 -> "开始回放"
            else -> "开始连点"
        }
        startBtn.background = if (running)
            grad(this, 12f, Theme.dangerStart, Theme.dangerEnd)
        else
            grad(this, 12f)
    }

    private fun syncRecord() {
        val rec = ClickerService.instance?.isRecording == true
        recordBtn.text = if (rec) "停止录制" else "开始录制"
        recordBtn.background = if (rec)
            grad(this, 10f, Theme.dangerStart, Theme.dangerEnd)
        else
            grad(this, 10f, Theme.recordStart, Theme.gradEnd)
    }

    private fun syncPick() {
        val svc = ClickerService.instance
        pickBtn.text = if (svc != null && svc.hasTarget)
            String.format(Locale.getDefault(), "点击位置：(%d, %d)", svc.targetX, svc.targetY)
        else
            "点击位置：未选择（点我选取）"
    }

    private fun syncLoopField() {
        val svc = ClickerService.instance ?: return
        if (loopEdit.text.toString() != svc.loops.toString()) {
            loopEdit.setText(svc.loops.toString())
        }
        loopUnit.text = if (svc.loops == 0) "无限" else "次"
    }

    private fun toggleStart() {
        val svc = ClickerService.instance
        if (svc == null) {
            toast("请先开启无障碍服务")
            return
        }
        if (svc.isRunning) {
            svc.stop()
        } else {
            svc.start()
            // 与电脑版一致：启动即收缩为悬浮胶囊条
            if (svc.isRunning) moveTaskToBack(true)
        }
        syncStart()
    }

    private fun toggleRecord() {
        val svc = ClickerService.instance
        if (svc == null) {
            toast("请先开启无障碍服务")
            return
        }
        if (svc.isRecording) {
            svc.stopRecording()
        } else {
            svc.startRecording()
            moveTaskToBack(true)
        }
        syncRecord()
    }

    private fun applyFreq() {
        val v = freqEdit.text.toString().toDoubleOrNull() ?: return
        val ms = if (unitSeg.index == 0) v else 1000.0 / v.coerceAtLeast(0.1)
        prefs.edit().putString("freq", freqEdit.text.toString())
            .putInt("unit", unitSeg.index).apply()
        ClickerService.instance?.intervalMs = ms.coerceAtLeast(10.0).toLong()
    }

    private fun pushSettings(svc: ClickerService) {
        svc.setMode(mode == 1)
        freqEdit.text.toString().toDoubleOrNull()?.let { v ->
            svc.intervalMs = (if (unitSeg.index == 0) v else 1000.0 / v.coerceAtLeast(0.1))
                .coerceAtLeast(10.0).toLong()
        }
        svc.clicksPerPress = if (typeSeg.index == 1) 2 else 1
        prefs.getString("loops", "1")?.toIntOrNull()?.let { svc.loops = it }
        prefs.getString("gap", "0.5")?.toDoubleOrNull()?.let { svc.gapMs = (it * 1000).toLong() }
        prefs.getString("sel", "")?.takeIf { it.isNotEmpty() }?.let { svc.selectMacro(it) }
        syncLoopField()
    }

    // ---------------- 存档 ----------------

    private fun refreshSlots() {
        slotBox.removeAllViews()
        val store = MacroStore(this)
        val names = store.names()
        val selected = ClickerService.instance?.selectedMacro?.name
            ?: prefs.getString("sel", "")
        if (names.isEmpty()) {
            slotBox.addView(TextView(this).apply {
                text = "暂无存档 · 点「新建存档」创建第一个回放"
                textSize = 12f
                setTextColor(Theme.pal.dim)
                gravity = Gravity.CENTER
                setPadding(0, dp(this@MainActivity, 20f), 0, dp(this@MainActivity, 20f))
            })
            return
        }
        for (name in names) {
            val macro = store.find(name)
            val steps = macro?.steps ?: emptyList()
            val secs = steps.sumOf { it.interval } / 1000.0
            val meta = "${steps.size} 步" + (if (secs > 0) String.format(Locale.getDefault(), " · %.1fs", secs) else "")
            val isSel = name == selected
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(this@MainActivity, 12f), 0, dp(this@MainActivity, 14f), 0)
                background = if (isSel) grad(this@MainActivity, 10f)
                else solid(this@MainActivity, Theme.pal.listBg, 10f)
                setOnClickListener {
                    ClickerService.instance?.selectMacro(name)
                    prefs.edit().putString("sel", name).apply()
                    refreshSlots()
                }
            }
            row.addView(TextView(this).apply {
                text = name
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (isSel) 0xFFFFFFFF.toInt() else Theme.pal.text)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = meta
                textSize = 11f
                setTextColor(if (isSel) 0xFFE4E4FF.toInt() else Theme.pal.dim)
            })
            slotBox.addView(row, ViewGroup.LayoutParams.MATCH_PARENT, dp(this, 52f))
            (row.layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(this, 4f)
        }
    }

    private fun newSlot() {
        val svc = ClickerService.instance
        if (svc == null) {
            toast("请先开启无障碍服务")
            return
        }
        if (svc.recordedStepCount() == 0) {
            toast("没有已录制的步骤，请先录制")
            return
        }
        promptName("") { name ->
            val exists = MacroStore(this).names().contains(name)
            val doSave = {
                if (svc.saveCurrentRecording(name)) {
                    prefs.edit().putString("sel", name).apply()
                    refreshSlots()
                    toast("已保存存档[$name]")
                }
            }
            if (exists) confirm("覆盖存档", "已存在同名存档「$name」，是否覆盖？", "覆盖") { doSave() }
            else doSave()
        }
    }

    private fun deleteSlot() {
        val svc = ClickerService.instance
        val name = svc?.selectedMacro?.name
        if (name == null) {
            toast("请先选择一个存档")
            return
        }
        confirm("删除存档", "确定删除「$name」？此操作不可恢复。", "删除", danger = true) {
            svc.deleteSelectedMacro()
            prefs.edit().putString("sel", "").apply()
            refreshSlots()
            toast("已删除")
        }
    }

    // ---------------- 弹窗 ----------------

    private fun showAbout() {
        val d = CardDialog(this)
        d.card.gravity = Gravity.CENTER_HORIZONTAL
        d.card.addView(ImageView(this).apply {
            setImageResource(R.drawable.logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(this@MainActivity, 56f), dp(this@MainActivity, 56f))
                .apply { gravity = Gravity.CENTER_HORIZONTAL }
        })
        d.card.addView(space(dp(this, 4f)))
        d.card.addView(centerText("AutoClicker 连点器", 15f, Theme.pal.text, bold = true))
        d.card.addView(centerText("版本 1.0.0", 12f, Theme.pal.dim))
        d.card.addView(centerText("作者 · cui-huajin / yueryuyueryun", 12f, Theme.pal.dim))
        d.card.addView(centerText(
            "开源地址\ngithub.com/yueryuyueryun/AutoClicker\ngitee.com/cui-huajin/AutoClicker",
            12f, Theme.pal.dim))
        d.card.addView(space(dp(this, 8f)))
        val ok = pillButton(this, "知道了") { d.fadeClose() }
        d.card.addView(ok, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        d.popup()
    }

    private fun promptName(default: String, onOk: (String) -> Unit) {
        val d = CardDialog(this)
        d.card.addView(dialogHead(d, "新建存档"))
        d.card.addView(TextView(this).apply {
            text = "为当前录制的 ${ClickerService.instance?.recordedStepCount() ?: 0} 步命名"
            textSize = 12f
            setTextColor(Theme.pal.dim)
        })
        val edit = fieldInput(this, number = false).apply { setText(default) }
        d.card.addView(edit, ViewGroup.LayoutParams.MATCH_PARENT, dp(this, 38f))
        d.card.addView(space(dp(this, 10f)))
        d.card.addView(dialogActions(d, "保存", false) {
            val name = edit.text.toString().trim()
            if (name.isEmpty()) {
                toast("请输入存档名称")
                return@dialogActions
            }
            d.fadeClose()
            onOk(name)
        })
        d.popup()
    }

    private fun confirm(title: String, message: String, okText: String,
                        danger: Boolean = false, onOk: () -> Unit) {
        val d = CardDialog(this)
        d.card.addView(dialogHead(d, title))
        d.card.addView(TextView(this).apply {
            text = message
            textSize = 12f
            setTextColor(Theme.pal.dim)
        })
        d.card.addView(space(dp(this, 10f)))
        d.card.addView(dialogActions(d, okText, danger) {
            d.fadeClose()
            onOk()
        })
        d.popup()
    }

    private fun dialogHead(d: CardDialog, title: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(this).apply {
            text = title
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Theme.pal.text)
        })
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(winButton("✕") { d.fadeClose() })
        return row
    }

    private fun dialogActions(d: CardDialog, okText: String, danger: Boolean, onOk: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        row.addView(ghostButton(this, "取消") { d.fadeClose() }
            .apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = dp(this@MainActivity, 10f) } })
        row.addView(pillButton(this, okText, danger = danger) { onOk() }
            .apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
        return row
    }

    private fun centerText(t: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = t
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setLineSpacing(dp(this@MainActivity, 2f).toFloat(), 1f)
        }

    // ---------------- 工具 ----------------

    private fun addView(
        parent: LinearLayout,
        v: View,
        height: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
        weight: Float = 0f,
        bottomMargin: Int = 0
    ) {
        parent.addView(
            v,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height, weight)
                .apply { this.bottomMargin = bottomMargin }
        )
    }

    private fun space(h: Int, weight: Float = 0f) =
        View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h, weight)
        }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
        val enabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val myClassName = ClickerService::class.java.name
        return enabled.any { it.resolveInfo.serviceInfo.packageName == packageName &&
                it.resolveInfo.serviceInfo.name == myClassName }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

fun EditText.addTextChangedListener(afterChanged: (String) -> Unit) {
    addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) { afterChanged(s?.toString() ?: "") }
    })
}
