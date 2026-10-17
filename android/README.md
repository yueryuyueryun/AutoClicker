# AutoClicker 安卓版

这是 AutoClicker 仓库的 **android/** 目录，为连点器的安卓版本（Windows 版在仓库根目录，脚本版在 script/ 目录）。安卓版基于**无障碍服务（AccessibilityService）**实现点击注入，**无需 root**。

## 功能清单

- **连续点击**：用准星覆盖层在屏幕上选一个点，按设定的间隔（毫秒）循环点击；循环次数 0 表示无限，直到手动停止。
- **宏录制 / 回放**：录制模式下显示全屏半透明捕获层，每次按下记录坐标与距上一步的时间间隔；回放时按记录的间隔依次注入点击，支持循环次数与循环间隔（gap）。
- **多存档**：宏以 JSON 存入 SharedPreferences，结构与 Windows 版 `macros.json` 对齐：
  `[{"name":..,"steps":[{"x":..,"y":..,"interval":..}],"gap":..,"loops":..}]`
  支持保存、切换、删除多个存档。
- **循环次数与循环间隔**：连续点击与宏回放均可配置。
- **悬浮控制条**：可拖动，包含模式切换 / 设置 / 选点 / 录制 / 存档 / 开始 / 停止按钮，可在任意应用之上操作。

## 构建步骤

本目录自带 Gradle Wrapper（Gradle 8.7，AGP 8.1 + Kotlin 1.9，需 JDK 17）：

1. **命令行**：在 `android/` 目录执行 `gradlew assembleDebug`（Windows 用 `gradlew.bat assembleDebug`），首次运行会自动下载对应 Gradle。
2. **Android Studio**：`File → Open` 选择本 `android/` 目录，Studio 会按 wrapper 自动同步并构建。

不想自己构建的话，本目录已附编好的 debug 包 `AutoClicker.apk`，传到手机安装即可。

产物在 `app/build/outputs/apk/`。发布前请配置签名：在 Android Studio 中 `Build → Generate Signed Bundle / APK`，或在 `app/build.gradle` 中添加自己的 `signingConfigs`。

环境要求：compileSdk 34、minSdk 24（`dispatchGesture` 的最低系统要求）、targetSdk 34。

## 首次使用（三步）

1. **安装** APK 并打开应用。
2. **开无障碍**：点「1. 开启无障碍服务」，在系统设置列表中找到 AutoClicker 并启用（系统会弹出无障碍服务确认框，本服务不读取任何窗口内容）。
3. **开悬浮窗**：点「2. 开启悬浮窗权限」并允许；随后点「3. 显示悬浮控制条」，即可在任意应用上使用控制条。

使用流程：
- 连续点击：控制条上「模式:连点」→「设置」调间隔与次数 →「选点」用准星选目标点 →「开始」/「停止」。
- 宏：「录制」→ 在屏幕上依次点击 →「停止录制」→「存档」中输入名称并「保存当前录制」；回放时「模式:宏」→「存档」中选中存档 →「开始」。

## 无需 root 的原理

Android 7.0（API 24）起，无障碍服务可以调用 `AccessibilityService.dispatchGesture()`，向系统注入合成的触摸手势（`GestureDescription.StrokeDescription` 描述路径、起点时刻与时长）。系统把这些手势当作真实触摸派发给当前前台窗口，因此无需 root、无需 adb，就能在任意应用上实现自动点击。本工程中每次点击是一个 60ms 的点按手势，连续点击与宏回放都由主线程 Handler 按毫秒间隔调度注入。

## 目录结构

```
android/
├── settings.gradle / build.gradle / gradle.properties
└── app/
    ├── build.gradle
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/xml/accessibility_service_config.xml
        ├── res/values/strings.xml
        └── java/com/yueryuyueryun/autoclicker/
            ├── MainActivity.kt        # 权限引导页
            ├── ClickerService.kt      # 无障碍服务：手势注入与调度
            ├── OverlayController.kt   # 悬浮窗（控制条/面板/选点/录制层）
            └── MacroStore.kt          # SharedPreferences + org.json 宏存档
```
