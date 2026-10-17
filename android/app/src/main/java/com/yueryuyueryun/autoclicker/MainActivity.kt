// AutoClicker Android 版 · 作者 cui-huajin / yueryuyueryun · MIT License
package com.yueryuyueryun.autoclicker

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

// 引导页：只负责两项权限的检测/跳转，以及控制条的显隐入口
class MainActivity : Activity() {

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 界面简单，直接代码构建，省掉布局资源文件
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f), dp(48f), dp(24f), dp(24f))
        }

        val title = TextView(this).apply {
            text = "AutoClicker 安卓版"
            textSize = 22f
            setPadding(0, 0, 0, dp(8f))
        }
        root.addView(title)

        statusText = TextView(this).apply {
            textSize = 14f
            setPadding(0, 0, 0, dp(24f))
        }
        root.addView(statusText)

        root.addView(makeButton("1. 开启无障碍服务") {
            if (isAccessibilityEnabled()) {
                toast("无障碍服务已开启")
            } else {
                // 系统设置页无法直接定位到本服务，提示用户手动找到 AutoClicker
                toast("请在列表中找到 AutoClicker 并开启")
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        })

        root.addView(makeButton("2. 开启悬浮窗权限") {
            if (Settings.canDrawOverlays(this)) {
                toast("悬浮窗权限已开启")
            } else {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        })

        root.addView(makeButton("3. 显示悬浮控制条") {
            when {
                !isAccessibilityEnabled() -> toast("请先开启无障碍服务")
                !Settings.canDrawOverlays(this) -> toast("请先授予悬浮窗权限")
                else -> {
                    val svc = ClickerService.instance
                    if (svc == null) {
                        // 服务未连接通常是刚开启无障碍，系统还没完成绑定
                        toast("无障碍服务尚未连接，请稍等或重新开关一次服务")
                    } else {
                        svc.showBar()
                        toast("控制条已显示")
                    }
                }
            }
        })

        root.addView(makeButton("隐藏悬浮控制条") {
            val svc = ClickerService.instance
            if (svc == null) toast("无障碍服务未连接") else svc.hideBar()
        })

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val a11y = if (isAccessibilityEnabled()) "已开启" else "未开启"
        val overlay = if (Settings.canDrawOverlays(this)) "已授予" else "未授予"
        val svc = if (ClickerService.instance != null) "已连接" else "未连接"
        statusText.text = "无障碍服务: $a11y\n悬浮窗权限: $overlay\n服务状态: $svc"
    }

    // 遍历已启用的无障碍服务，按包名+类名精确匹配本应用的服务
    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val myClassName = ClickerService::class.java.name
        return enabled.any { info ->
            val si = info.resolveInfo.serviceInfo
            si.packageName == packageName && si.name == myClassName
        }
    }

    private fun makeButton(text: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            isAllCaps = false
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8f) }
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
