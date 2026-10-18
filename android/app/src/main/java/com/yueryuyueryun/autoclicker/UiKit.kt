// AutoClicker Android 版 · 作者 cui-huajin / yueryuyueryun · MIT License
package com.yueryuyueryun.autoclicker

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

// 与电脑版 app.py 的 PALETTES 同源的浅/深两套配色
data class Pal(
    val card: Int, val cardBorder: Int, val text: Int, val dim: Int,
    val field: Int, val fieldBorder: Int, val fieldText: Int,
    val hover: Int, val rowHover: Int, val listBg: Int, val pageBg: Int
)

object Theme {
    var dark = false

    private val light = Pal(
        card = Color.parseColor("#f6f6fb"), cardBorder = Color.parseColor("#dfdfea"),
        text = Color.parseColor("#25253c"), dim = Color.parseColor("#71718c"),
        field = Color.parseColor("#ffffff"), fieldBorder = Color.parseColor("#d9d9e6"),
        fieldText = Color.parseColor("#2b2b42"), hover = Color.parseColor("#e9e9f4"),
        rowHover = Color.parseColor("#e6e6f2"), listBg = Color.parseColor("#eeeef6"),
        pageBg = Color.parseColor("#e3e3ed")
    )
    private val darkPal = Pal(
        card = Color.parseColor("#1b1b26"), cardBorder = Color.parseColor("#2e2e3e"),
        text = Color.parseColor("#e8e8f0"), dim = Color.parseColor("#8a8aa0"),
        field = Color.parseColor("#242432"), fieldBorder = Color.parseColor("#34344a"),
        fieldText = Color.parseColor("#f0f0f8"), hover = Color.parseColor("#2a2a3a"),
        rowHover = Color.parseColor("#232332"), listBg = Color.parseColor("#14141c"),
        pageBg = Color.parseColor("#0f0f16")
    )
    val pal: Pal get() = if (dark) darkPal else light

    val gradStart = Color.parseColor("#5b7cfa")
    val gradEnd = Color.parseColor("#8a5bfa")
    val dangerStart = Color.parseColor("#fa5b6e")
    val dangerEnd = Color.parseColor("#fa8a5b")
    val recordStart = Color.parseColor("#22c1a3")
}

fun dp(ctx: Context, v: Float): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

// 与电脑版分段高亮块/页转同款的 InOutCubic 曲线
val inOutCubic = PathInterpolator(0.65f, 0f, 0.35f, 1f)

fun solid(ctx: Context, color: Int, radiusDp: Float, strokeColor: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(ctx, radiusDp).toFloat()
        if (strokeColor != 0) setStroke(dp(ctx, 1f), strokeColor)
    }

fun grad(
    ctx: Context,
    radiusDp: Float,
    start: Int = Theme.gradStart,
    end: Int = Theme.gradEnd
): GradientDrawable =
    GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(start, end)).apply {
        cornerRadius = dp(ctx, radiusDp).toFloat()
    }

// 分段按钮行：选中高亮块以水滴形变从旧项流转到新项，与电脑版 SegRow 同节奏
class SegView(
    ctx: Context,
    options: List<String>,
    checked: Int,
    private val onSelected: (Int) -> Unit
) : FrameLayout(ctx) {

    var index = checked
        private set

    private val indicator = View(ctx)
    private val row = LinearLayout(ctx)
    private val labels = mutableListOf<TextView>()
    private var animator: ValueAnimator? = null
    // 高亮块的目标矩形；由 onLayout 在每次布局后按此矩形重新摆放，
    // 避免 FrameLayout 默认把它铺满整行
    private val indRect = Rect()

    init {
        val p = Theme.pal
        background = solid(ctx, p.field, 10f, p.fieldBorder)
        setPadding(dp(ctx, 3f), dp(ctx, 3f), dp(ctx, 3f), dp(ctx, 3f))
        indicator.background = grad(ctx, 9f)
        addView(indicator, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.TOP or Gravity.START))
        row.orientation = LinearLayout.HORIZONTAL
        addView(row, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        options.forEachIndexed { i, text ->
            val tv = TextView(ctx).apply {
                this.text = text
                textSize = 12f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                setOnClickListener { select(i, true) }
            }
            labels += tv
            row.addView(tv)
        }
        paintLabels()
        post { syncIndicator() }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // 父类会把 indicator 摆成 0 宽，这里按当前矩形覆盖回去
        if (indRect.width() > 0) {
            indicator.layout(indRect.left, indRect.top, indRect.right, indRect.bottom)
        }
    }

    fun select(i: Int, animate: Boolean) {
        if (i == index && animate) return
        index = i
        paintLabels()
        onSelected(i)
        if (!animate) {
            syncIndicator()
            return
        }
        val from = Rect(indicator.left, indicator.top, indicator.right, indicator.bottom)
        val to = targetRect(i) ?: return
        animator?.cancel()
        val va = ValueAnimator.ofFloat(0f, 1f)
        va.duration = 260
        va.interpolator = inOutCubic
        va.addUpdateListener { a ->
            val f = a.animatedFraction
            // 流转途中横向鼓胀再收回，模拟水波形变
            val bulge = (Math.abs(to.left - from.left) * 0.45 * Math.sin(Math.PI * f)).toInt()
            val l = (from.left + (to.left - from.left) * f).toInt() - bulge / 2
            val r = (from.right + (to.right - from.right) * f).toInt() + bulge / 2
            indRect.set(l, to.top, r, to.bottom)
            indicator.layout(l, to.top, r, to.bottom)
        }
        animator = va
        va.start()
    }

    private fun paintLabels() {
        labels.forEachIndexed { k, tv ->
            val sel = k == index
            tv.setTextColor(if (sel) Color.WHITE else Theme.pal.dim)
            tv.typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }

    private fun targetRect(i: Int): Rect? {
        val v = labels.getOrNull(i) ?: return null
        return Rect(row.left + v.left, row.top + v.top, row.left + v.right, row.top + v.bottom)
    }

    private fun syncIndicator() {
        if (animator?.isRunning == true) return
        targetRect(index)?.let {
            indRect.set(it)
            indicator.layout(it.left, it.top, it.right, it.bottom)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post { syncIndicator() }
    }

    fun retheme() {
        val p = Theme.pal
        background = solid(context, p.field, 10f, p.fieldBorder)
        paintLabels()
    }
}

// 渐变胶囊按钮（电脑版 pillBtn / startBtn / recordBtn 同源）
fun pillButton(
    ctx: Context,
    text: String,
    danger: Boolean = false,
    record: Boolean = false,
    onClick: () -> Unit
): TextView {
    val v = TextView(ctx).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(ctx, 14f), dp(ctx, 9f), dp(ctx, 14f), dp(ctx, 9f))
        val s = if (danger) Theme.dangerStart else if (record) Theme.recordStart else Theme.gradStart
        val e = if (danger) Theme.dangerEnd else Theme.gradEnd
        background = grad(ctx, 10f, s, e)
        setOnClickListener { onClick() }
    }
    v.setOnTouchListener(PressDim(v))
    return v
}

// 描边幽灵按钮（电脑版 ghostBtn 同源）
fun ghostButton(ctx: Context, text: String, onClick: () -> Unit): TextView {
    val v = TextView(ctx).apply {
        this.text = text
        textSize = 13f
        setTextColor(Theme.pal.dim)
        gravity = Gravity.CENTER
        setPadding(dp(ctx, 14f), dp(ctx, 9f), dp(ctx, 14f), dp(ctx, 9f))
        background = solid(ctx, Theme.pal.field, 10f, Theme.pal.fieldBorder)
        setOnClickListener { onClick() }
    }
    v.setOnTouchListener(PressDim(v))
    return v
}

// 按下时轻微提亮，松手恢复；不做扩散残影
class PressDim(private val v: View) : View.OnTouchListener {
    private var origin: android.graphics.drawable.Drawable? = null
    override fun onTouch(view: View, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                origin = v.background
                v.alpha = 0.82f
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.alpha = 1f
        }
        return false
    }
}

// 输入框（电脑版 spinFrame / QLineEdit 同源）
fun fieldInput(ctx: Context, number: Boolean, decimal: Boolean = false): EditText =
    EditText(ctx).apply {
        textSize = 14f
        setTextColor(Theme.pal.fieldText)
        setHintTextColor(Theme.pal.dim)
        background = solid(ctx, Theme.pal.field, 9f, Theme.pal.fieldBorder)
        setPadding(dp(ctx, 12f), dp(ctx, 8f), dp(ctx, 12f), dp(ctx, 8f))
        if (number) {
            inputType = if (decimal)
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            else
                InputType.TYPE_CLASS_NUMBER
        }
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
    }

// 小节标题（电脑版 section 同源）
fun sectionLabel(ctx: Context, text: String): TextView =
    TextView(ctx).apply {
        this.text = text
        textSize = 12f
        setTextColor(Theme.pal.dim)
    }

// 与电脑版同风格的卡片弹窗：透明窗口 + 圆角卡片 + 淡入淡出
class CardDialog(ctx: Context) : android.app.Dialog(ctx, android.R.style.Theme_Translucent_NoTitleBar) {

    val card = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = solid(ctx, Theme.pal.card, 18f, ctx.let { Theme.pal.cardBorder })
        setPadding(dp(ctx, 22f), dp(ctx, 20f), dp(ctx, 22f), dp(ctx, 18f))
    }

    init {
        val outer = FrameLayout(ctx).apply {
            setPadding(dp(ctx, 40f), 0, dp(ctx, 40f), 0)
            addView(
                card,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )
        }
        setContentView(outer)
        setCanceledOnTouchOutside(true)
        window?.setDimAmount(0.42f)
    }

    fun popup() {
        show()
        fade(0f, 1f, 160)
    }

    fun fadeClose() {
        fade(1f, 0f, 120) { dismiss() }
    }

    private fun fade(from: Float, to: Float, ms: Long, end: (() -> Unit)? = null) {
        val w = window ?: return
        val va = ValueAnimator.ofFloat(from, to)
        va.duration = ms
        va.interpolator = inOutCubic
        va.addUpdateListener { a ->
            val lp = w.attributes
            lp.alpha = a.animatedValue as Float
            w.attributes = lp
        }
        if (end != null) va.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) { end() }
        })
        va.start()
    }
}
